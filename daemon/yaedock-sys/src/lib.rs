//! Metric sources and brightness/volume backends.

pub mod brightness;
pub mod metrics;

/// Why a brightness or volume change failed.
#[derive(Debug, thiserror::Error)]
pub enum ControlError {
    #[error("no device with id {0}")]
    UnknownDevice(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
