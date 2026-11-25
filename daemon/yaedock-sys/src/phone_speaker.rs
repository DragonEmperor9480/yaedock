//! The phone as one of the PC's speakers: a virtual output device whose
//! sound is read and handed on, to be sent to the phone.
//!
//! The sound server's `module-pipe-sink` makes the device and writes what it
//! plays into a named pipe. PipeWire (through pipewire-pulse) and PulseAudio
//! both have it, and loading it needs no root. The device is made the default
//! output while it is there, and the old default comes back after.

use std::fs::{File, OpenOptions};
use std::io::{self, Read};
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;

use crate::phone_mic::pactl;

/// Samples per second, for each of the two channels of signed 16-bit
/// little-endian samples, interleaved left then right.
pub const RATE: u32 = 48_000;

/// Bytes in 80 ms of sound: the most handed on at once. The sound server
/// writes about 21 ms at a time, which goes on whole, as soon as it comes.
pub const PIECE: usize = (RATE as usize / 50) * 2 * 2 * 4;

/// The device's name for programs, and what marks the module as ours.
const SINK: &str = "yaedock_phone_speaker";

/// Whether a virtual speaker can be made: `pactl` is there and a sound
/// server answers it.
pub fn detect() -> bool {
    crate::phone_mic::detect()
}

/// The ids of the modules in `pactl list short modules` that are our device:
/// one a daemon that was killed left behind.
fn our_modules(list: &str) -> Vec<&str> {
    list.lines()
        .filter(|line| line.contains(&format!("sink_name={SINK}")))
        .filter_map(|line| line.split_whitespace().next())
        .collect()
}

/// The PC's virtual speaker, there for as long as this value.
pub struct PhoneSpeaker {
    module: String,
    path: PathBuf,
    /// The default output before this one, given back when it goes.
    before: Option<String>,
    stop: Arc<AtomicBool>,
    reader: Option<JoinHandle<()>>,
}

impl PhoneSpeaker {
    /// Makes the device, called `name` in the PC's sound settings, and makes
    /// it the default output. Its pipe goes to `path`, which should be in the
    /// user's runtime directory. `on_sound` gets what it plays, whole samples
    /// in the format of [`RATE`], up to [`PIECE`] bytes at a time, on a thread
    /// of its own; nothing comes while nothing plays.
    pub fn open(
        name: &str,
        path: &Path,
        on_sound: impl FnMut(Vec<u8>) + Send + 'static,
    ) -> io::Result<Self> {
        if let Ok(list) = pactl(&["list", "short", "modules"]) {
            for module in our_modules(&list) {
                let _ = pactl(&["unload-module", module]);
            }
        }
        let _ = std::fs::remove_file(path);
        let before = pactl(&["get-default-sink"])
            .ok()
            .map(|sink| sink.trim().to_string())
            .filter(|sink| !sink.is_empty() && sink != SINK);
        // The name sits inside two pairs of quotes, so it can have neither kind.
        let name = name.replace(['\\', '"', '\''], "");
        let module = pactl(&[
            "load-module",
            "module-pipe-sink",
            &format!("sink_name={SINK}"),
            &format!("file={}", path.display()),
            "format=s16le",
            &format!("rate={RATE}"),
            "channels=2",
            &format!("sink_properties='device.description=\"{name}\"'"),
        ])?
        .trim()
        .to_string();
        // Opening a pipe to read waits for a writer unless told not to.
        let pipe = OpenOptions::new()
            .read(true)
            .custom_flags(libc::O_NONBLOCK)
            .open(path);
        let pipe = match pipe {
            Ok(pipe) => pipe,
            Err(err) => {
                let _ = pactl(&["unload-module", &module]);
                return Err(err);
            }
        };
        // The sound server adds the device a moment after loading it, and
        // ignores making a device it has not added yet the default.
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        loop {
            let _ = pactl(&["set-default-sink", SINK]);
            let default = pactl(&["get-default-sink"]).unwrap_or_default();
            if default.trim() == SINK || std::time::Instant::now() >= deadline {
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(50));
        }
        let stop = Arc::new(AtomicBool::new(false));
        let reader = {
            let stop = stop.clone();
            std::thread::Builder::new()
                .name("phone-speaker".into())
                .spawn(move || read_sound(pipe, &stop, on_sound))?
        };
        Ok(Self {
            module,
            path: path.to_owned(),
            before,
            stop,
            reader: Some(reader),
        })
    }
}

impl Drop for PhoneSpeaker {
    fn drop(&mut self) {
        // The old default first, so what plays moves there and not elsewhere,
        // unless someone picked another output meanwhile.
        let ours = pactl(&["get-default-sink"]).is_ok_and(|sink| sink.trim() == SINK);
        if let (true, Some(before)) = (ours, &self.before) {
            let _ = pactl(&["set-default-sink", before]);
        }
        let _ = pactl(&["unload-module", &self.module]);
        let _ = std::fs::remove_file(&self.path);
        self.stop.store(true, Ordering::SeqCst);
        if let Some(reader) = self.reader.take() {
            let _ = reader.join();
        }
    }
}

/// Reads the pipe until `stop`, handing on whole samples as they come.
fn read_sound(mut pipe: File, stop: &AtomicBool, mut on_sound: impl FnMut(Vec<u8>)) {
    // A frame of both channels.
    const FRAME: usize = 4;
    let mut piece = Vec::with_capacity(PIECE);
    let mut buffer = [0u8; PIECE];
    while !stop.load(Ordering::SeqCst) {
        let mut poll = libc::pollfd {
            fd: pipe.as_raw_fd(),
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: one valid pollfd, for as long as the call.
        let ready = unsafe { libc::poll(&mut poll, 1, 100) };
        if ready <= 0 {
            continue;
        }
        if poll.revents & libc::POLLIN == 0 {
            // No writer (POLLHUP) or worse: wait, rather than spin.
            std::thread::sleep(std::time::Duration::from_millis(20));
            continue;
        }
        match pipe.read(&mut buffer[..PIECE - piece.len()]) {
            Ok(0) => std::thread::sleep(std::time::Duration::from_millis(20)),
            Ok(read) => {
                // All of what came goes on at once: splitting it would only
                // make more, smaller messages, each one a chance to wait.
                piece.extend_from_slice(&buffer[..read]);
                let whole = piece.len() - piece.len() % FRAME;
                if whole > 0 {
                    let rest = piece.split_off(whole);
                    on_sound(std::mem::replace(&mut piece, rest));
                }
            }
            Err(err) if err.kind() == io::ErrorKind::WouldBlock => {}
            Err(err) if err.kind() == io::ErrorKind::Interrupted => {}
            Err(_) => break,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use std::time::{Duration, Instant};

    #[test]
    fn finds_the_module_left_behind() {
        let list = "1\tlibpipewire-module-rt\t\n\
            536870918\tmodule-pipe-sink\tsink_name=yaedock_phone_speaker file=/run/user/1000/yaedock/speaker format=s16le\n\
            536870919\tmodule-pipe-sink\tsink_name=other file=/tmp/x\n";
        assert_eq!(our_modules(list), ["536870918"]);
        assert!(our_modules("").is_empty());
    }

    /// Makes the device on this PC, plays a moment of silence into it and
    /// checks it comes out whole, then that the default output comes back.
    /// Skipped where there is no sound server.
    #[test]
    fn passes_on_what_plays() {
        if !detect()
            || std::process::Command::new("pw-play")
                .arg("--version")
                .output()
                .is_err()
        {
            eprintln!("no sound server or pw-play; skipped");
            return;
        }
        let before = pactl(&["get-default-sink"]).unwrap();
        let dir = std::env::temp_dir().join(format!("yaedock-speaker-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let got = Arc::new(Mutex::new(Vec::<usize>::new()));
        let speaker = {
            let got = got.clone();
            PhoneSpeaker::open("YaeDock test speaker", &dir.join("pipe"), move |sound| {
                got.lock().unwrap().push(sound.len());
            })
            .unwrap()
        };
        assert_eq!(pactl(&["get-default-sink"]).unwrap().trim(), SINK);
        // A tenth of a second of silence, as a WAV file.
        let wav = dir.join("quiet.wav");
        let samples = RATE as usize / 10;
        let mut file = Vec::new();
        let data = (samples * 4) as u32;
        file.extend_from_slice(b"RIFF");
        file.extend_from_slice(&(36 + data).to_le_bytes());
        file.extend_from_slice(b"WAVEfmt ");
        file.extend_from_slice(&16u32.to_le_bytes());
        file.extend_from_slice(&1u16.to_le_bytes());
        file.extend_from_slice(&2u16.to_le_bytes());
        file.extend_from_slice(&RATE.to_le_bytes());
        file.extend_from_slice(&(RATE * 4).to_le_bytes());
        file.extend_from_slice(&4u16.to_le_bytes());
        file.extend_from_slice(&16u16.to_le_bytes());
        file.extend_from_slice(b"data");
        file.extend_from_slice(&data.to_le_bytes());
        file.resize(file.len() + samples * 4, 0);
        std::fs::write(&wav, file).unwrap();
        let played = std::process::Command::new("pw-play")
            .args(["--target", SINK])
            .arg(&wav)
            .status()
            .unwrap();
        assert!(played.success());
        let deadline = Instant::now() + Duration::from_secs(2);
        while got.lock().unwrap().iter().sum::<usize>() < samples * 4 && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(20));
        }
        drop(speaker);
        let got = got.lock().unwrap();
        assert!(got.iter().sum::<usize>() >= samples * 4, "{got:?}");
        assert!(
            got.iter()
                .all(|&len| len > 0 && len <= PIECE && len % 4 == 0),
            "{got:?}"
        );
        assert_eq!(pactl(&["get-default-sink"]).unwrap(), before);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
