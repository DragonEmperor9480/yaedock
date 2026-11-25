//! How much room is on each drive the PC has mounted.

use std::collections::BTreeMap;
use std::ffi::CString;
use std::io;
use std::os::unix::ffi::OsStrExt;
use std::path::Path;
use std::sync::{Arc, Mutex, Weak};
use std::time::Duration;

use yaedock_proto::{Drive, Stats};

use super::MetricSource;

/// How often the drives are looked at again. Their space changes slowly, and
/// a drive plugged in shows up within this.
const EVERY: Duration = Duration::from_secs(5);

/// Filesystems that are only ever read: disc images, snaps and the like.
const READ_ONLY_KINDS: [&str; 5] = ["squashfs", "iso9660", "udf", "erofs", "cramfs"];

/// The drives, read on a thread of their own: asking a drive that has gone
/// away, or a slow USB stick, how full it is can take a long time, and the
/// rest of the stats must not wait for it.
pub struct DriveSource {
    drives: Arc<Mutex<Option<Vec<Drive>>>>,
}

impl DriveSource {
    pub fn new() -> io::Result<Self> {
        let drives = Arc::new(Mutex::new(None));
        let weak = Arc::downgrade(&drives);
        std::thread::Builder::new()
            .name("drives".into())
            .spawn(move || watch(weak))?;
        Ok(Self { drives })
    }
}

/// Reads the drives every [`EVERY`] until the source is dropped.
fn watch(drives: Weak<Mutex<Option<Vec<Drive>>>>) {
    loop {
        let found = std::fs::read_to_string("/proc/self/mountinfo")
            .ok()
            .map(|mountinfo| {
                mounted(&mountinfo)
                    .into_iter()
                    .filter_map(|mount| space(&mount))
                    .collect::<Vec<_>>()
            })
            .filter(|found| !found.is_empty());
        let Some(drives) = drives.upgrade() else {
            return;
        };
        *drives.lock().unwrap_or_else(|e| e.into_inner()) = found;
        drop(drives);
        std::thread::sleep(EVERY);
    }
}

impl MetricSource for DriveSource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        stats.drives = self
            .drives
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        Ok(())
    }
}

/// A drive's mount, before its space is known.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Mount {
    name: String,
    path: String,
}

/// The drives in `mountinfo` (`/proc/self/mountinfo`), each once: the drive
/// the PC runs from first, then the others by where they are mounted.
fn mounted(mountinfo: &str) -> Vec<Mount> {
    // By device number, so one filesystem mounted in several places (Btrfs
    // subvolumes, bind mounts) counts once, under its shortest path.
    let mut by_device: BTreeMap<&str, String> = BTreeMap::new();
    for line in mountinfo.lines() {
        let Some((before, after)) = line.split_once(" - ") else {
            continue;
        };
        let fields: Vec<&str> = before.split(' ').collect();
        let rest: Vec<&str> = after.split(' ').collect();
        let (Some(device), Some(path), Some(options), Some(kind), Some(source)) = (
            fields.get(2),
            fields.get(4),
            fields.get(5),
            rest.first(),
            rest.get(1),
        ) else {
            continue;
        };
        let path = unescape(path);
        let read_only = options.split(',').any(|option| option == "ro");
        if !source.starts_with("/dev/")
            || read_only
            || READ_ONLY_KINDS.contains(kind)
            || is_boot(&path)
        {
            continue;
        }
        let shorter = by_device
            .get(device)
            .is_none_or(|known| path.len() < known.len());
        if shorter {
            by_device.insert(device, path);
        }
    }
    let mut mounts: Vec<Mount> = by_device
        .into_values()
        .map(|path| Mount {
            name: name_of(&path),
            path,
        })
        .collect();
    mounts.sort_by_key(|mount| (mount.path != "/", mount.path.to_lowercase()));
    mounts
}

/// The small partitions the PC starts from, which nobody fills.
fn is_boot(path: &str) -> bool {
    ["/boot", "/efi"]
        .iter()
        .any(|boot| path == *boot || path.starts_with(&format!("{boot}/")))
}

/// What to call the drive mounted at `path`.
fn name_of(path: &str) -> String {
    match path {
        "/" => "System".into(),
        "/home" => "Home".into(),
        _ => path.rsplit('/').next().unwrap_or(path).to_string(),
    }
}

/// Undoes mountinfo's escapes: a space is `\040`, a tab `\011`, and so on.
fn unescape(field: &str) -> String {
    let bytes = field.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let octal = bytes
            .get(i + 1..i + 4)
            .filter(|digits| bytes[i] == b'\\' && digits.iter().all(|d| (b'0'..=b'7').contains(d)));
        match octal.and_then(|digits| u8::from_str_radix(std::str::from_utf8(digits).ok()?, 8).ok())
        {
            Some(byte) => {
                out.push(byte);
                i += 4;
            }
            None => {
                out.push(bytes[i]);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// How big the drive at `mount` is and how much of it is free.
fn space(mount: &Mount) -> Option<Drive> {
    let path = CString::new(Path::new(&mount.path).as_os_str().as_bytes()).ok()?;
    // SAFETY: statvfs fills the struct it is given, from a valid C string.
    let mut info: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(path.as_ptr(), &mut info) } != 0 {
        return None;
    }
    let block = info.f_frsize as u64;
    let total = info.f_blocks as u64 * block;
    (total > 0).then(|| Drive {
        name: mount.name.clone(),
        mount: mount.path.clone(),
        total,
        free: info.f_bavail as u64 * block,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// This test PC's mounts, cut down: one Btrfs drive with subvolumes, a
    /// boot partition, two USB drives and things that are not drives.
    const MOUNTINFO: &str = "\
22 1 0:36 /@ / rw,noatime shared:1 - btrfs /dev/nvme0n1p2 rw,ssd
23 22 0:23 / /proc rw,nosuid shared:5 - proc proc rw
25 22 0:28 / /dev/shm rw,nosuid shared:3 - tmpfs tmpfs rw
40 22 0:36 /@home /home rw,noatime shared:30 - btrfs /dev/nvme0n1p2 rw,ssd
41 22 0:36 /@log /var/log rw,noatime shared:31 - btrfs /dev/nvme0n1p2 rw,ssd
44 22 259:1 / /boot rw,relatime shared:34 - vfat /dev/nvme0n1p1 rw
90 22 8:17 / /run/media/me/backup_hdd rw,nosuid shared:60 - exfat /dev/sdb1 rw
91 22 8:1 / /run/media/me/My\\040Files rw,nosuid shared:61 - ext4 /dev/sda1 rw
92 22 7:3 / /var/lib/snapd/snap/core/1 ro,nodev shared:62 - squashfs /dev/loop3 ro
93 22 8:49 / /run/media/me/DVD ro,nosuid shared:63 - iso9660 /dev/sr0 ro
94 22 0:50 / /mnt/nas rw shared:64 - cifs //nas/share rw
";

    #[test]
    fn lists_each_drive_once_with_the_system_first() {
        let mounts = mounted(MOUNTINFO);
        let paths: Vec<&str> = mounts.iter().map(|m| m.path.as_str()).collect();
        assert_eq!(
            paths,
            ["/", "/run/media/me/backup_hdd", "/run/media/me/My Files"]
        );
        let names: Vec<&str> = mounts.iter().map(|m| m.name.as_str()).collect();
        assert_eq!(names, ["System", "backup_hdd", "My Files"]);
    }

    #[test]
    fn a_separate_home_drive_is_called_home() {
        assert_eq!(name_of("/home"), "Home");
        assert_eq!(name_of("/mnt/games"), "games");
    }

    #[test]
    fn reads_escaped_mount_points() {
        assert_eq!(
            unescape("/run/media/me/My\\040Files"),
            "/run/media/me/My Files"
        );
        assert_eq!(unescape("/a\\011b\\134c"), "/a\tb\\c");
        assert_eq!(unescape("/plain\\x"), "/plain\\x");
    }

    #[test]
    fn measures_this_pcs_root() {
        let root = space(&Mount {
            name: "System".into(),
            path: "/".into(),
        })
        .unwrap();
        assert!(root.total > 0 && root.free <= root.total, "{root:?}");
    }
}
