//! The Superwhisper CLI as a transcriber.
//!
//! `superwhisper transcribe <wav>` only starts a daemon-side session and answers
//! `{"ok":true,"session_id":N}`. The text arrives later, so this module polls
//! `superwhisper status` until the daemon is idle and `last.session_id` is the
//! session it started. One mutex around the whole sequence keeps two requests
//! from fighting over the daemon's single active session.

use std::{
    path::{Path, PathBuf},
    process::Stdio,
    time::{Duration, Instant},
};

use async_trait::async_trait;
use serde::Deserialize;
use tokio::{process::Command, sync::Mutex};

use super::{TranscribeRequest, Transcriber, TranscriberError, Transcript};

const DEFAULT_POLL_INTERVAL: Duration = Duration::from_millis(200);
const CONTROL_TIMEOUT: Duration = Duration::from_secs(10);
const TRANSCRIBE_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug)]
pub struct SuperwhisperCli {
    bin: PathBuf,
    timeout: Duration,
    poll_interval: Duration,
    /// The daemon has one session; the whole set-language/transcribe/poll
    /// sequence takes this lock.
    run: Mutex<()>,
}

impl SuperwhisperCli {
    pub fn new(bin: impl Into<PathBuf>, timeout: Duration) -> Self {
        Self {
            bin: bin.into(),
            timeout,
            poll_interval: DEFAULT_POLL_INTERVAL,
            run: Mutex::new(()),
        }
    }

    /// Tests use a short interval; production leaves the 200 ms default.
    pub fn with_poll_interval(mut self, poll_interval: Duration) -> Self {
        self.poll_interval = poll_interval.max(Duration::from_millis(1));
        self
    }

    /// `superwhisper general set language <json>` takes a JSON scalar.
    async fn set_language(&self, language: &str) -> Result<(), TranscriberError> {
        let encoded = serde_json::to_string(language)
            .map_err(|error| TranscriberError::Protocol(error.to_string()))?;
        self.run_str(
            &["general", "set", "language", encoded.as_str()],
            CONTROL_TIMEOUT,
        )
        .await?;
        Ok(())
    }

    /// `superwhisper general set tone <json>`; same global-profile caveat as
    /// the language pin, applied inside the session mutex.
    async fn set_tone(&self, tone: &str) -> Result<(), TranscriberError> {
        let encoded = serde_json::to_string(tone)
            .map_err(|error| TranscriberError::Protocol(error.to_string()))?;
        self.run_str(
            &["general", "set", "tone", encoded.as_str()],
            CONTROL_TIMEOUT,
        )
        .await?;
        Ok(())
    }

    async fn run_str(
        &self,
        args: &[&str],
        timeout: Duration,
    ) -> Result<std::process::Output, TranscriberError> {
        let mut command = Command::new(&self.bin);
        command
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        self.wait(command, timeout).await
    }

    async fn wait(
        &self,
        command: Command,
        timeout: Duration,
    ) -> Result<std::process::Output, TranscriberError> {
        let output = self.output(command, timeout).await?;
        if output.status.success() {
            Ok(output)
        } else {
            let stderr = String::from_utf8_lossy(&output.stderr);
            Err(TranscriberError::Cli(format!(
                "{} exited {}: {}",
                self.bin.display(),
                output.status,
                stderr.trim()
            )))
        }
    }

    /// Runs a command and returns its output even when the exit status is
    /// non-zero. `superwhisper transcribe` exits 1 while the daemon is warming
    /// but still prints the JSON error the retry loop needs.
    async fn output(
        &self,
        mut command: Command,
        timeout: Duration,
    ) -> Result<std::process::Output, TranscriberError> {
        match tokio::time::timeout(timeout, command.output()).await {
            Ok(Ok(output)) => Ok(output),
            Ok(Err(error)) => Err(TranscriberError::Io(error)),
            Err(_) => Err(TranscriberError::Timeout),
        }
    }

    async fn read_status(&self) -> Result<Status, TranscriberError> {
        let output = self.run_str(&["status"], CONTROL_TIMEOUT).await?;
        serde_json::from_slice(&output.stdout).map_err(|error| {
            TranscriberError::Protocol(format!(
                "status is not JSON: {error}: {}",
                String::from_utf8_lossy(&output.stdout).trim()
            ))
        })
    }
}

#[derive(Debug, Deserialize)]
struct TranscribeAck {
    #[serde(default)]
    ok: bool,
    #[serde(default)]
    session_id: Option<u64>,
    #[serde(default)]
    error: Option<String>,
}

#[derive(Debug, Deserialize)]
struct Status {
    phase: String,
    #[serde(default)]
    last: Option<LastSession>,
}

#[derive(Debug, Deserialize)]
struct LastSession {
    #[serde(default)]
    session_id: Option<u64>,
    #[serde(default)]
    text: Option<String>,
    #[serde(default)]
    raw: Option<String>,
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    audio_seconds: Option<f64>,
    #[serde(default)]
    error: Option<String>,
}

#[async_trait]
impl Transcriber for SuperwhisperCli {
    fn name(&self) -> &'static str {
        "superwhisper-cli"
    }

    async fn transcribe(
        &self,
        wav: &Path,
        request: &TranscribeRequest,
    ) -> Result<Transcript, TranscriberError> {
        // The daemon's language pin is global. Requests are serialized here, so
        // changing it per request cannot race another request.
        let _guard = self.run.lock().await;
        self.set_language(&request.language).await?;
        if let Some(tone) = request.tone.as_deref() {
            self.set_tone(tone).await?;
        }

        let deadline = Instant::now() + self.timeout;
        let ack = loop {
            let mut command = Command::new(&self.bin);
            command
                .arg("transcribe")
                .arg(wav)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .kill_on_drop(true);
            let output = self.output(command, TRANSCRIBE_TIMEOUT).await?;
            let ack: TranscribeAck = match serde_json::from_slice(&output.stdout) {
                Ok(ack) => ack,
                Err(error) => {
                    if !output.status.success() {
                        let stderr = String::from_utf8_lossy(&output.stderr);
                        return Err(TranscriberError::Cli(format!(
                            "{} exited {}: {}: {}",
                            self.bin.display(),
                            output.status,
                            stderr.trim(),
                            String::from_utf8_lossy(&output.stdout).trim()
                        )));
                    }
                    return Err(TranscriberError::Protocol(format!(
                        "transcribe is not JSON: {error}: {}",
                        String::from_utf8_lossy(&output.stdout).trim()
                    )));
                }
            };
            if ack.ok {
                break ack;
            }
            let error = ack
                .error
                .unwrap_or_else(|| "the daemon refused the file".to_string());
            // Switching the processing mode restarts the workers and the
            // daemon answers with this until the model is warm. Wait it out
            // instead of failing the first dictation after the switch.
            if is_warming(&error) && Instant::now() < deadline {
                tokio::time::sleep(Duration::from_millis(500)).await;
                continue;
            }
            return Err(TranscriberError::Rejected(error));
        };
        let session_id = ack
            .session_id
            .ok_or_else(|| TranscriberError::Protocol("transcribe has no session_id".into()))?;

        loop {
            let status = self.read_status().await?;
            if status.phase == "idle"
                && let Some(last) = status.last
                && last.session_id == Some(session_id)
            {
                if let Some(error) = last.error.filter(|error| !error.trim().is_empty()) {
                    return Err(TranscriberError::Rejected(error));
                }
                let text = last
                    .text
                    .or(last.raw)
                    .unwrap_or_default()
                    .trim()
                    .to_string();
                return Ok(Transcript {
                    text,
                    language: last
                        .language
                        .filter(|language| !language.trim().is_empty())
                        .unwrap_or_else(|| request.language.clone()),
                    duration_secs: last.audio_seconds.unwrap_or(0.0),
                });
            }

            if Instant::now() >= deadline {
                return Err(TranscriberError::Timeout);
            }
            tokio::time::sleep(self.poll_interval).await;
        }
    }
}

fn is_warming(error: &str) -> bool {
    let error = error.to_ascii_lowercase();
    error.contains("not ready") || error.contains("warming")
}

#[cfg(test)]
mod tests {
    use super::is_warming;

    #[test]
    fn a_warming_daemon_is_retryable() {
        assert!(is_warming("Not ready: warming"));
        assert!(is_warming("not ready"));
        assert!(!is_warming("no Superwhisper Pro license"));
    }
}
