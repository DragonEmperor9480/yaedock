//! `yaedock --install` and `--uninstall`: put this one program where the user's
//! shell finds it, without root, and take it away again.

use std::env;
use std::fs;
use std::io::{self, BufRead, IsTerminal, Write};
use std::os::unix::fs::{PermissionsExt, symlink};
use std::path::{Path, PathBuf};

use anyhow::Context;
use yaedock_core::paths;

use crate::check::{self, Outcome};
use crate::service;

/// `~/.local/bin`, the usual home for programs a user installs for themselves.
fn bin_dir() -> anyhow::Result<PathBuf> {
    Ok(PathBuf::from(env::var_os("HOME").context("HOME is not set")?).join(".local/bin"))
}

fn on_path(dir: &Path) -> bool {
    env::var_os("PATH").is_some_and(|path| env::split_paths(&path).any(|p| p == dir))
}

fn ask(question: &str) -> bool {
    if !io::stdin().is_terminal() {
        return false;
    }
    print!("{question} [y/N] ");
    let _ = io::stdout().flush();
    let mut answer = String::new();
    io::stdin().lock().read_line(&mut answer).is_ok() && matches!(answer.trim(), "y" | "Y" | "yes")
}

/// Puts this program at `~/.local/bin/yaedock` (unless it already runs from
/// there) and links `yaedockd` to it. Returns the installed path, which is
/// what the background service should run: a downloaded copy may be moved or
/// deleted, and its folder name may contain spaces.
pub fn copy_to_bin() -> anyhow::Result<PathBuf> {
    let dir = bin_dir()?;
    fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
    let target = dir.join("yaedock");
    let source = env::current_exe().context("could not find where this program is")?;
    if fs::canonicalize(&source).ok() != fs::canonicalize(&target).ok() {
        // Copy beside the target and rename over it, so a running copy (such as
        // the daemon) keeps working until it restarts.
        let tmp = dir.join(".yaedock.new");
        fs::copy(&source, &tmp).with_context(|| format!("copying to {}", tmp.display()))?;
        fs::set_permissions(&tmp, fs::Permissions::from_mode(0o755))?;
        fs::rename(&tmp, &target)?;
    }
    let link = dir.join("yaedockd");
    if fs::symlink_metadata(&link).is_ok_and(|m| m.file_type().is_symlink()) {
        fs::remove_file(&link)?;
    }
    if !link.exists() {
        symlink("yaedock", &link)?;
    }
    Ok(target)
}

/// Copies this program to `~/.local/bin/yaedock`, links `yaedockd` to it, and
/// updates the background service if there is one. With `yes`, also sets up
/// the service and starts it at login. Returns whether the menu should open.
pub fn install(yes: bool) -> anyhow::Result<bool> {
    let dir = bin_dir()?;
    let updating = dir.join("yaedock").exists();
    let target = copy_to_bin()?;
    println!(
        "✓ {} yaedock {} in {}",
        if updating { "Updated" } else { "Installed" },
        env!("CARGO_PKG_VERSION"),
        dir.display()
    );

    // Point an existing service at the installed copy, or set one up if asked.
    let state = service::state().ok();
    let ours = service::user_unit_path().is_ok_and(|p| p.exists());
    if ours || yes {
        service::install_for(&target)?;
        if yes {
            service::control("enable")?;
        }
        // Restarting also starts a daemon that was stopped or had failed, for
        // example one whose earlier service file pointed somewhere wrong, and
        // replaces a running daemon with this version.
        let was_running = state.is_some_and(|s| s.running);
        service::control("restart")?;
        println!(
            "✓ {} the daemon with this version",
            if was_running { "Restarted" } else { "Started" }
        );
        if yes {
            println!("✓ It starts at login");
        }
    } else if state.is_some_and(|s| s.installed) {
        println!("  The background service comes from a package; left as it is.");
    }

    if !on_path(&dir) {
        println!();
        println!(
            "! {} is not on your PATH yet, so `yaedock` alone will not be found.",
            dir.display()
        );
        let shell = env::var("SHELL").unwrap_or_default();
        let (file, line) = if shell.ends_with("fish") {
            (
                "~/.config/fish/config.fish",
                "fish_add_path ~/.local/bin".to_string(),
            )
        } else if shell.ends_with("zsh") {
            (
                "~/.zshrc",
                "export PATH=\"$HOME/.local/bin:$PATH\"".to_string(),
            )
        } else {
            (
                "~/.bashrc",
                "export PATH=\"$HOME/.local/bin:$PATH\"".to_string(),
            )
        };
        println!("  Add this line to {file}, then open a new terminal:\n    {line}");
    }

    if let Some(firewall) = check::run()
        .into_iter()
        .find(|c| c.area == "Firewall" && c.outcome != Outcome::Ok)
        && let Some(fix) = firewall.fix
    {
        println!();
        println!("! {}. {fix}", firewall.summary);
    }

    let interactive = io::stdin().is_terminal() && io::stdout().is_terminal();
    if interactive && !yes {
        println!();
        println!("Opening YaeDock…");
        std::thread::sleep(std::time::Duration::from_millis(800));
    }
    Ok(interactive && !yes)
}

/// Stops and removes the service and the installed program. With `purge`, also
/// deletes the certificate and the list of paired phones.
pub fn uninstall(purge: bool) -> anyhow::Result<()> {
    if service::user_unit_path().is_ok_and(|p| p.exists()) {
        println!("✓ {}", service::uninstall()?);
    } else if service::state().is_ok_and(|s| s.installed) {
        println!(
            "  The background service comes from a package; remove it with your package manager."
        );
    }

    let dir = bin_dir()?;
    let mut removed = false;
    for name in ["yaedockd", "yaedock"] {
        let path = dir.join(name);
        if fs::symlink_metadata(&path).is_ok() {
            fs::remove_file(&path).with_context(|| format!("removing {}", path.display()))?;
            removed = true;
        }
    }
    if removed {
        println!("✓ Removed yaedock from {}", dir.display());
    } else {
        println!("  yaedock was not installed in {}", dir.display());
    }

    let config = paths::config_dir()?;
    // Looking the directory up creates it; an empty one is just tidied away.
    if fs::read_dir(&config).is_ok_and(|mut entries| entries.next().is_none()) {
        fs::remove_dir(&config)?;
        return Ok(());
    }
    if purge
        || ask(&format!(
            "Also delete paired phones and the certificate in {}?",
            config.display()
        ))
    {
        fs::remove_dir_all(&config).with_context(|| format!("removing {}", config.display()))?;
        println!("✓ Removed {}", config.display());
    } else {
        println!(
            "  Kept paired phones and the certificate in {}",
            config.display()
        );
        println!("  Installing again later reconnects your phones without pairing.");
    }
    Ok(())
}
