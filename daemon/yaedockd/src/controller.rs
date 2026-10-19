//! The controller task owns the brightness and volume backends. Sessions send
//! it commands, and it publishes the current [`State`] on a watch channel so
//! changes made on the PC reach every phone too.
//!
//! Volume changes arrive as events from the audio server; brightness is polled.
//! While no phone is connected the controller reads nothing, and it refreshes
//! the state when a phone connects.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::sync::{Notify, mpsc, oneshot, watch};
use tokio::task;
use tokio::time::{self, Instant, MissedTickBehavior};
use tracing::{info, warn};
use yaedock_proto::{DisplayState, Error, ErrorCode, State, VolumeState};
use yaedock_sys::ControlError;
use yaedock_sys::brightness::BrightnessBackend;
use yaedock_sys::volume::{VolumeBackend, Watcher};

const POLL_INTERVAL: Duration = Duration::from_secs(1);
/// How often to try restarting a volume watcher that stopped.
const WATCH_RETRY: Duration = Duration::from_secs(30);

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
    /// Read everything now, for a phone that just connected.
    Refresh {
        reply: oneshot::Sender<Result<(), Error>>,
    },
}

/// A cheap, cloneable handle sessions use to reach the controller.
#[derive(Clone)]
pub struct Controller {
    requests: mpsc::Sender<Request>,
    state: watch::Receiver<Arc<State>>,
    /// Sessions currently holding a [`StateWatch`].
    watchers: Arc<AtomicUsize>,
}

/// A session's view of the state. While any exists, the controller keeps the
/// state current; when the last is dropped it stops reading the backends.
pub struct StateWatch {
    pub state: watch::Receiver<Arc<State>>,
    watchers: Arc<AtomicUsize>,
}

impl Drop for StateWatch {
    fn drop(&mut self) {
        self.watchers.fetch_sub(1, Ordering::Relaxed);
    }
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
        let watchers = Arc::new(AtomicUsize::new(0));
        tokio::spawn(run(backends, rx, state_tx, watchers.clone()));
        Self {
            requests,
            state,
            watchers,
        }
    }

    /// Starts watching the state, after bringing it up to date.
    pub async fn watch(&self) -> StateWatch {
        self.watchers.fetch_add(1, Ordering::Relaxed);
        let watch = StateWatch {
            state: self.state.clone(),
            watchers: self.watchers.clone(),
        };
        // If this fails the controller has stopped, and the state stays as it was.
        let _ = self.request(|reply| Request::Refresh { reply }).await;
        watch
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
    /// so reads run on the blocking thread pool.
    async fn read_state(self) -> State {
        State {
            displays: self.clone().read_displays().await,
            volume: self.read_volume().await,
        }
    }

    async fn read_displays(self) -> Vec<DisplayState> {
        task::spawn_blocking(move || {
            self.brightness.displays().unwrap_or_else(|err| {
                warn!("reading brightness failed: {err}");
                Vec::new()
            })
        })
        .await
        .unwrap_or_default()
    }

    async fn read_volume(self) -> Option<VolumeState> {
        let volume = self.volume?;
        task::spawn_blocking(move || {
            volume
                .get()
                .inspect_err(|err| warn!("reading volume failed: {err}"))
                .ok()
        })
        .await
        .ok()
        .flatten()
    }

    /// Starts a volume watcher that wakes `notify` on every change.
    fn watch_volume(&self, notify: &Arc<Notify>) -> Option<Watcher> {
        let notify = notify.clone();
        self.volume
            .as_ref()?
            .watch(Box::new(move || notify.notify_one()))
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
            // `run` answers refreshes itself, after reading the state.
            Request::Refresh { reply } => (Ok(Ok(())), reply),
        };
        let result =
            result.unwrap_or_else(|err| Err(error(ErrorCode::BackendFailed, err.to_string())));
        // The session may have gone away; that is fine.
        let _ = reply.send(result);
    }
}

/// What to read after an event.
enum Read {
    All,
    Displays,
    Volume,
}

async fn run(
    backends: Backends,
    mut requests: mpsc::Receiver<Request>,
    state: watch::Sender<Arc<State>>,
    watchers: Arc<AtomicUsize>,
) {
    let volume_changed = Arc::new(Notify::new());
    let mut watcher = backends.watch_volume(&volume_changed);
    let mut watcher_started = Instant::now();
    if backends.volume.is_some() && watcher.is_none() {
        info!("cannot watch volume changes (is pactl installed?); polling instead");
    }

    let mut poll = time::interval(POLL_INTERVAL);
    poll.set_missed_tick_behavior(MissedTickBehavior::Delay);
    loop {
        let mut refreshed = None;
        let read = tokio::select! {
            request = requests.recv() => match request {
                Some(Request::Refresh { reply }) => {
                    refreshed = Some(reply);
                    Read::All
                }
                Some(request) => {
                    backends.apply(request).await;
                    Read::All
                }
                None => return,
            },
            _ = volume_changed.notified() => Read::Volume,
            _ = poll.tick() => {
                let watching = watcher.as_ref().is_some_and(Watcher::is_alive);
                if !watching && backends.volume.is_some() && watcher_started.elapsed() >= WATCH_RETRY {
                    watcher = backends.watch_volume(&volume_changed);
                    watcher_started = Instant::now();
                }
                if watching { Read::Displays } else { Read::All }
            }
        };
        // Nobody is looking; a phone that connects triggers a full refresh.
        if watchers.load(Ordering::Relaxed) == 0 {
            continue;
        }
        let current = state.borrow().as_ref().clone();
        let new = match read {
            Read::All => backends.clone().read_state().await,
            Read::Displays => State {
                displays: backends.clone().read_displays().await,
                ..current
            },
            Read::Volume => State {
                volume: backends.clone().read_volume().await,
                ..current
            },
        };
        state.send_if_modified(|current| {
            let changed = **current != new;
            if changed {
                *current = Arc::new(new);
            }
            changed
        });
        if let Some(reply) = refreshed {
            let _ = reply.send(Ok(()));
        }
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
