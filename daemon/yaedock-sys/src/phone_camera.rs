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

use crate::screen::has_element;

/// The camera's name for programs.
const NODE: &str = "yaedock_phone_camera";

/// The most pictures kept waiting for the decoder. Beyond this they are
/// dropped up to the next keyframe, or the camera would fall behind for good.
const MOST_WAITING: usize = 30;

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
        Decoder::Va => format!(
            "vah264dec ! vapostproc video-direction={}",
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

/// Whether an H.264 access unit in Annex B form is a picture that needs none
/// before it: the first of its units that says so is the stream's settings
/// or a whole picture, not part of one.
pub fn is_keyframe(frame: &[u8]) -> bool {
    frame
        .windows(4)
        .filter(|window| window[..3] == [0, 0, 1])
        .map(|window| window[3] & 0x1f)
        // Before these come only notes: delimiters and the like.
        .find(|kind| matches!(kind, 1 | 5 | 7 | 8))
        .is_some_and(|kind| kind != 1)
}

/// The camera stopped: its GStreamer ended.
#[derive(Debug, thiserror::Error)]
#[error("the camera's GStreamer stopped")]
pub struct Stopped;

/// The PC's camera, there for as long as this value.
pub struct PhoneCamera {
    child: Child,
    frames: SyncSender<Vec<u8>>,
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
        let (frames, waiting) = sync_channel::<Vec<u8>>(MOST_WAITING);
        // Its own thread, so a decoder that stalls holds up nothing else.
        // It ends when the camera is dropped or GStreamer is.
        std::thread::spawn(move || {
            while let Ok(frame) = waiting.recv() {
                if input.write_all(&frame).is_err() {
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
    /// Annex B form. Pictures the decoder has no time for are dropped.
    pub fn write(&mut self, frame: Vec<u8>) -> Result<(), Stopped> {
        if self.wants_keyframe && !is_keyframe(&frame) {
            return Ok(());
        }
        match self.frames.try_send(frame) {
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
        assert!(args.contains("h264parse ! vah264dec ! vapostproc video-direction=90r !"));
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
    fn keyframes_start_with_settings_or_a_whole_picture() {
        assert!(is_keyframe(&[0, 0, 0, 1, 0x67, 1]));
        assert!(is_keyframe(&[0, 0, 1, 0x65]));
        assert!(is_keyframe(&[0, 0, 0, 1, 0x68]));
        assert!(!is_keyframe(&[0, 0, 0, 1, 0x41, 9]));
        // A delimiter first changes nothing.
        assert!(is_keyframe(&[0, 0, 0, 1, 9, 0xf0, 0, 0, 0, 1, 0x67, 1]));
        assert!(!is_keyframe(&[0, 0, 0, 1, 9, 0xf0, 0, 0, 1, 0x41, 1]));
        assert!(!is_keyframe(&[0x67]));
        assert!(!is_keyframe(&[]));
    }
}
