//! One session per connected phone. Sessions speak only in protocol messages
//! over channels, so every transport (WebSocket now, Bluetooth later) can
//! share this code.

use std::collections::{BTreeMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::{mpsc, watch};
use tokio::time::{self, Instant, sleep, timeout};
use tracing::{debug, info, warn};
use yaedock_core::control::ConnectedPhone;
use yaedock_core::devices::DeviceStore;
use yaedock_core::pairing;
use yaedock_core::sampler::Snapshot;
use yaedock_proto::{
    ClientMessage, Error, ErrorCode, Hello, MetricGroup, PROTOCOL_VERSION, RecordingSaved,
    Screenshot, ServerMessage, SetVolume, Stats, Welcome,
};
use yaedock_sys::metrics::SystemInfo;
use yaedock_sys::recorder::{self, RecorderBackend};
use yaedock_sys::screenshot;

use crate::controller::{Command, Controller};

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const PING_AFTER: Duration = Duration::from_secs(10);
const DROP_AFTER: Duration = Duration::from_secs(30);
/// Delay before rejecting a phone, to slow down guessing.
const REJECT_DELAY: Duration = Duration::from_secs(1);
/// Media and power profile commands waiting at most; more are dropped.
const MAX_QUEUED: usize = 8;

const ALL_GROUPS: [MetricGroup; 7] = [
    MetricGroup::Cpu,
    MetricGroup::Mem,
    MetricGroup::Temps,
    MetricGroup::Net,
    MetricGroup::Disk,
    MetricGroup::Gpu,
    MetricGroup::Game,
];

/// What every session shares.
pub struct Shared {
    pub snapshots: watch::Receiver<Arc<Snapshot>>,
    pub controller: Controller,
    pub info: SystemInfo,
    pub sensors: Vec<String>,
    pub gpus: Vec<yaedock_proto::GpuInfo>,
    pub tick: Duration,
    pub devices: DeviceStore,
    /// Serializes checks of tokens and pairing codes, which read and write files.
    pub auth_lock: Mutex<()>,
    pub phones: Arc<Phones>,
    /// OBS Studio, when installed; its state reaches phones through the controller.
    pub recorder: Option<Arc<dyn RecorderBackend>>,
}

/// The phones connected right now, as the control socket reports them.
#[derive(Default)]
pub struct Phones {
    next_id: AtomicU64,
    connected: Mutex<BTreeMap<u64, ConnectedPhone>>,
}

impl Phones {
    /// Whether any phone is connected.
    pub fn any(&self) -> bool {
        !self.lock().is_empty()
    }

    pub fn list(&self) -> Vec<ConnectedPhone> {
        self.lock().values().cloned().collect()
    }

    /// Records a connected phone until the returned guard is dropped.
    fn add(&self, phone: ConnectedPhone) -> PhoneGuard<'_> {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        self.lock().insert(id, phone);
        PhoneGuard { phones: self, id }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, BTreeMap<u64, ConnectedPhone>> {
        self.connected.lock().unwrap_or_else(|err| err.into_inner())
    }
}

struct PhoneGuard<'a> {
    phones: &'a Phones,
    id: u64,
}

impl Drop for PhoneGuard<'_> {
    fn drop(&mut self) {
        self.phones.lock().remove(&self.id);
    }
}

/// Messages received from the phone, as the transport delivers them.
pub enum Incoming {
    Message(ClientMessage),
    /// A frame that was not a valid message; the text says why.
    Invalid(String),
}

/// Runs one session until the phone disconnects or goes silent.
///
/// Returns when `incoming` closes or the session ends; dropping `outgoing`
/// tells the transport to close the connection.
/// `peer` describes where the phone connects from, for status reports.
pub async fn run(
    shared: Arc<Shared>,
    peer: String,
    mut incoming: mpsc::Receiver<Incoming>,
    outgoing: mpsc::Sender<ServerMessage>,
) {
    let hello = match timeout(HELLO_TIMEOUT, incoming.recv()).await {
        Ok(Some(Incoming::Message(ClientMessage::Hello(hello)))) => hello,
        Ok(None) => return,
        Err(_) => {
            debug!("no hello within {HELLO_TIMEOUT:?}");
            return;
        }
        Ok(Some(_)) => {
            send_error(&outgoing, ErrorCode::BadRequest, "expected hello").await;
            return;
        }
    };
    if hello.version != PROTOCOL_VERSION {
        let message = format!("daemon speaks protocol version {PROTOCOL_VERSION}");
        send_error(&outgoing, ErrorCode::UnsupportedVersion, &message).await;
        return;
    }
    let new_token = match authenticate(&shared, &hello) {
        Ok(Auth::Known) => None,
        Ok(Auth::Paired(token)) => {
            info!(device = %hello.device_name, id = %hello.device_id, "paired a new phone");
            Some(token)
        }
        Ok(Auth::Denied) => {
            info!(device = %hello.device_name, id = %hello.device_id, "rejected a phone");
            sleep(REJECT_DELAY).await;
            let message = "unknown device or token, or wrong pairing code";
            send_error(&outgoing, ErrorCode::Unauthorized, message).await;
            return;
        }
        Err(err) => {
            warn!("checking a phone's credentials failed: {err:#}");
            send_error(
                &outgoing,
                ErrorCode::Unauthorized,
                "could not check credentials",
            )
            .await;
            return;
        }
    };
    info!(device = %hello.device_name, id = %hello.device_id, "phone connected");
    let _registered = shared.phones.add(ConnectedPhone {
        device_id: hello.device_id.clone(),
        name: hello.device_name.clone(),
        address: peer,
        connected_at: pairing::now(),
    });

    let mut session = Session::new();
    if session
        .serve(&shared, new_token, &mut incoming, &outgoing)
        .await
        .is_none()
    {
        debug!("transport closed");
    }
    info!(device = %hello.device_name, "phone disconnected");
}

enum Auth {
    /// A paired phone with a valid token.
    Known,
    /// A new phone with the right pairing code, and the token it was given.
    Paired(String),
    Denied,
}

/// Checks the token of a paired phone, or the pairing code of a new one.
fn authenticate(shared: &Shared, hello: &Hello) -> anyhow::Result<Auth> {
    let _guard = shared
        .auth_lock
        .lock()
        .unwrap_or_else(|err| err.into_inner());
    if let Some(token) = &hello.token
        && shared.devices.authorize(&hello.device_id, token)?
    {
        return Ok(Auth::Known);
    }
    if let Some(code) = &hello.code
        && pairing::redeem(code, &hello.device_name)?
    {
        let token = shared.devices.add(&hello.device_id, &hello.device_name)?;
        return Ok(Auth::Paired(token));
    }
    Ok(Auth::Denied)
}

struct Session {
    groups: HashSet<MetricGroup>,
    /// Send stats every this many sampler ticks.
    every: u64,
    ticks: u64,
    last_rx: Instant,
    last_tx: Instant,
    /// Brightness and volume changes waiting to be applied. Only the newest
    /// value of each is kept, so a fast slider drag skips intermediate steps
    /// instead of queueing every one of them.
    pending_brightness: BTreeMap<String, u8>,
    pending_volume: Option<SetVolume>,
    pending_mic: Option<SetVolume>,
    pending_apps: BTreeMap<u32, SetVolume>,
    /// Media and power profile commands, applied in order: two taps on
    /// "next" skip two tracks.
    pending_commands: VecDeque<Command>,
    /// Whether a change is being applied; at most one runs at a time.
    changing: bool,
    /// Whether a screenshot is being taken; a second tap meanwhile is refused.
    screenshotting: Arc<AtomicBool>,
    /// Whether a recording is being started or stopped, likewise.
    recording_busy: Arc<AtomicBool>,
}

impl Session {
    fn new() -> Self {
        Self {
            groups: ALL_GROUPS.into(),
            every: 1,
            ticks: 0,
            last_rx: Instant::now(),
            last_tx: Instant::now(),
            pending_brightness: BTreeMap::new(),
            pending_volume: None,
            pending_mic: None,
            pending_apps: BTreeMap::new(),
            pending_commands: VecDeque::new(),
            changing: false,
            screenshotting: Arc::new(AtomicBool::new(false)),
            recording_busy: Arc::new(AtomicBool::new(false)),
        }
    }

    /// Returns `None` when the transport closes.
    async fn serve(
        &mut self,
        shared: &Shared,
        new_token: Option<String>,
        incoming: &mut mpsc::Receiver<Incoming>,
        outgoing: &mpsc::Sender<ServerMessage>,
    ) -> Option<()> {
        let mut snapshots = shared.snapshots.clone();
        // Held for the whole session, so the controller keeps the state current.
        let mut watch = shared.controller.watch().await;
        let state = &mut watch.state;
        let welcome = Welcome {
            version: PROTOCOL_VERSION,
            hostname: shared.info.hostname.clone(),
            os: shared.info.os.clone(),
            cpu_model: shared.info.cpu_model.clone(),
            cores: shared.info.cores,
            sensors: shared.sensors.clone(),
            token: new_token,
            state: state.borrow_and_update().as_ref().clone(),
            gpus: shared.gpus.clone(),
            screenshot: screenshot::detect().is_some(),
            recorder: recorder::detect().is_some(),
        };
        self.send(outgoing, ServerMessage::Welcome(welcome)).await?;
        snapshots.mark_unchanged();

        // Results of brightness and volume changes, which run as separate tasks
        // so the session keeps reading messages (and newer values) meanwhile.
        let (done_tx, mut done) = mpsc::channel(1);
        let mut keepalive = time::interval(Duration::from_secs(1));
        loop {
            tokio::select! {
                message = incoming.recv() => {
                    self.last_rx = Instant::now();
                    match message? {
                        Incoming::Message(message) => {
                            self.handle(shared, outgoing, message).await?;
                            self.start_change(shared, &done_tx);
                        }
                        Incoming::Invalid(why) => {
                            send_error(outgoing, ErrorCode::BadRequest, &why).await;
                        }
                    }
                }
                Some(result) = done.recv() => {
                    self.changing = false;
                    if let Err(error) = result {
                        self.send(outgoing, ServerMessage::Error(error)).await?;
                    }
                    self.start_change(shared, &done_tx);
                }
                changed = snapshots.changed() => {
                    changed.ok()?;
                    self.ticks += 1;
                    if self.ticks.is_multiple_of(self.every) {
                        let stats = self.filter(&snapshots.borrow_and_update());
                        self.send(outgoing, ServerMessage::Stats(stats)).await?;
                    }
                }
                changed = state.changed() => {
                    changed.ok()?;
                    let current = state.borrow_and_update().as_ref().clone();
                    self.send(outgoing, ServerMessage::State(current)).await?;
                }
                _ = keepalive.tick() => {
                    if self.last_rx.elapsed() > DROP_AFTER {
                        debug!("no message for {DROP_AFTER:?}, dropping");
                        return Some(());
                    }
                    if self.last_tx.elapsed() > PING_AFTER {
                        self.send(outgoing, ServerMessage::Ping).await?;
                    }
                }
            }
        }
    }

    async fn handle(
        &mut self,
        shared: &Shared,
        outgoing: &mpsc::Sender<ServerMessage>,
        message: ClientMessage,
    ) -> Option<()> {
        // A successful change needs no reply: the controller publishes the new
        // state, which reaches every session, this one included.
        let result = match message {
            ClientMessage::Ping => return self.send(outgoing, ServerMessage::Pong).await,
            ClientMessage::Pong => Ok(()),
            ClientMessage::Hello(_) => Err(Error {
                code: ErrorCode::BadRequest,
                message: "already said hello".into(),
            }),
            ClientMessage::Subscribe(subscribe) => {
                self.groups = subscribe.groups.into_iter().collect();
                let tick = shared.tick.as_millis().max(1) as u64;
                self.every = (subscribe.interval_ms as u64).div_ceil(tick).max(1);
                Ok(())
            }
            ClientMessage::SetBrightness(set) => {
                self.pending_brightness.insert(set.display, set.percent);
                Ok(())
            }
            ClientMessage::SetVolume(set) => {
                self.pending_volume = Some(merge_volume(self.pending_volume.take(), set));
                Ok(())
            }
            ClientMessage::SetMic(set) => {
                let set = SetVolume {
                    percent: set.percent,
                    muted: set.muted,
                };
                self.pending_mic = Some(merge_volume(self.pending_mic.take(), set));
                Ok(())
            }
            ClientMessage::SetAppVolume(set) => {
                let older = self.pending_apps.remove(&set.app);
                let newer = SetVolume {
                    percent: set.percent,
                    muted: set.muted,
                };
                self.pending_apps
                    .insert(set.app, merge_volume(older, newer));
                Ok(())
            }
            ClientMessage::Media(media) => self.queue(Command::Media {
                player: media.player,
                action: media.action,
            }),
            ClientMessage::SetPowerProfile(set) => self.queue_profile(set.profile),
            ClientMessage::TakeScreenshot => self.take_screenshot(outgoing),
            ClientMessage::StartRecording => self.record(shared, outgoing, true),
            ClientMessage::StopRecording => self.record(shared, outgoing, false),
        };
        match result {
            Ok(()) => Some(()),
            Err(error) => self.send(outgoing, ServerMessage::Error(error)).await,
        }
    }

    /// Starts taking a screenshot, which answers by itself when done: it can
    /// take a second or two, and the session keeps going meanwhile.
    fn take_screenshot(&self, outgoing: &mpsc::Sender<ServerMessage>) -> Result<(), Error> {
        let failed = |message: &str| Error {
            code: ErrorCode::BackendFailed,
            message: message.into(),
        };
        let backend =
            screenshot::detect().ok_or_else(|| failed("Install Spectacle to take screenshots"))?;
        let folder = screenshot::folder().ok_or_else(|| failed("HOME is not set"))?;
        if self.screenshotting.swap(true, Ordering::SeqCst) {
            return Err(failed("Already taking a screenshot"));
        }
        let busy = self.screenshotting.clone();
        let outgoing = outgoing.clone();
        tokio::spawn(async move {
            let taken = tokio::task::spawn_blocking(move || backend.take(&folder)).await;
            busy.store(false, Ordering::SeqCst);
            let message = match taken {
                Ok(Ok(path)) => {
                    info!(path = %path.display(), "took a screenshot");
                    ServerMessage::Screenshot(Screenshot {
                        path: path.to_string_lossy().into_owned(),
                    })
                }
                Ok(Err(err)) => {
                    warn!("screenshot failed: {err}");
                    ServerMessage::Error(failed(&err.to_string()))
                }
                Err(err) => ServerMessage::Error(failed(&err.to_string())),
            };
            let _ = outgoing.send(message).await;
        });
        Ok(())
    }

    /// Starts or stops recording in the background: starting may first open
    /// OBS, which takes a few seconds. The new state reaches the phone through
    /// the controller; stopping also answers with where the video was saved.
    fn record(
        &self,
        shared: &Shared,
        outgoing: &mpsc::Sender<ServerMessage>,
        start: bool,
    ) -> Result<(), Error> {
        let failed = |message: String| Error {
            code: ErrorCode::BackendFailed,
            message,
        };
        let recorder = shared
            .recorder
            .clone()
            .ok_or_else(|| failed("Install OBS Studio to record the screen".into()))?;
        if self.recording_busy.swap(true, Ordering::SeqCst) {
            return Err(failed("Still starting or stopping the recording".into()));
        }
        let busy = self.recording_busy.clone();
        let outgoing = outgoing.clone();
        tokio::spawn(async move {
            let done = tokio::task::spawn_blocking(move || {
                if start {
                    recorder.start().map(|()| None)
                } else {
                    recorder.stop().map(Some)
                }
            })
            .await;
            busy.store(false, Ordering::SeqCst);
            let message = match done {
                Ok(Ok(None)) => {
                    info!("started recording");
                    return;
                }
                Ok(Ok(Some(path))) => {
                    info!(path = %path.display(), "saved a recording");
                    ServerMessage::RecordingSaved(RecordingSaved {
                        path: path.to_string_lossy().into_owned(),
                    })
                }
                Ok(Err(err)) => {
                    warn!("recording failed: {err}");
                    ServerMessage::Error(failed(err.to_string()))
                }
                Err(err) => ServerMessage::Error(failed(err.to_string())),
            };
            let _ = outgoing.send(message).await;
        });
        Ok(())
    }

    fn queue(&mut self, command: Command) -> Result<(), Error> {
        if self.pending_commands.len() >= MAX_QUEUED {
            return Err(Error {
                code: ErrorCode::BadRequest,
                message: "too many commands waiting".into(),
            });
        }
        self.pending_commands.push_back(command);
        Ok(())
    }

    /// Queues a power profile change, replacing one still waiting: only the
    /// newest profile matters.
    fn queue_profile(&mut self, profile: String) -> Result<(), Error> {
        self.pending_commands
            .retain(|c| !matches!(c, Command::Power { .. }));
        self.queue(Command::Power { profile })
    }

    /// The next pending change, if any.
    fn next_change(&mut self) -> Option<Command> {
        if let Some((display, percent)) = self.pending_brightness.pop_first() {
            return Some(Command::Brightness { display, percent });
        }
        if let Some(set) = self.pending_volume.take() {
            return Some(Command::Volume {
                percent: set.percent,
                muted: set.muted,
            });
        }
        if let Some(set) = self.pending_mic.take() {
            return Some(Command::Mic {
                percent: set.percent,
                muted: set.muted,
            });
        }
        if let Some((id, set)) = self.pending_apps.pop_first() {
            return Some(Command::App {
                id,
                percent: set.percent,
                muted: set.muted,
            });
        }
        self.pending_commands.pop_front()
    }

    /// Starts applying the next pending change, unless one is already running.
    fn start_change(&mut self, shared: &Shared, done: &mpsc::Sender<Result<(), Error>>) {
        if self.changing {
            return;
        }
        let Some(command) = self.next_change() else {
            return;
        };
        let controller = shared.controller.clone();
        let done = done.clone();
        tokio::spawn(async move {
            let _ = done.send(controller.apply(command).await).await;
        });
        self.changing = true;
    }

    /// Copies the snapshot, keeping only the groups this phone subscribed to.
    fn filter(&self, snapshot: &Snapshot) -> Stats {
        let want = |group| self.groups.contains(&group);
        Stats {
            ts: snapshot.ts,
            uptime: snapshot.uptime,
            cpu: snapshot.cpu.clone().filter(|_| want(MetricGroup::Cpu)),
            mem: snapshot.mem.clone().filter(|_| want(MetricGroup::Mem)),
            temps: snapshot.temps.clone().filter(|_| want(MetricGroup::Temps)),
            net: snapshot.net.clone().filter(|_| want(MetricGroup::Net)),
            disk: snapshot.disk.clone().filter(|_| want(MetricGroup::Disk)),
            gpus: snapshot.gpus.clone().filter(|_| want(MetricGroup::Gpu)),
            game: snapshot.game.clone().filter(|_| want(MetricGroup::Game)),
        }
    }

    /// Returns `None` when the transport has closed.
    async fn send(
        &mut self,
        outgoing: &mpsc::Sender<ServerMessage>,
        message: ServerMessage,
    ) -> Option<()> {
        self.last_tx = Instant::now();
        outgoing.send(message).await.ok()
    }
}

/// Combines a waiting volume change with a newer one; the newer one wins for
/// each field it sets.
fn merge_volume(older: Option<SetVolume>, newer: SetVolume) -> SetVolume {
    match older {
        None => newer,
        Some(older) => SetVolume {
            percent: newer.percent.or(older.percent),
            muted: newer.muted.or(older.muted),
        },
    }
}

async fn send_error(outgoing: &mpsc::Sender<ServerMessage>, code: ErrorCode, message: &str) {
    let error = Error {
        code,
        message: message.into(),
    };
    let _ = outgoing.send(ServerMessage::Error(error)).await;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn queues_media_and_keeps_the_newest_profile() {
        let mut session = Session::new();
        let next = Command::Media {
            player: None,
            action: yaedock_proto::MediaAction::Next,
        };
        let profile = |p: &str| Command::Power { profile: p.into() };
        session.queue(next.clone()).unwrap();
        session.queue(next.clone()).unwrap();
        session.queue_profile("balanced".into()).unwrap();
        session.queue_profile("performance".into()).unwrap();
        session.pending_apps.insert(
            7,
            SetVolume {
                percent: Some(30),
                muted: None,
            },
        );
        // Volume changes go first, then commands in order.
        assert_eq!(
            session.next_change(),
            Some(Command::App {
                id: 7,
                percent: Some(30),
                muted: None
            })
        );
        assert_eq!(session.next_change(), Some(next.clone()));
        assert_eq!(session.next_change(), Some(next));
        assert_eq!(session.next_change(), Some(profile("performance")));
        assert_eq!(session.next_change(), None);
        for _ in 0..MAX_QUEUED {
            session.queue(profile("x")).unwrap();
        }
        assert!(session.queue(profile("x")).is_err());
    }

    #[test]
    fn newer_volume_changes_win() {
        let set = |percent, muted| SetVolume { percent, muted };
        assert_eq!(merge_volume(None, set(Some(40), None)), set(Some(40), None));
        assert_eq!(
            merge_volume(Some(set(Some(40), Some(true))), set(Some(55), None)),
            set(Some(55), Some(true))
        );
        assert_eq!(
            merge_volume(Some(set(Some(40), None)), set(None, Some(false))),
            set(Some(40), Some(false))
        );
    }
}
