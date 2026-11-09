//! Updates from GitHub Releases: finding the newest release, and replacing
//! this program with its `yaedock`, checked against the release's
//! `SHA256SUMS`. Used by `yaedock update` and by the daemon when a phone asks.

use std::fmt;
use std::fs::{self, File};
use std::io::{Read, Write};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Duration;

use anyhow::{Context, bail};
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};

/// Every release, newest first. Pre-releases count as updates too.
const RELEASES: &str =
    "https://api.github.com/repos/DragonEmperor9480/yaedock/releases?per_page=20";
/// The release's PC program.
pub const PROGRAM: &str = "yaedock";
/// `sha256sum` output for every file of a release.
const SUMS: &str = "SHA256SUMS";
/// Where to look instead of GitHub, for testing against a release served locally.
const URL_OVERRIDE: &str = "YAEDOCK_UPDATE_URL";
/// The program is about 15 MB; a slow line still gets there.
const DOWNLOAD_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const CHECK_TIMEOUT: Duration = Duration::from_secs(15);

/// A published release.
#[derive(Debug, Clone, PartialEq)]
pub struct Release {
    pub version: Version,
    /// The release notes, in Markdown.
    pub notes: String,
    /// The release's page on GitHub.
    pub page: String,
    pub assets: Vec<Asset>,
}

/// A file of a release.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Asset {
    pub name: String,
    #[serde(rename = "browser_download_url")]
    pub url: String,
    #[serde(default)]
    pub size: u64,
}

impl Release {
    fn asset(&self, name: &str) -> anyhow::Result<&Asset> {
        self.assets
            .iter()
            .find(|a| a.name == name)
            .with_context(|| format!("release {} has no {name}", self.version))
    }
}

/// This program's version.
pub fn current() -> Version {
    Version::parse(env!("CARGO_PKG_VERSION")).expect("the crate version is a version")
}

/// The newest release, or `None` when there is none yet.
pub fn newest() -> anyhow::Result<Option<Release>> {
    let url = std::env::var(URL_OVERRIDE).unwrap_or_else(|_| RELEASES.to_string());
    let json = agent(CHECK_TIMEOUT)
        .get(&url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", user_agent())
        .call()
        .context("could not reach GitHub to check for updates")?
        .into_body()
        .read_to_string()
        .context("could not read the list of releases")?;
    newest_of(&json)
}

/// The newest release in a GitHub list of releases. Drafts and tags that are
/// not versions are left out.
fn newest_of(json: &str) -> anyhow::Result<Option<Release>> {
    #[derive(Deserialize)]
    struct Listed {
        tag_name: String,
        #[serde(default)]
        draft: bool,
        #[serde(default)]
        body: Option<String>,
        #[serde(default)]
        html_url: String,
        #[serde(default)]
        assets: Vec<Asset>,
    }
    let listed: Vec<Listed> =
        serde_json::from_str(json).context("GitHub sent an unexpected list of releases")?;
    Ok(listed
        .into_iter()
        .filter(|r| !r.draft)
        .filter_map(|r| {
            let version =
                Version::parse(r.tag_name.strip_prefix('v').unwrap_or(&r.tag_name)).ok()?;
            Some(Release {
                version,
                notes: r.body.unwrap_or_default().trim().to_string(),
                page: r.html_url,
                assets: r.assets,
            })
        })
        .max_by(|a, b| a.version.cmp(&b.version)))
}

/// Why this copy of the program cannot update itself.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotUpdatable(pub String);

impl fmt::Display for NotUpdatable {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for NotUpdatable {}

/// The file to replace when updating `program`: the program itself, through
/// any symlink, when this user owns it and its folder. A copy owned by root,
/// as a package manager installs it, is left to the package manager: writing
/// there needs root, and the package manager would put its own version back.
pub fn target(program: &Path) -> Result<PathBuf, NotUpdatable> {
    if std::env::consts::OS != "linux" || std::env::consts::ARCH != "x86_64" {
        return Err(NotUpdatable(format!(
            "Releases only have a program for x86_64 Linux, not {}",
            std::env::consts::ARCH
        )));
    }
    // Once replaced, a running program's own path reads "… (deleted)".
    let program = program
        .to_str()
        .and_then(|p| p.strip_suffix(" (deleted)"))
        .map_or_else(|| program.to_path_buf(), PathBuf::from);
    let file = fs::canonicalize(&program)
        .map_err(|err| NotUpdatable(format!("Cannot find {}: {err}", program.display())))?;
    let folder = file.parent().unwrap_or(Path::new("/"));
    // SAFETY: geteuid has no preconditions and cannot fail.
    let me = unsafe { libc::geteuid() };
    for path in [file.as_path(), folder] {
        let owner = fs::metadata(path)
            .map_err(|err| NotUpdatable(format!("Cannot read {}: {err}", path.display())))?
            .uid();
        if owner != me {
            return Err(NotUpdatable(format!(
                "{} belongs to {}, probably a package manager; update YaeDock with it",
                path.display(),
                if owner == 0 {
                    "root".to_string()
                } else {
                    format!("user {owner}")
                }
            )));
        }
    }
    Ok(file)
}

/// How far an update has got.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stage {
    /// Downloading, with how much is done in percent when the size is known.
    Downloading(Option<u8>),
    Verifying,
    Installing,
}

/// Replaces `target` (from [`target`]) with `release`'s program. The new copy
/// is downloaded beside it, checked against `SHA256SUMS`, run once to be sure
/// it starts here, and only then moved over the old one, so a program that
/// is running keeps working until it restarts.
pub fn install(
    release: &Release,
    target: &Path,
    progress: &mut dyn FnMut(Stage),
) -> anyhow::Result<()> {
    let program = release.asset(PROGRAM)?;
    let sums = release.asset(SUMS)?;
    progress(Stage::Downloading(Some(0)));
    let sums = agent(CHECK_TIMEOUT)
        .get(&sums.url)
        .header("User-Agent", user_agent())
        .call()
        .context("could not download SHA256SUMS")?
        .into_body()
        .read_to_string()
        .context("could not read SHA256SUMS")?;
    let expected = checksum(&sums, PROGRAM).context("SHA256SUMS does not list the program")?;

    let folder = target.parent().context("the program has no folder")?;
    let new = folder.join(".yaedock.new");
    let result = download(program, &new, progress).and_then(|actual| {
        progress(Stage::Verifying);
        if actual != expected {
            bail!("the download does not match SHA256SUMS; nothing was changed");
        }
        fs::set_permissions(&new, fs::Permissions::from_mode(0o755))?;
        runs(&new, &release.version)?;
        progress(Stage::Installing);
        fs::rename(&new, target).with_context(|| format!("replacing {}", target.display()))
    });
    if result.is_err() {
        let _ = fs::remove_file(&new);
    }
    result
}

/// Downloads `asset` into `path`, returning its SHA-256 in lowercase hex.
fn download(asset: &Asset, path: &Path, progress: &mut dyn FnMut(Stage)) -> anyhow::Result<String> {
    let response = agent(DOWNLOAD_TIMEOUT)
        .get(&asset.url)
        .header("User-Agent", user_agent())
        .call()
        .with_context(|| format!("could not download {}", asset.name))?;
    let mut reader = response.into_body().into_reader();
    let mut file = File::create(path).with_context(|| format!("writing {}", path.display()))?;
    let mut hash = Sha256::new();
    let mut buffer = vec![0; 64 * 1024];
    let (mut done, mut shown) = (0u64, 0u8);
    loop {
        let read = reader.read(&mut buffer).context("the download broke off")?;
        if read == 0 {
            break;
        }
        hash.update(&buffer[..read]);
        file.write_all(&buffer[..read])?;
        done += read as u64;
        if let Some(percent) = (done * 100).checked_div(asset.size) {
            let percent = percent.min(100) as u8;
            if percent != shown {
                shown = percent;
                progress(Stage::Downloading(Some(percent)));
            }
        }
    }
    file.sync_all()?;
    Ok(hex(&hash.finalize()))
}

/// Checks that the program at `path` starts here and is `version`.
fn runs(path: &Path, version: &Version) -> anyhow::Result<()> {
    let output = Command::new(path)
        .arg("--version")
        .output()
        .context("the new version does not start on this PC; nothing was changed")?;
    let text = String::from_utf8_lossy(&output.stdout);
    if !output.status.success() || !text.contains(&version.to_string()) {
        bail!("the new version did not start properly on this PC; nothing was changed");
    }
    Ok(())
}

/// The SHA-256 that `sha256sum` output `sums` gives for `name`.
fn checksum(sums: &str, name: &str) -> Option<String> {
    sums.lines().find_map(|line| {
        let (hash, file) = line.split_once(char::is_whitespace)?;
        // `sha256sum` marks binary mode with a `*` before the name.
        let file = file.trim_start().trim_start_matches('*');
        (file == name && hash.len() == 64).then(|| hash.to_ascii_lowercase())
    })
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn agent(timeout: Duration) -> ureq::Agent {
    ureq::Agent::config_builder()
        .timeout_global(Some(timeout))
        .timeout_connect(Some(Duration::from_secs(10)))
        .build()
        .into()
}

fn user_agent() -> &'static str {
    concat!("YaeDock/", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIST: &str = r#"[
      {"tag_name":"v0.2.0","draft":true,"body":"","html_url":"d","assets":[]},
      {"tag_name":"v0.1.1-beta.1","draft":false,"prerelease":true,"body":" Beta \n","html_url":"b","assets":[
        {"name":"yaedock","browser_download_url":"https://x/yaedock","size":10}]},
      {"tag_name":"nightly","draft":false,"body":null,"html_url":"n","assets":[]},
      {"tag_name":"v0.1.0","draft":false,"body":"First","html_url":"a","assets":[]}
    ]"#;

    #[test]
    fn picks_the_newest_published_release() {
        let newest = newest_of(LIST).unwrap().unwrap();
        // The draft is newer but not published; a pre-release counts.
        assert_eq!(newest.version, Version::parse("0.1.1-beta.1").unwrap());
        assert_eq!(newest.notes, "Beta");
        assert_eq!(newest.page, "b");
        assert_eq!(newest.asset(PROGRAM).unwrap().size, 10);
        assert!(newest.asset(SUMS).is_err());
        assert_eq!(newest_of("[]").unwrap(), None);
        assert!(newest_of("{\"message\":\"rate limited\"}").is_err());
    }

    #[test]
    fn orders_versions_as_semver_does() {
        let v = |s| Version::parse(s).unwrap();
        // A pre-release comes before its release, and 0.10 after 0.9.
        assert!(v("0.1.1-beta.1") < v("0.1.1"));
        assert!(v("0.9.0") < v("0.10.0"));
    }

    #[test]
    fn reads_checksums_as_sha256sum_writes_them() {
        let a = "a".repeat(64);
        let b = "B".repeat(64);
        let sums = format!("{a}  yaedock-0.1.0-x86_64.apk\n{b} *yaedock\n");
        assert_eq!(checksum(&sums, "yaedock"), Some("b".repeat(64)));
        assert_eq!(checksum(&sums, "yaedock-0.1.0-x86_64.apk"), Some(a));
        assert_eq!(checksum(&sums, "other"), None);
        assert_eq!(checksum("short  yaedock", "yaedock"), None);
    }

    #[test]
    fn updates_only_a_copy_this_user_owns() {
        let dir = std::env::temp_dir().join(format!("yaedock-update-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let mine = dir.join("yaedock");
        fs::write(&mine, "").unwrap();
        let link = dir.join("yaedockd");
        let _ = fs::remove_file(&link);
        std::os::unix::fs::symlink("yaedock", &link).unwrap();
        // SAFETY: as in `target`.
        if unsafe { libc::geteuid() } != 0 {
            // Through the symlink, the file itself is replaced.
            assert_eq!(target(&link), Ok(fs::canonicalize(&mine).unwrap()));
            let deleted = PathBuf::from(format!("{} (deleted)", mine.display()));
            assert_eq!(target(&deleted), Ok(fs::canonicalize(&mine).unwrap()));
            let root = target(Path::new("/bin/sh")).unwrap_err();
            assert!(root.0.contains("root"), "{root}");
        }
        fs::remove_dir_all(&dir).unwrap();
    }
}
