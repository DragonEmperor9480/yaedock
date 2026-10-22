//! Metric sources, and brightness, volume, media, power profile, screenshot and
//! screen recording backends.

pub mod brightness;
pub mod media;
pub mod metrics;
pub mod power;
pub mod recorder;
pub mod screenshot;
pub mod volume;

/// Why a brightness or volume change failed.
#[derive(Debug, thiserror::Error)]
pub enum ControlError {
    #[error("no device with id {0}")]
    UnknownDevice(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
