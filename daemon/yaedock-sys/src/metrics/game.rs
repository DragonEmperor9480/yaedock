use std::collections::VecDeque;
use std::fs::{self, File};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime};

use yaedock_proto::{Game, Stats};

use super::MetricSource;

/// A log not written to for this long belongs to a game that has stopped.
const STALE: Duration = Duration::from_secs(3);
/// Logs older than this are deleted; they only ever served the live view.
const KEEP: Duration = Duration::from_secs(60 * 60);
/// Frame times kept for the lows: a minute of rows at MangoHud's
/// `log_interval=100` (ten a second), as `yaedock fps on` sets it.
const WINDOW: usize = 600;
/// Joining a game that is already running (a phone reconnecting, the daemon
/// restarting) reads only this much of the end of its log: about a minute of
/// rows, enough for the lows. A long session's log runs to megabytes.
const TAIL: u64 = 64 * 1024;
/// How much of the start of a log is searched for its header row.
const HEAD: u64 = 4096;
/// Rows the first reading after joining averages: a second's worth, so it
/// shows the frame rate now rather than over the minute read.
const NOW_ROWS: usize = 10;

/// The running game's frame rate, read from the log MangoHud writes while a
/// game runs with it (logging into [`GameSource::new`]'s folder, which
/// `yaedock fps on` sets up). Only the newly written part of the log is read.
///
/// Each log row holds the time of the one frame drawn when the row was
/// written, so a single row is noisy. Each sample averages the frame times of
/// every row since the last one, as MangoHud's own overlay averages frames.
pub struct GameSource {
    folder: PathBuf,
    current: Option<Log>,
}

struct Log {
    path: PathBuf,
    name: String,
    offset: u64,
    /// Column position of `frametime`, from the header row.
    frametime_column: Option<usize>,
    /// The end of the last line read, when it was cut off mid-write.
    partial: String,
    latest: Option<Game>,
    /// The last [`WINDOW`] frame times, oldest first, for the lows.
    recent: VecDeque<f32>,
    /// Whether the next read is the first after skipping to [TAIL].
    joined: bool,
}

impl GameSource {
    /// Watches `folder`, creating it so MangoHud can write there.
    pub fn new(folder: PathBuf) -> Self {
        let _ = fs::create_dir_all(&folder);
        Self {
            folder,
            current: None,
        }
    }

    /// The newest frame log written to recently, if a game is running.
    fn active_log(&self) -> Option<PathBuf> {
        let now = SystemTime::now();
        fs::read_dir(&self.folder)
            .ok()?
            .flatten()
            .filter_map(|entry| {
                let path = entry.path();
                let name = path.file_name()?.to_str()?;
                if !name.ends_with(".csv") || name.ends_with("_summary.csv") {
                    return None;
                }
                let modified = entry.metadata().ok()?.modified().ok()?;
                (now.duration_since(modified).unwrap_or_default() < STALE)
                    .then_some((modified, path))
            })
            .max_by_key(|(modified, _)| *modified)
            .map(|(_, path)| path)
    }

    fn clean_up(&self) {
        let Ok(entries) = fs::read_dir(&self.folder) else {
            return;
        };
        let now = SystemTime::now();
        for entry in entries.flatten() {
            let old = entry
                .metadata()
                .and_then(|m| m.modified())
                .is_ok_and(|modified| now.duration_since(modified).unwrap_or_default() > KEEP);
            if old && entry.path().extension().is_some_and(|e| e == "csv") {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
}

impl MetricSource for GameSource {
    fn only_when_watched(&self) -> bool {
        true
    }

    fn pause(&mut self) {
        self.current = None;
    }

    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        let Some(path) = self.active_log() else {
            if self.current.take().is_some() {
                // The game ended; a good moment to tidy up.
                self.clean_up();
            }
            return Ok(());
        };
        if self.current.as_ref().is_none_or(|log| log.path != path) {
            self.current = Some(Log::new(path));
        }
        let log = self.current.as_mut().expect("just set");
        log.read_new()?;
        stats.game = log.latest.clone();
        Ok(())
    }
}

impl Log {
    fn new(path: PathBuf) -> Self {
        let name = game_name(&path);
        Self {
            path,
            name,
            offset: 0,
            frametime_column: None,
            partial: String::new(),
            latest: None,
            recent: VecDeque::with_capacity(WINDOW),
            joined: false,
        }
    }

    /// Reads the header row from the start of the log, then skips to its
    /// last [TAIL] bytes, at the start of a row.
    fn join(&mut self, file: &mut File, len: u64) -> io::Result<()> {
        let mut head = Vec::new();
        file.by_ref().take(HEAD).read_to_end(&mut head)?;
        // Lossy: the cut at HEAD may split a character of a hardware name.
        for line in String::from_utf8_lossy(&head).lines() {
            self.read_line(line);
        }
        self.offset = len - TAIL;
        file.seek(SeekFrom::Start(self.offset))?;
        // The row the tail starts in is cut; it is left out.
        let mut cut = Vec::new();
        io::BufRead::read_until(
            &mut io::BufReader::new(file.by_ref().take(TAIL)),
            b'\n',
            &mut cut,
        )?;
        self.offset += cut.len() as u64;
        self.joined = true;
        Ok(())
    }

    /// Reads what was appended since last time and averages its frame times.
    fn read_new(&mut self) -> io::Result<()> {
        let mut file = File::open(&self.path)?;
        let len = file.metadata()?.len();
        if len < self.offset {
            // The file was replaced; start over.
            *self = Log::new(self.path.clone());
        }
        if self.offset == 0 && len > TAIL + HEAD {
            self.join(&mut file, len)?;
        }
        file.seek(SeekFrom::Start(self.offset))?;
        let mut text = String::new();
        file.read_to_string(&mut text)?;
        self.offset += text.len() as u64;

        let text = std::mem::take(&mut self.partial) + &text;
        let (complete, rest) = match text.rfind('\n') {
            Some(end) => text.split_at(end + 1),
            None => ("", text.as_str()),
        };
        self.partial = rest.to_string();
        let frametimes: Vec<f32> = complete
            .lines()
            .filter_map(|line| self.read_line(line))
            .collect();
        // Without new rows the last reading stands.
        if !frametimes.is_empty() {
            for &frametime in &frametimes {
                if self.recent.len() == WINDOW {
                    self.recent.pop_front();
                }
                self.recent.push_back(frametime);
            }
            let now = if std::mem::take(&mut self.joined) {
                &frametimes[frametimes.len().saturating_sub(NOW_ROWS)..]
            } else {
                &frametimes[..]
            };
            let frametime = now.iter().sum::<f32>() / now.len() as f32;
            let recent: Vec<f32> = self.recent.iter().copied().collect();
            self.latest = Some(Game {
                name: self.name.clone(),
                fps: 1000.0 / frametime,
                frametime,
                low_1: low(&recent, 0.01),
                low_0_1: low(&recent, 0.001),
            });
        }
        Ok(())
    }

    /// The frame time in milliseconds of a data row; header rows set the column.
    fn read_line(&mut self, line: &str) -> Option<f32> {
        let fields: Vec<&str> = line.split(',').map(str::trim).collect();
        // MangoHud writes a system description, then a header, then one row per interval.
        if let Some(column) = fields.iter().position(|f| *f == "frametime") {
            self.frametime_column = Some(column);
            return None;
        }
        let frametime: f32 = fields.get(self.frametime_column?)?.parse().ok()?;
        (frametime > 0.0).then_some(frametime)
    }
}

/// The frame rate the slowest `share` of frames stays under, as MangoHud's
/// "1% low": the frame time at that percentile, as frames per second. None
/// until there are enough frames for the slowest share to be one whole frame.
fn low(frametimes: &[f32], share: f32) -> Option<f32> {
    let count = (frametimes.len() as f32 * share).floor() as usize;
    if count == 0 {
        return None;
    }
    let mut sorted = frametimes.to_vec();
    sorted.sort_by(|a, b| b.total_cmp(a));
    Some(1000.0 / sorted[count - 1])
}

/// `eldenring.exe_2026-10-19_16-40-14.csv` → `eldenring.exe`.
fn game_name(path: &Path) -> String {
    let stem = path
        .file_stem()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_default();
    // The program name is followed by `_<date>_<time>`.
    let mut parts: Vec<&str> = stem.rsplitn(3, '_').collect();
    if parts.len() == 3 && parts[0].contains('-') && parts[1].contains('-') {
        parts.pop().unwrap_or(&stem).to_string()
    } else {
        stem
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEAD: &str = "os,cpu,gpu,ram,kernel,driver,cpuscheduler\n\
CachyOS,Intel Core i5-9300H,NVIDIA GeForce GTX 1050,16216120,7.2.4,,powersave\n\
fps,frametime,cpu_load,gpu_load,elapsed\n";

    #[test]
    fn names_games_from_log_files() {
        assert_eq!(
            game_name(Path::new("/x/vkcube_2026-10-19_16-40-14.csv")),
            "vkcube"
        );
        assert_eq!(
            game_name(Path::new("/x/elden_ring.exe_2026-10-19_16-40-14.csv")),
            "elden_ring.exe"
        );
        assert_eq!(game_name(Path::new("/x/odd.csv")), "odd");
    }

    #[test]
    fn finds_the_slowest_frames() {
        // 200 frames: 198 at 10 ms (100 fps), one at 20 ms, one at 40 ms.
        let mut frametimes = vec![10.0; 198];
        frametimes.extend([20.0, 40.0]);
        // The slowest 1% is two frames; the second slowest is 20 ms.
        assert_eq!(low(&frametimes, 0.01), Some(50.0));
        // 0.1% of 200 frames is less than one frame.
        assert_eq!(low(&frametimes, 0.001), None);
        frametimes.extend(vec![10.0; 800]);
        assert_eq!(low(&frametimes, 0.001), Some(25.0));
    }

    #[test]
    fn follows_a_growing_log() {
        let dir = std::env::temp_dir().join(format!("yaedock-game-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mut source = GameSource::new(dir.clone());
        let log = dir.join("vkcube_2026-10-19_16-40-14.csv");

        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(stats.game, None, "no game is running yet");

        // Rows since the last sample are averaged: frames of 10 and 30 ms make
        // 20 ms, 50 frames a second. A row cut off mid-write waits for the rest.
        fs::write(
            &log,
            format!("{HEAD}100,10,3,2,100\n33.3,30,3,2,200\n25,40,3"),
        )
        .unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(
            stats.game,
            Some(Game {
                name: "vkcube".into(),
                fps: 50.0,
                frametime: 20.0,
                low_1: None,
                low_0_1: None,
            })
        );

        let mut file = fs::OpenOptions::new().append(true).open(&log).unwrap();
        std::io::Write::write_all(&mut file, b",2,300\n").unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(stats.game.unwrap().fps, 25.0);

        // Nothing new: the last reading stands.
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(stats.game.unwrap().fps, 25.0);

        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn joins_a_long_game_at_its_end() {
        let dir = std::env::temp_dir().join(format!("yaedock-join-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mut source = GameSource::new(dir.clone());
        // An hour at 10 ms a frame, then the last second at 20 ms.
        let mut log = String::from(HEAD);
        for i in 0..36_000 {
            log.push_str(&format!("100,10,3,2,{i}\n"));
        }
        for i in 0..10 {
            log.push_str(&format!("50,20,3,2,{i}\n"));
        }
        fs::write(dir.join("vkcube_2026-10-19_16-40-14.csv"), &log).unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        let game = stats.game.unwrap();
        // The frame rate now, not the hour's average.
        assert_eq!(game.fps, 50.0);
        // The lows come from the minute read: 600 rows, the slowest 1% at 20 ms.
        assert_eq!(game.low_1, Some(50.0));
        let read = source.current.as_ref().unwrap().offset;
        assert_eq!(read, log.len() as u64, "the log was read to its end");
        fs::remove_dir_all(&dir).unwrap();
    }
}
