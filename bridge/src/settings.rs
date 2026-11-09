//! Superwhisper profile settings, read and written through the CLI.
//!
//! The daemon owns one profile: language, cleanup tone, vocabulary, and the
//! processing mode all live in `preferences.json` / `settings.json`. The phone
//! can read that profile and edit the parts that change a dictation's text
//! (tone, vocabulary) or its engine (processing mode). Model downloads run in a
//! background Tokio task so an HTTP request never holds the 2.2 GB transfer.

use std::{
    collections::HashMap,
    path::PathBuf,
    process::Stdio,
    sync::Arc,
    time::{Duration, Instant},
};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::{process::Command, sync::Mutex, time::sleep};

use crate::config::TONES;

const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(3 * 60 * 60);
const DOWNLOAD_POLL: Duration = Duration::from_secs(2);
const MAX_TERMS: usize = 200;
const MAX_REPLACEMENTS: usize = 100;
const MAX_TERM_CHARS: usize = 64;

const FALLBACK_TONES: [(&str, &str); 5] = [
    ("casual", "Casual"),
    ("semi-casual", "Semi-casual"),
    ("balanced", "Balanced"),
    ("semi-formal", "Semi-formal"),
    ("formal", "Formal"),
];

#[derive(Debug, Clone)]
pub struct SettingsService {
    bin: PathBuf,
    timeout: Duration,
    poll: Duration,
    apply: Arc<Mutex<ApplyState>>,
}

#[derive(Debug, Clone, Default)]
struct ApplyState {
    target: Option<String>,
    error: Option<String>,
    total: u64,
}

#[derive(Debug, Serialize, Deserialize, Clone, Default)]
pub struct Vocabulary {
    #[serde(default)]
    pub terms: Vec<String>,
    #[serde(default)]
    pub replacements: Vec<Replacement>,
}

#[derive(Debug, Serialize, Deserialize, Clone, PartialEq, Eq)]
pub struct Replacement {
    pub original: String,
    pub with: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct Snapshot {
    pub tone: String,
    pub tones: Vec<OptionItem>,
    pub languages: Vec<OptionItem>,
    pub vocabulary: Vocabulary,
    pub processing: String,
    pub processing_modes: Vec<ProcessingMode>,
    pub processing_target: Option<String>,
    pub processing_error: Option<String>,
    pub download_bytes: u64,
    pub download_total: u64,
    pub download_percent: u32,
    pub models: Vec<ModelState>,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct OptionItem {
    pub label: String,
    pub value: String,
}

#[derive(Debug, Serialize, Deserialize, Clone)]
pub struct ProcessingMode {
    pub value: String,
    pub label: String,
    pub download_bytes: u64,
    pub missing: Vec<String>,
}

#[derive(Debug, Serialize, Clone)]
pub struct ModelState {
    pub id: String,
    pub title: String,
    pub state: String,
    pub bytes: u64,
    pub total: u64,
    pub disk_bytes: u64,
}

#[derive(Debug, thiserror::Error)]
pub enum SettingsError {
    #[error("superwhisper {command} failed: {message}")]
    Cli { command: String, message: String },
    #[error("superwhisper returned invalid JSON: {0}")]
    Protocol(String),
    #[error("{0:?} is not a processing mode")]
    InvalidMode(String),
    #[error("{0}")]
    InvalidVocabulary(String),
    #[error("a processing change is already running")]
    Busy,
    #[error("the {0} download failed{1}")]
    DownloadFailed(String, String),
    #[error("the model download timed out")]
    DownloadTimeout,
    #[error("timed out waiting for superwhisper")]
    Timeout,
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

impl SettingsService {
    pub fn new(bin: impl Into<PathBuf>, timeout: Duration) -> Self {
        Self {
            bin: bin.into(),
            timeout,
            poll: DOWNLOAD_POLL,
            apply: Arc::new(Mutex::new(ApplyState::default())),
        }
    }

    /// Tests use a short poll; production leaves the 2 s default.
    pub fn with_download_poll(mut self, poll: Duration) -> Self {
        self.poll = poll.max(Duration::from_millis(1));
        self
    }

    /// The profile, its options, and the current (or running) processing
    /// change. Three CLI reads; a Settings screen opens this once.
    pub async fn snapshot(&self) -> Result<Snapshot, SettingsError> {
        let apply = self.apply.lock().await.clone();
        let general = self.json(&["general", "show"]).await?;
        let vocabulary = self.json(&["vocab", "show"]).await?;
        let models = self.json(&["models", "show"]).await?;
        Ok(snapshot_from(&general, &vocabulary, &models, &apply))
    }

    /// Replaces the vocabulary list: adds what is new, removes what is gone,
    /// and rewrites a replacement whose target changed.
    pub async fn set_vocabulary(&self, desired: Vocabulary) -> Result<(), SettingsError> {
        let desired = normalize_vocabulary(desired)?;
        let current = self.json(&["vocab", "show"]).await?;
        let current = vocabulary_from(&current);

        for term in &desired.terms {
            if !current.terms.iter().any(|existing| existing == term) {
                self.run_ok(&["vocab", "add", term]).await?;
            }
        }
        for term in &current.terms {
            if !desired.terms.iter().any(|wanted| wanted == term) {
                self.run_ok(&["vocab", "remove", term]).await?;
            }
        }

        let current_replacements: HashMap<&str, &str> = current
            .replacements
            .iter()
            .map(|item| (item.original.as_str(), item.with.as_str()))
            .collect();
        let desired_replacements: HashMap<&str, &str> = desired
            .replacements
            .iter()
            .map(|item| (item.original.as_str(), item.with.as_str()))
            .collect();

        for (original, with) in &desired_replacements {
            match current_replacements.get(original) {
                Some(existing) if existing == with => {}
                Some(_) => {
                    self.run_ok(&["vocab", "remove-replacement", original])
                        .await?;
                    self.run_ok(&["vocab", "replace", original, with]).await?;
                }
                None => {
                    self.run_ok(&["vocab", "replace", original, with]).await?;
                }
            }
        }
        for original in current_replacements.keys() {
            if !desired_replacements.contains_key(original) {
                self.run_ok(&["vocab", "remove-replacement", original])
                    .await?;
            }
        }
        Ok(())
    }

    /// Starts a processing change. Cloud and already-downloaded modes switch
    /// immediately; a mode with missing models downloads them in the
    /// background and switches when the last one is installed.
    pub async fn set_processing(&self, mode: &str) -> Result<(), SettingsError> {
        let mode = mode.trim().to_ascii_lowercase();
        if !matches!(mode.as_str(), "cloud" | "offline" | "offline_lite") {
            return Err(SettingsError::InvalidMode(mode));
        }

        let models = self.json(&["models", "show"]).await?;
        let target =
            find_mode(&models, &mode).ok_or_else(|| SettingsError::InvalidMode(mode.clone()))?;

        if mode == "cloud" || target.missing.is_empty() {
            {
                let mut apply = self.apply.lock().await;
                apply.target = None;
                apply.error = None;
                apply.total = 0;
            }
            self.run_ok(&["models", "select", &mode]).await?;
            return Ok(());
        }

        {
            let mut apply = self.apply.lock().await;
            if let Some(running) = &apply.target {
                if running != &mode {
                    return Err(SettingsError::Busy);
                }
                return Ok(());
            }
            apply.target = Some(mode.clone());
            apply.error = None;
            apply.total = target.download_bytes;
        }

        let service = self.clone();
        tokio::spawn(async move {
            service.apply_mode(mode).await;
        });
        Ok(())
    }

    async fn apply_mode(&self, mode: String) {
        let outcome = self.apply_mode_inner(&mode).await;
        let mut apply = self.apply.lock().await;
        if apply.target.as_deref() != Some(mode.as_str()) {
            return;
        }
        apply.target = None;
        apply.total = 0;
        apply.error = outcome.err().map(|error| error.to_string());
    }

    async fn apply_mode_inner(&self, mode: &str) -> Result<(), SettingsError> {
        let deadline = Instant::now() + DOWNLOAD_TIMEOUT;
        loop {
            if !self.is_active(mode).await {
                return Ok(());
            }
            let models = self.json(&["models", "show"]).await?;
            let target = find_mode(&models, mode)
                .ok_or_else(|| SettingsError::InvalidMode(mode.to_string()))?;
            if target.missing.is_empty() {
                break;
            }

            for id in &target.missing {
                if !self.is_active(mode).await {
                    return Ok(());
                }
                self.run_ok(&["models", "download", id]).await?;
                loop {
                    if !self.is_active(mode).await {
                        return Ok(());
                    }
                    let models = self.json(&["models", "show"]).await?;
                    match model_state(&models, id).as_str() {
                        "installed" => break,
                        "error" => {
                            let error = model_error(&models, id)
                                .map(|error| format!(": {error}"))
                                .unwrap_or_default();
                            return Err(SettingsError::DownloadFailed(id.clone(), error));
                        }
                        _ => {}
                    }
                    if Instant::now() >= deadline {
                        return Err(SettingsError::DownloadTimeout);
                    }
                    sleep(self.poll).await;
                }
            }
        }

        if !self.is_active(mode).await {
            return Ok(());
        }
        self.run_ok(&["models", "select", mode]).await
    }

    async fn is_active(&self, mode: &str) -> bool {
        self.apply.lock().await.target.as_deref() == Some(mode)
    }

    async fn json(&self, args: &[&str]) -> Result<Value, SettingsError> {
        let output = self.run(args).await?;
        if !output.status.success() {
            return Err(SettingsError::Cli {
                command: args.join(" "),
                message: stderr(&output),
            });
        }
        serde_json::from_slice(&output.stdout).map_err(|error| {
            SettingsError::Protocol(format!(
                "{error}: {}",
                String::from_utf8_lossy(&output.stdout).trim()
            ))
        })
    }

    async fn run_ok(&self, args: &[&str]) -> Result<(), SettingsError> {
        let output = self.run(args).await?;
        if output.status.success() {
            Ok(())
        } else {
            Err(SettingsError::Cli {
                command: args.join(" "),
                message: stderr(&output),
            })
        }
    }

    async fn run(&self, args: &[&str]) -> Result<std::process::Output, SettingsError> {
        let mut command = Command::new(&self.bin);
        command
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        match tokio::time::timeout(self.timeout, command.output()).await {
            Ok(Ok(output)) => Ok(output),
            Ok(Err(error)) => Err(SettingsError::Io(error)),
            Err(_) => Err(SettingsError::Timeout),
        }
    }
}

fn stderr(output: &std::process::Output) -> String {
    let stderr = String::from_utf8_lossy(&output.stderr);
    let stderr = stderr.trim();
    if stderr.is_empty() {
        format!("exited {}", output.status)
    } else {
        stderr.to_string()
    }
}

fn snapshot_from(
    general: &Value,
    vocabulary: &Value,
    models: &Value,
    apply: &ApplyState,
) -> Snapshot {
    let tone = general["config"]["tone"]
        .as_str()
        .unwrap_or("semi-formal")
        .to_string();
    let tones = option_items(&general["options"]["tones"]).unwrap_or_else(|| {
        FALLBACK_TONES
            .iter()
            .map(|(value, label)| OptionItem {
                label: (*label).to_string(),
                value: (*value).to_string(),
            })
            .collect()
    });
    let languages = option_items(&general["options"]["languages"]).unwrap_or_default();
    let vocabulary = vocabulary_from(vocabulary);
    let processing = models["processing"].as_str().unwrap_or("cloud").to_string();
    let processing_modes = processing_modes(models);
    let model_states = model_states(models);

    let (download_bytes, download_total) = match &apply.target {
        Some(target) => {
            let missing = processing_modes
                .iter()
                .find(|mode| &mode.value == target)
                .map(|mode| mode.download_bytes)
                .unwrap_or(0);
            (apply.total.saturating_sub(missing), apply.total)
        }
        None => (0, 0),
    };
    let download_percent = download_bytes
        .saturating_mul(100)
        .checked_div(download_total)
        .map(|percent| percent.min(100) as u32)
        .unwrap_or(0);

    Snapshot {
        tone,
        tones,
        languages,
        vocabulary,
        processing,
        processing_modes,
        processing_target: apply.target.clone(),
        processing_error: apply.error.clone(),
        download_bytes,
        download_total,
        download_percent,
        models: model_states,
    }
}

fn option_items(value: &Value) -> Option<Vec<OptionItem>> {
    let items = value.as_array()?;
    Some(
        items
            .iter()
            .filter_map(|item| {
                Some(OptionItem {
                    label: item["label"].as_str()?.to_string(),
                    value: item["value"].as_str()?.to_string(),
                })
            })
            .collect(),
    )
}

fn vocabulary_from(value: &Value) -> Vocabulary {
    let terms = value["config"]["terms"]
        .as_array()
        .map(|terms| {
            terms
                .iter()
                .filter_map(|term| term.as_str().map(str::to_string))
                .collect()
        })
        .unwrap_or_default();
    let replacements = value["config"]["replacements"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    Some(Replacement {
                        original: item["original"].as_str()?.to_string(),
                        with: item["with"].as_str()?.to_string(),
                    })
                })
                .collect()
        })
        .unwrap_or_default();
    Vocabulary {
        terms,
        replacements,
    }
}

fn processing_modes(models: &Value) -> Vec<ProcessingMode> {
    models["processingModes"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .filter_map(|item| {
                    Some(ProcessingMode {
                        value: item["value"].as_str()?.to_string(),
                        label: item["label"].as_str().unwrap_or_default().to_string(),
                        download_bytes: item["downloadBytes"].as_u64().unwrap_or(0),
                        missing: item["missing"]
                            .as_array()
                            .map(|missing| {
                                missing
                                    .iter()
                                    .filter_map(|id| id.as_str().map(str::to_string))
                                    .collect()
                            })
                            .unwrap_or_default(),
                    })
                })
                .collect()
        })
        .unwrap_or_default()
}

fn find_mode(models: &Value, mode: &str) -> Option<ProcessingMode> {
    processing_modes(models)
        .into_iter()
        .find(|candidate| candidate.value == mode)
}

fn model_states(models: &Value) -> Vec<ModelState> {
    models["models"]
        .as_array()
        .map(|items| {
            items
                .iter()
                .map(|item| ModelState {
                    id: item["id"].as_str().unwrap_or_default().to_string(),
                    title: item["title"].as_str().unwrap_or_default().to_string(),
                    state: item["download"]["state"]
                        .as_str()
                        .unwrap_or("missing")
                        .to_string(),
                    bytes: item["download"]["bytes"].as_u64().unwrap_or(0),
                    total: item["download"]["total"].as_u64().unwrap_or(0),
                    disk_bytes: item["diskBytes"].as_u64().unwrap_or(0),
                })
                .collect()
        })
        .unwrap_or_default()
}

fn model_state(models: &Value, id: &str) -> String {
    models["models"]
        .as_array()
        .and_then(|items| items.iter().find(|item| item["id"] == id))
        .and_then(|item| item["download"]["state"].as_str())
        .unwrap_or("missing")
        .to_string()
}

fn model_error(models: &Value, id: &str) -> Option<String> {
    models["models"]
        .as_array()
        .and_then(|items| items.iter().find(|item| item["id"] == id))
        .and_then(|item| item["download"]["error"].as_str())
        .map(str::to_string)
}

fn normalize_vocabulary(vocabulary: Vocabulary) -> Result<Vocabulary, SettingsError> {
    if vocabulary.terms.len() > MAX_TERMS {
        return Err(SettingsError::InvalidVocabulary(format!(
            "at most {MAX_TERMS} vocabulary terms are allowed"
        )));
    }
    let mut terms = Vec::with_capacity(vocabulary.terms.len());
    for term in vocabulary.terms {
        let term = term.trim().to_string();
        validate_vocabulary_text("term", &term)?;
        if !terms.contains(&term) {
            terms.push(term);
        }
    }

    if vocabulary.replacements.len() > MAX_REPLACEMENTS {
        return Err(SettingsError::InvalidVocabulary(format!(
            "at most {MAX_REPLACEMENTS} replacements are allowed"
        )));
    }
    let mut replacements: Vec<Replacement> = Vec::with_capacity(vocabulary.replacements.len());
    for replacement in vocabulary.replacements {
        let original = replacement.original.trim().to_string();
        let with = replacement.with.trim().to_string();
        validate_vocabulary_text("replacement source", &original)?;
        validate_vocabulary_text("replacement target", &with)?;
        if replacements.iter().any(|item| item.original == original) {
            return Err(SettingsError::InvalidVocabulary(format!(
                "replacement {original:?} is listed twice"
            )));
        }
        replacements.push(Replacement { original, with });
    }

    Ok(Vocabulary {
        terms,
        replacements,
    })
}

fn validate_vocabulary_text(kind: &str, value: &str) -> Result<(), SettingsError> {
    if value.is_empty() {
        return Err(SettingsError::InvalidVocabulary(format!(
            "a {kind} cannot be empty"
        )));
    }
    if value.chars().count() > MAX_TERM_CHARS {
        return Err(SettingsError::InvalidVocabulary(format!(
            "a {kind} is limited to {MAX_TERM_CHARS} characters"
        )));
    }
    if value.starts_with('-') {
        return Err(SettingsError::InvalidVocabulary(format!(
            "a {kind} cannot start with '-'"
        )));
    }
    if value.chars().any(char::is_control) {
        return Err(SettingsError::InvalidVocabulary(format!(
            "a {kind} cannot contain control characters"
        )));
    }
    Ok(())
}

/// The values `/transcribe?tone=` accepts, used by the API layer and tests.
pub fn is_tone(value: &str) -> bool {
    TONES.contains(&value)
}
