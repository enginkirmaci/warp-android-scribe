use std::{
    env,
    path::{Path, PathBuf},
    sync::Arc,
};

use anyhow::{Context, Result};
use tokio::net::TcpListener;
use tracing_subscriber::EnvFilter;
use warp_android_scribe_bridge::{
    api::{AppState, Engine, router},
    config::Config,
    settings::SettingsService,
    transcriber::{HexCli, MockTranscriber, SuperwhisperCli, Transcriber},
};

#[tokio::main]
async fn main() -> Result<()> {
    init_tracing();

    let config = Config::from_env().context("bridge configuration is invalid")?;
    let timeout = config.timeout;
    let state = match config.transcriber.as_str() {
        // The mock stands alone: there is no daemon or app behind it.
        "mock" => AppState::new(
            Arc::new(MockTranscriber::new("mock transcript")),
            config.token.clone(),
            timeout,
        ),
        "superwhisper-cli" => {
            let superwhisper: Arc<dyn Transcriber> = Arc::new(SuperwhisperCli::new(
                config.superwhisper_bin.clone(),
                timeout,
            ));
            let (hex_ready, hex_detail) = hex_status(&config.hex_bin);
            let hex: Arc<dyn Transcriber> = Arc::new(HexCli::new(
                config.hex_bin.clone(),
                config.hex_model.clone(),
                timeout,
            ));
            AppState::new(superwhisper, config.token.clone(), timeout)
                .with_engine(Engine::new("hex", "Hex", hex).with_status(hex_ready, hex_detail))
                .with_default_engine("superwhisper")
        }
        "hex-cli" => {
            let hex: Arc<dyn Transcriber> = Arc::new(HexCli::new(
                config.hex_bin.clone(),
                config.hex_model.clone(),
                timeout,
            ));
            let (hex_ready, hex_detail) = hex_status(&config.hex_bin);
            let superwhisper: Arc<dyn Transcriber> = Arc::new(SuperwhisperCli::new(
                config.superwhisper_bin.clone(),
                timeout,
            ));
            let (superwhisper_ready, superwhisper_detail) = binary_status(&config.superwhisper_bin);
            AppState::new(hex.clone(), config.token.clone(), timeout)
                .with_engine(Engine::new("hex", "Hex", hex).with_status(hex_ready, hex_detail))
                .with_engine(
                    Engine::new("superwhisper", "Superwhisper", superwhisper)
                        .with_status(superwhisper_ready, superwhisper_detail),
                )
                .with_default_engine("hex")
        }
        other => anyhow::bail!(
            "WARP_SCRIBE_TRANSCRIBER={other:?} is not supported \
             (use superwhisper-cli, hex-cli or mock)"
        ),
    }
    .with_limits(config.max_audio_bytes, config.max_audio_secs)
    .with_default_language(config.language.clone())
    .with_settings(Arc::new(SettingsService::new(
        config.superwhisper_bin.clone(),
        timeout,
    )));
    let app = router(state);

    let listener = TcpListener::bind(config.addr)
        .await
        .with_context(|| format!("cannot bind {}", config.addr))?;
    tracing::info!(
        addr = %config.addr,
        default_engine = config.transcriber,
        hex_model = config.hex_model,
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

/// Whether the CLI resolves, and the path it resolves to. Spawning `--version`
/// on every start is not worth it: a binary that is there answers or it does
/// not, and the request that uses it reports the real error.
fn binary_status(bin: &Path) -> (bool, String) {
    match resolve(bin) {
        Some(path) => (true, path.display().to_string()),
        None => (false, format!("{} was not found", bin.display())),
    }
}

/// Hex has a cheap `--version`; putting it in the settings screen's detail line
/// tells the owner which build answers.
fn hex_status(bin: &Path) -> (bool, String) {
    let Some(path) = resolve(bin) else {
        return (false, format!("{} was not found", bin.display()));
    };
    let version = std::process::Command::new(&path)
        .arg("--version")
        .output()
        .ok()
        .filter(|output| output.status.success())
        .map(|output| String::from_utf8_lossy(&output.stdout).trim().to_string())
        .filter(|version| !version.is_empty());
    match version {
        Some(version) => (true, format!("{version} · {}", path.display())),
        None => (true, path.display().to_string()),
    }
}

fn resolve(bin: &Path) -> Option<PathBuf> {
    if bin.components().count() > 1 {
        return bin.is_file().then(|| bin.to_path_buf());
    }
    env::var_os("PATH").and_then(|paths| {
        env::split_paths(&paths)
            .map(|dir| dir.join(bin))
            .find(|candidate| candidate.is_file())
    })
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
