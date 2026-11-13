//! Metric sources, and backends for brightness, volume, media, power profiles,
//! screenshots, screen recording, and locking or shutting down the PC.

pub mod art;
pub mod brightness;
pub mod desktop;
pub mod media;
pub mod metrics;
pub mod phone_mic;
pub mod power;
pub mod recorder;
pub mod screen;
pub mod screenshot;
pub mod session;
pub mod volume;

/// Why a brightness or volume change failed.
#[derive(Debug, thiserror::Error)]
pub enum ControlError {
    #[error("no device with id {0}")]
    UnknownDevice(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
