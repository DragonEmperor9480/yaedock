//! Running the daemon in the background as a systemd user service.

use std::env;
use std::fs;
use std::path::PathBuf;
use std::process::Command;

use anyhow::{Context, bail};

const UNIT: &str = "yaedockd.service";

/// The unit shipped in `packaging/`; installing locally only changes the path
/// to the program.
const TEMPLATE: &str = include_str!("../../../packaging/systemd/yaedockd.service");
const PACKAGED_EXEC: &str = "/usr/bin/yaedock";

/// What systemd knows about the daemon's service.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServiceState {
    /// systemd has a unit file for it (installed by `yaedock` or a package).
    pub installed: bool,
    pub running: bool,
    /// Starts at login.
    pub enabled: bool,
}

pub fn state() -> anyhow::Result<ServiceState> {
    let out = systemctl_output(&["show", UNIT, "-p", "LoadState,ActiveState,UnitFileState"])?;
    let field = |name: &str| {
        out.lines()
            .find_map(|line| line.strip_prefix(name)?.strip_prefix('='))
            .unwrap_or("")
            .to_string()
    };
    Ok(ServiceState {
        installed: field("LoadState") == "loaded",
        running: matches!(
            field("ActiveState").as_str(),
            "active" | "activating" | "reloading"
        ),
        enabled: field("UnitFileState") == "enabled",
    })
}

/// Where `install` writes the unit: the user's own systemd directory.
pub fn user_unit_path() -> anyhow::Result<PathBuf> {
    let config = match env::var_os("XDG_CONFIG_HOME").filter(|v| !v.is_empty()) {
        Some(dir) => PathBuf::from(dir),
        None => PathBuf::from(env::var_os("HOME").context("HOME is not set")?).join(".config"),
    };
    Ok(config.join("systemd/user").join(UNIT))
}

/// Installs this program in `~/.local/bin` and writes a user unit that runs
/// that copy as the daemon, unless a package already provides one. Returns a
/// description of what was done.
pub fn install() -> anyhow::Result<String> {
    install_for(&crate::install::copy_to_bin()?)
}

/// Like [`install`], for the program at `program` (such as an installed copy).
pub fn install_for(program: &std::path::Path) -> anyhow::Result<String> {
    let path = user_unit_path()?;
    let state = state()?;
    if state.installed && !path.exists() {
        return Ok("The service is already installed by a package; nothing to do.".into());
    }
    let daemon = program;
    let unit = TEMPLATE.replace(PACKAGED_EXEC, &exec_path(daemon));
    fs::create_dir_all(path.parent().context("unit path has no directory")?)?;
    fs::write(&path, unit).with_context(|| format!("writing {}", path.display()))?;
    systemctl(&["daemon-reload"])?;
    Ok(format!(
        "Installed {} (runs {}).",
        path.display(),
        daemon.display()
    ))
}

/// Stops the service and removes a unit that `install` wrote.
pub fn uninstall() -> anyhow::Result<String> {
    let path = user_unit_path()?;
    if !path.exists() {
        bail!("no service installed by yaedock at {}", path.display());
    }
    // Disabling also stops it, and fails harmlessly if it was not enabled.
    let _ = systemctl(&["disable", "--now", UNIT]);
    fs::remove_file(&path)?;
    systemctl(&["daemon-reload"])?;
    Ok(format!("Removed {}.", path.display()))
}

/// Runs `systemctl --user <verb> yaedockd.service`, installing the unit first
/// if needed.
pub fn control(verb: &str) -> anyhow::Result<()> {
    if verb != "stop" && verb != "disable" && !state()?.installed {
        install()?;
    }
    if matches!(verb, "start" | "restart" | "enable") {
        // After repeated failures systemd refuses to start the unit until its
        // failed state is cleared; a fresh start deserves a fresh count.
        let _ = systemctl(&["reset-failed", UNIT]);
    }
    match verb {
        // Enabling also starts it now, which is what people expect from "start at login".
        "enable" => systemctl(&["enable", "--now", UNIT]),
        _ => systemctl(&[verb, UNIT]),
    }
}

/// `program` written for an `ExecStart=` line: quoted, because systemd splits
/// the line at spaces, with `%` and `$` doubled, because systemd expands them.
fn exec_path(program: &std::path::Path) -> String {
    let escaped = program
        .to_string_lossy()
        .replace('\\', "\\\\")
        .replace('"', "\\\"")
        .replace('%', "%%")
        .replace('$', "$$");
    format!("\"{escaped}\"")
}

/// Follows the daemon's log until interrupted.
pub fn logs() -> anyhow::Result<()> {
    let status = Command::new("journalctl")
        .args(["--user", "-u", UNIT, "-f", "-n", "50"])
        .status()
        .context("running journalctl")?;
    if !status.success() {
        bail!("journalctl failed");
    }
    Ok(())
}

fn systemctl(args: &[&str]) -> anyhow::Result<()> {
    systemctl_output(args).map(|_| ())
}

fn systemctl_output(args: &[&str]) -> anyhow::Result<String> {
    let output = Command::new("systemctl")
        .arg("--user")
        .args(args)
        .output()
        .context("running systemctl; is this a systemd system?")?;
    if !output.status.success() {
        bail!(
            "systemctl --user {} failed: {}",
            args.join(" "),
            String::from_utf8_lossy(&output.stderr).trim()
        );
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

#[cfg(test)]
mod tests {
    use std::path::Path;

    use super::*;

    #[test]
    fn template_runs_the_packaged_binary() {
        assert!(TEMPLATE.contains(&format!("ExecStart={PACKAGED_EXEC} daemon")));
        let unit = TEMPLATE.replace(
            PACKAGED_EXEC,
            &exec_path(Path::new("/home/me/.local/bin/yaedock")),
        );
        assert!(unit.contains(r#"ExecStart="/home/me/.local/bin/yaedock" daemon"#));
    }

    #[test]
    fn quotes_paths_systemd_would_split_or_expand() {
        // A folder with a space broke the service on a real install.
        assert_eq!(
            exec_path(Path::new("/home/jinx/Downloads/Telegram Desktop/yaedock")),
            r#""/home/jinx/Downloads/Telegram Desktop/yaedock""#
        );
        assert_eq!(
            exec_path(Path::new("/home/a/100%/$x/y\"z")),
            r#""/home/a/100%%/$$x/y\"z""#
        );
    }
}
