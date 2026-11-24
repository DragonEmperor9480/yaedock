//! Phones over a USB cable, through Android Open Accessory (AOA).
//!
//! The PC is the USB host. A paired phone that is plugged in is switched into
//! accessory mode with AOA's vendor requests; it comes back on the bus as a
//! Google accessory device, and the two sides then exchange the frames of
//! [`yaedock_proto::link`] over its bulk endpoints, carrying the same messages
//! as the WebSocket. See "Over a USB cable" in `protocol/protocol.md`.
//!
//! A phone is only switched when it is known to be a paired phone: its USB
//! serial number was learnt on an earlier cable session, or the phone just
//! said over Wi-Fi that it was plugged in (`usb_plugged`). While switched,
//! the phone's file transfer is off, so no other phone is ever touched.
//!
//! The one exception is pairing: while `yaedock pair` waits, the user has
//! asked for a new phone, so any Android phone plugged in is switched, and
//! handed the pairing link in the accessory's version string.

use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

use anyhow::Context;
use futures_util::StreamExt;
use nusb::descriptors::TransferType;
use nusb::hotplug::HotplugEvent;
use nusb::transfer::{Bulk, ControlIn, ControlOut, ControlType, Direction, In, Out, Recipient};
use nusb::{DeviceId, DeviceInfo};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;
use tokio::task::JoinHandle;
use tokio::time::timeout;
use tracing::{debug, info, warn};
use yaedock_core::pairing;
use yaedock_proto::link::{self, Frame, Splitter};
use yaedock_proto::{PROTOCOL_VERSION, PcFrame, ServerMessage};

use crate::session::{self, Ended, Incoming, Shared};

/// Google's vendor id, which every phone in accessory mode takes.
const GOOGLE: u16 = 0x18d1;
/// Accessory mode, alone or with adb, audio, or both.
const ACCESSORY: [u16; 4] = [0x2d00, 0x2d01, 0x2d04, 0x2d05];
/// AOA's vendor requests.
const GET_PROTOCOL: u8 = 51;
const SEND_STRING: u8 = 52;
const START: u8 = 53;
const CONTROL_TIMEOUT: Duration = Duration::from_secs(1);
/// The size of each USB transfer; Android reads the accessory in pieces of this.
const TRANSFER: usize = 16 * 1024;
/// How long one frame may wait to be taken by the phone, as on Wi-Fi.
const WRITE_TIMEOUT: Duration = Duration::from_secs(20);
/// Video frames waiting to be sent, as on Wi-Fi.
const VIDEO_QUEUE: usize = 16;
/// How long after a cable link ends before a phone still plugged in is served again.
const RESERVE_AFTER: Duration = Duration::from_secs(1);
/// How often to look for a `yaedock pair` that just started.
const PAIRING_POLL: Duration = Duration::from_secs(1);
const WEBSITE: &str = "https://yaedock.amrutlabs.in/";

/// What the PC tells a phone it switches: manufacturer, model, description,
/// version, URI and serial, in AOA's order. The serial is the PC's
/// certificate fingerprint, so the phone knows which paired PC this is.
pub fn accessory_strings(hostname: &str, fingerprint: &str) -> [String; 6] {
    [
        "YaeDock".into(),
        "YaeDock PC".into(),
        hostname.into(),
        PROTOCOL_VERSION.to_string(),
        WEBSITE.into(),
        fingerprint.into(),
    ]
}

/// The accessory strings while pairing: the version string also carries the
/// pairing link the QR code shows, after a space.
pub fn pairing_strings(strings: &[String; 6], uri: &str) -> [String; 6] {
    let mut strings = strings.clone();
    strings[3] = format!("{PROTOCOL_VERSION} {uri}");
    strings
}

/// Whether `info` is a phone already in accessory mode.
fn is_accessory(info: &DeviceInfo) -> bool {
    info.vendor_id() == GOOGLE && ACCESSORY.contains(&info.product_id())
}

/// Whether `info` looks like an Android phone: it offers file transfer (MTP)
/// or adb.
fn is_android(info: &DeviceInfo) -> bool {
    info.interfaces().any(|interface| {
        matches!(
            (
                interface.class(),
                interface.subclass(),
                interface.protocol()
            ),
            (0x06, 0x01, 0x01) | (0xff, 0x42, 0x01)
        )
    })
}

/// Where a device is plugged in, which stays the same across the switch.
fn port(info: &DeviceInfo) -> String {
    format!("{}:{:?}", info.bus_id(), info.port_chain())
}

/// Which of `phones` (manufacturer strings) a phone named `name` could be:
/// all of them when there is one, otherwise those whose maker starts its name.
fn which(phones: &[Option<&str>], name: &str) -> Vec<usize> {
    if phones.len() <= 1 {
        return (0..phones.len()).collect();
    }
    let maker = name
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .to_lowercase();
    (0..phones.len())
        .filter(|&i| {
            phones[i].is_some_and(|m| !maker.is_empty() && m.to_lowercase().starts_with(&maker))
        })
        .collect()
}

/// What sessions ask of the USB bus.
#[derive(Debug)]
pub enum Request {
    /// The phone of this name says it was just plugged in.
    Plugged(String),
    /// A phone turned the cable off; these were its serial numbers, now forgotten.
    Off(Vec<String>),
    /// Serving this accessory ended, perhaps with the phone still plugged in.
    Ended(DeviceInfo),
}

/// Watches the USB bus for as long as the daemon runs, and answers `requests`.
pub async fn serve(
    shared: Arc<Shared>,
    strings: [String; 6],
    mut requests: mpsc::UnboundedReceiver<Request>,
) -> anyhow::Result<()> {
    // Watched before listing, so nothing plugged in between is missed.
    let mut watch = nusb::watch_devices().context("watching USB devices")?;
    let mut bus = Bus {
        shared,
        strings: Arc::new(strings),
        serving: HashSet::new(),
        switched: HashMap::new(),
        offered: None,
    };
    let mut pairing_poll = tokio::time::interval(PAIRING_POLL);
    for info in nusb::list_devices().await.context("listing USB devices")? {
        bus.arrived(info, false).await;
    }
    loop {
        tokio::select! {
            event = watch.next() => match event {
                Some(HotplugEvent::Connected(info)) => bus.arrived(info, false).await,
                Some(HotplugEvent::Disconnected(id)) => {
                    bus.serving.remove(&id);
                }
                None => anyhow::bail!("the USB watch ended"),
            },
            _ = pairing_poll.tick() => bus.offer_pairing().await,
            Some(request) = requests.recv() => match request {
                Request::Plugged(name) => bus.plugged(&name).await,
                Request::Off(serials) => bus.let_go(&serials).await,
                Request::Ended(info) => bus.ended(info).await,
            },
        }
    }
}

struct Bus {
    shared: Arc<Shared>,
    strings: Arc<[String; 6]>,
    /// Accessories being served now.
    serving: HashSet<DeviceId>,
    /// The serial number each switched phone had, by where it is plugged in:
    /// a phone may show another one in accessory mode.
    switched: HashMap<String, String>,
    /// The pairing link already offered to the phones plugged in.
    offered: Option<String>,
}

impl Bus {
    async fn arrived(&mut self, info: DeviceInfo, asked: bool) {
        if is_accessory(&info) {
            if !self.serving.insert(info.id()) {
                return;
            }
            let serial = self.serial_of(&info);
            let shared = self.shared.clone();
            tokio::spawn(async move {
                info!("a phone is connected by USB cable");
                if let Err(err) = serve_accessory(shared.clone(), &info, serial).await {
                    info!("the USB cable link ended: {err:#}");
                }
                // A moment's pause, so a link that fails at once is not retried in a tight loop.
                tokio::time::sleep(RESERVE_AFTER).await;
                if let Some(usb) = &shared.usb {
                    let _ = usb.send(Request::Ended(info));
                }
            });
            return;
        }
        if !is_android(&info) {
            return;
        }
        let known = info
            .serial_number()
            .is_some_and(|serial| self.shared.devices.knows_usb(serial).unwrap_or(false));
        let strings = if known || asked {
            (*self.strings).clone()
        } else if let Some(uri) = pairing::waiting_uri() {
            info!("a phone was plugged in while pairing; offering it the code");
            pairing_strings(&self.strings, &uri)
        } else {
            return;
        };
        if let Some(serial) = info.serial_number() {
            self.switched.insert(port(&info), serial.to_string());
        }
        match switch(&info, &strings).await {
            Ok(()) => debug!("switched a phone to accessory mode"),
            Err(err) => warn!("could not switch a plugged-in phone to the USB cable: {err:#}"),
        }
    }

    /// When `yaedock pair` starts, offers its code to the phones already plugged in.
    async fn offer_pairing(&mut self) {
        let uri = pairing::waiting_uri();
        if uri.is_none() || uri == self.offered {
            self.offered = uri;
            return;
        }
        self.offered = uri;
        let Ok(devices) = nusb::list_devices().await else {
            return;
        };
        for info in devices.filter(|info| is_android(info) && !is_accessory(info)) {
            self.arrived(info, false).await;
        }
    }

    /// Serving `info` ended. If the phone is still plugged in, as when the app
    /// on it was restarted, it is served again for the app's next link.
    async fn ended(&mut self, info: DeviceInfo) {
        self.serving.remove(&info.id());
        let Ok(mut devices) = nusb::list_devices().await else {
            return;
        };
        if let Some(info) = devices.find(|device| device.id() == info.id()) {
            self.arrived(info, false).await;
        }
    }

    /// The serial number of a phone in accessory mode, as it was before the switch.
    fn serial_of(&self, info: &DeviceInfo) -> Option<String> {
        self.switched
            .get(&port(info))
            .cloned()
            .or_else(|| info.serial_number().map(String::from))
    }

    /// Resets the phones with these serial numbers that are in accessory mode.
    /// They come back as they were before the switch, file transfer and all.
    async fn let_go(&mut self, serials: &[String]) {
        let Ok(devices) = nusb::list_devices().await else {
            return;
        };
        for info in devices.filter(is_accessory) {
            if !self
                .serial_of(&info)
                .is_some_and(|serial| serials.contains(&serial))
            {
                continue;
            }
            let reset = async { info.open().await?.reset().await };
            match reset.await {
                // The phone leaves the bus as it resets, which can end the request.
                Ok(()) => info!("let a phone go from the USB cable"),
                Err(err) if err.kind() == nusb::ErrorKind::Disconnected => {
                    info!("let a phone go from the USB cable");
                }
                Err(err) => warn!("could not let a phone go from the USB cable: {err}"),
            }
        }
    }

    /// A paired phone said it was plugged in: switch it, if it is plugged into this PC.
    async fn plugged(&mut self, name: &str) {
        let Ok(devices) = nusb::list_devices().await else {
            return;
        };
        let phones: Vec<DeviceInfo> = devices
            .filter(|info| is_android(info) && !is_accessory(info))
            .collect();
        let makers: Vec<Option<&str>> =
            phones.iter().map(DeviceInfo::manufacturer_string).collect();
        let chosen = which(&makers, name);
        if chosen.is_empty() {
            debug!(%name, "a phone says it was plugged in, but none here matches it");
        }
        for i in chosen {
            self.arrived(phones[i].clone(), true).await;
        }
    }
}

/// Switches a phone into accessory mode; it then leaves the bus and comes back.
async fn switch(info: &DeviceInfo, strings: &[String; 6]) -> anyhow::Result<()> {
    let device = info.open().await.context("opening the phone")?;
    let version = device
        .control_in(
            ControlIn {
                control_type: ControlType::Vendor,
                recipient: Recipient::Device,
                request: GET_PROTOCOL,
                value: 0,
                index: 0,
                length: 2,
            },
            CONTROL_TIMEOUT,
        )
        .await
        .context("asking for the accessory protocol")?;
    let version = match version[..] {
        [low, high] => u16::from_le_bytes([low, high]),
        _ => 0,
    };
    anyhow::ensure!(
        version >= 1,
        "the phone does not offer Android Open Accessory"
    );
    for (index, text) in strings.iter().enumerate() {
        let mut data = text.as_bytes().to_vec();
        data.push(0);
        device
            .control_out(
                ControlOut {
                    control_type: ControlType::Vendor,
                    recipient: Recipient::Device,
                    request: SEND_STRING,
                    value: 0,
                    index: index as u16,
                    data: &data,
                },
                CONTROL_TIMEOUT,
            )
            .await
            .context("sending the accessory's strings")?;
    }
    device
        .control_out(
            ControlOut {
                control_type: ControlType::Vendor,
                recipient: Recipient::Device,
                request: START,
                value: 0,
                index: 0,
                data: &[],
            },
            CONTROL_TIMEOUT,
        )
        .await
        .context("starting accessory mode")?;
    Ok(())
}

/// The bulk endpoints of an accessory's first interface: in, then out.
fn endpoints(interface: &nusb::Interface) -> anyhow::Result<(u8, u8)> {
    let descriptor = interface
        .descriptor()
        .context("the accessory has no interface")?;
    let (mut input, mut output) = (None, None);
    for endpoint in descriptor.endpoints() {
        if endpoint.transfer_type() == TransferType::Bulk {
            match endpoint.direction() {
                Direction::In => input = input.or(Some(endpoint.address())),
                Direction::Out => output = output.or(Some(endpoint.address())),
            }
        }
    }
    Ok((
        input.context("the accessory has no way in")?,
        output.context("the accessory has no way out")?,
    ))
}

/// Serves an accessory until it is unplugged or stops answering. A session
/// starts at each start frame the phone sends.
async fn serve_accessory(
    shared: Arc<Shared>,
    info: &DeviceInfo,
    serial: Option<String>,
) -> anyhow::Result<()> {
    let device = info.open().await.context("opening the accessory")?;
    let interface = device
        .claim_interface(0)
        .await
        .context("claiming the accessory")?;
    let (way_in, way_out) = endpoints(&interface)?;
    let mut reader = interface
        .endpoint::<Bulk, In>(way_in)?
        .reader(TRANSFER)
        .with_num_transfers(4);
    let mut writer = interface
        .endpoint::<Bulk, Out>(way_out)?
        .writer(TRANSFER)
        .with_num_transfers(8);

    let (wire, mut wire_rx) = mpsc::channel::<Vec<u8>>(32);
    let writing = async move {
        while let Some(bytes) = wire_rx.recv().await {
            let send = async {
                writer.write_all(&bytes).await?;
                // A short packet ends the transfer, so the phone's read returns at once.
                writer.submit_end();
                std::io::Result::Ok(())
            };
            timeout(WRITE_TIMEOUT, send)
                .await
                .map_err(|_| anyhow::anyhow!("the phone stopped taking messages"))??;
        }
        anyhow::Ok(())
    };
    let reading = async {
        let mut splitter = Splitter::default();
        let mut buffer = vec![0; TRANSFER];
        let mut session: Option<Current> = None;
        loop {
            let read = reader
                .read(&mut buffer)
                .await
                .context("reading from the phone")?;
            anyhow::ensure!(read > 0, "the phone closed the cable link");
            splitter.push(&buffer[..read]);
            while let Some(frame) = splitter.next_frame()? {
                let incoming = match frame {
                    Frame::Start => {
                        // Dropping the old one ends its session.
                        session = Some(Current::start(&shared, serial.clone(), wire.clone()));
                        continue;
                    }
                    Frame::Text(text) => Incoming::from_text(&text),
                    Frame::Binary(bytes) => Incoming::from_binary(&bytes),
                };
                // Frames before the first start belong to no session.
                if let Some(current) = &session
                    && current.incoming.send(incoming).await.is_err()
                {
                    session = None;
                }
            }
        }
    };
    tokio::select! {
        result = writing => result,
        result = reading => result,
    }
}

/// The session running on a cable now.
struct Current {
    incoming: mpsc::Sender<Incoming>,
    forwarding: JoinHandle<()>,
}

impl Drop for Current {
    fn drop(&mut self) {
        // Nothing more from an ended session goes to the phone.
        self.forwarding.abort();
    }
}

impl Current {
    fn start(shared: &Arc<Shared>, serial: Option<String>, wire: mpsc::Sender<Vec<u8>>) -> Self {
        let (incoming, incoming_rx) = mpsc::channel(16);
        let (outgoing, mut outgoing_rx) = mpsc::channel::<ServerMessage>(16);
        let (video, mut video_rx) = mpsc::channel::<PcFrame>(VIDEO_QUEUE);
        let (sound, mut sound_rx) = mpsc::channel::<PcFrame>(session::SOUND_QUEUE);
        let forwarding = tokio::spawn(async move {
            loop {
                let bytes = tokio::select! {
                    // Messages first: they are small, and say what the video means.
                    biased;
                    message = outgoing_rx.recv() => match message {
                        Some(message) => match serde_json::to_string(&message) {
                            Ok(text) => link::encode(link::TEXT, text.as_bytes()),
                            Err(_) => continue,
                        },
                        None => break,
                    },
                    // Then sound, which a gap is heard in, ahead of video, which can skip.
                    Some(frame) = sound_rx.recv() => link::encode(link::BINARY, &frame.to_bytes()),
                    Some(frame) = video_rx.recv() => link::encode(link::BINARY, &frame.to_bytes()),
                };
                if wire.send(bytes).await.is_err() {
                    break;
                }
            }
        });
        let shared = shared.clone();
        tokio::spawn(async move {
            // Noted as soon as the phone is known, so a daemon stopped mid-session keeps it.
            let remember = |device_id: &str| {
                if let Some(serial) = &serial
                    && let Err(err) = shared.devices.remember_usb(device_id, serial)
                {
                    warn!("could not note the phone's USB serial number: {err:#}");
                }
            };
            let ended = session::run(
                shared.clone(),
                "USB cable".into(),
                incoming_rx,
                outgoing,
                Some(session::Binary { video, sound }),
                remember,
            )
            .await;
            // A phone that turned the cable down is not switched again by itself.
            if let (Ended::Rejected, Some(serial)) = (ended, &serial)
                && let Err(err) = shared.devices.forget_usb(serial)
            {
                warn!("could not forget the phone's USB serial number: {err:#}");
            }
        });
        Self {
            incoming,
            forwarding,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tells_the_phone_which_pc_it_is() {
        let strings = accessory_strings("laptop", "ab12");
        assert_eq!(strings[0], "YaeDock");
        assert_eq!(strings[2], "laptop");
        assert_eq!(strings[3], PROTOCOL_VERSION.to_string());
        assert_eq!(strings[5], "ab12");
        let pairing = pairing_strings(&strings, "yaedock://pair?code=123456");
        assert_eq!(
            pairing[3],
            format!("{PROTOCOL_VERSION} yaedock://pair?code=123456")
        );
        assert_eq!(pairing[5], "ab12");
    }

    #[test]
    fn picks_the_phone_that_asked() {
        assert_eq!(which(&[], "samsung SM-S711B"), Vec::<usize>::new());
        assert_eq!(which(&[Some("Google")], "samsung SM-S711B"), [0]);
        let two = [Some("Google"), Some("SAMSUNG")];
        assert_eq!(which(&two, "samsung SM-S711B"), [1]);
        assert_eq!(which(&two, "OnePlus CPH2581"), Vec::<usize>::new());
        assert_eq!(which(&[None, None], "samsung"), Vec::<usize>::new());
    }
}
