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
use yaedock_proto::{Screen, ScreenMode, ScreenState, ServerMessage, StartScreen, VideoFrame};
use yaedock_sys::screen::{self, Picture, ScreenError, Source};

/// How long the user at the PC has to answer the desktop's question.
const ANSWER_WITHIN: Duration = Duration::from_secs(120);

/// The size of the display made for a phone that names none.
const DISPLAY: (u32, u32) = (1920, 1080);

/// A running mirror; dropping it stops the encoder and the share, and takes
/// away the display made for it.
pub struct Mirror {
    task: JoinHandle<()>,
    /// What the phone asked for.
    pub request: StartScreen,
}

impl Mirror {
    /// Starts showing the screen as `request` says. `screen` messages say how
    /// it goes; frames go to `video`.
    pub fn start(
        request: StartScreen,
        outgoing: mpsc::Sender<ServerMessage>,
        video: mpsc::Sender<VideoFrame>,
    ) -> Self {
        let task = tokio::spawn(async move {
            let message = match run(request, &outgoing, video).await {
                Ok(()) => "The screen is no longer shared.".to_string(),
                Err(err) => {
                    info!("mirroring the screen failed: {err}");
                    err.to_string()
                }
            };
            let _ = outgoing
                .send(state(ScreenState::Off, None, Some(message)))
                .await;
        });
        Self { task, request }
    }

    /// Whether it has ended by itself.
    pub fn ended(&self) -> bool {
        self.task.is_finished()
    }
}

impl Drop for Mirror {
    fn drop(&mut self) {
        self.task.abort();
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
    request: StartScreen,
    outgoing: &mpsc::Sender<ServerMessage>,
    video: mpsc::Sender<VideoFrame>,
) -> Result<(), ScreenError> {
    let encoder = spawn_blocking(screen::detect).await.map_err(io_error)??;
    let _ = outgoing.send(state(ScreenState::Asking, None, None)).await;

    // Lets the desktop share the same monitor again without asking. Each
    // kind of share has its own token: one stands for what was picked.
    let (source, token_file) = match request.mode {
        ScreenMode::Mirror => (Source::Monitor, "screen_token"),
        ScreenMode::Extend => (Source::Virtual, "screen_token_extend"),
    };
    let token_path = paths::config_dir().ok().map(|dir| dir.join(token_file));
    let token = token_path
        .as_deref()
        .and_then(|path| fs::read_to_string(path).ok());
    let share = timeout(
        ANSWER_WITHIN,
        screen::share(source, token.as_deref().map(str::trim)),
    )
    .await
    .map_err(|_| ScreenError::Denied)??;
    if let (Some(path), Some(token)) = (&token_path, &share.restore_token) {
        let _ = paths::write_private(path, token.as_bytes());
    }

    let (picture, size) = match request.mode {
        // The new display takes the size the capture asks for.
        ScreenMode::Extend => {
            let size = screen::sane((
                request.width.unwrap_or(DISPLAY.0),
                request.height.unwrap_or(DISPLAY.1),
            ));
            (Picture::Captured(size), Some(size))
        }
        ScreenMode::Mirror => match share
            .size
            .and_then(|native| screen::fit(native, request.width, request.height))
        {
            Some(smaller) => (Picture::Scaled(smaller), Some(smaller)),
            None => (Picture::AsItIs, share.size),
        },
    };
    let mut encoder = Encoder(screen::encode(&share, encoder, picture)?);
    let frames = encoder.0.stdout.take().ok_or(ScreenError::Unexpected)?;
    let _ = outgoing.send(state(ScreenState::On, size, None)).await;
    info!(mode = ?request.mode, ?size, "showing the screen on the phone");

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
