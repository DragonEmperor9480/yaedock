//! The phone's camera as one of the PC's: a camera in PipeWire showing the
//! video the phone sends.
//!
//! GStreamer runs as a child process (`gst-launch-1.0`), the same as for the
//! screen: it decodes the phone's H.264 and its `pipewiresink` offers the
//! pictures as a camera. That needs no root. Programs that look for cameras
//! in PipeWire (browsers, OBS) find it; ones that only know `/dev/video*`
//! do not.

use std::io::{self, Write};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{SyncSender, TrySendError, sync_channel};
use std::time::{Duration, Instant};

use tracing::info;

use crate::screen::has_element;

/// The camera's name for programs.
const NODE: &str = "yaedock_phone_camera";

/// The most pictures kept waiting for the decoder. Beyond this they are
/// dropped up to the next keyframe, or the camera would fall behind for good.
const MOST_WAITING: usize = 30;

/// How far behind the phone the camera shows its pictures. Wi-Fi delivers
/// them in fits and starts; held this long, they are shown as evenly as they
/// were filmed. One later than this is shown the moment it comes.
const BEHIND: Duration = Duration::from_millis(120);

/// How much later than the quickest picture so far one can come before the
/// phone's clock is taken to have jumped, as when it starts filming anew.
const JUMP: Duration = Duration::from_secs(1);

/// How far the quickest trip is let grow with each picture, so a phone whose
/// clock runs slower than the PC's does not fall further and further behind.
const DRIFT_US: i64 = 20;

/// Lateness is put in the log when it passes this, at most this often.
const WORTH_SAYING: Duration = Duration::from_millis(300);
const SAY_EVERY: Duration = Duration::from_secs(30);

/// What decodes the phone's video.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decoder {
    /// The graphics card, through VA-API.
    Va,
    Libav,
    OpenH264,
}

impl Decoder {
    fn element(self) -> &'static str {
        match self {
            Decoder::Va => "vah264dec",
            Decoder::Libav => "avdec_h264",
            Decoder::OpenH264 => "openh264dec",
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Decoder::Va => "the graphics card",
            Decoder::Libav => "libav",
            Decoder::OpenH264 => "OpenH264",
        }
    }
}

/// How this PC shows the phone's camera.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Setup {
    pub decoder: Decoder,
    /// Whether the picture can be turned upright for a phone held upright.
    pub turns: bool,
}

/// Why the phone's camera can't be one of this PC's.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error)]
pub enum CameraError {
    #[error("GStreamer (gst-launch-1.0) is not installed")]
    NoGStreamer,
    #[error("GStreamer's {0} is not installed")]
    Missing(&'static str),
    #[error("GStreamer has no H.264 decoder")]
    NoDecoder,
}

/// What the camera needs on this PC, or the first thing that is missing.
pub fn detect() -> Result<Setup, CameraError> {
    let launches = Command::new("gst-launch-1.0")
        .arg("--version")
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .is_ok_and(|status| status.success());
    if !launches {
        return Err(CameraError::NoGStreamer);
    }
    for element in ["pipewiresink", "h264parse", "videoconvert"] {
        if !has_element(element) {
            return Err(CameraError::Missing(element));
        }
    }
    // The graphics card only when it can also hand the picture back.
    let va = has_element("vah264dec") && has_element("vapostproc");
    let decoder = if va {
        Decoder::Va
    } else {
        [Decoder::Libav, Decoder::OpenH264]
            .into_iter()
            .find(|decoder| has_element(decoder.element()))
            .ok_or(CameraError::NoDecoder)?
    };
    Ok(Setup {
        decoder,
        turns: va || has_element("videoflip"),
    })
}

/// How far the phone's picture is turned to stand upright.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Turn {
    #[default]
    None,
    Right,
    Half,
    Left,
}

impl Turn {
    /// From degrees clockwise; anything but a quarter turn is no turn.
    pub fn from_degrees(degrees: u16) -> Self {
        match degrees % 360 {
            90 => Turn::Right,
            180 => Turn::Half,
            270 => Turn::Left,
            _ => Turn::None,
        }
    }

    fn direction(self) -> Option<&'static str> {
        match self {
            Turn::None => None,
            Turn::Right => Some("90r"),
            Turn::Half => Some("180"),
            Turn::Left => Some("90l"),
        }
    }
}

/// The arguments for `gst-launch-1.0`: H.264 from its input to a camera
/// called `name`.
fn pipeline(name: &str, setup: Setup, turn: Turn) -> Vec<String> {
    // The name sits in quotes inside a list, so it can have none of these.
    let name = name.replace(['\\', '"', '\'', ',', ';'], "");
    let turn = turn.direction().filter(|_| setup.turns);
    let decode = match setup.decoder {
        // Not live, the decoder would hold pictures back in case later ones
        // belong before them; the phone sends them in order.
        Decoder::Va => format!(
            "vah264dec compliance=flexible ! vapostproc video-direction={}",
            turn.unwrap_or("identity")
        ),
        decoder => match turn {
            Some(turn) => format!(
                "{} ! videoflip video-direction={turn} ! videoconvert",
                decoder.element()
            ),
            None => format!("{} ! videoconvert", decoder.element()),
        },
    };
    let mut args: Vec<String> = "-q fdsrc fd=0 do-timestamp=true ! h264parse !"
        .split(' ')
        .map(String::from)
        .collect();
    args.extend(decode.split(' ').map(String::from));
    args.extend(
        "! video/x-raw,format=I420 ! pipewiresink mode=provide sync=false"
            .split(' ')
            .map(String::from),
    );
    args.push(format!(
        "stream-properties=\"props,media.class=Video/Source,media.role=Camera,\
         node.name={NODE},node.description=\\\"{name}\\\"\""
    ));
    args
}

/// Says when each picture is due, from when the phone filmed it: the phone's
/// clock set against the PC's by the quickest trip seen, plus [`BEHIND`].
#[derive(Default)]
struct Pace {
    /// The least time between the phone's clock and a picture's arrival, in
    /// microseconds since `start`.
    quickest: Option<i64>,
    start: Option<Instant>,
    /// How much later than the quickest the last picture came.
    late: Duration,
}

impl Pace {
    /// When to show the picture filmed at `filmed_us` on the phone's clock
    /// that arrived at `arrived`: never before it came.
    fn due(&mut self, filmed_us: u64, arrived: Instant) -> Instant {
        let start = *self.start.get_or_insert(arrived);
        let arrived_us = arrived.saturating_duration_since(start).as_micros() as i64;
        let trip = arrived_us.wrapping_sub(filmed_us as i64);
        let quickest = match self.quickest {
            Some(quickest) if (trip.saturating_sub(quickest)).abs() < JUMP.as_micros() as i64 => {
                trip.min(quickest + DRIFT_US)
            }
            _ => trip,
        };
        self.late =
            Duration::from_micros(self.quickest.map_or(0, |was| (trip - was).max(0)) as u64);
        self.quickest = Some(quickest);
        let wait = (BEHIND.as_micros() as i64 - (trip - quickest)).max(0);
        arrived + Duration::from_micros(wait as u64)
    }
}

/// One picture on its way to the decoder.
struct Picture {
    filmed_us: u64,
    arrived: Instant,
    data: Vec<u8>,
}

/// The camera stopped: its GStreamer ended.
#[derive(Debug, thiserror::Error)]
#[error("the camera's GStreamer stopped")]
pub struct Stopped;

/// The PC's camera, there for as long as this value.
pub struct PhoneCamera {
    child: Child,
    frames: SyncSender<Picture>,
    /// Pictures are dropped until one that needs none before it.
    wants_keyframe: bool,
}

impl PhoneCamera {
    /// Makes the camera, called `name` in programs' camera lists.
    pub fn open(name: &str, setup: Setup, turn: Turn) -> io::Result<Self> {
        let mut child = Command::new("gst-launch-1.0")
            .args(pipeline(name, setup, turn))
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()?;
        let mut input = child.stdin.take().expect("stdin is piped");
        let (frames, waiting) = sync_channel::<Picture>(MOST_WAITING);
        // Its own thread, so holding pictures until they are due, and a
        // decoder that stalls, hold up nothing else. It ends when the camera
        // is dropped or GStreamer is.
        std::thread::spawn(move || {
            let mut pace = Pace::default();
            // How late each picture came since it was last said, how much
            // video that was, and when.
            let mut lates: Vec<Duration> = Vec::new();
            let mut bytes = 0;
            let mut said = Instant::now();
            while let Ok(picture) = waiting.recv() {
                let due = pace.due(picture.filmed_us, picture.arrived);
                lates.push(pace.late);
                bytes += picture.data.len();
                if said.elapsed() >= SAY_EVERY {
                    lates.sort();
                    let latest = lates.last().copied().unwrap_or_default();
                    if latest >= WORTH_SAYING {
                        info!(
                            "the phone's camera came late: usually {} ms, at worst {} ms, in {} pictures at {} kbit/s",
                            lates[lates.len() / 2].as_millis(),
                            latest.as_millis(),
                            lates.len(),
                            bytes as u128 * 8 / said.elapsed().as_millis().max(1)
                        );
                    }
                    lates.clear();
                    bytes = 0;
                    said = Instant::now();
                }
                std::thread::sleep(due.saturating_duration_since(Instant::now()));
                if input.write_all(&picture.data).is_err() {
                    break;
                }
            }
        });
        Ok(Self {
            child,
            frames,
            wants_keyframe: true,
        })
    }

    /// Hands the camera one picture from the phone: an H.264 access unit in
    /// Annex B form, `key` when it needs none before it, filmed at
    /// `filmed_us` microseconds on the phone's clock. Pictures the decoder
    /// has no time for are dropped.
    pub fn write(&mut self, key: bool, filmed_us: u64, data: Vec<u8>) -> Result<(), Stopped> {
        if self.wants_keyframe && !key {
            return Ok(());
        }
        let picture = Picture {
            filmed_us,
            arrived: Instant::now(),
            data,
        };
        match self.frames.try_send(picture) {
            Ok(()) => self.wants_keyframe = false,
            Err(TrySendError::Full(_)) => self.wants_keyframe = true,
            Err(TrySendError::Disconnected(_)) => return Err(Stopped),
        }
        Ok(())
    }
}

impl Drop for PhoneCamera {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn setup(decoder: Decoder, turns: bool) -> Setup {
        Setup { decoder, turns }
    }

    #[test]
    fn the_graphics_card_decodes_and_turns() {
        let args = pipeline("Pixel camera", setup(Decoder::Va, true), Turn::Right).join(" ");
        assert!(args.contains(
            "h264parse ! vah264dec compliance=flexible ! vapostproc video-direction=90r !"
        ));
        assert!(args.contains("! video/x-raw,format=I420 ! pipewiresink mode=provide"));
        assert!(args.contains("media.class=Video/Source"));
        assert!(args.contains("node.description=\\\"Pixel camera\\\""));
    }

    #[test]
    fn software_turns_only_with_videoflip() {
        let turned = pipeline("c", setup(Decoder::Libav, true), Turn::Left).join(" ");
        assert!(turned.contains("avdec_h264 ! videoflip video-direction=90l ! videoconvert !"));
        let fixed = pipeline("c", setup(Decoder::OpenH264, false), Turn::Left).join(" ");
        assert!(fixed.contains("openh264dec ! videoconvert !"));
        let upright = pipeline("c", setup(Decoder::Libav, true), Turn::None).join(" ");
        assert!(!upright.contains("videoflip"));
    }

    #[test]
    fn a_name_cannot_break_out_of_its_quotes() {
        let args = pipeline("a\",b\\'c;d", setup(Decoder::Libav, false), Turn::None);
        assert!(
            args.last()
                .unwrap()
                .ends_with("node.description=\\\"abcd\\\"\"")
        );
    }

    #[test]
    fn turns_come_from_quarter_turns() {
        assert_eq!(Turn::from_degrees(0), Turn::None);
        assert_eq!(Turn::from_degrees(90), Turn::Right);
        assert_eq!(Turn::from_degrees(180), Turn::Half);
        assert_eq!(Turn::from_degrees(270), Turn::Left);
        assert_eq!(Turn::from_degrees(450), Turn::Right);
        assert_eq!(Turn::from_degrees(45), Turn::None);
    }

    #[test]
    fn pictures_that_come_in_fits_and_starts_are_shown_evenly() {
        let mut pace = Pace::default();
        let start = Instant::now();
        let ms = Duration::from_millis;
        // Filmed 33 ms apart; the third comes 60 ms late, the fourth with it.
        let first = pace.due(1_000_000, start);
        let second = pace.due(1_033_000, start + ms(33));
        let third = pace.due(1_066_000, start + ms(126));
        let fourth = pace.due(1_099_000, start + ms(127));
        assert_eq!(first, start + BEHIND);
        let apart = |a: Instant, b: Instant| b.duration_since(a).as_micros() as i64;
        for gap in [
            apart(first, second),
            apart(second, third),
            apart(third, fourth),
        ] {
            assert!((gap - 33_000).abs() < 100, "{gap}");
        }
    }

    #[test]
    fn a_picture_too_late_is_shown_at_once() {
        let mut pace = Pace::default();
        let start = Instant::now();
        pace.due(0, start);
        let late = start + Duration::from_millis(33) + BEHIND * 2;
        assert_eq!(pace.due(33_000, late), late);
    }

    #[test]
    fn a_phone_clock_that_jumps_starts_the_count_anew() {
        let mut pace = Pace::default();
        let start = Instant::now();
        pace.due(5_000_000, start);
        // Filming anew: its clock starts over, far behind the old one.
        let again = start + Duration::from_secs(3);
        assert_eq!(pace.due(0, again), again + BEHIND);
        // And one far ahead of it.
        let ahead = again + Duration::from_millis(33);
        assert_eq!(pace.due(90_000_000, ahead), ahead + BEHIND);
    }
}
