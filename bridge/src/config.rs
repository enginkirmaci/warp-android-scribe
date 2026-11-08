//! Environment-driven configuration.
//!
//! One rule matters more than the rest: the bridge listens on the LAN and each
//! request can commit text into whatever field has focus, so the bearer token is
//! required and must be long enough to be worth generating.

use std::{env, net::SocketAddr, path::PathBuf, time::Duration};

pub const DEFAULT_ADDR: &str = "0.0.0.0:8787";
pub const DEFAULT_TIMEOUT_SECS: u64 = 60;
pub const DEFAULT_MAX_AUDIO_BYTES: usize = 10 * 1024 * 1024;
pub const DEFAULT_MAX_AUDIO_SECS: f64 = 120.0;
pub const DEFAULT_LANGUAGE: &str = "tr";
pub const MIN_TOKEN_LEN: usize = 16;

#[derive(Debug, Clone)]
pub struct Config {
    pub addr: SocketAddr,
    pub token: String,
    pub transcriber: String,
    pub superwhisper_bin: PathBuf,
    pub timeout: Duration,
    pub max_audio_bytes: usize,
    pub max_audio_secs: f64,
    pub language: String,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ConfigError {
    #[error("WARP_SCRIBE_TOKEN is required; generate one with `openssl rand -hex 32`")]
    MissingToken,
    #[error("WARP_SCRIBE_TOKEN must be at least {MIN_TOKEN_LEN} characters")]
    ShortToken,
    #[error("WARP_SCRIBE_ADDR is not a valid socket address: {0}")]
    Addr(String),
    #[error("{name} is not a valid number: {value}")]
    Number { name: &'static str, value: String },
    #[error("{name} must be greater than zero")]
    Zero { name: &'static str },
    #[error("language {0:?} is not a language code or `auto`")]
    Language(String),
}

impl Config {
    pub fn from_env() -> Result<Self, ConfigError> {
        let addr = env::var("WARP_SCRIBE_ADDR").unwrap_or_else(|_| DEFAULT_ADDR.to_string());
        let addr = addr.parse().map_err(|_| ConfigError::Addr(addr.clone()))?;

        let token = env::var("WARP_SCRIBE_TOKEN")
            .unwrap_or_default()
            .trim()
            .to_string();
        if token.is_empty() {
            return Err(ConfigError::MissingToken);
        }
        if token.len() < MIN_TOKEN_LEN {
            return Err(ConfigError::ShortToken);
        }

        let transcriber =
            env::var("WARP_SCRIBE_TRANSCRIBER").unwrap_or_else(|_| "superwhisper-cli".to_string());
        let superwhisper_bin = env::var_os("WARP_SCRIBE_SUPERWHISPER_BIN")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from("superwhisper"));

        let timeout_secs = parse_u64("WARP_SCRIBE_TIMEOUT_SECS", DEFAULT_TIMEOUT_SECS)?;
        let max_audio_bytes = parse_usize("WARP_SCRIBE_MAX_AUDIO_BYTES", DEFAULT_MAX_AUDIO_BYTES)?;
        let max_audio_secs = parse_f64("WARP_SCRIBE_MAX_AUDIO_SECS", DEFAULT_MAX_AUDIO_SECS)?;
        let language = validate_language(
            &env::var("WARP_SCRIBE_LANGUAGE").unwrap_or_else(|_| DEFAULT_LANGUAGE.to_string()),
        )?;

        Ok(Self {
            addr,
            token,
            transcriber,
            superwhisper_bin,
            timeout: Duration::from_secs(timeout_secs),
            max_audio_bytes,
            max_audio_secs,
            language,
        })
    }
}

/// Accepts `auto` or a Superwhisper language code such as `tr`, `en` or `en-GB`.
pub fn validate_language(value: &str) -> Result<String, ConfigError> {
    let value = value.trim();
    if value.eq_ignore_ascii_case("auto") {
        return Ok("auto".to_string());
    }

    let mut parts = value.split('-');
    let base = parts.next().unwrap_or_default();
    let region = parts.next();
    let base_ok = (2..=3).contains(&base.len()) && base.chars().all(|c| c.is_ascii_alphabetic());
    let region_ok = region
        .is_none_or(|region| region.len() == 2 && region.chars().all(|c| c.is_ascii_alphabetic()));

    if base_ok && region_ok && parts.next().is_none() {
        Ok(value.to_string())
    } else {
        Err(ConfigError::Language(value.to_string()))
    }
}

fn env_number(name: &'static str, default: String) -> Result<String, ConfigError> {
    Ok(env::var(name).unwrap_or(default))
}

fn parse_u64(name: &'static str, default: u64) -> Result<u64, ConfigError> {
    let value = env_number(name, default.to_string())?;
    match value.parse::<u64>() {
        Ok(0) => Err(ConfigError::Zero { name }),
        Ok(parsed) => Ok(parsed),
        Err(_) => Err(ConfigError::Number { name, value }),
    }
}

fn parse_usize(name: &'static str, default: usize) -> Result<usize, ConfigError> {
    let value = env_number(name, default.to_string())?;
    match value.parse::<usize>() {
        Ok(0) => Err(ConfigError::Zero { name }),
        Ok(parsed) => Ok(parsed),
        Err(_) => Err(ConfigError::Number { name, value }),
    }
}

fn parse_f64(name: &'static str, default: f64) -> Result<f64, ConfigError> {
    let value = env_number(name, default.to_string())?;
    match value.parse::<f64>() {
        Ok(parsed) if parsed.is_finite() && parsed > 0.0 => Ok(parsed),
        Ok(_) => Err(ConfigError::Zero { name }),
        Err(_) => Err(ConfigError::Number { name, value }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn language_accepts_the_codes_superwhisper_uses() {
        for value in ["tr", "en", "en-GB", "auto", "AUTO", "de"] {
            assert!(validate_language(value).is_ok(), "{value}");
        }
    }

    #[test]
    fn language_rejects_junk() {
        for value in ["", "t", "turkish", "tr_TR", "tr-", "-TR", "tr-TUR"] {
            assert!(validate_language(value).is_err(), "{value}");
        }
    }

    #[test]
    fn auto_is_normalized_to_lowercase() {
        assert_eq!(validate_language("AUTO").unwrap(), "auto");
    }
}
