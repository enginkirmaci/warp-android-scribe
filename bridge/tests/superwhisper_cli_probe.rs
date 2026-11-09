#![cfg(unix)]

use std::{fs, os::unix::fs::PermissionsExt, path::PathBuf, time::Duration};

use tempfile::TempDir;
use warp_android_scribe_bridge::transcriber::{
    SuperwhisperCli, TranscribeRequest, Transcriber, TranscriberError,
};

/// The probe writes and immediately executes a shell script. Running the
/// cases one at a time keeps a concurrently forked child from touching the
/// script file while the kernel is exec'ing it (ETXTBSY).
static TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

const TEMPLATE: &str = r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> '@LOG@'
case "$1" in
  transcribe)
    echo '@ACK@'
    ;;
  status)
    n=0
    if [ -f '@COUNT@' ]; then n=$(cat '@COUNT@'); fi
    n=$((n + 1))
    printf '%s' "$n" > '@COUNT@'
    if [ "$n" -lt @IDLE_AFTER@ ]; then
      echo '@BUSY@'
    else
      echo '@IDLE@'
    fi
    ;;
  general)
    echo '{"ok":true}'
    ;;
esac
"#;

struct FakeCli {
    _dir: TempDir,
    bin: PathBuf,
    log: PathBuf,
    count: PathBuf,
    wav: PathBuf,
}

impl FakeCli {
    fn new(ack: &str, idle: &str, idle_after: u32) -> Self {
        let dir = TempDir::new().unwrap();
        let bin = dir.path().join("superwhisper");
        let log = dir.path().join("log");
        let count = dir.path().join("count");
        let wav = dir.path().join("sample.wav");
        fs::write(&wav, [0u8; 44]).unwrap();

        let script = TEMPLATE
            .replace("@LOG@", &log.display().to_string())
            .replace("@COUNT@", &count.display().to_string())
            .replace("@ACK@", ack)
            .replace(
                "@BUSY@",
                r#"{"phase":"processing","last":{"session_id":0}}"#,
            )
            .replace("@IDLE@", idle)
            .replace("@IDLE_AFTER@", &idle_after.to_string());
        fs::write(&bin, script).unwrap();
        let mut permissions = fs::metadata(&bin).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&bin, permissions).unwrap();

        Self {
            _dir: dir,
            bin,
            log,
            count,
            wav,
        }
    }

    fn cli(&self, timeout: Duration) -> SuperwhisperCli {
        SuperwhisperCli::new(&self.bin, timeout).with_poll_interval(Duration::from_millis(5))
    }

    fn log(&self) -> String {
        fs::read_to_string(&self.log).unwrap_or_default()
    }

    fn status_calls(&self) -> u32 {
        fs::read_to_string(&self.count)
            .ok()
            .and_then(|value| value.trim().parse().ok())
            .unwrap_or(0)
    }
}

fn idle(session_id: u64, text: &str) -> String {
    format!(
        r#"{{"phase":"idle","last":{{"session_id":{session_id},"text":"{text}","language":"tr","audio_seconds":2.5}}}}"#
    )
}

fn request(language: &str, tone: Option<&str>) -> TranscribeRequest {
    TranscribeRequest {
        language: language.to_string(),
        tone: tone.map(str::to_string),
    }
}

#[tokio::test]
async fn transcribes_a_file_and_polls_until_idle() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new(
        r#"{"ok":true,"session_id":7}"#,
        &idle(7, "merhaba dünya"),
        3,
    );
    let transcript = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("tr", None))
        .await
        .unwrap();

    assert_eq!(transcript.text, "merhaba dünya");
    assert_eq!(transcript.language, "tr");
    assert!((transcript.duration_secs - 2.5).abs() < 0.001);
    assert_eq!(fake.status_calls(), 3);
    let log = fake.log();
    assert!(log.contains("general set language \"tr\""), "{log}");
    assert!(log.contains("transcribe "), "{log}");
}

#[tokio::test]
async fn waits_past_a_busy_daemon() {
    let _guard = TEST_LOCK.lock().await;
    let idle_json = idle(7, "merhaba");
    let fake = FakeCli::new(r#"{"ok":true,"session_id":7}"#, &idle_json, 2);
    let transcript = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("tr", None))
        .await
        .unwrap();
    assert_eq!(transcript.text, "merhaba");
    assert_eq!(fake.status_calls(), 2);
}

#[tokio::test]
async fn a_refused_file_is_reported_as_rejected() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new(
        r#"{"ok":false,"error":"no Superwhisper Pro license"}"#,
        &idle(7, "never"),
        1,
    );
    let error = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("tr", None))
        .await
        .unwrap_err();
    assert!(matches!(error, TranscriberError::Rejected(_)), "{error}");
}

#[tokio::test]
async fn a_daemon_that_never_finishes_times_out() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new(r#"{"ok":true,"session_id":7}"#, &idle(7, "never"), 10_000);
    let error = fake
        .cli(Duration::from_millis(80))
        .transcribe(&fake.wav, &request("tr", None))
        .await
        .unwrap_err();
    assert!(matches!(error, TranscriberError::Timeout), "{error}");
}

#[tokio::test]
async fn sets_the_language_on_each_request() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new(r#"{"ok":true,"session_id":7}"#, &idle(7, "merhaba"), 1);
    let cli = fake.cli(Duration::from_secs(2));
    cli.transcribe(&fake.wav, &request("tr", None))
        .await
        .unwrap();
    cli.transcribe(&fake.wav, &request("auto", None))
        .await
        .unwrap();

    let log = fake.log();
    assert!(log.contains("general set language \"tr\""), "{log}");
    assert!(log.contains("general set language \"auto\""), "{log}");
}

#[tokio::test]
async fn sets_the_tone_on_each_request() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeCli::new(r#"{"ok":true,"session_id":7}"#, &idle(7, "merhaba"), 1);
    let cli = fake.cli(Duration::from_secs(2));
    cli.transcribe(&fake.wav, &request("tr", Some("formal")))
        .await
        .unwrap();
    cli.transcribe(&fake.wav, &request("tr", Some("casual")))
        .await
        .unwrap();

    let log = fake.log();
    assert!(log.contains("general set tone \"formal\""), "{log}");
    assert!(log.contains("general set tone \"casual\""), "{log}");
}

#[tokio::test]
async fn retries_a_warming_daemon_that_exits_nonzero() {
    let _guard = TEST_LOCK.lock().await;
    let dir = TempDir::new().unwrap();
    let bin = dir.path().join("superwhisper");
    let log = dir.path().join("log");
    let count = dir.path().join("count");
    let wav = dir.path().join("sample.wav");
    fs::write(&wav, [0u8; 44]).unwrap();
    let script = r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> '@LOG@'
case "$1" in
  transcribe)
    n=0
    [ -f '@COUNT@' ] && n=$(cat '@COUNT@')
    n=$((n + 1))
    printf '%s' "$n" > '@COUNT@'
    if [ "$n" -lt 2 ]; then
      echo '{"error":"Not ready: warming"}'
      exit 1
    fi
    echo '{"ok":true,"session_id":7}'
    ;;
  status)
    echo '{"phase":"idle","last":{"session_id":7,"text":"warm"}}'
    ;;
  general)
    echo '{"ok":true}'
    ;;
esac
"#
    .replace("@LOG@", &log.display().to_string())
    .replace("@COUNT@", &count.display().to_string());
    fs::write(&bin, script).unwrap();
    let mut permissions = fs::metadata(&bin).unwrap().permissions();
    permissions.set_mode(0o755);
    fs::set_permissions(&bin, permissions).unwrap();

    let cli = SuperwhisperCli::new(&bin, Duration::from_secs(2))
        .with_poll_interval(Duration::from_millis(5));
    let transcript = cli
        .transcribe(&wav, &request("tr", Some("formal")))
        .await
        .unwrap();
    assert_eq!(transcript.text, "warm");
    assert_eq!(fs::read_to_string(&count).unwrap(), "2");
    assert!(
        fs::read_to_string(&log)
            .unwrap()
            .contains("general set tone \"formal\"")
    );
}

#[tokio::test]
async fn a_missing_binary_is_an_io_error() {
    let _guard = TEST_LOCK.lock().await;
    let dir = TempDir::new().unwrap();
    let error = SuperwhisperCli::new(dir.path().join("missing"), Duration::from_secs(1))
        .transcribe(&dir.path().join("x.wav"), &request("tr", None))
        .await
        .unwrap_err();
    assert!(matches!(error, TranscriberError::Io(_)), "{error}");
}
