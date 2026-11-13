//! Mirroring the screen to the phone: one monitor, shared through the
//! desktop's ScreenCast portal (which asks the user on the PC), read from
//! PipeWire and encoded to H.264 by GStreamer.
//!
//! GStreamer runs as a child process (`gst-launch-1.0`), found when needed,
//! so the daemon starts on PCs without it. It writes each encoded frame with
//! a GDP header (`gdppay`), which gives the frame's length, time and whether
//! it is a keyframe, so nothing here parses H.264.

use std::collections::HashMap;
use std::io::{self, Read};
use std::os::fd::OwnedFd;
use std::process::{Child, Command, Stdio};
use std::sync::OnceLock;

use futures_util::StreamExt;
use zbus::zvariant::{ObjectPath, OwnedValue, Value};

const PORTAL: &str = "org.freedesktop.portal.Desktop";
const PORTAL_PATH: &str = "/org/freedesktop/portal/desktop";
const SCREENCAST: &str = "org.freedesktop.portal.ScreenCast";

/// Kilobits per second of video: plenty for a sharp 1080p desktop on Wi-Fi.
const BITRATE_KBPS: u32 = 8000;
/// Frames between keyframes: two seconds at 60 frames a second. A phone that
/// fell behind waits for the next one; each is a large frame, so they are
/// kept apart.
const KEYFRAME_EVERY: u32 = 120;

#[derive(Debug, thiserror::Error)]
pub enum ScreenError {
    #[error("screen sharing needs {0}")]
    Missing(&'static str),
    #[error("sharing the screen was not allowed on the PC")]
    Denied,
    #[error("the desktop's screen sharing failed: {0}")]
    Portal(#[from] zbus::Error),
    #[error("the desktop's screen sharing gave an answer this version does not understand")]
    Unexpected,
    #[error(transparent)]
    Io(#[from] io::Error),
}

/// An H.264 encoder GStreamer has on this PC.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Encoder {
    /// Intel and AMD graphics, through VA-API.
    Va,
    Nvidia,
    /// On the CPU.
    X264,
    /// On the CPU, where x264 is not packaged.
    OpenH264,
}

impl Encoder {
    const ALL: [Encoder; 4] = [
        Encoder::Va,
        Encoder::Nvidia,
        Encoder::X264,
        Encoder::OpenH264,
    ];

    fn element(self) -> &'static str {
        match self {
            Encoder::Va => "vah264enc",
            Encoder::Nvidia => "nvh264enc",
            Encoder::X264 => "x264enc",
            Encoder::OpenH264 => "openh264enc",
        }
    }

    /// What it is, for reports such as `yaedock check`.
    pub fn name(self) -> &'static str {
        match self {
            Encoder::Va => "the graphics card (VA-API)",
            Encoder::Nvidia => "the NVIDIA card",
            Encoder::X264 => "the CPU (x264)",
            Encoder::OpenH264 => "the CPU (OpenH264)",
        }
    }

    /// Whether the encoder delays its output unless its source is live.
    fn wants_live_source(self) -> bool {
        matches!(self, Encoder::Va)
    }

    /// The pipeline from raw video to H.264, tuned for low delay. With
    /// `scale`, the picture is first made that many pixels wide and high.
    fn chain(self, scale: Option<(u32, u32)>) -> String {
        let size = scale
            .map(|(width, height)| format!(",width={width},height={height}"))
            .unwrap_or_default();
        // The graphics card scales for the Intel encoder; the others scale first,
        // so there is less to convert.
        let convert = match scale {
            Some(_) => format!("videoscale ! video/x-raw{size} ! videoconvert"),
            None => "videoconvert".to_string(),
        };
        match self {
            // Variable bitrate: a constant one pads a still desktop up to the full rate.
            Encoder::Va => format!(
                "vapostproc ! video/x-raw,format=NV12{size} ! vah264enc bitrate={BITRATE_KBPS} \
                 rate-control=vbr key-int-max={KEYFRAME_EVERY} b-frames=0 target-usage=6"
            ),
            Encoder::Nvidia => format!(
                "{convert} ! nvh264enc bitrate={BITRATE_KBPS} rc-mode=cbr \
                 gop-size={KEYFRAME_EVERY} bframes=0 zerolatency=true"
            ),
            Encoder::X264 => format!(
                "{convert} ! x264enc bitrate={BITRATE_KBPS} tune=zerolatency \
                 speed-preset=ultrafast key-int-max={KEYFRAME_EVERY}"
            ),
            Encoder::OpenH264 => format!(
                "{convert} ! openh264enc bitrate={} complexity=low gop-size={KEYFRAME_EVERY}",
                BITRATE_KBPS * 1000
            ),
        }
    }
}

/// Whether GStreamer has `element`.
fn has_element(element: &str) -> bool {
    Command::new("gst-inspect-1.0")
        .args(["--exists", element])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success())
}

/// What mirroring needs on this PC, or the first thing that is missing.
/// A working setup is remembered; a missing piece is looked for again, so
/// installing it needs no restart.
pub fn detect() -> Result<Encoder, ScreenError> {
    static FOUND: OnceLock<Encoder> = OnceLock::new();
    if let Some(encoder) = FOUND.get() {
        return Ok(*encoder);
    }
    for (element, package) in [
        ("pipewiresrc", "GStreamer's PipeWire plugin"),
        ("gdppay", "GStreamer's GDP plugin (gst-plugins-bad)"),
        ("h264parse", "GStreamer's H.264 parser (gst-plugins-bad)"),
    ] {
        if !has_element(element) {
            return Err(ScreenError::Missing(package));
        }
    }
    let encoder = Encoder::ALL
        .into_iter()
        .find(|encoder| has_element(encoder.element()))
        .ok_or(ScreenError::Missing(
            "a GStreamer H.264 encoder (gst-plugin-va, or x264 from gst-plugins-ugly)",
        ))?;
    Ok(*FOUND.get_or_init(|| encoder))
}

/// What the desktop is asked to share.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// One of the PC's monitors, which the user picks.
    Monitor,
    /// A new display the desktop makes for this share, beside the monitors.
    /// It takes the size the capture asks for: see [`encode`].
    Virtual,
}

impl Source {
    /// The portal's bit for this kind of source.
    fn bit(self) -> u32 {
        match self {
            Source::Monitor => 1,
            Source::Virtual => 4,
        }
    }
}

/// Whether the desktop can make a virtual display to share.
pub async fn can_extend() -> bool {
    async fn types() -> zbus::Result<u32> {
        let bus = zbus::Connection::session().await?;
        let portal = zbus::Proxy::new(&bus, PORTAL, PORTAL_PATH, SCREENCAST).await?;
        portal.get_property("AvailableSourceTypes").await
    }
    types()
        .await
        .is_ok_and(|types| types & Source::Virtual.bit() != 0)
}

/// The smallest and largest picture sides taken from a phone's request.
const SIDES: std::ops::RangeInclusive<u32> = 240..=4096;

/// A size an encoder takes: even, and neither tiny nor huge.
pub fn sane((width, height): (u32, u32)) -> (u32, u32) {
    let side = |pixels: u32| pixels.clamp(*SIDES.start(), *SIDES.end()) & !1;
    (side(width), side(height))
}

/// `native` made to fit in `width` by `height` where given, keeping its
/// shape and never growing. `None` when it fits as it is.
pub fn fit(native: (u32, u32), width: Option<u32>, height: Option<u32>) -> Option<(u32, u32)> {
    let (w, h) = (u64::from(native.0), u64::from(native.1));
    if w == 0 || h == 0 {
        return None;
    }
    let max_w = u64::from(width.unwrap_or(native.0).max(*SIDES.start()));
    let max_h = u64::from(height.unwrap_or(native.1).max(*SIDES.start()));
    if w <= max_w && h <= max_h {
        return None;
    }
    // Whichever side is the tighter fit decides.
    let (fw, fh) = if max_w * h <= max_h * w {
        (max_w, max_w * h / w)
    } else {
        (max_h * w / h, max_h)
    };
    Some(((fw as u32).max(2) & !1, (fh as u32).max(2) & !1))
}

/// A monitor the user agreed to share. The share lasts as long as this value.
pub struct Share {
    /// The portal ends the share when this connection closes.
    _bus: zbus::Connection,
    /// The PipeWire node carrying the monitor's picture.
    node: u32,
    /// PipeWire, opened for this share only.
    remote: OwnedFd,
    /// The monitor's size in pixels, when the portal says.
    pub size: Option<(u32, u32)>,
    /// Lets the next share skip the question on the PC, where the desktop allows it.
    pub restore_token: Option<String>,
}

/// Asks the desktop to share `source`. Unless `restore_token` from an
/// earlier share of the same kind still holds, the desktop asks the user on
/// the PC, and this waits for the answer; drop the future to stop waiting.
pub async fn share(source: Source, restore_token: Option<&str>) -> Result<Share, ScreenError> {
    let bus = zbus::Connection::session().await?;
    let portal = zbus::Proxy::new(&bus, PORTAL, PORTAL_PATH, SCREENCAST).await?;
    let mut requests = Requests {
        bus: &bus,
        portal: &portal,
        count: 0,
    };

    let mut options = HashMap::new();
    options.insert("session_handle_token", Value::from("yaedock"));
    let created = requests.call("CreateSession", |o| ((), o), options).await?;
    let session = created
        .get("session_handle")
        .and_then(|v| <&str>::try_from(v).ok())
        .and_then(|path| ObjectPath::try_from(path.to_owned()).ok())
        .ok_or(ScreenError::Unexpected)?;

    let mut options = HashMap::new();
    options.insert("types", Value::U32(source.bit()));
    options.insert("multiple", Value::Bool(false));
    options.insert("cursor_mode", Value::U32(2)); // drawn into the picture
    options.insert("persist_mode", Value::U32(2)); // until revoked
    if let Some(token) = restore_token {
        options.insert("restore_token", Value::from(token));
    }
    requests
        .call("SelectSources", |o| (&session, o), options)
        .await?;
    let started = requests
        .call("Start", |o| (&session, "", o), HashMap::new())
        .await?;

    let (node, size) = first_stream(&started).ok_or(ScreenError::Unexpected)?;
    let restore_token = started
        .get("restore_token")
        .and_then(|v| <&str>::try_from(v).ok())
        .map(str::to_owned);
    let remote: zbus::zvariant::OwnedFd = portal
        .call(
            "OpenPipeWireRemote",
            &(&session, HashMap::<&str, Value>::new()),
        )
        .await?;
    Ok(Share {
        _bus: bus,
        node,
        remote: remote.into(),
        size,
        restore_token,
    })
}

/// Portal calls answer later, through a `Response` signal on a request object.
struct Requests<'a> {
    bus: &'a zbus::Connection,
    portal: &'a zbus::Proxy<'a>,
    count: u32,
}

impl Requests<'_> {
    /// Calls `method` with the arguments `args` builds around the options,
    /// and waits for the user's or the desktop's answer.
    async fn call<'o, A>(
        &mut self,
        method: &str,
        args: impl FnOnce(HashMap<&'o str, Value<'o>>) -> A,
        mut options: HashMap<&'o str, Value<'o>>,
    ) -> Result<HashMap<String, OwnedValue>, ScreenError>
    where
        A: zbus::export::serde::Serialize + zbus::zvariant::DynamicType,
    {
        self.count += 1;
        let token = format!("yaedock{}", self.count);
        // The request's path is known beforehand, so no answer can be missed.
        let sender = self
            .bus
            .unique_name()
            .ok_or(ScreenError::Unexpected)?
            .trim_start_matches(':')
            .replace('.', "_");
        let path = format!("{PORTAL_PATH}/request/{sender}/{token}");
        let request =
            zbus::Proxy::new(self.bus, PORTAL, path, "org.freedesktop.portal.Request").await?;
        let mut responses = request.receive_signal("Response").await?;
        options.insert("handle_token", Value::from(token));
        self.portal.call_method(method, &args(options)).await?;
        let response = responses.next().await.ok_or(ScreenError::Unexpected)?;
        let (code, results): (u32, HashMap<String, OwnedValue>) = response.body().deserialize()?;
        match code {
            0 => Ok(results),
            _ => Err(ScreenError::Denied),
        }
    }
}

/// The PipeWire node and size of the first stream in `Start`'s answer.
fn first_stream(started: &HashMap<String, OwnedValue>) -> Option<(u32, Option<(u32, u32)>)> {
    let Value::Array(streams) = &**started.get("streams")? else {
        return None;
    };
    let Value::Structure(stream) = streams.iter().next()? else {
        return None;
    };
    let [Value::U32(node), Value::Dict(properties)] = stream.fields() else {
        return None;
    };
    let size = match properties.get::<&str, Value>(&"size") {
        Ok(Some(Value::Structure(size))) => match size.fields() {
            [Value::I32(width), Value::I32(height)] => {
                Some((u32::try_from(*width).ok()?, u32::try_from(*height).ok()?))
            }
            _ => None,
        },
        _ => None,
    };
    Some((*node, size))
}

/// Hands the captured frames to a second pipeline at a steady rate.
/// GStreamer asks the PipeWire source whether it is live before it says so,
/// and `vah264enc` on Intel then holds four frames back for throughput and
/// lets them go in a clump at every keyframe, which shows as a stutter. The
/// relay's source is live from the start. It never shows black for a still
/// screen: the capture repeats its last frame well within the timeout.
const LIVE_RELAY: &str = "intervideosink channel=screen sync=false \
     intervideosrc channel=screen ! video/x-raw,framerate=60/1 !";

/// The GStreamer pipeline from PipeWire node `node` to framed H.264 on
/// standard output.
fn pipeline(node: u32, encoder: Encoder, relay: bool, picture: Picture) -> String {
    let (capture, scale) = match picture {
        Picture::AsItIs => (String::new(), None),
        Picture::Scaled(size) => (String::new(), Some(size)),
        Picture::Captured((width, height)) => (format!(",width={width},height={height}"), None),
    };
    format!(
        "pipewiresrc fd=0 path={node} do-timestamp=true keepalive-time=33 ! \
         video/x-raw,max-framerate=60/1{capture} ! queue max-size-buffers=2 leaky=downstream ! {} {} ! \
         h264parse config-interval=-1 ! video/x-h264,stream-format=byte-stream,alignment=au ! \
         gdppay ! fdsink fd=1 sync=false",
        if relay { LIVE_RELAY } else { "" },
        encoder.chain(scale)
    )
}

/// The size of the picture [`encode`] makes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Picture {
    /// The size the desktop shares.
    AsItIs,
    /// Scaled to this size after capture: for a monitor, whose own size stays.
    Scaled((u32, u32)),
    /// Captured at this size: a virtual display becomes the size asked for.
    Captured((u32, u32)),
}

/// Starts GStreamer encoding `share` with `encoder`. Its standard output
/// carries the frames, which [`read_frame`] takes apart.
pub fn encode(share: &Share, encoder: Encoder, picture: Picture) -> io::Result<Child> {
    // The relay comes with gst-plugins-bad, like `gdppay`; without it the
    // picture still arrives, less evenly.
    let relay = encoder.wants_live_source() && has_element("intervideosrc");
    // PipeWire reaches the child as its standard input, so no other program
    // the daemon starts meanwhile can inherit it.
    let pipeline = pipeline(share.node, encoder, relay, picture);
    Command::new("gst-launch-1.0")
        .arg("-q")
        .args(pipeline.split_whitespace())
        .stdin(Stdio::from(share.remote.try_clone()?))
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
}

/// One encoded picture, as the phone receives it.
pub use yaedock_proto::VideoFrame as Frame;

/// The largest frame taken; a 4K keyframe is well under this.
const MAX_FRAME: usize = 8 << 20;

/// Reads the next frame from GStreamer's output, skipping what `gdppay`
/// sends besides frames. `None` when the stream ends.
pub fn read_frame(from: &mut impl Read) -> io::Result<Option<Frame>> {
    loop {
        let mut header = [0u8; 62];
        match from.read_exact(&mut header) {
            Ok(()) => {}
            Err(err) if err.kind() == io::ErrorKind::UnexpectedEof => return Ok(None),
            Err(err) => return Err(err),
        }
        let kind = u16::from_be_bytes([header[4], header[5]]);
        let length = u32::from_be_bytes([header[6], header[7], header[8], header[9]]) as usize;
        if length > MAX_FRAME {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "a video frame is too large",
            ));
        }
        let mut data = vec![0u8; length];
        from.read_exact(&mut data)?;
        // 1 is a buffer; the rest describe the stream.
        if kind != 1 {
            continue;
        }
        let pts_ns = u64::from_be_bytes(header[10..18].try_into().expect("8 bytes"));
        let flags = u16::from_be_bytes([header[42], header[43]]);
        return Ok(Some(Frame {
            // GST_BUFFER_FLAG_DELTA_UNIT marks a picture that needs earlier ones.
            key: flags & 0x2000 == 0,
            pts_us: if pts_ns == u64::MAX { 0 } else { pts_ns / 1000 },
            data,
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn packet(kind: u16, flags: u16, pts_ns: u64, payload: &[u8]) -> Vec<u8> {
        let mut header = [0u8; 62];
        header[0] = 1;
        header[4..6].copy_from_slice(&kind.to_be_bytes());
        header[6..10].copy_from_slice(&(payload.len() as u32).to_be_bytes());
        header[10..18].copy_from_slice(&pts_ns.to_be_bytes());
        header[42..44].copy_from_slice(&flags.to_be_bytes());
        [&header[..], payload].concat()
    }

    #[test]
    fn sizes_the_picture() {
        let scaled = pipeline(7, Encoder::Va, false, Picture::Scaled((1280, 720)));
        assert!(scaled.contains("video/x-raw,max-framerate=60/1 ! queue"));
        assert!(
            scaled
                .contains("vapostproc ! video/x-raw,format=NV12,width=1280,height=720 ! vah264enc")
        );
        let software = pipeline(7, Encoder::X264, false, Picture::Scaled((1280, 720)));
        assert!(
            software.contains(
                "videoscale ! video/x-raw,width=1280,height=720 ! videoconvert ! x264enc"
            )
        );
        let made = pipeline(7, Encoder::Va, false, Picture::Captured((2340, 1080)));
        assert!(made.contains("video/x-raw,max-framerate=60/1,width=2340,height=1080 ! queue"));
        assert!(made.contains("format=NV12 ! vah264enc"));
    }

    #[test]
    fn fits_a_monitor_into_what_the_phone_asks() {
        let full_hd = (1920, 1080);
        assert_eq!(fit(full_hd, None, None), None);
        assert_eq!(fit(full_hd, None, Some(1080)), None);
        assert_eq!(fit(full_hd, Some(4000), Some(4000)), None);
        assert_eq!(fit(full_hd, None, Some(720)), Some((1280, 720)));
        assert_eq!(fit(full_hd, Some(1280), None), Some((1280, 720)));
        assert_eq!(fit(full_hd, Some(1000), Some(720)), Some((1000, 562)));
        assert_eq!(fit((2560, 1600), None, Some(1080)), Some((1728, 1080)));
        assert_eq!(fit((1080, 1920), None, Some(540)), Some((302, 540)));
        // Never smaller than the smallest side taken.
        assert_eq!(fit(full_hd, None, Some(1)), Some((426, 240)));
        assert_eq!(fit((0, 0), None, Some(720)), None);
    }

    #[test]
    fn keeps_asked_sizes_sane() {
        assert_eq!(sane((2340, 1080)), (2340, 1080));
        assert_eq!(sane((1081, 721)), (1080, 720));
        assert_eq!(sane((1, 100_000)), (240, 4096));
    }

    #[test]
    fn relays_the_capture_only_when_asked() {
        let direct = pipeline(42, Encoder::X264, false, Picture::AsItIs);
        assert!(direct.starts_with("pipewiresrc fd=0 path=42 "));
        assert!(!direct.contains("intervideo"));
        assert!(direct.contains("leaky=downstream !  videoconvert ! x264enc"));

        let relayed = pipeline(42, Encoder::Va, true, Picture::AsItIs);
        assert!(relayed.contains("leaky=downstream ! intervideosink channel=screen sync=false"));
        assert!(
            relayed
                .contains("intervideosrc channel=screen ! video/x-raw,framerate=60/1 ! vapostproc")
        );
        assert!(Encoder::Va.wants_live_source() && !Encoder::Nvidia.wants_live_source());
    }

    #[test]
    fn reads_frames_and_skips_the_rest() {
        let stream = [
            packet(2, 0, 0, b"video/x-h264"),
            packet(1, 0x0440, 3_000_000, &[0, 0, 0, 1, 9]),
            packet(1, 0x2000, 36_333_333, &[0, 0, 0, 1, 1]),
        ]
        .concat();
        let mut stream = &stream[..];
        let first = read_frame(&mut stream).unwrap().unwrap();
        assert_eq!(
            first,
            Frame {
                key: true,
                pts_us: 3000,
                data: vec![0, 0, 0, 1, 9]
            }
        );
        let second = read_frame(&mut stream).unwrap().unwrap();
        assert!(!second.key);
        assert_eq!(second.pts_us, 36_333);
        assert_eq!(read_frame(&mut stream).unwrap(), None);
    }

    #[test]
    fn a_frame_cut_short_is_an_error() {
        let mut cut = packet(1, 0, 0, &[1, 2, 3, 4]);
        cut.truncate(64);
        assert!(read_frame(&mut &cut[..]).is_err());
    }

    #[test]
    fn refuses_an_absurd_length() {
        let mut huge = packet(1, 0, 0, &[]);
        huge[6..10].copy_from_slice(&u32::MAX.to_be_bytes());
        assert!(read_frame(&mut &huge[..]).is_err());
    }
}
