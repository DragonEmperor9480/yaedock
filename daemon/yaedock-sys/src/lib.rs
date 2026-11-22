//! Metric sources, and backends for brightness, volume, media, power profiles,
//! the phone as keyboard, mouse and controller,
//! screenshots, screen recording, and locking or shutting down the PC.

pub mod art;
pub mod brightness;
pub mod desktop;
pub mod gamepad;
pub mod input;
pub mod media;
pub mod metrics;
pub mod opus;
pub mod phone_camera;
pub mod phone_mic;
pub mod phone_speaker;
pub mod power;
pub mod recorder;
pub mod screen;
pub mod screenshot;
pub mod session;
pub mod volume;

/// Kills `child` and leaves it to be reaped on a thread of its own. A
/// GStreamer stuck in a graphics driver cannot be killed and never exits;
/// waiting for it where it is stopped would freeze whatever stopped it.
pub fn stop_child(mut child: std::process::Child) {
    let _ = child.kill();
    std::thread::spawn(move || child.wait());
}

/// Why a brightness or volume change failed.
#[derive(Debug, thiserror::Error)]
pub enum ControlError {
    #[error("no device with id {0}")]
    UnknownDevice(String),
    #[error(transparent)]
    Io(#[from] std::io::Error),
}
