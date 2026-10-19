//! One session per connected phone. Sessions speak only in protocol messages
//! over channels, so every transport (WebSocket now, Bluetooth later) can
//! share this code.

use std::collections::HashSet;
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tokio::sync::{mpsc, watch};
use tokio::time::{self, Instant, sleep, timeout};
use tracing::{debug, info, warn};
use yaedock_proto::{
    ClientMessage, Error, ErrorCode, Hello, MetricGroup, PROTOCOL_VERSION, ServerMessage, Stats,
    Welcome,
};
use yaedock_sys::metrics::SystemInfo;

use crate::controller::Controller;
use crate::devices::DeviceStore;
use crate::pairing;
use crate::sampler::Snapshot;

const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const PING_AFTER: Duration = Duration::from_secs(10);
const DROP_AFTER: Duration = Duration::from_secs(30);
/// Delay before rejecting a phone, to slow down guessing.
const REJECT_DELAY: Duration = Duration::from_secs(1);

const ALL_GROUPS: [MetricGroup; 5] = [
    MetricGroup::Cpu,
    MetricGroup::Mem,
    MetricGroup::Temps,
    MetricGroup::Net,
    MetricGroup::Disk,
];

/// What every session shares.
pub struct Shared {
    pub snapshots: watch::Receiver<Arc<Snapshot>>,
    pub controller: Controller,
    pub info: SystemInfo,
    pub sensors: Vec<String>,
    pub tick: Duration,
    pub devices: DeviceStore,
    /// Serializes checks of tokens and pairing codes, which read and write files.
    pub auth_lock: Mutex<()>,
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
pub async fn run(
    shared: Arc<Shared>,
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
}

impl Session {
    fn new() -> Self {
        Self {
            groups: ALL_GROUPS.into(),
            every: 1,
            ticks: 0,
            last_rx: Instant::now(),
            last_tx: Instant::now(),
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
        };
        self.send(outgoing, ServerMessage::Welcome(welcome)).await?;
        snapshots.mark_unchanged();

        let mut keepalive = time::interval(Duration::from_secs(1));
        loop {
            tokio::select! {
                message = incoming.recv() => {
                    self.last_rx = Instant::now();
                    match message? {
                        Incoming::Message(message) => self.handle(shared, outgoing, message).await?,
                        Incoming::Invalid(why) => {
                            send_error(outgoing, ErrorCode::BadRequest, &why).await;
                        }
                    }
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
                shared
                    .controller
                    .set_brightness(set.display, set.percent)
                    .await
            }
            ClientMessage::SetVolume(set) => {
                shared.controller.set_volume(set.percent, set.muted).await
            }
        };
        match result {
            Ok(()) => Some(()),
            Err(error) => self.send(outgoing, ServerMessage::Error(error)).await,
        }
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

async fn send_error(outgoing: &mpsc::Sender<ServerMessage>, code: ErrorCode, message: &str) {
    let error = Error {
        code,
        message: message.into(),
    };
    let _ = outgoing.send(ServerMessage::Error(error)).await;
}
