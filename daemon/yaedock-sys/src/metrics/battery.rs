use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use yaedock_proto::{Battery, BatteryState, Stats};

use super::MetricSource;

const POWER_SUPPLY: &str = "/sys/class/power_supply";
/// Below this the power reading is noise, too small to estimate time from.
const MIN_WATTS: f64 = 0.5;

/// The laptop's batteries from `/sys/class/power_supply`, added together.
pub struct BatterySource {
    batteries: Vec<PathBuf>,
}

impl BatterySource {
    /// `None` when the PC has no battery.
    pub fn new() -> Option<Self> {
        Self::in_folder(Path::new(POWER_SUPPLY))
    }

    fn in_folder(folder: &Path) -> Option<Self> {
        let mut batteries: Vec<PathBuf> = fs::read_dir(folder)
            .ok()?
            .flatten()
            .map(|entry| entry.path())
            .filter(|path| is_system_battery(path))
            .collect();
        batteries.sort();
        (!batteries.is_empty()).then_some(Self { batteries })
    }
}

/// A battery that powers the PC, not one in a wireless mouse or headset
/// (those have `scope` set to `Device`).
fn is_system_battery(path: &Path) -> bool {
    let read = |name| fs::read_to_string(path.join(name)).unwrap_or_default();
    read("type").trim() == "Battery" && read("scope").trim() != "Device"
}

impl MetricSource for BatterySource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        let readings: Vec<Reading> = self
            .batteries
            .iter()
            .filter_map(|b| Reading::read(b))
            .collect();
        stats.battery = combine(&readings);
        Ok(())
    }
}

/// One battery, in watt-hours and watts.
#[derive(Debug, Clone, PartialEq)]
struct Reading {
    status: BatteryState,
    now_wh: f64,
    full_wh: f64,
    design_wh: Option<f64>,
    watts: Option<f64>,
}

impl Reading {
    fn read(dir: &Path) -> Option<Self> {
        let number = |name: &str| -> Option<f64> {
            fs::read_to_string(dir.join(name)).ok()?.trim().parse().ok()
        };
        // Batteries report energy (µWh, µW) or charge (µAh, µA); charge times
        // voltage (µV) gives energy.
        let volts = number("voltage_now").map(|uv| uv / 1e6);
        let wh = |energy: &str, charge: &str| {
            number(energy)
                .map(|uwh| uwh / 1e6)
                .or_else(|| Some(number(charge)? / 1e6 * volts?))
        };
        let watts = number("power_now")
            .map(|uw| uw / 1e6)
            .or_else(|| Some(number("current_now")?.abs() / 1e6 * volts?));
        let status = match fs::read_to_string(dir.join("status")).ok()?.trim() {
            "Charging" => BatteryState::Charging,
            "Discharging" => BatteryState::Discharging,
            "Full" => BatteryState::Full,
            "Not charging" => BatteryState::NotCharging,
            _ => BatteryState::Unknown,
        };
        match (
            wh("energy_now", "charge_now"),
            wh("energy_full", "charge_full"),
        ) {
            (Some(now_wh), Some(full_wh)) if full_wh > 0.0 => Some(Self {
                status,
                now_wh,
                full_wh,
                design_wh: wh("energy_full_design", "charge_full_design"),
                watts,
            }),
            // Some batteries report no amounts at all, only `capacity` in
            // percent; counted as a 100 Wh battery, it adds up the same.
            _ => Some(Self {
                status,
                now_wh: number("capacity")?,
                full_wh: 100.0,
                design_wh: None,
                watts: None,
            }),
        }
    }
}

/// Adds batteries together, as one bigger battery.
fn combine(readings: &[Reading]) -> Option<Battery> {
    if readings.is_empty() {
        return None;
    }
    let now: f64 = readings.iter().map(|r| r.now_wh).sum();
    let full: f64 = readings.iter().map(|r| r.full_wh).sum();
    let design: Option<f64> = readings.iter().map(|r| r.design_wh).sum();
    let watts: Option<f64> = readings.iter().map(|r| r.watts).sum();
    // Charging wins over discharging: one battery charging means power is plugged in.
    let state = [
        BatteryState::Charging,
        BatteryState::Discharging,
        BatteryState::NotCharging,
        BatteryState::Full,
    ]
    .into_iter()
    .find(|state| readings.iter().any(|r| r.status == *state))
    .unwrap_or(BatteryState::Unknown);

    let hours_left = watts.filter(|w| *w >= MIN_WATTS).and_then(|w| match state {
        BatteryState::Discharging => Some(now / w),
        BatteryState::Charging => Some((full - now).max(0.0) / w),
        _ => None,
    });
    Some(Battery {
        percent: (100.0 * now / full).clamp(0.0, 100.0) as f32,
        state,
        seconds_left: hours_left.map(|h| (h * 3600.0).round() as u64),
        power_w: watts.map(|w| w as f32),
        health: design
            .filter(|d| *d > 0.0)
            .map(|d| (100.0 * full / d).min(100.0) as f32),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn battery(dir: &Path, files: &[(&str, &str)]) {
        fs::create_dir_all(dir).unwrap();
        for (name, value) in files {
            fs::write(dir.join(name), format!("{value}\n")).unwrap();
        }
    }

    #[test]
    fn reads_energy_and_charge_batteries() {
        let root = std::env::temp_dir().join(format!("yaedock-battery-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        // The test laptop: energy in µWh, charging at 19 W.
        battery(
            &root.join("BAT0"),
            &[
                ("type", "Battery"),
                ("status", "Charging"),
                ("energy_now", "3730000"),
                ("energy_full", "28420000"),
                ("energy_full_design", "50527000"),
                ("power_now", "19225000"),
            ],
        );
        battery(&root.join("AC0"), &[("type", "Mains"), ("online", "1")]);
        // A mouse battery is left out.
        battery(
            &root.join("hidpp_battery_0"),
            &[
                ("type", "Battery"),
                ("scope", "Device"),
                ("status", "Discharging"),
            ],
        );

        let mut source = BatterySource::in_folder(&root).unwrap();
        assert_eq!(source.batteries.len(), 1);
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        let b = stats.battery.unwrap();
        assert_eq!(b.state, BatteryState::Charging);
        assert!((b.percent - 13.12).abs() < 0.01, "{}", b.percent);
        assert!((b.health.unwrap() - 56.25).abs() < 0.01);
        // (28.42 - 3.73) Wh at 19.225 W is 1.284 h.
        assert_eq!(b.seconds_left, Some(4623));

        // A battery that reports charge (µAh) and current (µA) instead.
        battery(
            &root.join("BAT0"),
            &[
                ("status", "Discharging"),
                ("charge_now", "2000000"),
                ("charge_full", "4000000"),
                ("charge_full_design", "5000000"),
                ("current_now", "1000000"),
                ("voltage_now", "12000000"),
            ],
        );
        for name in [
            "energy_now",
            "energy_full",
            "energy_full_design",
            "power_now",
        ] {
            fs::remove_file(root.join("BAT0").join(name)).unwrap();
        }
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        let b = stats.battery.unwrap();
        assert_eq!(b.percent, 50.0);
        assert_eq!(b.health, Some(80.0));
        assert_eq!(b.power_w, Some(12.0));
        // 24 Wh left at 12 W.
        assert_eq!(b.seconds_left, Some(7200));

        fs::remove_dir_all(&root).unwrap();
        assert!(BatterySource::in_folder(&root).is_none());
    }

    #[test]
    fn adds_batteries_together() {
        let reading = |status, now_wh, watts| Reading {
            status,
            now_wh,
            full_wh: 50.0,
            design_wh: Some(50.0),
            watts: Some(watts),
        };
        let both = combine(&[
            reading(BatteryState::Discharging, 25.0, 5.0),
            reading(BatteryState::Charging, 50.0, 0.0),
        ])
        .unwrap();
        assert_eq!(both.percent, 75.0);
        assert_eq!(both.state, BatteryState::Charging);
        assert_eq!(combine(&[]), None);
    }
}
