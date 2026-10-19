use std::collections::HashSet;
use std::fs;
use std::io;
use std::time::Instant;

use yaedock_proto::{Disk, Stats};

use super::{MetricSource, Rate};

/// `/proc/diskstats` always counts in 512-byte sectors.
const SECTOR_SIZE: u64 = 512;

/// Disk throughput summed over whole physical disks.
///
/// Partitions and virtual devices (zram, loop, device-mapper) are skipped so
/// the same I/O is not counted twice.
pub struct DiskSource {
    disks: HashSet<String>,
    rate: Rate,
}

impl DiskSource {
    pub fn new() -> io::Result<Self> {
        let mut disks = HashSet::new();
        for entry in fs::read_dir("/sys/block")? {
            let entry = entry?;
            let target = fs::canonicalize(entry.path())?;
            if !target.starts_with("/sys/devices/virtual") {
                disks.insert(entry.file_name().to_string_lossy().into_owned());
            }
        }
        Ok(Self {
            disks,
            rate: Rate::default(),
        })
    }
}

impl MetricSource for DiskSource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        let text = fs::read_to_string("/proc/diskstats")?;
        let (read, write) = sum_sectors(&text, &self.disks);
        let (read, write) =
            self.rate
                .update(Instant::now(), read * SECTOR_SIZE, write * SECTOR_SIZE);
        stats.disk = Some(Disk { read, write });
        Ok(())
    }
}

/// Sums sectors read and written over the named devices.
fn sum_sectors(diskstats: &str, disks: &HashSet<String>) -> (u64, u64) {
    let mut total = (0, 0);
    for line in diskstats.lines() {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 10 || !disks.contains(fields[2]) {
            continue;
        }
        total.0 += fields[5].parse::<u64>().unwrap_or(0);
        total.1 += fields[9].parse::<u64>().unwrap_or(0);
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sums_only_listed_disks() {
        let text = "\
   8       0 sda 37634 1 37634 100 24 0 24 10 0 0 0 0
   8       1 sda1 34578 1 34578 100 24 0 24 10 0 0 0 0
 259       0 nvme0n1 100 0 6147483 100 200 0 8345662 10 0 0 0 0
 252       0 zram0 4416 0 4416 1 8 0 8 0 0 0 0 0";
        let disks = HashSet::from(["sda".to_string(), "nvme0n1".to_string()]);
        assert_eq!(sum_sectors(text, &disks), (37634 + 6147483, 24 + 8345662));
    }
}
