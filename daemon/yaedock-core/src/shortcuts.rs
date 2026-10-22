//! Shortcut buttons: commands set up on the PC (`~/.config/yaedock/shortcuts.toml`)
//! that the phone can start by name. The phone never sees or sends a command,
//! only a shortcut's id, so it can run nothing the user did not set up here.

use std::fs;
use std::io;
use std::path::PathBuf;
use std::process::{Command, Stdio};

use anyhow::{Context, bail};
use serde::{Deserialize, Serialize};
use yaedock_proto::Shortcut;

use crate::paths;

/// One shortcut as stored.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Entry {
    pub name: String,
    /// Run with `sh -c`, so it can be a whole command line.
    pub command: String,
}

impl Entry {
    /// Derived from the name: `Open Steam` is `open-steam`.
    pub fn id(&self) -> String {
        id(&self.name)
    }
}

#[derive(Debug, Default, Serialize, Deserialize)]
struct File {
    #[serde(default, rename = "shortcut")]
    shortcuts: Vec<Entry>,
}

const HEADER: &str = "# YaeDock shortcut buttons, shown on the phone in this order.\n\
# Edit here or with `yaedock shortcuts add NAME COMMAND` and `yaedock shortcuts remove NAME`.\n\
# Each command runs with `sh -c` in your desktop session.\n\n";

pub fn path() -> anyhow::Result<PathBuf> {
    Ok(paths::config_dir()?.join("shortcuts.toml"))
}

/// The shortcuts, in order; none if the file does not exist yet.
pub fn load() -> anyhow::Result<Vec<Entry>> {
    let path = path()?;
    let text = match fs::read_to_string(&path) {
        Ok(text) => text,
        Err(err) if err.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(err) => return Err(err).with_context(|| format!("reading {}", path.display())),
    };
    let file: File =
        toml::from_str(&text).with_context(|| format!("{} is not valid", path.display()))?;
    Ok(file.shortcuts)
}

fn save(shortcuts: Vec<Entry>) -> anyhow::Result<()> {
    let path = path()?;
    let text = HEADER.to_string() + &toml::to_string(&File { shortcuts })?;
    paths::write_private(&path, text.as_bytes())
        .with_context(|| format!("writing {}", path.display()))
}

/// What the phone sees: ids and names, never commands.
pub fn for_phone(shortcuts: &[Entry]) -> Vec<Shortcut> {
    shortcuts
        .iter()
        .map(|entry| Shortcut {
            id: entry.id(),
            name: entry.name.clone(),
        })
        .collect()
}

/// Adds a shortcut at the end, or replaces the command of one with the same name.
pub fn add(name: &str, command: &str) -> anyhow::Result<()> {
    let name = name.trim();
    if id(name).is_empty() {
        bail!("give the shortcut a name with at least one letter or digit");
    }
    if command.trim().is_empty() {
        bail!("give the shortcut a command to run");
    }
    let mut shortcuts = load()?;
    let entry = Entry {
        name: name.to_string(),
        command: command.to_string(),
    };
    match shortcuts.iter_mut().find(|e| e.id() == entry.id()) {
        Some(existing) => *existing = entry,
        None => shortcuts.push(entry),
    }
    save(shortcuts)
}

/// Removes the shortcut with this name; false if there was none.
pub fn remove(name: &str) -> anyhow::Result<bool> {
    let mut shortcuts = load()?;
    let before = shortcuts.len();
    shortcuts.retain(|e| e.id() != id(name));
    let removed = shortcuts.len() != before;
    if removed {
        save(shortcuts)?;
    }
    Ok(removed)
}

/// Starts the shortcut with this id in the background, as a transient user
/// unit of its own: it gets the desktop session's environment and keeps
/// running if the daemon restarts. Returns its name.
pub fn run(id: &str) -> anyhow::Result<String> {
    let entry = load()?
        .into_iter()
        .find(|e| e.id() == id)
        .with_context(|| format!("no shortcut {id}; it may have been removed on the PC"))?;
    let output = Command::new("systemd-run")
        .args(["--user", "--collect", "--quiet"])
        .arg(format!("--description=YaeDock shortcut: {}", entry.name))
        .args(["--", "sh", "-c", &entry.command])
        .stdin(Stdio::null())
        .output()
        .context("starting systemd-run")?;
    if !output.status.success() {
        bail!(
            "could not start {}: {}",
            entry.name,
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(entry.name)
}

/// Lowercase letters and digits, with `-` between words.
fn id(name: &str) -> String {
    name.split(|c: char| !c.is_alphanumeric())
        .filter(|word| !word.is_empty())
        .map(str::to_lowercase)
        .collect::<Vec<_>>()
        .join("-")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn makes_ids_from_names() {
        assert_eq!(id("Steam"), "steam");
        assert_eq!(id("  Open my Project! "), "open-my-project");
        assert_eq!(id("Backup (NAS)"), "backup-nas");
        assert_eq!(id("!!"), "");
    }

    #[test]
    fn reads_and_writes_the_file() {
        let file = File {
            shortcuts: vec![Entry {
                name: "Steam".into(),
                command: "steam -silent".into(),
            }],
        };
        let text = toml::to_string(&file).unwrap();
        assert_eq!(
            text,
            "[[shortcut]]\nname = \"Steam\"\ncommand = \"steam -silent\"\n"
        );
        let back: File = toml::from_str(&(HEADER.to_string() + &text)).unwrap();
        assert_eq!(back.shortcuts, file.shortcuts);
        assert_eq!(
            for_phone(&back.shortcuts),
            vec![Shortcut {
                id: "steam".into(),
                name: "Steam".into()
            }]
        );
    }
}
