//! The phone's microphone as one of the PC's: a virtual input device fed
//! with the sound the phone sends.
//!
//! The sound server's `module-pipe-source` makes the device and reads its
//! sound from a named pipe. PipeWire (through pipewire-pulse) and PulseAudio
//! both have it, and loading it needs no root.

use std::fs::{File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// Samples per second of the phone's sound: one channel of signed 16-bit
/// little-endian samples.
pub const RATE: u32 = 48_000;
const BYTES_PER_SAMPLE: u64 = 2;

/// The device's name for programs, and what marks the module as ours.
const SOURCE: &str = "yaedock_phone_mic";

/// The most sound kept waiting for the sound server. A phone on Wi-Fi sends
/// in fits and starts; what arrives beyond this is dropped, or every stall
/// would leave the microphone that much further behind for good.
const MOST_WAITING: Duration = Duration::from_millis(100);

/// Whether a virtual microphone can be made: `pactl` is there and a sound
/// server answers it.
pub fn detect() -> bool {
    pactl(&["info"]).is_ok()
}

fn pactl(args: &[&str]) -> io::Result<String> {
    let output = Command::new("pactl")
        .args(args)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()?;
    if !output.status.success() {
        return Err(io::Error::other(format!("pactl {} failed", args.join(" "))));
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// The ids of the modules in `pactl list short modules` that are our device:
/// one a daemon that was killed left behind.
fn our_modules(list: &str) -> Vec<&str> {
    list.lines()
        .filter(|line| line.contains(&format!("source_name={SOURCE}")))
        .filter_map(|line| line.split_whitespace().next())
        .collect()
}

/// The PC's virtual microphone, there for as long as this value.
pub struct PhoneMic {
    module: String,
    pipe: File,
    path: PathBuf,
    waiting: Waiting,
}

impl PhoneMic {
    /// Makes the device, called `name` in the PC's sound settings. Its pipe
    /// goes to `path`, which should be in the user's runtime directory.
    pub fn open(name: &str, path: &Path) -> io::Result<Self> {
        if let Ok(list) = pactl(&["list", "short", "modules"]) {
            for module in our_modules(&list) {
                let _ = pactl(&["unload-module", module]);
            }
        }
        let _ = std::fs::remove_file(path);
        // The name sits inside two pairs of quotes, so it can have neither kind.
        let name = name.replace(['\\', '"', '\''], "");
        let module = pactl(&[
            "load-module",
            "module-pipe-source",
            &format!("source_name={SOURCE}"),
            &format!("file={}", path.display()),
            "format=s16le",
            &format!("rate={RATE}"),
            "channels=1",
            &format!("source_properties='device.description=\"{name}\"'"),
        ])?
        .trim()
        .to_string();
        // Never waits for the sound server: a full pipe drops sound instead.
        let pipe = OpenOptions::new()
            .write(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(path);
        let pipe = match pipe {
            Ok(pipe) => pipe,
            Err(err) => {
                let _ = pactl(&["unload-module", &module]);
                return Err(err);
            }
        };
        Ok(Self {
            module,
            pipe,
            path: path.to_owned(),
            waiting: Waiting::default(),
        })
    }

    /// Hands the device `sound` from the phone: whole samples in the format
    /// of [`RATE`]. Sound that would wait too long is dropped.
    pub fn write(&mut self, sound: &[u8]) {
        let sound = &sound[..sound.len() - sound.len() % BYTES_PER_SAMPLE as usize];
        if sound.is_empty() || !self.waiting.admit(Instant::now(), sound.len() as u64) {
            return;
        }
        // A pipe the sound server is not emptying loses the sound too.
        let _ = self.pipe.write_all(sound);
    }
}

impl Drop for PhoneMic {
    fn drop(&mut self) {
        let _ = pactl(&["unload-module", &self.module]);
        let _ = std::fs::remove_file(&self.path);
    }
}

/// Follows how much sound the sound server still has to play. It takes
/// sound out at the speed of the clock, and plays silence when it has none.
#[derive(Default)]
struct Waiting {
    /// How much was waiting at `at`.
    sound: Duration,
    at: Option<Instant>,
}

impl Waiting {
    /// Whether `bytes` more of sound arriving at `now` should be passed on.
    fn admit(&mut self, now: Instant, bytes: u64) -> bool {
        if let Some(at) = self.at {
            self.sound = self.sound.saturating_sub(now.saturating_duration_since(at));
        }
        self.at = Some(now);
        let more = Duration::from_micros(bytes * 1_000_000 / (BYTES_PER_SAMPLE * u64::from(RATE)));
        if self.sound + more > MOST_WAITING {
            return false;
        }
        self.sound += more;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 20 ms of sound.
    const PIECE: u64 = 1920;

    #[test]
    fn finds_the_module_left_behind() {
        let list = "1\tlibpipewire-module-rt\t\n\
            536870916\tmodule-pipe-source\tsource_name=yaedock_phone_mic file=/run/user/1000/yaedock/mic format=s16le\n\
            536870917\tmodule-pipe-source\tsource_name=other file=/tmp/x\n";
        assert_eq!(our_modules(list), ["536870916"]);
        assert!(our_modules("").is_empty());
    }

    #[test]
    fn passes_on_sound_that_comes_evenly() {
        let mut waiting = Waiting::default();
        let start = Instant::now();
        for piece in 0..500 {
            assert!(waiting.admit(start + Duration::from_millis(20 * piece), PIECE));
        }
    }

    #[test]
    fn drops_what_piles_up_after_a_stall() {
        let mut waiting = Waiting::default();
        let start = Instant::now();
        assert!(waiting.admit(start, PIECE));
        // Nothing for 300 ms, then all of it at once: 15 pieces.
        let late = start + Duration::from_millis(300);
        let passed = (0..15).filter(|_| waiting.admit(late, PIECE)).count();
        assert_eq!(passed, 5);
        // The newest sound gets through again once that has played.
        assert!(!waiting.admit(late + Duration::from_millis(10), PIECE));
        assert!(waiting.admit(late + Duration::from_millis(20), PIECE));
    }

    #[test]
    fn keeps_up_with_a_phone_whose_clock_runs_fast() {
        let mut waiting = Waiting::default();
        let start = Instant::now();
        // 20 ms of sound every 19 ms: one piece in twenty is one too many.
        let passed = (0..2000)
            .filter(|piece| waiting.admit(start + Duration::from_millis(19 * piece), PIECE))
            .count();
        assert!((1890..=1905).contains(&passed), "{passed}");
    }
}
