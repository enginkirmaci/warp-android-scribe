#![cfg(unix)]

use std::{fs, os::unix::fs::PermissionsExt, path::PathBuf, time::Duration};

use tempfile::TempDir;
use warp_android_scribe_bridge::transcriber::{
    HexCli, TranscribeRequest, Transcriber, TranscriberError,
};

/// The probe writes and immediately executes a shell script. Running the cases
/// one at a time keeps a concurrently forked child from touching the script
/// file while the kernel is exec'ing it (ETXTBSY).
static TEST_LOCK: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

const TEMPLATE: &str = r#"#!/bin/sh
set -eu
printf '%s\n' "$*" >> '@LOG@'
mode=$(cat '@MODE@')
case "$mode" in
  ok)
    printf '%s\n' '@TEXT@'
    exit 0
    ;;
  refuse)
    echo '@ERROR@' >&2
    exit 1
    ;;
  nospeech)
    exit 4
    ;;
  slow)
    sleep 5
    exit 0
    ;;
esac
"#;

struct FakeHex {
    _dir: TempDir,
    bin: PathBuf,
    log: PathBuf,
    mode: PathBuf,
    wav: PathBuf,
}

impl FakeHex {
    fn new(text: &str) -> Self {
        let dir = TempDir::new().unwrap();
        let bin = dir.path().join("hex");
        let log = dir.path().join("log");
        let mode = dir.path().join("mode");
        let wav = dir.path().join("sample.wav");
        fs::write(&wav, [0u8; 44]).unwrap();
        fs::write(&mode, "ok").unwrap();

        let script = TEMPLATE
            .replace("@LOG@", &log.display().to_string())
            .replace("@MODE@", &mode.display().to_string())
            .replace("@TEXT@", text)
            .replace("@ERROR@", "unsupported transcription language: zz");
        fs::write(&bin, script).unwrap();
        let mut permissions = fs::metadata(&bin).unwrap().permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&bin, permissions).unwrap();

        Self {
            _dir: dir,
            bin,
            log,
            mode,
            wav,
        }
    }

    fn with_mode(self, mode: &str) -> Self {
        fs::write(&self.mode, mode).unwrap();
        self
    }

    fn cli(&self, timeout: Duration) -> HexCli {
        HexCli::new(&self.bin, "whisper_large_v3_turbo", timeout)
    }

    fn log(&self) -> String {
        fs::read_to_string(&self.log).unwrap_or_default()
    }
}

fn request(language: &str) -> TranscribeRequest {
    TranscribeRequest {
        language: language.to_string(),
        tone: None,
    }
}

#[tokio::test]
async fn transcribes_a_file_through_the_cli() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeHex::new("merhaba dünya");

    let transcript = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("tr"))
        .await
        .unwrap();

    assert_eq!(transcript.text, "merhaba dünya");
    assert_eq!(transcript.language, "tr");
    let log = fake.log();
    assert!(log.contains("model transcribe"), "{log}");
    assert!(log.contains("--model whisper_large_v3_turbo"), "{log}");
    assert!(log.contains("--language tr"), "{log}");
}

#[tokio::test]
async fn a_regional_language_narrows_to_its_base() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeHex::new("hello");

    fake.cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("en-GB"))
        .await
        .unwrap();

    assert!(
        fake.log().trim_end().ends_with("--language en"),
        "{}",
        fake.log()
    );
}

#[tokio::test]
async fn no_speech_is_an_empty_transcript() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeHex::new("ignored").with_mode("nospeech");

    let transcript = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("tr"))
        .await
        .unwrap();

    assert!(transcript.text.is_empty());
}

#[tokio::test]
async fn a_refusal_becomes_a_cli_error_with_the_stderr() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeHex::new("ignored").with_mode("refuse");

    let error = fake
        .cli(Duration::from_secs(2))
        .transcribe(&fake.wav, &request("zz"))
        .await
        .unwrap_err();

    match error {
        TranscriberError::Cli(message) => {
            assert!(
                message.contains("unsupported transcription language"),
                "{message}"
            );
        }
        other => panic!("unexpected error: {other}"),
    }
}

#[tokio::test]
async fn a_hung_cli_times_out() {
    let _guard = TEST_LOCK.lock().await;
    let fake = FakeHex::new("ignored").with_mode("slow");

    let error = fake
        .cli(Duration::from_millis(150))
        .transcribe(&fake.wav, &request("tr"))
        .await
        .unwrap_err();

    assert!(matches!(error, TranscriberError::Timeout));
}
