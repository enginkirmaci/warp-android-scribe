//! The Hex desktop app's CLI as a transcriber.
//!
//! `hex model transcribe <wav> --model <id> --language <code>` reads the file
//! and prints the transcript on stdout; there is no session to start or poll.
//! Every call loads the model into a fresh process, so the calls run one at a
//! time: two 886 MB model loads at once is the one way this path can hurt the
//! machine it runs on.

use std::{
    path::{Path, PathBuf},
    process::Stdio,
    time::Duration,
};

use async_trait::async_trait;
use tokio::{process::Command, sync::Mutex};

use super::{TranscribeRequest, Transcriber, TranscriberError, Transcript};

/// `hex model transcribe` exits 4 when it heard no speech; nothing is printed.
const NO_SPEECH: i32 = 4;

#[derive(Debug)]
pub struct HexCli {
    bin: PathBuf,
    model: String,
    timeout: Duration,
    run: Mutex<()>,
}

impl HexCli {
    pub fn new(bin: impl Into<PathBuf>, model: impl Into<String>, timeout: Duration) -> Self {
        Self {
            bin: bin.into(),
            model: model.into(),
            timeout,
            run: Mutex::new(()),
        }
    }

    pub fn model(&self) -> &str {
        &self.model
    }
}

/// `auto` stays `auto`; a regional code narrows to the base code Hex knows
/// (`en-GB` becomes `en`). Hex validates the code itself and refuses an
/// unknown one with a readable message.
pub fn cli_language(language: &str) -> String {
    let language = language.trim();
    if language.eq_ignore_ascii_case("auto") {
        return "auto".to_string();
    }
    language
        .split('-')
        .next()
        .unwrap_or(language)
        .to_ascii_lowercase()
}

#[async_trait]
impl Transcriber for HexCli {
    fn name(&self) -> &'static str {
        "hex-cli"
    }

    async fn transcribe(
        &self,
        wav: &Path,
        request: &TranscribeRequest,
    ) -> Result<Transcript, TranscriberError> {
        let _guard = self.run.lock().await;
        let language = cli_language(&request.language);

        let mut command = Command::new(&self.bin);
        command
            .arg("model")
            .arg("transcribe")
            .arg(wav)
            .arg("--model")
            .arg(&self.model)
            .arg("--language")
            .arg(&language)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);

        let output = match tokio::time::timeout(self.timeout, command.output()).await {
            Ok(Ok(output)) => output,
            Ok(Err(error)) => return Err(TranscriberError::Io(error)),
            Err(_) => return Err(TranscriberError::Timeout),
        };

        if !output.status.success() {
            let code = output.status.code().unwrap_or(-1);
            if code == NO_SPEECH {
                return Ok(Transcript {
                    text: String::new(),
                    language: request.language.clone(),
                    duration_secs: 0.0,
                });
            }
            let stderr = String::from_utf8_lossy(&output.stderr);
            let stdout = String::from_utf8_lossy(&output.stdout);
            let detail = if stderr.trim().is_empty() {
                stdout.trim()
            } else {
                stderr.trim()
            };
            return Err(TranscriberError::Cli(format!(
                "{} exited {code} running model transcribe: {detail}",
                self.bin.display()
            )));
        }

        Ok(Transcript {
            text: String::from_utf8_lossy(&output.stdout).trim().to_string(),
            language: request.language.clone(),
            // The CLI prints text only; the bridge keeps the WAV's own duration.
            duration_secs: 0.0,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::cli_language;

    #[test]
    fn auto_stays_auto() {
        assert_eq!(cli_language("auto"), "auto");
        assert_eq!(cli_language("AUTO"), "auto");
    }

    #[test]
    fn regional_codes_narrow_to_the_base_code() {
        assert_eq!(cli_language("en-GB"), "en");
        assert_eq!(cli_language("pt-BR"), "pt");
        assert_eq!(cli_language("tr"), "tr");
        assert_eq!(cli_language(" TR "), "tr");
    }
}
