//! The controller task owns the brightness and volume backends. Sessions send
//! it commands, and it publishes the current [`State`] on a watch channel,
//! polling the backends so changes made on the PC reach every phone too.

use std::sync::Arc;
use std::time::Duration;

use tokio::sync::{mpsc, oneshot, watch};
use tokio::task;
use tokio::time::{self, MissedTickBehavior};
use tracing::warn;
use yaedock_proto::{Error, ErrorCode, State};
use yaedock_sys::ControlError;
use yaedock_sys::brightness::BrightnessBackend;
use yaedock_sys::volume::VolumeBackend;

const POLL_INTERVAL: Duration = Duration::from_secs(1);

enum Request {
    Brightness {
        display: String,
        percent: u8,
        reply: oneshot::Sender<Result<(), Error>>,
    },
    Volume {
        percent: Option<u8>,
        muted: Option<bool>,
        reply: oneshot::Sender<Result<(), Error>>,
    },
}

/// A cheap, cloneable handle sessions use to reach the controller.
#[derive(Clone)]
pub struct Controller {
    requests: mpsc::Sender<Request>,
    state: watch::Receiver<Arc<State>>,
}

impl Controller {
    /// Reads the initial state and starts the controller task.
    pub async fn spawn(
        brightness: Arc<dyn BrightnessBackend>,
        volume: Option<Arc<dyn VolumeBackend>>,
    ) -> Self {
        let backends = Backends { brightness, volume };
        let initial = backends.clone().read_state().await;
        let (state_tx, state) = watch::channel(Arc::new(initial));
        let (requests, rx) = mpsc::channel(16);
        tokio::spawn(run(backends, rx, state_tx));
        Self { requests, state }
    }

    /// The current state; call `changed()` on it to wait for updates.
    pub fn state(&self) -> watch::Receiver<Arc<State>> {
        self.state.clone()
    }

    pub async fn set_brightness(&self, display: String, percent: u8) -> Result<(), Error> {
        self.request(|reply| Request::Brightness {
            display,
            percent,
            reply,
        })
        .await
    }

    pub async fn set_volume(&self, percent: Option<u8>, muted: Option<bool>) -> Result<(), Error> {
        self.request(|reply| Request::Volume {
            percent,
            muted,
            reply,
        })
        .await
    }

    async fn request(
        &self,
        make: impl FnOnce(oneshot::Sender<Result<(), Error>>) -> Request,
    ) -> Result<(), Error> {
        let (reply, response) = oneshot::channel();
        let stopped = || error(ErrorCode::BackendFailed, "controller stopped".into());
        self.requests
            .send(make(reply))
            .await
            .map_err(|_| stopped())?;
        response.await.map_err(|_| stopped())?
    }
}

#[derive(Clone)]
struct Backends {
    brightness: Arc<dyn BrightnessBackend>,
    volume: Option<Arc<dyn VolumeBackend>>,
}

impl Backends {
    /// Reads brightness and volume. The backends block (D-Bus calls, `wpctl`),
    /// so this runs on the blocking thread pool.
    async fn read_state(self) -> State {
        let read = task::spawn_blocking(move || {
            let displays = self.brightness.displays().unwrap_or_else(|err| {
                warn!("reading brightness failed: {err}");
                Vec::new()
            });
            let volume = self.volume.as_ref().and_then(|volume| {
                volume
                    .get()
                    .inspect_err(|err| warn!("reading volume failed: {err}"))
                    .ok()
            });
            State { displays, volume }
        });
        read.await.unwrap_or_default()
    }

    async fn apply(&self, request: Request) {
        let brightness = self.brightness.clone();
        let volume = self.volume.clone();
        let (result, reply) = match request {
            Request::Brightness {
                display,
                percent,
                reply,
            } => {
                let result = task::spawn_blocking(move || brightness.set(&display, percent)).await;
                (result.map(|r| r.map_err(control_error)), reply)
            }
            Request::Volume {
                percent,
                muted,
                reply,
            } => {
                let result = task::spawn_blocking(move || match volume {
                    Some(volume) => volume.set(percent, muted).map_err(control_error),
                    None => Err(error(
                        ErrorCode::BackendFailed,
                        "no volume control available".into(),
                    )),
                })
                .await;
                (result, reply)
            }
        };
        let result =
            result.unwrap_or_else(|err| Err(error(ErrorCode::BackendFailed, err.to_string())));
        // The session may have gone away; that is fine.
        let _ = reply.send(result);
    }
}

async fn run(
    backends: Backends,
    mut requests: mpsc::Receiver<Request>,
    state: watch::Sender<Arc<State>>,
) {
    let mut poll = time::interval(POLL_INTERVAL);
    poll.set_missed_tick_behavior(MissedTickBehavior::Delay);
    loop {
        tokio::select! {
            request = requests.recv() => match request {
                Some(request) => backends.apply(request).await,
                None => return,
            },
            _ = poll.tick() => {}
        }
        let new = backends.clone().read_state().await;
        state.send_if_modified(|current| {
            let changed = **current != new;
            if changed {
                *current = Arc::new(new);
            }
            changed
        });
    }
}

fn control_error(err: ControlError) -> Error {
    match err {
        ControlError::UnknownDevice(_) => error(ErrorCode::UnsupportedDisplay, err.to_string()),
        ControlError::Io(_) => error(ErrorCode::BackendFailed, err.to_string()),
    }
}

fn error(code: ErrorCode, message: String) -> Error {
    Error { code, message }
}
