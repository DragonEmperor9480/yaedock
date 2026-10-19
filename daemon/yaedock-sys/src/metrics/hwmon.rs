use std::collections::{BTreeMap, HashMap};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use yaedock_proto::Stats;

use super::MetricSource;

/// Temperatures from `/sys/class/hwmon`.
///
/// Sensors are found once at startup and given stable names such as
/// `cpu_package` or `nvme`, which the phone sees in `welcome.sensors`.
pub struct HwmonSource {
    sensors: Vec<(String, PathBuf)>,
}

impl HwmonSource {
    pub fn new() -> io::Result<Self> {
        Self::discover(Path::new("/sys/class/hwmon"))
    }

    fn discover(root: &Path) -> io::Result<Self> {
        // (chip, label, device path, input path). hwmonN numbering can change between
        // boots, so duplicates are ordered by their device path to keep suffixes stable.
        let mut found = Vec::new();
        for chip_dir in fs::read_dir(root)? {
            let chip_dir = chip_dir?.path();
            let Ok(chip) = fs::read_to_string(chip_dir.join("name")) else {
                continue;
            };
            let device = fs::canonicalize(chip_dir.join("device")).unwrap_or(chip_dir.clone());
            let Ok(entries) = fs::read_dir(&chip_dir) else {
                continue;
            };
            for entry in entries.flatten() {
                let file = entry.file_name().to_string_lossy().into_owned();
                let Some(prefix) = file
                    .strip_suffix("_input")
                    .filter(|p| p.starts_with("temp"))
                else {
                    continue;
                };
                let label = fs::read_to_string(chip_dir.join(format!("{prefix}_label")))
                    .unwrap_or_default();
                let (chip, label) = (chip.trim().to_string(), label.trim().to_string());
                found.push((chip, label, device.clone(), entry.path()));
            }
        }
        found.sort();

        let mut seen: HashMap<String, u32> = HashMap::new();
        let sensors = found
            .into_iter()
            .map(|(chip, label, _, path)| {
                let base = sensor_name(&chip, &label);
                let count = seen.entry(base.clone()).or_default();
                *count += 1;
                let name = if *count == 1 {
                    base
                } else {
                    format!("{base}_{count}")
                };
                (name, path)
            })
            .collect();
        Ok(Self { sensors })
    }

    /// Sensor names, as used for the keys of `Stats::temps`.
    pub fn sensor_names(&self) -> Vec<String> {
        self.sensors.iter().map(|(name, _)| name.clone()).collect()
    }
}

impl MetricSource for HwmonSource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        let mut temps = BTreeMap::new();
        for (name, path) in &self.sensors {
            // A sensor can fail to read (for example a suspended GPU); skip it for this tick.
            let Ok(text) = fs::read_to_string(path) else {
                continue;
            };
            if let Ok(millidegrees) = text.trim().parse::<i64>() {
                temps.insert(name.clone(), millidegrees as f32 / 1000.0);
            }
        }
        stats.temps = Some(temps);
        Ok(())
    }
}

/// Picks a short, stable name for a sensor from its chip name and label.
fn sensor_name(chip: &str, label: &str) -> String {
    match (chip, label) {
        ("coretemp", l) if l.starts_with("Package id") => return "cpu_package".into(),
        ("k10temp", "Tctl" | "Tdie") => return "cpu_package".into(),
        ("nvme", "Composite" | "") => return "nvme".into(),
        ("amdgpu", "edge") => return "gpu".into(),
        _ => {}
    }
    let name = if label.is_empty() {
        chip.to_string()
    } else {
        format!("{chip}_{label}")
    };
    name.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_lowercase()
            } else {
                '_'
            }
        })
        .collect::<String>()
        .split('_')
        .filter(|part| !part.is_empty())
        .collect::<Vec<_>>()
        .join("_")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn names_known_and_generic_sensors() {
        assert_eq!(sensor_name("coretemp", "Package id 0"), "cpu_package");
        assert_eq!(sensor_name("k10temp", "Tctl"), "cpu_package");
        assert_eq!(sensor_name("nvme", "Composite"), "nvme");
        assert_eq!(sensor_name("coretemp", "Core 3"), "coretemp_core_3");
        assert_eq!(sensor_name("acpitz", ""), "acpitz");
        assert_eq!(sensor_name("iwlwifi_1", ""), "iwlwifi_1");
    }

    #[test]
    fn discovers_and_reads_sensors() {
        let root = std::env::temp_dir().join(format!("yaedock-hwmon-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        for (dir, chip) in [("hwmon0", "nvme"), ("hwmon1", "nvme"), ("hwmon2", "AC0")] {
            fs::create_dir_all(root.join(dir)).unwrap();
            fs::write(root.join(dir).join("name"), format!("{chip}\n")).unwrap();
        }
        fs::write(root.join("hwmon0/temp1_input"), "28850\n").unwrap();
        fs::write(root.join("hwmon0/temp1_label"), "Composite\n").unwrap();
        fs::write(root.join("hwmon1/temp1_input"), "41000\n").unwrap();

        let mut source = HwmonSource::discover(&root).unwrap();
        assert_eq!(source.sensor_names(), ["nvme", "nvme_2"]);
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        let temps = stats.temps.unwrap();
        assert_eq!(temps.len(), 2);
        assert!(temps.values().any(|&t| t == 28.85));

        fs::remove_dir_all(&root).unwrap();
    }
}
