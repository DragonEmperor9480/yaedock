//! Play history: every game run with MangoHud, kept on the PC.
//!
//! Each finished MangoHud log becomes a [`Run`]: when it started and ended,
//! its frame rate, and one [`PlayMinute`] for each minute. Runs of the same
//! program less than [`GAP`] apart make one session, so a launcher or a
//! restart does not split it. [`Store`] keeps the runs, a file a month, and the
//! names given to programs.

use std::collections::BTreeMap;
use std::fs::{self, OpenOptions};
use std::io::{self, BufRead, BufReader, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use yaedock_proto::{PlayMinute, PlaySession, PlaySessionDetail};
use yaedock_sys::metrics::{frame_low, game_name};

use crate::paths;

/// Runs of one program less than this many seconds apart are one session.
pub const GAP: u64 = 5 * 60;

/// Frame times above this many milliseconds are not frames: MangoHud's first
/// rows, before the game draws, hold the time since it started.
const MAX_FRAMETIME: f32 = 1000.0;

/// One MangoHud log: a game from the moment it started drawing until it quit.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Run {
    /// The program, as the log is named after it.
    pub program: String,
    /// Unix seconds.
    pub start: u64,
    pub end: u64,
    /// Rows read: MangoHud writes one every tenth of a second, each with the
    /// time of the frame drawn then.
    pub samples: u64,
    /// The sum of those frame times, in seconds; `samples / busy` is the
    /// average frame rate.
    pub busy: f64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub low_1: Option<f32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub low_0_1: Option<f32>,
    #[serde(default)]
    pub minutes: Vec<PlayMinute>,
}

impl Run {
    pub fn fps(&self) -> f32 {
        if self.busy > 0.0 {
            (self.samples as f64 / self.busy) as f32
        } else {
            0.0
        }
    }
}

/// Reads a MangoHud log of `program` whose last row was written at `end`
/// (Unix seconds, the file's modification time). None if it holds no frames.
///
/// The start is `end` less the last row's `elapsed`, so the log's file name,
/// in local time, is never parsed.
pub fn parse_log(text: &str, program: &str, end: u64) -> Option<Run> {
    let mut columns: Option<Columns> = None;
    let mut last_elapsed = 0u64;
    // Frame times and temperatures by minute since the start.
    let mut buckets: BTreeMap<u64, Bucket> = BTreeMap::new();
    let mut all = Vec::new();
    for line in text.lines() {
        let fields: Vec<&str> = line.split(',').map(str::trim).collect();
        if fields.contains(&"frametime") {
            columns = Some(Columns::from_header(&fields));
            continue;
        }
        let Some(columns) = &columns else { continue };
        let number = |column: Option<usize>| -> Option<f64> {
            fields
                .get(column?)?
                .parse::<f64>()
                .ok()
                .filter(|v| v.is_finite())
        };
        let Some(elapsed) = number(columns.elapsed) else {
            continue;
        };
        let elapsed = elapsed.max(0.0) as u64;
        last_elapsed = last_elapsed.max(elapsed);
        let Some(frametime) = number(Some(columns.frametime)).map(|f| f as f32) else {
            continue;
        };
        if !(frametime > 0.0 && frametime <= MAX_FRAMETIME) {
            continue;
        }
        let bucket = buckets.entry(elapsed / 60_000_000_000).or_default();
        bucket.frametimes.push(frametime);
        // MangoHud writes 0 for a temperature it cannot read.
        if let Some(t) = number(columns.cpu_temp).filter(|t| *t > 0.0) {
            bucket.cpu_temps.push(t as f32);
        }
        if let Some(t) = number(columns.gpu_temp).filter(|t| *t > 0.0) {
            bucket.gpu_temps.push(t as f32);
        }
        all.push(frametime);
    }
    if all.is_empty() {
        return None;
    }
    let start = end.saturating_sub((last_elapsed + 500_000_000) / 1_000_000_000);
    let minutes = buckets
        .into_iter()
        .map(|(minute, bucket)| PlayMinute {
            at: start + minute * 60,
            fps: fps(&bucket.frametimes),
            low_1: frame_low(&bucket.frametimes, 0.01),
            cpu_temp: average(&bucket.cpu_temps),
            gpu_temp: average(&bucket.gpu_temps),
        })
        .collect();
    Some(Run {
        program: program.to_string(),
        start,
        end,
        samples: all.len() as u64,
        busy: all.iter().map(|&f| f as f64).sum::<f64>() / 1000.0,
        low_1: frame_low(&all, 0.01),
        low_0_1: frame_low(&all, 0.001),
        minutes,
    })
}

/// Where the columns the history needs are in a log's header row.
struct Columns {
    frametime: usize,
    elapsed: Option<usize>,
    cpu_temp: Option<usize>,
    gpu_temp: Option<usize>,
}

impl Columns {
    fn from_header(fields: &[&str]) -> Self {
        let find = |name: &str| fields.iter().position(|f| *f == name);
        Self {
            frametime: find("frametime").unwrap_or_default(),
            elapsed: find("elapsed"),
            cpu_temp: find("cpu_temp"),
            gpu_temp: find("gpu_temp"),
        }
    }
}

#[derive(Default)]
struct Bucket {
    frametimes: Vec<f32>,
    cpu_temps: Vec<f32>,
    gpu_temps: Vec<f32>,
}

fn fps(frametimes: &[f32]) -> f32 {
    let total: f32 = frametimes.iter().sum();
    if total > 0.0 {
        frametimes.len() as f32 * 1000.0 / total
    } else {
        0.0
    }
}

fn average(values: &[f32]) -> Option<f32> {
    (!values.is_empty()).then(|| values.iter().sum::<f32>() / values.len() as f32)
}

/// Groups runs into sessions: runs of the same program less than [`GAP`]
/// apart, even with another game between them. Sessions and their runs come
/// oldest first.
pub fn merge(mut runs: Vec<Run>) -> Vec<Vec<Run>> {
    runs.sort_by_key(|run| (run.start, run.end));
    let mut sessions: Vec<Vec<Run>> = Vec::new();
    for run in runs {
        let open = sessions.iter_mut().rev().find(|session| {
            let last = session.last().expect("sessions are never empty");
            last.program == run.program && last.end.max(session_end(session)) + GAP >= run.start
        });
        match open {
            Some(session) => session.push(run),
            None => sessions.push(vec![run]),
        }
    }
    sessions
}

fn session_end(runs: &[Run]) -> u64 {
    runs.iter().map(|run| run.end).max().unwrap_or_default()
}

/// The id `get_play_session` names a session by.
pub fn session_id(program: &str, start: u64) -> String {
    format!("{program}@{start}")
}

/// Splits a session id into its program and start.
pub fn parse_id(id: &str) -> Option<(&str, u64)> {
    let (program, start) = id.rsplit_once('@')?;
    Some((program, start.parse().ok()?))
}

/// A session as the phone sees it. `name` is the program's given name, if any.
pub fn summary(runs: &[Run], name: Option<&str>, live: bool) -> PlaySession {
    let first = &runs[0];
    let samples: u64 = runs.iter().map(|run| run.samples).sum();
    let busy: f64 = runs.iter().map(|run| run.busy).sum();
    // The lows of the runs, weighted by their length: close enough, and the
    // long runs, where they matter, decide it.
    let weighted = |low: fn(&Run) -> Option<f32>| -> Option<f32> {
        let (sum, weight) = runs
            .iter()
            .filter_map(|run| Some((low(run)? as f64 * run.samples as f64, run.samples as f64)))
            .fold((0.0, 0.0), |(s, w), (a, b)| (s + a, w + b));
        (weight > 0.0).then(|| (sum / weight) as f32)
    };
    PlaySession {
        id: session_id(&first.program, first.start),
        game: name.unwrap_or(&first.program).to_string(),
        program: first.program.clone(),
        start: first.start,
        end: session_end(runs),
        played_secs: runs
            .iter()
            .map(|run| run.end.saturating_sub(run.start))
            .sum(),
        fps: if busy > 0.0 {
            (samples as f64 / busy) as f32
        } else {
            0.0
        },
        low_1: weighted(|run| run.low_1),
        low_0_1: weighted(|run| run.low_0_1),
        live,
    }
}

/// The minutes of a session's runs, oldest first.
pub fn minutes(runs: &[Run]) -> Vec<PlayMinute> {
    let mut minutes: Vec<PlayMinute> = runs.iter().flat_map(|run| run.minutes.clone()).collect();
    minutes.sort_by_key(|minute| minute.at);
    minutes
}

/// Where the history is kept: `runs-YYYY-MM.jsonl`, one run a line by the
/// month (UTC) it started, and `names.toml`, the names given to programs.
pub struct Store {
    dir: PathBuf,
}

/// How far around a range runs are loaded, so sessions reaching into it
/// come whole.
const MARGIN: u64 = 24 * 60 * 60;

impl Store {
    /// `$XDG_DATA_HOME/yaedock/play`, usually `~/.local/share/yaedock/play`.
    pub fn open() -> anyhow::Result<Self> {
        Ok(Self::at(paths::data_dir()?.join("play")))
    }

    pub fn at(dir: PathBuf) -> Self {
        Self { dir }
    }

    fn ensure_dir(&self) -> io::Result<()> {
        fs::DirBuilder::new()
            .recursive(true)
            .mode(0o700)
            .create(&self.dir)
    }

    fn month_file(&self, (year, month): (i64, u32)) -> PathBuf {
        self.dir.join(format!("runs-{year:04}-{month:02}.jsonl"))
    }

    /// Adds a run. Appending a line never touches the ones before it.
    pub fn append(&self, run: &Run) -> anyhow::Result<()> {
        self.ensure_dir()?;
        let mut line = serde_json::to_string(run)?;
        line.push('\n');
        let mut file = OpenOptions::new()
            .create(true)
            .append(true)
            .mode(0o600)
            .open(self.month_file(month_of(run.start)))?;
        file.write_all(line.as_bytes())?;
        Ok(())
    }

    /// The runs that start between `from` and `to`, give or take a day.
    pub fn runs(&self, from: u64, to: u64) -> Vec<Run> {
        let from = from.saturating_sub(MARGIN);
        let to = to.saturating_add(MARGIN);
        let mut runs = Vec::new();
        let mut month = month_of(from);
        let last = month_of(to);
        while month <= last {
            runs.extend(read_runs(&self.month_file(month)));
            month = next_month(month);
        }
        runs.retain(|run| run.start <= to && run.end >= from);
        runs
    }

    /// The names given to programs.
    pub fn names(&self) -> BTreeMap<String, String> {
        fs::read_to_string(self.dir.join("names.toml"))
            .ok()
            .and_then(|text| toml::from_str(&text).ok())
            .unwrap_or_default()
    }

    /// Gives `program` the name `name`, or takes its name away when `name` is
    /// blank. Returns the name it shows as now.
    pub fn rename(&self, program: &str, name: &str) -> anyhow::Result<String> {
        let name = name.trim();
        let mut names = self.names();
        if name.is_empty() || name == program {
            names.remove(program);
        } else {
            names.insert(program.to_string(), name.to_string());
        }
        self.ensure_dir()?;
        paths::write_private(
            &self.dir.join("names.toml"),
            toml::to_string(&names)?.as_bytes(),
        )?;
        Ok(names
            .get(program)
            .cloned()
            .unwrap_or_else(|| program.to_string()))
    }

    /// Deletes the whole history, names included.
    pub fn clear(&self) -> io::Result<()> {
        match fs::remove_dir_all(&self.dir) {
            Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(()),
            other => other,
        }
    }
}

/// A log not written to for this long belongs to a game that has quit. Much
/// longer than the live view's few seconds: a game that stops drawing for a
/// while, such as on a loading screen, still writes to the same log after.
pub const FINISHED_AFTER: Duration = Duration::from_secs(60);

/// A log that cannot be read into a run is deleted after this long.
const KEEP_UNREADABLE: Duration = Duration::from_secs(24 * 60 * 60);

/// A MangoHud frame log in `folder`, and when it was last written.
struct LogFile {
    path: PathBuf,
    modified: SystemTime,
}

fn logs(folder: &Path) -> Vec<LogFile> {
    let Ok(entries) = fs::read_dir(folder) else {
        return Vec::new();
    };
    entries
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            let name = path.file_name()?.to_str()?;
            if !name.ends_with(".csv") || name.ends_with("_summary.csv") {
                return None;
            }
            let modified = entry.metadata().ok()?.modified().ok()?;
            Some(LogFile { path, modified })
        })
        .collect()
}

fn unix(time: SystemTime) -> u64 {
    time.duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}

fn read_log(log: &LogFile) -> Option<Run> {
    // Lossy: the system description may hold any bytes.
    let text = String::from_utf8_lossy(&fs::read(&log.path).ok()?).into_owned();
    parse_log(&text, &game_name(&log.path), unix(log.modified))
}

/// Puts every finished log in `folder` into the history, then deletes it with
/// its summary. Logs that hold no frames go after a day. Returns how many runs
/// were added.
pub fn record_finished(folder: &Path, store: &Store, now: SystemTime) -> anyhow::Result<usize> {
    let mut added = 0;
    for log in logs(folder) {
        let idle = now.duration_since(log.modified).unwrap_or_default();
        if idle < FINISHED_AFTER {
            continue;
        }
        match read_log(&log) {
            Some(run) => {
                store.append(&run)?;
                added += 1;
            }
            None if idle < KEEP_UNREADABLE => continue,
            None => {}
        }
        let _ = fs::remove_file(&log.path);
        if let Some(stem) = log.path.file_stem().and_then(|s| s.to_str()) {
            let _ = fs::remove_file(log.path.with_file_name(format!("{stem}_summary.csv")));
        }
    }
    // Summaries whose log is gone.
    if let Ok(entries) = fs::read_dir(folder) {
        for entry in entries.flatten() {
            let path = entry.path();
            let Some(name) = path.file_name().and_then(|n| n.to_str()) else {
                continue;
            };
            let Some(log) = name.strip_suffix("_summary.csv") else {
                continue;
            };
            let old = entry
                .metadata()
                .and_then(|m| m.modified())
                .is_ok_and(|m| now.duration_since(m).unwrap_or_default() > FINISHED_AFTER);
            if old && !folder.join(format!("{log}.csv")).exists() {
                let _ = fs::remove_file(path);
            }
        }
    }
    Ok(added)
}

/// The games running now, each as a run up to its log's last row.
pub fn live_runs(folder: &Path, now: SystemTime) -> Vec<Run> {
    logs(folder)
        .iter()
        .filter(|log| now.duration_since(log.modified).unwrap_or_default() < FINISHED_AFTER)
        .filter_map(read_log)
        .collect()
}

/// The sessions overlapping `from` to `to`, oldest first, with the games
/// running now; `folder` is where MangoHud writes its logs.
pub fn history(
    store: &Store,
    folder: &Path,
    from: u64,
    to: u64,
    now: SystemTime,
) -> Vec<PlaySession> {
    let names = store.names();
    sessions_with_live(store, folder, from, to, now)
        .into_iter()
        .map(|(runs, live)| summary(&runs, names.get(&runs[0].program).map(String::as_str), live))
        .filter(|session| session.end >= from && session.start <= to)
        .collect()
}

/// One session by its id, with its minutes.
pub fn session(
    store: &Store,
    folder: &Path,
    id: &str,
    now: SystemTime,
) -> Option<PlaySessionDetail> {
    let (program, start) = parse_id(id)?;
    let names = store.names();
    let (runs, live) = sessions_with_live(store, folder, start, start, now)
        .into_iter()
        .find(|(runs, _)| runs[0].program == program && runs[0].start == start)?;
    Some(PlaySessionDetail {
        session: summary(&runs, names.get(program).map(String::as_str), live),
        minutes: minutes(&runs),
    })
}

/// Sessions around `from` to `to`, each with whether it is still being played.
fn sessions_with_live(
    store: &Store,
    folder: &Path,
    from: u64,
    to: u64,
    now: SystemTime,
) -> Vec<(Vec<Run>, bool)> {
    let live = live_runs(folder, now);
    let playing: Vec<(String, u64)> = live
        .iter()
        .map(|run| (run.program.clone(), run.start))
        .collect();
    let mut runs = store.runs(from, to);
    runs.extend(live);
    merge(runs)
        .into_iter()
        .map(|runs| {
            let live = runs
                .iter()
                .any(|run| playing.contains(&(run.program.clone(), run.start)));
            (runs, live)
        })
        .collect()
}

/// Reads a month's runs; a line that does not parse is skipped.
fn read_runs(path: &Path) -> Vec<Run> {
    let Ok(file) = fs::File::open(path) else {
        return Vec::new();
    };
    BufReader::new(file)
        .lines()
        .map_while(Result::ok)
        .filter_map(|line| serde_json::from_str(&line).ok())
        .collect()
}

/// The UTC year and month of a Unix time.
fn month_of(secs: u64) -> (i64, u32) {
    // Howard Hinnant's civil_from_days.
    let z = (secs / 86_400) as i64 + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month)
}

fn next_month((year, month): (i64, u32)) -> (i64, u32) {
    if month == 12 {
        (year + 1, 1)
    } else {
        (year, month + 1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The start of a real log from this PC: MangoHud's first rows hold the
    /// time since the game started, not a frame time.
    const HEAD: &str = "os,cpu,gpu,ram,kernel,driver,cpuscheduler\n\
Steam Runtime 4,Intel Core i5-9300H CPU @ 2.40GHz,NVIDIA GeForce GTX 1050,16214832,7.3.5-2-cachyos,,performance\n\
fps,frametime,cpu_load,cpu_power,gpu_load,cpu_temp,gpu_temp,gpu_core_clock,gpu_mem_clock,gpu_vram_used,gpu_power,ram_used,swap_used,process_rss,cpu_mhz,elapsed\n\
7.78941e-05,1.28379e+07,58.6485,0,1,75,0,0,0,0,0,5.0394,0.973743,0,4000,100537736\n\
7.78941e-05,1.28379e+07,58.6485,0,1,75,0,0,0,0,0,5.0394,0.973743,0,4000,200696384\n";

    fn row(frametime: f32, cpu: u32, gpu: u32, elapsed_ms: u64) -> String {
        format!(
            "{:.2},{frametime},55,0,54,{cpu},{gpu},1733,3504,1.7,0,9.3,1.4,0,4000,{}\n",
            1000.0 / frametime,
            elapsed_ms * 1_000_000
        )
    }

    /// A log of `secs` seconds, a row every tenth: 10 ms frames, with every
    /// 100th row at 20 ms.
    fn log(secs: u64) -> String {
        let mut text = String::from(HEAD);
        for i in 3..secs * 10 {
            let frametime = if i % 100 == 0 { 20.0 } else { 10.0 };
            text.push_str(&row(frametime, 80, 70, i * 100));
        }
        text
    }

    #[test]
    fn reads_a_log_into_a_run() {
        let run = parse_log(&log(150), "SB-Win64-Shipping", 10_000).unwrap();
        assert_eq!(run.program, "SB-Win64-Shipping");
        // The last row is at 149.9 s.
        assert_eq!((run.start, run.end), (9_850, 10_000));
        // The first two rows are not frames.
        assert_eq!(run.samples, 1497);
        assert!((run.fps() - 99.0).abs() < 1.0, "{}", run.fps());
        assert_eq!(run.low_1, Some(50.0));
        // Three minutes: 0–60 s, 60–120 s, 120–150 s.
        assert_eq!(
            run.minutes.iter().map(|m| m.at).collect::<Vec<_>>(),
            [9_850, 9_910, 9_970]
        );
        let first = &run.minutes[0];
        assert_eq!(first.cpu_temp, Some(80.0));
        assert_eq!(first.gpu_temp, Some(70.0));
        assert_eq!(first.low_1, Some(50.0));
    }

    #[test]
    fn a_log_without_frames_is_no_run() {
        assert_eq!(parse_log(HEAD, "x", 100), None);
        assert_eq!(parse_log("", "x", 100), None);
    }

    fn run(program: &str, start: u64, end: u64) -> Run {
        Run {
            program: program.into(),
            start,
            end,
            samples: (end - start) * 10,
            busy: (end - start) as f64 * 10.0 / 60.0,
            low_1: Some(40.0),
            low_0_1: None,
            minutes: vec![PlayMinute {
                at: start,
                fps: 60.0,
                low_1: None,
                cpu_temp: None,
                gpu_temp: None,
            }],
        }
    }

    #[test]
    fn merges_restarts_into_one_session() {
        // A launcher and many short runs a few seconds apart, as Stellar
        // Blade makes, then the game; another game plays in between.
        let mut runs: Vec<Run> = (0..29)
            .map(|i| run("SB-Win64-Shipping", 1_000 + i * 10, 1_005 + i * 10))
            .collect();
        runs.push(run("vkcube", 1_100, 1_150));
        runs.push(run("SB-Win64-Shipping", 1_400, 5_000));
        // Over five minutes later: a new session.
        runs.push(run("SB-Win64-Shipping", 5_400, 6_000));
        let sessions = merge(runs);
        let shape: Vec<(&str, usize)> = sessions
            .iter()
            .map(|s| (s[0].program.as_str(), s.len()))
            .collect();
        assert_eq!(
            shape,
            [
                ("SB-Win64-Shipping", 30),
                ("vkcube", 1),
                ("SB-Win64-Shipping", 1)
            ]
        );
        let first = summary(&sessions[0], Some("Stellar Blade"), false);
        assert_eq!(first.id, "SB-Win64-Shipping@1000");
        assert_eq!(first.game, "Stellar Blade");
        assert_eq!((first.start, first.end), (1_000, 5_000));
        assert_eq!(first.played_secs, 29 * 5 + 3_600);
        assert!((first.fps - 60.0).abs() < 0.01);
        assert_eq!(first.low_1, Some(40.0));
        assert_eq!(minutes(&sessions[0]).len(), 30);
        assert_eq!(parse_id(&first.id), Some(("SB-Win64-Shipping", 1_000)));
    }

    #[test]
    fn keeps_runs_by_month() {
        let dir = std::env::temp_dir().join(format!("yaedock-play-{}", std::process::id()));
        let store = Store::at(dir.clone());
        // 2026-10-31 23:30 and 2026-11-01 00:30 UTC.
        let october = run("vkcube", 1_793_489_400, 1_793_490_000);
        let november = run("vkcube", 1_793_493_000, 1_793_493_600);
        store.append(&october).unwrap();
        store.append(&november).unwrap();
        assert!(dir.join("runs-2026-10.jsonl").exists());
        assert!(dir.join("runs-2026-11.jsonl").exists());
        let runs = store.runs(1_793_493_000, 1_793_496_600);
        assert_eq!(runs, [october.clone(), november]);
        // Far from both: nothing.
        assert!(store.runs(1_800_000_000, 1_800_086_400).is_empty());

        assert_eq!(store.rename("vkcube", " Cube ").unwrap(), "Cube");
        assert_eq!(
            store.names().get("vkcube").map(String::as_str),
            Some("Cube")
        );
        assert_eq!(store.rename("vkcube", "").unwrap(), "vkcube");
        assert!(store.names().is_empty());

        store.clear().unwrap();
        assert!(!dir.exists());
        store.clear().unwrap();
    }

    #[test]
    fn records_finished_games_and_shows_the_live_one() {
        let base = std::env::temp_dir().join(format!("yaedock-record-{}", std::process::id()));
        let _ = fs::remove_dir_all(&base);
        let folder = base.join("mangohud");
        fs::create_dir_all(&folder).unwrap();
        let store = Store::at(base.join("play"));
        let now = UNIX_EPOCH + Duration::from_secs(1_800_000_000);
        let write = |name: &str, text: &str, ago: u64| {
            let path = folder.join(name);
            fs::write(&path, text).unwrap();
            let file = fs::File::options().write(true).open(&path).unwrap();
            file.set_modified(now - Duration::from_secs(ago)).unwrap();
        };
        // Finished ten minutes ago, with its summary.
        write("vkcube_2027-01-15_08-00-00.csv", &log(150), 600);
        write("vkcube_2027-01-15_08-00-00_summary.csv", "x", 600);
        // Running now.
        write("eldenring.exe_2027-01-15_08-20-00.csv", &log(90), 1);
        // Never drew a frame; kept for now in case it does.
        write("odd_2027-01-15_08-10-00.csv", HEAD, 600);

        assert_eq!(record_finished(&folder, &store, now).unwrap(), 1);
        let mut left: Vec<String> = fs::read_dir(&folder)
            .unwrap()
            .flatten()
            .map(|e| e.file_name().into_string().unwrap())
            .collect();
        left.sort();
        assert_eq!(
            left,
            [
                "eldenring.exe_2027-01-15_08-20-00.csv",
                "odd_2027-01-15_08-10-00.csv"
            ]
        );

        store.rename("vkcube", "Cube").unwrap();
        let sessions = history(&store, &folder, 1_799_990_000, 1_800_010_000, now);
        let shape: Vec<(&str, bool, u64)> = sessions
            .iter()
            .map(|s| (s.game.as_str(), s.live, s.played_secs))
            .collect();
        assert_eq!(shape, [("Cube", false, 150), ("eldenring.exe", true, 90)]);

        let detail = session(&store, &folder, &sessions[0].id, now).unwrap();
        assert_eq!(detail.session, sessions[0]);
        assert_eq!(detail.minutes.len(), 3);
        assert!(session(&store, &folder, "vkcube@1", now).is_none());
        fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn finds_the_utc_month() {
        assert_eq!(month_of(0), (1970, 1));
        assert_eq!(month_of(1_793_489_400), (2026, 10));
        assert_eq!(month_of(1_793_491_200), (2026, 11));
        assert_eq!(month_of(951_782_400), (2000, 2)); // 2000-02-29
        assert_eq!(next_month((2026, 12)), (2027, 1));
    }
}
