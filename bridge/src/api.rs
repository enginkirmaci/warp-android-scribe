//! The HTTP surface: `POST /transcribe` plus a token-checked `GET /health`.

use std::{
    io::Write,
    path::PathBuf,
    sync::Arc,
    time::{Duration, Instant},
};

use axum::{
    Json, Router,
    body::{Body, to_bytes},
    extract::{Query, Request, State},
    http::{HeaderMap, StatusCode, header},
    middleware::{self, Next},
    response::{IntoResponse, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use subtle::ConstantTimeEq;
use tower_http::trace::TraceLayer;

use crate::{
    config,
    transcriber::{Transcriber, TranscriberError, Transcript},
    wav,
};

const SERVICE: &str = "warp-android-scribe-bridge";

#[derive(Clone)]
pub struct AppState {
    pub transcriber: Arc<dyn Transcriber>,
    token: Arc<str>,
    timeout: Duration,
    max_audio_bytes: usize,
    max_audio_secs: f64,
    default_language: String,
    work_dir: PathBuf,
    started: Instant,
}

impl AppState {
    pub fn new(
        transcriber: Arc<dyn Transcriber>,
        token: impl Into<Arc<str>>,
        timeout: Duration,
    ) -> Self {
        Self {
            transcriber,
            token: token.into(),
            timeout,
            max_audio_bytes: config::DEFAULT_MAX_AUDIO_BYTES,
            max_audio_secs: config::DEFAULT_MAX_AUDIO_SECS,
            default_language: config::DEFAULT_LANGUAGE.to_string(),
            work_dir: std::env::temp_dir(),
            started: Instant::now(),
        }
    }

    pub fn with_limits(mut self, max_audio_bytes: usize, max_audio_secs: f64) -> Self {
        self.max_audio_bytes = max_audio_bytes;
        self.max_audio_secs = max_audio_secs;
        self
    }

    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    pub fn with_default_language(mut self, language: impl Into<String>) -> Self {
        self.default_language = language.into();
        self
    }

    /// Where request WAVs live while the transcriber works. The file is deleted
    /// when the request returns; tests point this at a scratch directory.
    pub fn with_work_dir(mut self, work_dir: impl Into<PathBuf>) -> Self {
        self.work_dir = work_dir.into();
        self
    }
}

pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/health", get(health))
        .route("/transcribe", post(transcribe))
        .route_layer(middleware::from_fn_with_state(state.clone(), require_token))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

#[derive(Debug, Serialize)]
struct Health {
    ok: bool,
    service: &'static str,
    version: &'static str,
    transcriber: &'static str,
    default_language: String,
    uptime_secs: u64,
}

async fn health(State(state): State<AppState>) -> Json<Health> {
    Json(Health {
        ok: true,
        service: SERVICE,
        version: env!("CARGO_PKG_VERSION"),
        transcriber: state.transcriber.name(),
        default_language: state.default_language.clone(),
        uptime_secs: state.started.elapsed().as_secs(),
    })
}

#[derive(Debug, Deserialize)]
struct TranscribeQuery {
    #[serde(default)]
    lang: Option<String>,
}

#[derive(Debug, Serialize)]
struct TranscribeResponse {
    text: String,
    language: String,
    duration: f64,
}

#[derive(Debug, Serialize)]
struct ErrorBody {
    error: String,
    code: &'static str,
}

/// A boxed response keeps the handlers' error type pointer-sized; clippy is
/// right that a bare `Response` makes every `Result` in the call chain large.
struct ApiError(Box<Response>);

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        *self.0
    }
}

async fn transcribe(
    State(state): State<AppState>,
    Query(query): Query<TranscribeQuery>,
    headers: HeaderMap,
    body: Body,
) -> Result<Json<TranscribeResponse>, ApiError> {
    reject_unsupported_content_type(&headers)?;
    reject_oversized_declaration(&headers, state.max_audio_bytes)?;

    let bytes = to_bytes(body, state.max_audio_bytes).await.map_err(|_| {
        fail(
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_large",
            format!("audio exceeds {} bytes", state.max_audio_bytes),
        )
    })?;
    if bytes.is_empty() {
        return Err(fail(
            StatusCode::BAD_REQUEST,
            "empty_audio",
            "the request body is empty",
        ));
    }

    let wav = wav::inspect(&bytes)
        .map_err(|error| fail(StatusCode::BAD_REQUEST, "invalid_wav", error.to_string()))?;
    if wav.duration_secs > state.max_audio_secs {
        return Err(fail(
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_long",
            format!(
                "audio is {:.1}s; the limit is {:.0}s",
                wav.duration_secs, state.max_audio_secs
            ),
        ));
    }

    let language = match query.lang.as_deref() {
        Some(language) => config::validate_language(language).map_err(|error| {
            fail(
                StatusCode::BAD_REQUEST,
                "invalid_language",
                error.to_string(),
            )
        })?,
        None => state.default_language.clone(),
    };

    let mut temp = tempfile::Builder::new()
        .prefix("warp-scribe-")
        .suffix(".wav")
        .tempfile_in(&state.work_dir)
        .map_err(|error| {
            fail(
                StatusCode::INTERNAL_SERVER_ERROR,
                "io",
                format!("cannot create a temp file: {error}"),
            )
        })?;
    temp.write_all(&bytes).map_err(|error| {
        fail(
            StatusCode::INTERNAL_SERVER_ERROR,
            "io",
            format!("cannot write the temp file: {error}"),
        )
    })?;
    temp.flush().map_err(|error| {
        fail(
            StatusCode::INTERNAL_SERVER_ERROR,
            "io",
            format!("cannot flush the temp file: {error}"),
        )
    })?;

    tracing::info!(
        bytes = bytes.len(),
        audio_secs = wav.duration_secs,
        language = %language,
        "transcribing"
    );

    let outcome = tokio::time::timeout(
        state.timeout,
        state.transcriber.transcribe(temp.path(), &language),
    )
    .await;

    let transcript = match outcome {
        Err(_) => {
            return Err(fail(
                StatusCode::GATEWAY_TIMEOUT,
                "timeout",
                "transcription timed out",
            ));
        }
        Ok(Err(error)) => return Err(transcriber_error(error)),
        Ok(Ok(transcript)) => transcript,
    };

    // The transcriber has read the file; do not leave the recording in /tmp.
    drop(temp);

    let duration = response_duration(&transcript, &wav);
    tracing::info!(
        chars = transcript.text.chars().count(),
        duration_secs = duration,
        "transcribed"
    );

    Ok(Json(TranscribeResponse {
        text: transcript.text,
        language: transcript.language,
        duration,
    }))
}

fn response_duration(transcript: &Transcript, wav: &wav::WavInfo) -> f64 {
    let duration = if transcript.duration_secs.is_finite() && transcript.duration_secs > 0.0 {
        transcript.duration_secs
    } else {
        wav.duration_secs
    };
    (duration * 100.0).round() / 100.0
}

fn reject_unsupported_content_type(headers: &HeaderMap) -> Result<(), ApiError> {
    let Some(content_type) = headers.get(header::CONTENT_TYPE) else {
        return Ok(());
    };
    let content_type = content_type.to_str().unwrap_or_default();
    let accepted = ["audio/wav", "audio/x-wav", "application/octet-stream"]
        .iter()
        .any(|prefix| content_type.starts_with(prefix));
    if accepted {
        Ok(())
    } else {
        Err(fail(
            StatusCode::UNSUPPORTED_MEDIA_TYPE,
            "unsupported_media_type",
            "Content-Type must be audio/wav",
        ))
    }
}

fn reject_oversized_declaration(
    headers: &HeaderMap,
    max_audio_bytes: usize,
) -> Result<(), ApiError> {
    let declared = headers
        .get(header::CONTENT_LENGTH)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.parse::<usize>().ok());
    match declared {
        Some(length) if length > max_audio_bytes => Err(fail(
            StatusCode::PAYLOAD_TOO_LARGE,
            "too_large",
            format!("audio exceeds {max_audio_bytes} bytes"),
        )),
        _ => Ok(()),
    }
}

fn transcriber_error(error: TranscriberError) -> ApiError {
    match error {
        TranscriberError::Timeout => fail(
            StatusCode::GATEWAY_TIMEOUT,
            "timeout",
            "transcription timed out",
        ),
        TranscriberError::Rejected(message) => {
            tracing::warn!(%message, "superwhisper rejected the transcription");
            fail(StatusCode::BAD_GATEWAY, "transcriber_rejected", message)
        }
        TranscriberError::Cli(message) => {
            tracing::warn!(%message, "superwhisper CLI failed");
            fail(StatusCode::BAD_GATEWAY, "transcriber_failed", message)
        }
        TranscriberError::Protocol(message) => {
            tracing::warn!(%message, "superwhisper returned an invalid response");
            fail(StatusCode::BAD_GATEWAY, "transcriber_protocol", message)
        }
        TranscriberError::Io(io_error) => {
            tracing::warn!(%io_error, "superwhisper I/O failed");
            fail(
                StatusCode::BAD_GATEWAY,
                "transcriber_io",
                format!("cannot run the transcriber: {io_error}"),
            )
        }
    }
}

async fn require_token(State(state): State<AppState>, request: Request, next: Next) -> Response {
    if bearer_matches(request.headers(), &state.token) {
        next.run(request).await
    } else {
        fail(
            StatusCode::UNAUTHORIZED,
            "unauthorized",
            "missing or invalid bearer token",
        )
        .into_response()
    }
}

fn bearer_matches(headers: &HeaderMap, expected: &str) -> bool {
    let Some(value) = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
    else {
        return false;
    };
    let Some(token) = value.strip_prefix("Bearer ") else {
        return false;
    };
    token
        .trim()
        .as_bytes()
        .ct_eq(expected.as_bytes())
        .unwrap_u8()
        == 1
}

fn fail(status: StatusCode, code: &'static str, message: impl Into<String>) -> ApiError {
    ApiError(Box::new(
        (
            status,
            Json(ErrorBody {
                error: message.into(),
                code,
            }),
        )
            .into_response(),
    ))
}
