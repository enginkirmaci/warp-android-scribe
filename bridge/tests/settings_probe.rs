#![cfg(unix)]

use std::{fs, os::unix::fs::PermissionsExt, path::PathBuf, sync::Arc, time::Duration};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode, header},
};
use http_body_util::BodyExt;
use serde_json::{Value, json};
use tempfile::TempDir;
use tower::ServiceExt;
use warp_android_scribe_bridge::{
    api::{AppState, router},
    settings::SettingsService,
    transcriber::MockTranscriber,
};

const TOKEN: &str = "0123456789abcdef";

/// Scripts are written and exec'd one at a time; a forked child must not see
/// the file change while the kernel is reading it (ETXTBSY).
static TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

const SCRIPT: &str = r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> '@LOG@'
case "$1 $2" in
  "general show")
    cat <<'JSON'
{"config":{"language":"tr","tone":"semi-formal"},"options":{"tones":[{"label":"Casual","value":"casual"},{"label":"Semi-casual","value":"semi-casual"},{"label":"Balanced","value":"balanced"},{"label":"Semi-formal","value":"semi-formal"},{"label":"Formal","value":"formal"}],"languages":[{"label":"Automatic","value":"auto"},{"label":"Turkish","value":"tr"},{"label":"English","value":"en"}]}}
JSON
    ;;
  "vocab show")
    cat <<'JSON'
{"config":{"terms":["Omarchy","Superwhisper"],"replacements":[{"id":"r1","original":"Oh Marchie","with":"Omarchy"}]}}
JSON
    ;;
  "models show")
    mode=cloud
    [ -f '@MODE@' ] && mode=$(cat '@MODE@')
    state=missing
    missing=' "cleanup"'
    bytes=100
    if [ -f '@DOWNLOADED@' ]; then
      state=installed
      missing=''
      bytes=0
    fi
    printf '{"processing":"%s","processingModes":[{"value":"cloud","label":"Cloud","downloadBytes":0,"missing":[]},{"value":"offline","label":"Offline","downloadBytes":%s,"missing":[%s]}],"models":[{"id":"cleanup","title":"S1 Mini","sizeBytes":100,"diskBytes":0,"download":{"state":"%s","bytes":0,"total":100}}]}\n' "$mode" "$bytes" "$missing" "$state"
    ;;
  "models select")
    printf '%s' "$3" > '@MODE@'
    echo '{}'
    ;;
  "models download")
    : > '@DOWNLOADED@'
    echo '{}'
    ;;
  "vocab add"|"vocab remove"|"vocab replace"|"vocab remove-replacement")
    echo '{}'
    ;;
  *)
    echo '{}'
    ;;
esac
"#;

struct FakeCli {
    _dir: TempDir,
    bin: PathBuf,
    log: PathBuf,
}

impl FakeCli {
    fn new() -> Self {
        let dir = TempDir::new().unwrap();
        let bin = dir.path().join("superwhisper");
        let log = dir.path().join("log");
        let script = SCRIPT
            .replace("@LOG@", &log.display().to_string())
            .replace("@MODE@", &dir.path().join("mode").display().to_string())
            .replace(
                "@DOWNLOADED@",
                &dir.path().join("downloaded").display().to_string(),
            );
        fs::write(&bin, script).unwrap();
        let mut permissions = fs::metadata(&bin).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&bin, permissions).unwrap();
        Self {
            _dir: dir,
            bin,
            log,
        }
    }

    fn settings(&self) -> Arc<SettingsService> {
        Arc::new(
            SettingsService::new(&self.bin, Duration::from_secs(2))
                .with_download_poll(Duration::from_millis(10)),
        )
    }

    fn log(&self) -> String {
        fs::read_to_string(&self.log).unwrap_or_default()
    }
}

fn app(settings: Arc<SettingsService>) -> Router {
    router(
        AppState::new(
            Arc::new(MockTranscriber::new("x")),
            TOKEN,
            Duration::from_secs(2),
        )
        .with_settings(settings),
    )
}

async fn send(
    app: Router,
    token: Option<&str>,
    method: &str,
    uri: &str,
    body: Option<Value>,
) -> (StatusCode, Value) {
    let mut builder = Request::builder().method(method).uri(uri);
    if let Some(token) = token {
        builder = builder.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let request = match body {
        Some(body) => builder
            .header(header::CONTENT_TYPE, "application/json")
            .body(Body::from(body.to_string()))
            .unwrap(),
        None => builder.body(Body::empty()).unwrap(),
    };
    let response = app.oneshot(request).await.unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let body = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
    (status, body)
}

async fn get(app: Router, uri: &str) -> (StatusCode, Value) {
    send(app, Some(TOKEN), "GET", uri, None).await
}

async fn put(app: Router, uri: &str, body: Value) -> (StatusCode, Value) {
    send(app, Some(TOKEN), "PUT", uri, Some(body)).await
}

async fn post(app: Router, uri: &str, body: Value) -> (StatusCode, Value) {
    send(app, Some(TOKEN), "POST", uri, Some(body)).await
}

#[tokio::test]
async fn settings_is_token_checked() {
    let fake = FakeCli::new();
    let (status, body) = send(app(fake.settings()), None, "GET", "/settings", None).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["code"], "unauthorized");
}

#[tokio::test]
async fn settings_reports_the_profile() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new();
    let (status, body) = get(app(fake.settings()), "/settings").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["tone"], "semi-formal");
    assert_eq!(body["tones"].as_array().unwrap().len(), 5);
    assert_eq!(body["languages"][1]["value"], "tr");
    assert_eq!(body["vocabulary"]["terms"][0], "Omarchy");
    assert_eq!(
        body["vocabulary"]["replacements"][0]["original"],
        "Oh Marchie"
    );
    assert_eq!(body["processing"], "cloud");
    assert_eq!(body["processing_modes"][1]["value"], "offline");
    assert_eq!(body["processing_modes"][1]["missing"][0], "cleanup");
}

#[tokio::test]
async fn vocabulary_adds_and_removes_only_the_difference() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new();
    let (status, _) = put(
        app(fake.settings()),
        "/settings/vocabulary",
        json!({
            "terms": ["Omarchy", "Warp"],
            "replacements": [{"original": "Oh Marchie", "with": "Omarchy"}]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let log = fake.log();
    assert!(log.contains("vocab add Warp"), "{log}");
    assert!(log.contains("vocab remove Superwhisper"), "{log}");
    assert!(!log.contains("vocab replace"), "{log}");
    assert!(!log.contains("vocab remove-replacement"), "{log}");
}

#[tokio::test]
async fn a_changed_replacement_is_rewritten() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new();
    let (status, _) = put(
        app(fake.settings()),
        "/settings/vocabulary",
        json!({
            "terms": ["Omarchy", "Superwhisper"],
            "replacements": [{"original": "Oh Marchie", "with": "Omarchy App"}]
        }),
    )
    .await;
    assert_eq!(status, StatusCode::OK);
    let log = fake.log();
    assert!(log.contains("vocab remove-replacement Oh Marchie"), "{log}");
    assert!(
        log.contains("vocab replace Oh Marchie Omarchy App"),
        "{log}"
    );
}

#[tokio::test]
async fn processing_downloads_missing_models_then_switches() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new();
    let app = app(fake.settings());
    let (status, _) = post(
        app.clone(),
        "/settings/processing",
        json!({"mode": "offline"}),
    )
    .await;
    assert_eq!(status, StatusCode::OK);

    let mut processing = String::new();
    for _ in 0..200 {
        let (_, body) = get(app.clone(), "/settings").await;
        processing = body["processing"].as_str().unwrap_or_default().to_string();
        if processing == "offline" {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(processing, "offline");
    let log = fake.log();
    assert!(log.contains("models download cleanup"), "{log}");
    assert!(log.contains("models select offline"), "{log}");
}

#[tokio::test]
async fn an_invalid_processing_mode_is_rejected() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new();
    let (status, body) = post(
        app(fake.settings()),
        "/settings/processing",
        json!({"mode": "quantum"}),
    )
    .await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["code"], "invalid_setting");
}
