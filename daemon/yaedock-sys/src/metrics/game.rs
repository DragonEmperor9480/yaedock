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

/// The running game's frame rate, read from the log MangoHud writes while a
/// game runs with it (logging into [`GameSource::new`]'s folder, which
/// `yaedock fps on` sets up). Only the newly written part of the log is read.
pub struct GameSource {
    folder: PathBuf,
    current: Option<Log>,
}

struct Log {
    path: PathBuf,
    name: String,
    offset: u64,
    /// Column positions of `fps` and `frametime`, from the header row.
    columns: Option<(usize, usize)>,
    /// The end of the last line read, when it was cut off mid-write.
    partial: String,
    latest: Option<Game>,
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
            columns: None,
            partial: String::new(),
            latest: None,
        }
    }

    /// Reads what was appended since last time and keeps the newest reading.
    fn read_new(&mut self) -> io::Result<()> {
        let mut file = File::open(&self.path)?;
        let len = file.metadata()?.len();
        if len < self.offset {
            // The file was replaced; start over.
            *self = Log::new(self.path.clone());
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
        for line in complete.lines() {
            self.read_line(line);
        }
        Ok(())
    }

    fn read_line(&mut self, line: &str) {
        let fields: Vec<&str> = line.split(',').map(str::trim).collect();
        // MangoHud writes a system description, then a header, then one row per interval.
        if let (Some(fps), Some(frametime)) = (
            fields.iter().position(|f| *f == "fps"),
            fields.iter().position(|f| *f == "frametime"),
        ) {
            self.columns = Some((fps, frametime));
            return;
        }
        let Some((fps, frametime)) = self.columns else {
            return;
        };
        let number = |i: usize| fields.get(i).and_then(|f| f.parse::<f32>().ok());
        if let (Some(fps), Some(frametime)) = (number(fps), number(frametime)) {
            self.latest = Some(Game {
                name: self.name.clone(),
                fps,
                frametime,
            });
        }
    }
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
    fn follows_a_growing_log() {
        let dir = std::env::temp_dir().join(format!("yaedock-game-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mut source = GameSource::new(dir.clone());
        let log = dir.join("vkcube_2026-10-19_16-40-14.csv");

        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(stats.game, None, "no game is running yet");

        // A row cut off mid-write is kept until the rest arrives.
        fs::write(&log, format!("{HEAD}59.7,16.7,3,2,100\n60.1,16.6,3")).unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(
            stats.game,
            Some(Game {
                name: "vkcube".into(),
                fps: 59.7,
                frametime: 16.7
            })
        );

        let mut file = fs::OpenOptions::new().append(true).open(&log).unwrap();
        std::io::Write::write_all(&mut file, b",2,200\n").unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert_eq!(stats.game.unwrap().fps, 60.1);

        fs::remove_dir_all(&dir).unwrap();
    }
}
