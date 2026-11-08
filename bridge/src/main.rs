use std::sync::Arc;

use anyhow::{Context, Result};
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;
use warp_android_scribe_bridge::{
    api::{AppState, router},
    config::Config,
    transcriber::{MockTranscriber, SuperwhisperCli, Transcriber},
};

#[tokio::main]
async fn main() -> Result<()> {
    init_tracing();

    let config = Config::from_env().context("bridge configuration is invalid")?;
    let transcriber: Arc<dyn Transcriber> = match config.transcriber.as_str() {
        "superwhisper-cli" => Arc::new(SuperwhisperCli::new(
            config.superwhisper_bin.clone(),
            config.timeout,
        )),
        // Useful while building the Android side without a Superwhisper daemon.
        "mock" => Arc::new(MockTranscriber::new("mock transcript")),
        other => anyhow::bail!(
            "WARP_SCRIBE_TRANSCRIBER={other:?} is not supported (use superwhisper-cli or mock)"
        ),
    };

    let state = AppState::new(transcriber, config.token.clone(), config.timeout)
        .with_limits(config.max_audio_bytes, config.max_audio_secs)
        .with_default_language(config.language.clone());
    let app = router(state);

    let listener = TcpListener::bind(config.addr)
        .await
        .with_context(|| format!("cannot bind {}", config.addr))?;
    tracing::info!(
        addr = %config.addr,
        transcriber = config.transcriber,
        language = config.language,
        timeout_secs = config.timeout.as_secs(),
        "warp-android-scribe bridge listening"
    );

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("HTTP server failed")?;
    Ok(())
}

fn init_tracing() {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_target(false)
        .init();
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("cannot install the ctrl-c handler")
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("cannot install the SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    tracing::info!("shutting down");
}
