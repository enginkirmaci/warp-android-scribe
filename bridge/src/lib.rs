//! The warp-android-scribe bridge.
//!
//! A small HTTP server that runs beside a Superwhisper daemon, turns an uploaded
//! WAV into text through the `superwhisper` CLI, and answers the one request the
//! Android IME makes. The binary is `main.rs`; this library boundary exists so
//! the router and the CLI runner can be tested without a daemon.

pub mod api;
pub mod config;
pub mod transcriber;
pub mod wav;
