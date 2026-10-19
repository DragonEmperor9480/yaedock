//! Media players, through MPRIS on the session bus.

use std::collections::HashMap;
use std::io;
use std::sync::Mutex;

use yaedock_proto::{Media, MediaAction};
use zbus::blocking::Connection;
use zbus::zvariant::OwnedValue;

use crate::ControlError;

/// Reads and controls media players.
pub trait MediaBackend: Send + Sync {
    /// The player to show: the one playing, or else the last one that played.
    /// `None` when no player is running.
    fn current(&self) -> Result<Option<Media>, ControlError>;

    /// Plays, pauses or skips on `player` (a `Media::player`), or on the
    /// current player when `None`.
    fn control(&self, player: Option<&str>, action: MediaAction) -> Result<(), ControlError>;
}

const PREFIX: &str = "org.mpris.MediaPlayer2.";
const PATH: &str = "/org/mpris/MediaPlayer2";
const ROOT: &str = "org.mpris.MediaPlayer2";
const PLAYER: &str = "org.mpris.MediaPlayer2.Player";

/// MPRIS players, the standard interface of Linux media players and browsers.
pub struct MprisBackend {
    bus: Connection,
    /// The player last seen playing, shown while nothing plays.
    last: Mutex<Option<String>>,
}

impl MprisBackend {
    /// `None` without a session bus.
    pub fn new() -> Option<Self> {
        Some(Self {
            bus: Connection::session().ok()?,
            last: Mutex::new(None),
        })
    }

    /// Player ids (bus names without the MPRIS prefix), in bus order.
    fn players(&self) -> zbus::Result<Vec<String>> {
        let names = zbus::blocking::fdo::DBusProxy::new(&self.bus)?.list_names()?;
        Ok(names
            .iter()
            .filter_map(|name| name.as_str().strip_prefix(PREFIX))
            // playerctld only mirrors other players.
            .filter(|id| *id != "playerctld")
            .map(str::to_string)
            .collect())
    }

    fn properties(&self, id: &str, interface: &str) -> zbus::Result<HashMap<String, OwnedValue>> {
        let reply = self.bus.call_method(
            Some(format!("{PREFIX}{id}").as_str()),
            PATH,
            Some("org.freedesktop.DBus.Properties"),
            "GetAll",
            &(interface,),
        )?;
        reply.body().deserialize()
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
        Ok(Media {
            player: id.to_string(),
            app,
            title: text(&metadata, "xesam:title"),
            artist,
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
}
