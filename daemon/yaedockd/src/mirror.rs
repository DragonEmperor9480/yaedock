//! Mirroring the screen to one phone: asks the desktop to share a monitor,
//! runs the encoder, and hands its frames to the transport.

use std::fs;
use std::io::BufReader;
use std::process::Child;
use std::time::Duration;

use tokio::sync::mpsc::{self, error::TrySendError};
use tokio::task::{JoinHandle, spawn_blocking};
use tokio::time::timeout;
use tracing::{debug, info};
use yaedock_core::paths;
use yaedock_proto::{Screen, ScreenState, ServerMessage, VideoFrame};
use yaedock_sys::screen::{self, ScreenError};

/// How long the user at the PC has to answer the desktop's question.
const ANSWER_WITHIN: Duration = Duration::from_secs(120);

/// A running mirror; dropping it stops the encoder and the share.
pub struct Mirror(JoinHandle<()>);

impl Mirror {
    /// Starts mirroring. `screen` messages say how it goes; frames go to `video`.
    pub fn start(outgoing: mpsc::Sender<ServerMessage>, video: mpsc::Sender<VideoFrame>) -> Self {
        Self(tokio::spawn(async move {
            let message = match run(&outgoing, video).await {
                Ok(()) => "The screen is no longer shared.".to_string(),
                Err(err) => {
                    info!("mirroring the screen failed: {err}");
                    err.to_string()
                }
            };
            let _ = outgoing
                .send(state(ScreenState::Off, None, Some(message)))
                .await;
        }))
    }

    /// Whether it has ended by itself.
    pub fn ended(&self) -> bool {
        self.0.is_finished()
    }
}

impl Drop for Mirror {
    fn drop(&mut self) {
        self.0.abort();
    }
}

/// A `screen` message.
pub fn state(
    state: ScreenState,
    size: Option<(u32, u32)>,
    message: Option<String>,
) -> ServerMessage {
    ServerMessage::Screen(Screen {
        state,
        width: size.map(|s| s.0),
        height: size.map(|s| s.1),
        message,
    })
}

/// The encoder, stopped when the mirror ends or is dropped.
struct Encoder(Child);

impl Drop for Encoder {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

async fn run(
    outgoing: &mpsc::Sender<ServerMessage>,
    video: mpsc::Sender<VideoFrame>,
) -> Result<(), ScreenError> {
    let encoder = spawn_blocking(screen::detect).await.map_err(io_error)??;
    let _ = outgoing.send(state(ScreenState::Asking, None, None)).await;

    // Lets the desktop share the same monitor again without asking.
    let token_path = paths::config_dir().ok().map(|dir| dir.join("screen_token"));
    let token = token_path
        .as_deref()
        .and_then(|path| fs::read_to_string(path).ok());
    let share = timeout(
        ANSWER_WITHIN,
        screen::share(token.as_deref().map(str::trim)),
    )
    .await
    .map_err(|_| ScreenError::Denied)??;
    if let (Some(path), Some(token)) = (&token_path, &share.restore_token) {
        let _ = paths::write_private(path, token.as_bytes());
    }

    let mut encoder = Encoder(screen::encode(&share, encoder)?);
    let frames = encoder.0.stdout.take().ok_or(ScreenError::Unexpected)?;
    let _ = outgoing
        .send(state(ScreenState::On, share.size, None))
        .await;
    info!(size = ?share.size, "mirroring the screen");

    // Ends when the encoder does, the phone leaves, or this task is dropped
    // (which stops the encoder, closing its output).
    spawn_blocking(move || {
        let mut frames = BufReader::new(frames);
        // A phone that cannot keep up misses frames; decoding then has to
        // start over at a keyframe.
        let mut need_key = true;
        while let Some(frame) = screen::read_frame(&mut frames)? {
            if need_key && !frame.key {
                continue;
            }
            match video.try_send(frame) {
                Ok(()) => need_key = false,
                Err(TrySendError::Full(_)) => {
                    debug!("the phone is behind; skipping to the next keyframe");
                    need_key = true;
                }
                Err(TrySendError::Closed(_)) => break,
            }
        }
        Ok::<_, std::io::Error>(())
    })
    .await
    .map_err(io_error)??;
    Ok(())
}

fn io_error(err: tokio::task::JoinError) -> ScreenError {
    ScreenError::Io(std::io::Error::other(err))
}
