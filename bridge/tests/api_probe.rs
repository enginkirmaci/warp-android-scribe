use std::{sync::Arc, time::Duration};

use axum::{
    Router,
    body::Body,
    http::{Request, StatusCode, header},
};
use http_body_util::BodyExt;
use serde_json::Value;
use tempfile::TempDir;
use tower::ServiceExt;
use warp_android_scribe_bridge::{
    api::{AppState, router},
    transcriber::MockTranscriber,
};

const TOKEN: &str = "0123456789abcdef";

fn wav(seconds: f64) -> Vec<u8> {
    let frames = (16_000.0 * seconds) as u32;
    let data = frames * 2;
    let mut bytes = Vec::with_capacity(44 + data as usize);
    bytes.extend_from_slice(b"RIFF");
    bytes.extend_from_slice(&(36 + data).to_le_bytes());
    bytes.extend_from_slice(b"WAVE");
    bytes.extend_from_slice(b"fmt ");
    bytes.extend_from_slice(&16u32.to_le_bytes());
    bytes.extend_from_slice(&1u16.to_le_bytes());
    bytes.extend_from_slice(&1u16.to_le_bytes());
    bytes.extend_from_slice(&16_000u32.to_le_bytes());
    bytes.extend_from_slice(&32_000u32.to_le_bytes());
    bytes.extend_from_slice(&2u16.to_le_bytes());
    bytes.extend_from_slice(&16u16.to_le_bytes());
    bytes.extend_from_slice(b"data");
    bytes.extend_from_slice(&data.to_le_bytes());
    bytes.resize(44 + data as usize, 0);
    bytes
}

fn state(mock: Arc<MockTranscriber>, work: &TempDir) -> AppState {
    AppState::new(mock, TOKEN, Duration::from_secs(2)).with_work_dir(work.path())
}

async fn post(app: Router, token: Option<&str>, uri: &str, body: Vec<u8>) -> (StatusCode, Value) {
    let mut request = Request::builder()
        .method("POST")
        .uri(uri)
        .header(header::CONTENT_TYPE, "audio/wav");
    if let Some(token) = token {
        request = request.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let response = app
        .oneshot(request.body(Body::from(body)).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let body = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
    (status, body)
}

async fn get(app: Router, token: Option<&str>, uri: &str) -> (StatusCode, Value) {
    let mut request = Request::builder().method("GET").uri(uri);
    if let Some(token) = token {
        request = request.header(header::AUTHORIZATION, format!("Bearer {token}"));
    }
    let response = app
        .oneshot(request.body(Body::empty()).unwrap())
        .await
        .unwrap();
    let status = response.status();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    let body = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
    (status, body)
}

#[tokio::test]
async fn missing_and_wrong_tokens_are_rejected() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work));

    let (status, body) = post(app.clone(), None, "/transcribe?lang=tr", wav(1.0)).await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
    assert_eq!(body["code"], "unauthorized");

    let (status, _) = post(
        app,
        Some("ffffffffffffffff"),
        "/transcribe?lang=tr",
        wav(1.0),
    )
    .await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn transcribes_and_removes_the_temp_file() {
    let work = TempDir::new().unwrap();
    let mock = Arc::new(MockTranscriber::new("merhaba dünya").with_duration_secs(2.5));
    let app = router(state(mock, &work));

    let (status, body) = post(app, Some(TOKEN), "/transcribe?lang=tr", wav(2.0)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["text"], "merhaba dünya");
    assert_eq!(body["language"], "tr");
    assert_eq!(body["duration"], 2.5);
    assert_eq!(std::fs::read_dir(work.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn passes_the_requested_language_through() {
    let work = TempDir::new().unwrap();
    let mock = Arc::new(MockTranscriber::new("hello").with_language("en"));
    let app = router(state(mock.clone(), &work));

    let (status, body) = post(app, Some(TOKEN), "/transcribe?lang=auto", wav(1.0)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["language"], "en");
    assert_eq!(mock.seen_languages(), vec!["auto".to_string()]);
}

#[tokio::test]
async fn defaults_to_the_configured_language() {
    let work = TempDir::new().unwrap();
    let mock = Arc::new(MockTranscriber::new("merhaba"));
    let app = router(state(mock.clone(), &work).with_default_language("tr"));

    let (status, _) = post(app, Some(TOKEN), "/transcribe", wav(1.0)).await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(mock.seen_languages(), vec!["tr".to_string()]);
}

#[tokio::test]
async fn rejects_an_invalid_language() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work));

    let (status, body) = post(app, Some(TOKEN), "/transcribe?lang=tr_TR", wav(1.0)).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["code"], "invalid_language");
}

#[tokio::test]
async fn rejects_audio_that_is_not_a_wav() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work));

    let (status, body) = post(app, Some(TOKEN), "/transcribe", b"hello".to_vec()).await;
    assert_eq!(status, StatusCode::BAD_REQUEST);
    assert_eq!(body["code"], "invalid_wav");
}

#[tokio::test]
async fn rejects_a_body_over_the_size_limit() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work).with_limits(1024, 120.0));

    let (status, body) = post(app, Some(TOKEN), "/transcribe", vec![0u8; 2048]).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(body["code"], "too_large");
}

#[tokio::test]
async fn rejects_audio_over_the_duration_limit() {
    let work = TempDir::new().unwrap();
    let app = router(
        state(Arc::new(MockTranscriber::new("x")), &work).with_limits(10 * 1024 * 1024, 0.5),
    );

    let (status, body) = post(app, Some(TOKEN), "/transcribe", wav(1.0)).await;
    assert_eq!(status, StatusCode::PAYLOAD_TOO_LARGE);
    assert_eq!(body["code"], "too_long");
}

#[tokio::test]
async fn maps_a_transcriber_failure_to_502() {
    let work = TempDir::new().unwrap();
    let mock = Arc::new(MockTranscriber::failing("no model"));
    let app = router(state(mock, &work));

    let (status, body) = post(app, Some(TOKEN), "/transcribe", wav(1.0)).await;
    assert_eq!(status, StatusCode::BAD_GATEWAY);
    assert_eq!(body["code"], "transcriber_failed");
    assert_eq!(std::fs::read_dir(work.path()).unwrap().count(), 0);
}

#[tokio::test]
async fn maps_a_slow_transcriber_to_504() {
    let work = TempDir::new().unwrap();
    let mock = Arc::new(MockTranscriber::new("late").with_delay(Duration::from_millis(200)));
    let app = router(state(mock, &work).with_timeout(Duration::from_millis(50)));

    let (status, body) = post(app, Some(TOKEN), "/transcribe", wav(1.0)).await;
    assert_eq!(status, StatusCode::GATEWAY_TIMEOUT);
    assert_eq!(body["code"], "timeout");
}

#[tokio::test]
async fn health_is_token_checked() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work));

    let (status, _) = get(app.clone(), None, "/health").await;
    assert_eq!(status, StatusCode::UNAUTHORIZED);

    let (status, body) = get(app, Some(TOKEN), "/health").await;
    assert_eq!(status, StatusCode::OK);
    assert_eq!(body["ok"], true);
    assert_eq!(body["service"], "warp-android-scribe-bridge");
    assert_eq!(body["transcriber"], "mock");
}

#[tokio::test]
async fn an_explicit_content_length_over_the_limit_is_rejected_before_reading() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work).with_limits(1024, 120.0));

    let request = Request::builder()
        .method("POST")
        .uri("/transcribe")
        .header(header::CONTENT_TYPE, "audio/wav")
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .header(header::CONTENT_LENGTH, "2048")
        .body(Body::empty())
        .unwrap();
    let response = app.oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
}

#[tokio::test]
async fn a_non_wav_content_type_is_rejected() {
    let work = TempDir::new().unwrap();
    let app = router(state(Arc::new(MockTranscriber::new("x")), &work));

    let request = Request::builder()
        .method("POST")
        .uri("/transcribe")
        .header(header::CONTENT_TYPE, "text/plain")
        .header(header::AUTHORIZATION, format!("Bearer {TOKEN}"))
        .body(Body::from(wav(1.0)))
        .unwrap();
    let response = app.oneshot(request).await.unwrap();
    assert_eq!(response.status(), StatusCode::UNSUPPORTED_MEDIA_TYPE);
}
