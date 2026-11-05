//! The controller task owns the brightness, volume, media and power profile
//! backends. Sessions send it commands, and it publishes the current [`State`]
//! on a watch channel so changes made on the PC reach every phone too.
//!
//! Audio changes arrive as events from the audio server; brightness, media and
//! the power profile are polled. While no phone is connected the controller
//! reads nothing, and it refreshes the state when a phone connects.

use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::sync::{Notify, mpsc, oneshot, watch};
use tokio::task;
use tokio::time::{self, Instant, MissedTickBehavior};
use tracing::{info, warn};
use yaedock_core::{mangohud, shortcuts};
use yaedock_proto::{
    AppVolume, AudioOutput, DisplayState, Error, ErrorCode, Media, MediaAction, PowerProfiles,
    Recording, Shortcut, State, VolumeState,
};
use yaedock_sys::ControlError;
use yaedock_sys::brightness::BrightnessBackend;
use yaedock_sys::media::MediaBackend;
use yaedock_sys::power::PowerBackend;
use yaedock_sys::recorder::RecorderBackend;
use yaedock_sys::volume::{self, VolumeBackend, Watcher};

const POLL_INTERVAL: Duration = Duration::from_secs(1);
/// How often to look again for a volume control while there is none.
const VOLUME_RETRY: Duration = Duration::from_secs(3);
/// How often to try restarting a volume watcher that stopped.
const WATCH_RETRY: Duration = Duration::from_secs(30);

/// A change a phone asked for.
#[derive(Debug, Clone, PartialEq)]
pub enum Command {
    Brightness {
        display: String,
        percent: u8,
    },
    Volume {
        percent: Option<u8>,
        muted: Option<bool>,
    },
    Mic {
        percent: Option<u8>,
        muted: Option<bool>,
    },
    App {
        id: u32,
        percent: Option<u8>,
        muted: Option<bool>,
    },
    Output {
        id: String,
    },
    Media {
        player: Option<String>,
        action: MediaAction,
    },
    Power {
        profile: String,
    },
    Overlay {
        visible: bool,
    },
}

enum Request {
    Change {
        command: Command,
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

/// Everything the controller reads and changes. A missing backend leaves its
/// part of the state out.
#[derive(Clone)]
pub struct Backends {
    pub brightness: Arc<dyn BrightnessBackend>,
    pub volume: Option<Arc<dyn VolumeBackend>>,
    pub media: Option<Arc<dyn MediaBackend>>,
    pub power: Option<Arc<dyn PowerBackend>>,
    /// Read here; sessions start and stop recordings themselves.
    pub recorder: Option<Arc<dyn RecorderBackend>>,
}

impl Controller {
    /// Reads the initial state and starts the controller task.
    pub async fn spawn(backends: Backends) -> Self {
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

    /// Applies a change; the new state follows on the watch channel.
    pub async fn apply(&self, command: Command) -> Result<(), Error> {
        self.request(|reply| Request::Change { command, reply })
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

/// Runs a blocking backend call (D-Bus, `wpctl`) on the blocking thread pool.
async fn blocking<T: Send + 'static>(call: impl FnOnce() -> T + Send + 'static) -> Option<T> {
    task::spawn_blocking(call).await.ok()
}

impl Backends {
    async fn read_state(self) -> State {
        State {
            displays: self.clone().read_displays().await,
            volume: self.clone().read_volume().await,
            mic: self.clone().read_mic().await,
            outputs: self.clone().read_outputs().await,
            apps: self.clone().read_apps().await,
            media: self.clone().read_media().await,
            power: self.clone().read_power().await,
            shortcuts: read_shortcuts().await,
            recording: self.read_recording().await,
            overlay: read_overlay().await,
        }
    }

    async fn read_displays(self) -> Vec<DisplayState> {
        blocking(move || {
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
        blocking(move || {
            volume
                .get()
                .inspect_err(|err| warn!("reading volume failed: {err}"))
                .ok()
        })
        .await
        .flatten()
    }

    async fn read_mic(self) -> Option<VolumeState> {
        let volume = self.volume?;
        blocking(move || {
            volume
                .mic()
                .inspect_err(|err| warn!("reading the microphone failed: {err}"))
                .ok()
                .flatten()
        })
        .await
        .flatten()
    }

    async fn read_outputs(self) -> Vec<AudioOutput> {
        let Some(volume) = self.volume else {
            return Vec::new();
        };
        blocking(move || {
            volume
                .outputs()
                .inspect_err(|err| warn!("reading audio outputs failed: {err}"))
                .unwrap_or_default()
        })
        .await
        .unwrap_or_default()
    }

    async fn read_apps(self) -> Vec<AppVolume> {
        let Some(volume) = self.volume else {
            return Vec::new();
        };
        blocking(move || {
            volume
                .apps()
                .inspect_err(|err| warn!("reading program volumes failed: {err}"))
                .unwrap_or_default()
        })
        .await
        .unwrap_or_default()
    }

    async fn read_media(self) -> Option<Media> {
        let media = self.media?;
        blocking(move || {
            media
                .current()
                .inspect_err(|err| warn!("reading media players failed: {err}"))
                .ok()
                .flatten()
        })
        .await
        .flatten()
    }

    async fn read_power(self) -> Option<PowerProfiles> {
        let power = self.power?;
        blocking(move || {
            power
                .get()
                .inspect_err(|err| warn!("reading the power profile failed: {err}"))
                .ok()
        })
        .await
        .flatten()
    }

    async fn read_recording(self) -> Option<Recording> {
        let recorder = self.recorder?;
        blocking(move || {
            recorder
                .status()
                .inspect_err(|err| warn!("reading the recorder failed: {err}"))
                .ok()
                .flatten()
        })
        .await
        .flatten()
    }

    /// Starts a volume watcher that wakes `notify` on every change.
    fn watch_volume(&self, notify: &Arc<Notify>) -> Option<Watcher> {
        let notify = notify.clone();
        self.volume
            .as_ref()?
            .watch(Box::new(move || notify.notify_one()))
    }

    async fn apply(&self, command: Command) -> Result<(), Error> {
        let missing = |what: &str| error(ErrorCode::BackendFailed, format!("no {what} available"));
        let volume = || self.volume.clone().ok_or_else(|| missing("volume control"));
        let result = match command {
            Command::Brightness { display, percent } => {
                let brightness = self.brightness.clone();
                blocking(move || brightness.set(&display, percent).map_err(display_error)).await
            }
            Command::Volume { percent, muted } => {
                let volume = volume()?;
                blocking(move || volume.set(percent, muted).map_err(backend_error)).await
            }
            Command::Mic { percent, muted } => {
                let volume = volume()?;
                blocking(move || volume.set_mic(percent, muted).map_err(backend_error)).await
            }
            Command::App { id, percent, muted } => {
                let volume = volume()?;
                blocking(move || volume.set_app(id, percent, muted).map_err(backend_error)).await
            }
            Command::Output { id } => {
                let volume = volume()?;
                blocking(move || volume.set_output(&id).map_err(backend_error)).await
            }
            Command::Media { player, action } => {
                let media = self.media.clone().ok_or_else(|| missing("media control"))?;
                blocking(move || {
                    media
                        .control(player.as_deref(), action)
                        .map_err(backend_error)
                })
                .await
            }
            Command::Power { profile } => {
                let power = self
                    .power
                    .clone()
                    .ok_or_else(|| missing("power profile control"))?;
                blocking(move || power.set(&profile).map_err(backend_error)).await
            }
            Command::Overlay { visible } => {
                blocking(move || {
                    mangohud::set_overlay_visible(visible)
                        .map_err(|err| error(ErrorCode::BackendFailed, format!("{err:#}")))
                })
                .await
            }
        };
        result.unwrap_or_else(|| Err(error(ErrorCode::BackendFailed, "backend failed".into())))
    }
}

/// The shortcut buttons from their file, which the user may edit at any time.
async fn read_shortcuts() -> Vec<Shortcut> {
    blocking(|| {
        shortcuts::load()
            .inspect_err(|err| warn!("reading shortcuts failed: {err:#}"))
            .map(|entries| shortcuts::for_phone(&entries))
            .unwrap_or_default()
    })
    .await
    .unwrap_or_default()
}

/// Whether MangoHud's overlay shows, from its config, which the user or
/// `yaedock fps` may change at any time.
async fn read_overlay() -> Option<bool> {
    blocking(mangohud::overlay_visible).await.flatten()
}

/// What to read after an event.
enum Read {
    All,
    /// What is polled: brightness, media, the power profile, the recorder,
    /// the shortcuts file and MangoHud's config.
    Polled,
    /// Everything an audio server event can touch.
    Audio,
    Displays,
    Volume,
    Mic,
    Apps,
    Media,
    Power,
    Overlay,
}

impl Read {
    /// Only what a change touched.
    fn after(command: &Command) -> Self {
        match command {
            Command::Brightness { .. } => Read::Displays,
            Command::Volume { .. } => Read::Volume,
            Command::Mic { .. } => Read::Mic,
            Command::App { .. } => Read::Apps,
            // The volume shown is the new output's.
            Command::Output { .. } => Read::Audio,
            Command::Media { .. } => Read::Media,
            Command::Power { .. } => Read::Power,
            Command::Overlay { .. } => Read::Overlay,
        }
    }
}

async fn run(
    mut backends: Backends,
    mut requests: mpsc::Receiver<Request>,
    state: watch::Sender<Arc<State>>,
    watchers: Arc<AtomicUsize>,
) {
    let audio_changed = Arc::new(Notify::new());
    let mut watcher = backends.watch_volume(&audio_changed);
    let mut watcher_started = Instant::now();
    let mut volume_searched = Instant::now();
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
                Some(Request::Change { command, reply }) => {
                    let read = Read::after(&command);
                    // The session may have gone away; that is fine.
                    let _ = reply.send(backends.apply(command).await);
                    read
                }
                None => return,
            },
            _ = audio_changed.notified() => Read::Audio,
            _ = poll.tick() => {
                // At login the audio server can start a moment after the daemon,
                // so keep looking for a volume control until one turns up.
                let mut found_volume = false;
                if backends.volume.is_none() && volume_searched.elapsed() >= VOLUME_RETRY {
                    volume_searched = Instant::now();
                    if let Some(Some(found)) = blocking(volume::detect).await {
                        info!("found volume control: {}", found.name());
                        backends.volume = Some(Arc::from(found));
                        watcher = backends.watch_volume(&audio_changed);
                        watcher_started = Instant::now();
                        found_volume = true;
                    }
                }
                let watching = watcher.as_ref().is_some_and(Watcher::is_alive);
                if !watching && backends.volume.is_some() && watcher_started.elapsed() >= WATCH_RETRY {
                    watcher = backends.watch_volume(&audio_changed);
                    watcher_started = Instant::now();
                }
                // A volume control that just turned up still has to be read once.
                if watching && !found_volume { Read::Polled } else { Read::All }
            }
        };
        // Nobody is looking; a phone that connects triggers a full refresh.
        if watchers.load(Ordering::Relaxed) > 0 {
            let current = state.borrow().as_ref().clone();
            let new = read_part(&backends, read, current).await;
            state.send_if_modified(|current| {
                let changed = **current != new;
                if changed {
                    *current = Arc::new(new);
                }
                changed
            });
        }
        if let Some(reply) = refreshed {
            let _ = reply.send(Ok(()));
        }
    }
}

/// Reads part of the state into a copy of `current`.
async fn read_part(backends: &Backends, read: Read, current: State) -> State {
    let b = || backends.clone();
    match read {
        Read::All => b().read_state().await,
        Read::Polled => State {
            displays: b().read_displays().await,
            media: b().read_media().await,
            power: b().read_power().await,
            recording: b().read_recording().await,
            shortcuts: read_shortcuts().await,
            overlay: read_overlay().await,
            ..current
        },
        Read::Audio => State {
            volume: b().read_volume().await,
            mic: b().read_mic().await,
            outputs: b().read_outputs().await,
            apps: b().read_apps().await,
            ..current
        },
        Read::Displays => State {
            displays: b().read_displays().await,
            ..current
        },
        Read::Volume => State {
            volume: b().read_volume().await,
            ..current
        },
        Read::Mic => State {
            mic: b().read_mic().await,
            ..current
        },
        Read::Apps => State {
            apps: b().read_apps().await,
            ..current
        },
        Read::Media => State {
            media: b().read_media().await,
            ..current
        },
        Read::Power => State {
            power: b().read_power().await,
            ..current
        },
        Read::Overlay => State {
            overlay: read_overlay().await,
            ..current
        },
    }
}

fn display_error(err: ControlError) -> Error {
    match err {
        ControlError::UnknownDevice(_) => error(ErrorCode::UnsupportedDisplay, err.to_string()),
        ControlError::Io(_) => error(ErrorCode::BackendFailed, err.to_string()),
    }
}

fn backend_error(err: ControlError) -> Error {
    error(ErrorCode::BackendFailed, err.to_string())
}

fn error(code: ErrorCode, message: String) -> Error {
    Error { code, message }
}
