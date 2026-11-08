//! The one seam between the HTTP API and whatever turns audio into text.

use std::{path::Path, sync::Mutex, time::Duration};

use async_trait::async_trait;

pub mod superwhisper;

pub use superwhisper::SuperwhisperCli;

#[derive(Debug, Clone, PartialEq)]
pub struct Transcript {
    pub text: String,
    pub language: String,
    pub duration_secs: f64,
}

#[derive(Debug, thiserror::Error)]
pub enum TranscriberError {
    #[error("superwhisper CLI failed: {0}")]
    Cli(String),
    #[error("superwhisper rejected the transcription: {0}")]
    Rejected(String),
    #[error("timed out waiting for superwhisper")]
    Timeout,
    #[error("invalid superwhisper response: {0}")]
    Protocol(String),
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

#[async_trait]
pub trait Transcriber: Send + Sync {
    /// Short name shown by `GET /health` and in logs.
    fn name(&self) -> &'static str;

    /// Transcribe `wav`, which the caller owns and keeps alive for the duration
    /// of this call. `language` is a Superwhisper language code or `auto`.
    async fn transcribe(&self, wav: &Path, language: &str) -> Result<Transcript, TranscriberError>;
}

/// An in-process transcriber for tests and for developing the phone UI while
/// the NAS is unavailable (`WARP_SCRIBE_TRANSCRIBER=mock`).
#[derive(Debug)]
pub struct MockTranscriber {
    text: String,
    language: String,
    duration_secs: f64,
    delay: Duration,
    failure: Option<String>,
    seen_languages: Mutex<Vec<String>>,
}

impl MockTranscriber {
    pub fn new(text: impl Into<String>) -> Self {
        Self {
            text: text.into(),
            language: "tr".to_string(),
            duration_secs: 1.0,
            delay: Duration::ZERO,
            failure: None,
            seen_languages: Mutex::new(Vec::new()),
        }
    }

    pub fn with_language(mut self, language: impl Into<String>) -> Self {
        self.language = language.into();
        self
    }

    pub fn with_duration_secs(mut self, duration_secs: f64) -> Self {
        self.duration_secs = duration_secs;
        self
    }

    pub fn with_delay(mut self, delay: Duration) -> Self {
        self.delay = delay;
        self
    }

    pub fn failing(message: impl Into<String>) -> Self {
        let mut mock = Self::new(String::new());
        mock.failure = Some(message.into());
        mock
    }

    /// Every language string the server passed in, in request order.
    pub fn seen_languages(&self) -> Vec<String> {
        self.seen_languages
            .lock()
            .map(|seen| seen.clone())
            .unwrap_or_default()
    }
}

#[async_trait]
impl Transcriber for MockTranscriber {
    fn name(&self) -> &'static str {
        "mock"
    }

    async fn transcribe(
        &self,
        _wav: &Path,
        language: &str,
    ) -> Result<Transcript, TranscriberError> {
        if !self.delay.is_zero() {
            tokio::time::sleep(self.delay).await;
        }
        if let Some(message) = &self.failure {
            return Err(TranscriberError::Cli(message.clone()));
        }
        if let Ok(mut seen) = self.seen_languages.lock() {
            seen.push(language.to_string());
        }
        Ok(Transcript {
            text: self.text.clone(),
            language: if language == "auto" {
                self.language.clone()
            } else {
                language.to_string()
            },
            duration_secs: self.duration_secs,
        })
    }
}
