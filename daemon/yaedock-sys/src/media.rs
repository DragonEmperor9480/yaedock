//! Media players, through MPRIS on the session bus.

use std::collections::HashMap;
use std::io;
use std::sync::Mutex;
use std::time::{Duration, Instant};

use yaedock_proto::{Media, MediaAction};
use zbus::blocking::Connection;
use zbus::zvariant::{OwnedValue, Value};

use crate::ControlError;

/// Reads and controls media players.
pub trait MediaBackend: Send + Sync {
    /// The player to show: the one playing, or else the last one that played.
    /// `None` when no player is running.
    fn current(&self) -> Result<Option<Media>, ControlError>;

    /// Plays, pauses or skips on `player` (a `Media::player`), or on the
    /// current player when `None`.
    fn control(&self, player: Option<&str>, action: MediaAction) -> Result<(), ControlError>;

    /// Jumps to `position_ms` in what `player` (or the current player) plays.
    fn seek(&self, _player: Option<&str>, _position_ms: u64) -> Result<(), ControlError> {
        Err(io::Error::other("this media backend cannot seek").into())
    }

    /// Where the cover a `Media::art` id names can be read, for
    /// [crate::art::thumbnail]. The phone only ever sees the id.
    fn art_url(&self, _art: &str) -> Option<String> {
        None
    }
}

/// A short, stable id for a cover's URL: the same cover keeps its id, so the
/// phone is sent each one once.
pub fn art_id(url: &str) -> String {
    // FNV-1a: stable across runs and Rust versions, unlike the std hasher.
    let hash = url.bytes().fold(0xcbf2_9ce4_8422_2325_u64, |hash, byte| {
        (hash ^ u64::from(byte)).wrapping_mul(0x0100_0000_01b3)
    });
    format!("{hash:016x}")
}

/// How long a player gets to answer before it is skipped.
const CALL_TIMEOUT: Duration = Duration::from_secs(2);
/// How long a player that did not answer in time is left out, so a frozen
/// one costs [CALL_TIMEOUT] this seldom rather than on every read.
const STALLED_FOR: Duration = Duration::from_secs(30);

const PREFIX: &str = "org.mpris.MediaPlayer2.";
const PATH: &str = "/org/mpris/MediaPlayer2";
const ROOT: &str = "org.mpris.MediaPlayer2";
const PLAYER: &str = "org.mpris.MediaPlayer2.Player";

/// KDE's browser integration: a copy of a browser tab's player with the
/// page's title, artist and cover, where the browser's own often has less
/// (Brave shows "YouTube Music" and its logo).
const PLASMA_BROWSER: &str = "plasma-browser-integration";

/// MPRIS players, the standard interface of Linux media players and browsers.
pub struct MprisBackend {
    bus: Connection,
    /// The player last seen playing, shown while nothing plays.
    last: Mutex<Option<String>>,
    /// Cover URLs by `art_id`, for the covers of the players seen last.
    arts: Mutex<HashMap<String, String>>,
    /// Players that did not answer in time, and when.
    stalled: Mutex<HashMap<String, Instant>>,
}

impl MprisBackend {
    /// `None` without a session bus.
    pub fn new() -> Option<Self> {
        // Players are other people's programs: one that hangs must not hold up
        // the controller, and the bus itself never gives up on a call.
        let bus = zbus::blocking::connection::Builder::session()
            .ok()?
            .method_timeout(CALL_TIMEOUT)
            .build()
            .ok()?;
        Some(Self {
            bus,
            last: Mutex::new(None),
            arts: Mutex::new(HashMap::new()),
            stalled: Mutex::new(HashMap::new()),
        })
    }

    /// Player ids (bus names without the MPRIS prefix), in bus order,
    /// without the ones that only mirror another.
    fn players(&self) -> zbus::Result<Vec<String>> {
        let names = zbus::blocking::fdo::DBusProxy::new(&self.bus)?.list_names()?;
        let ids: Vec<String> = names
            .iter()
            .filter_map(|name| name.as_str().strip_prefix(PREFIX))
            // playerctld only mirrors other players.
            .filter(|id| *id != "playerctld")
            .map(str::to_string)
            .collect();
        let ids = self.without_stalled(ids);
        let browser = ids
            .iter()
            .any(|id| id == PLASMA_BROWSER)
            .then(|| self.plasma_browser_pid())
            .flatten();
        Ok(without_mirrored(ids, browser))
    }

    /// The browser that KDE's browser integration mirrors, by process id.
    fn plasma_browser_pid(&self) -> Option<u32> {
        let player = self.properties(PLASMA_BROWSER, PLAYER).ok()?;
        let metadata: HashMap<String, OwnedValue> =
            player.get("Metadata")?.try_clone().ok()?.try_into().ok()?;
        // Integers of any width, as the extension's versions differ.
        match &**metadata.get("kde:pid")? {
            Value::I32(pid) => u32::try_from(*pid).ok(),
            Value::U32(pid) => Some(*pid),
            Value::I64(pid) => u32::try_from(*pid).ok(),
            Value::U64(pid) => u32::try_from(*pid).ok(),
            _ => None,
        }
    }

    fn properties(&self, id: &str, interface: &str) -> zbus::Result<HashMap<String, OwnedValue>> {
        let reply = self
            .bus
            .call_method(
                Some(format!("{PREFIX}{id}").as_str()),
                PATH,
                Some("org.freedesktop.DBus.Properties"),
                "GetAll",
                &(interface,),
            )
            .inspect_err(|err| {
                if timed_out(err) {
                    tracing::info!(
                        player = id,
                        "a media player did not answer; leaving it out for a while"
                    );
                    self.stalled().insert(id.to_string(), Instant::now());
                }
            })?;
        reply.body().deserialize()
    }

    /// `ids` without the players that recently did not answer in time.
    fn without_stalled(&self, mut ids: Vec<String>) -> Vec<String> {
        let mut stalled = self.stalled();
        stalled.retain(|_, at| at.elapsed() < STALLED_FOR);
        ids.retain(|id| !stalled.contains_key(id));
        ids
    }

    fn stalled(&self) -> std::sync::MutexGuard<'_, HashMap<String, Instant>> {
        self.stalled.lock().unwrap_or_else(|err| err.into_inner())
    }

    fn read(&self, id: &str) -> zbus::Result<Media> {
        let player = self.properties(id, PLAYER)?;
        let root = self.properties(id, ROOT).unwrap_or_default();
        let flag = |name: &str| {
            player
                .get(name)
                .and_then(|v| bool::try_from(v).ok())
                .unwrap_or(false)
        };
        let metadata: HashMap<String, OwnedValue> = player
            .get("Metadata")
            .and_then(|v| v.try_clone().ok())
            .and_then(|v| v.try_into().ok())
            .unwrap_or_default();
        let text = |map: &HashMap<String, OwnedValue>, key: &str| {
            map.get(key)
                .and_then(|v| v.try_clone().ok())
                .and_then(|v| String::try_from(v).ok())
                .unwrap_or_default()
        };
        let artist = metadata
            .get("xesam:artist")
            .and_then(|v| v.try_clone().ok())
            .and_then(|v| Vec::<String>::try_from(v).ok())
            .unwrap_or_default()
            .join(", ");
        let app = Some(text(&root, "Identity"))
            .filter(|name| !name.is_empty())
            .unwrap_or_else(|| id.split('.').next().unwrap_or(id).to_string());
        let art = Some(text(&metadata, "mpris:artUrl"))
            .filter(|url| !url.is_empty())
            .map(|url| {
                let id = art_id(&url);
                let mut arts = self.arts.lock().unwrap_or_else(|err| err.into_inner());
                // A few players' covers at most; start over rather than grow.
                if arts.len() > 32 {
                    arts.clear();
                }
                arts.insert(id.clone(), url);
                id
            });
        // MPRIS counts in microseconds; players send them as either signed or unsigned.
        let micros = |value: Option<&OwnedValue>| {
            let value = value?.try_clone().ok()?;
            i64::try_from(value.try_clone().ok()?).ok().or_else(|| {
                u64::try_from(value)
                    .ok()
                    .and_then(|v| i64::try_from(v).ok())
            })
        };
        let length_ms = micros(metadata.get("mpris:length"))
            .filter(|&us| us > 0)
            .map(|us| us as u64 / 1000);
        let position_ms = micros(player.get("Position"))
            .filter(|&us| us >= 0)
            .map(|us| us as u64 / 1000)
            .map(|ms| length_ms.map_or(ms, |length| ms.min(length)));
        Ok(Media {
            player: id.to_string(),
            app,
            title: text(&metadata, "xesam:title"),
            artist,
            art,
            length_ms,
            position_ms,
            // Without a length there is nothing to seek along.
            can_seek: flag("CanSeek") && length_ms.is_some(),
            playing: text(&player, "PlaybackStatus") == "Playing",
            can_play_pause: flag("CanPlay") || flag("CanPause"),
            can_next: flag("CanGoNext"),
            can_previous: flag("CanGoPrevious"),
        })
    }

    fn last(&self) -> std::sync::MutexGuard<'_, Option<String>> {
        self.last.lock().unwrap_or_else(|err| err.into_inner())
    }
}

impl MediaBackend for MprisBackend {
    fn art_url(&self, art: &str) -> Option<String> {
        let arts = self.arts.lock().unwrap_or_else(|err| err.into_inner());
        arts.get(art).cloned()
    }

    fn current(&self) -> Result<Option<Media>, ControlError> {
        let players: Vec<Media> = self
            .players()
            .map_err(bus_error)?
            .iter()
            // A player can quit between listing and reading.
            .filter_map(|id| self.read(id).ok())
            .collect();
        let mut last = self.last();
        let chosen = choose(&players, last.as_deref()).cloned();
        if let Some(media) = chosen.as_ref().filter(|m| m.playing) {
            *last = Some(media.player.clone());
        }
        Ok(chosen)
    }

    fn control(&self, player: Option<&str>, action: MediaAction) -> Result<(), ControlError> {
        let id = match player {
            Some(id) => id.to_string(),
            None => {
                self.current()?
                    .ok_or_else(|| not_found("no media player is running".into()))?
                    .player
            }
        };
        if !self.players().map_err(bus_error)?.contains(&id) {
            return Err(not_found(format!("media player {id} is not running")));
        }
        let method = match action {
            MediaAction::PlayPause => "PlayPause",
            MediaAction::Next => "Next",
            MediaAction::Previous => "Previous",
        };
        self.bus
            .call_method(
                Some(format!("{PREFIX}{id}").as_str()),
                PATH,
                Some(PLAYER),
                method,
                &(),
            )
            .map_err(bus_error)?;
        Ok(())
    }

    fn seek(&self, player: Option<&str>, position_ms: u64) -> Result<(), ControlError> {
        let id = match player {
            Some(id) => id.to_string(),
            None => {
                self.current()?
                    .ok_or_else(|| not_found("no media player is running".into()))?
                    .player
            }
        };
        let props = self
            .properties(&id, PLAYER)
            .map_err(|_| not_found(format!("media player {id} is not running")))?;
        let target = i64::try_from(position_ms.saturating_mul(1000)).unwrap_or(i64::MAX);
        let bus_name = format!("{PREFIX}{id}");
        let metadata: HashMap<String, OwnedValue> = props
            .get("Metadata")
            .and_then(|v| v.try_clone().ok())
            .and_then(|v| v.try_into().ok())
            .unwrap_or_default();
        let track = metadata
            .get("mpris:trackid")
            .and_then(|v| v.try_clone().ok())
            .and_then(|v| zbus::zvariant::OwnedObjectPath::try_from(v).ok());
        match track {
            // The standard way: a position in this very track, so a seek
            // that arrives after the track changed does nothing.
            Some(track) => self.bus.call_method(
                Some(bus_name.as_str()),
                PATH,
                Some(PLAYER),
                "SetPosition",
                &(track, target),
            ),
            // Players without track ids only take a jump relative to now.
            None => {
                let now = props
                    .get("Position")
                    .and_then(|v| v.try_clone().ok())
                    .and_then(|v| i64::try_from(v).ok())
                    .unwrap_or(0);
                self.bus.call_method(
                    Some(bus_name.as_str()),
                    PATH,
                    Some(PLAYER),
                    "Seek",
                    &(target - now,),
                )
            }
        }
        .map_err(bus_error)?;
        Ok(())
    }
}

/// `ids` without the browser's own player (`brave.instance1234`) when KDE's
/// browser integration mirrors that browser (process `browser`), so the same
/// tab is not shown twice and the better copy wins.
fn without_mirrored(ids: Vec<String>, browser: Option<u32>) -> Vec<String> {
    let Some(pid) = browser else {
        return ids;
    };
    let suffix = format!(".instance{pid}");
    ids.into_iter()
        .filter(|id| !id.ends_with(&suffix))
        .collect()
}

/// The player playing (the last one seen playing, if several are), else the
/// last one that played, else one that is paused, else the first.
fn choose<'a>(players: &'a [Media], last: Option<&str>) -> Option<&'a Media> {
    let is_last = |m: &&Media| Some(m.player.as_str()) == last;
    players
        .iter()
        .filter(|m| m.playing)
        .find(is_last)
        .or_else(|| players.iter().find(|m| m.playing))
        .or_else(|| players.iter().find(is_last))
        .or_else(|| players.iter().find(|m| m.can_play_pause))
        .or_else(|| players.first())
}

fn not_found(message: String) -> ControlError {
    io::Error::new(io::ErrorKind::NotFound, message).into()
}

/// Whether a call failed because the player did not answer within [CALL_TIMEOUT].
fn timed_out(err: &zbus::Error) -> bool {
    matches!(err, zbus::Error::InputOutput(err) if err.kind() == io::ErrorKind::TimedOut)
}

fn bus_error(err: zbus::Error) -> ControlError {
    io::Error::other(err.to_string()).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn player(id: &str, playing: bool) -> Media {
        Media {
            player: id.into(),
            app: id.into(),
            playing,
            can_play_pause: true,
            ..Media::default()
        }
    }

    #[test]
    fn art_ids_are_short_and_stable() {
        let id = art_id("https://i.scdn.co/image/ab67616d0000b273");
        assert_eq!(id.len(), 16);
        assert_eq!(id, art_id("https://i.scdn.co/image/ab67616d0000b273"));
        assert_ne!(id, art_id("https://i.scdn.co/image/ab67616d0000b274"));
    }

    #[test]
    fn prefers_the_playing_player_then_the_last_one() {
        let players = [player("firefox", false), player("spotify", true)];
        assert_eq!(choose(&players, None).unwrap().player, "spotify");

        let paused = [player("firefox", false), player("spotify", false)];
        assert_eq!(choose(&paused, Some("spotify")).unwrap().player, "spotify");
        assert_eq!(choose(&paused, None).unwrap().player, "firefox");

        let both = [player("firefox", true), player("spotify", true)];
        assert_eq!(choose(&both, Some("spotify")).unwrap().player, "spotify");
        assert!(choose(&[], None).is_none());
    }

    #[test]
    fn hides_the_browser_that_plasma_mirrors() {
        let ids = || {
            vec![
                "brave.instance86452".to_string(),
                "plasma-browser-integration".to_string(),
                "firefox.instance123".to_string(),
                "spotify".to_string(),
            ]
        };
        assert_eq!(
            without_mirrored(ids(), Some(86452)),
            [
                "plasma-browser-integration",
                "firefox.instance123",
                "spotify"
            ]
        );
        assert_eq!(without_mirrored(ids(), None), ids());
        // Another process with a pid that only starts the same.
        assert_eq!(without_mirrored(ids(), Some(8645)).len(), 4);
    }

    #[test]
    fn knows_a_player_that_did_not_answer() {
        let late = io::Error::new(io::ErrorKind::TimedOut, "timed out");
        assert!(timed_out(&zbus::Error::from(late)));
        let gone = io::Error::new(io::ErrorKind::BrokenPipe, "gone");
        assert!(!timed_out(&zbus::Error::from(gone)));
        assert!(!timed_out(&zbus::Error::Failure("no".into())));
    }
}
