use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::time::Instant;

use nvml_wrapper::Nvml;
use nvml_wrapper::enum_wrappers::device::{Clock, TemperatureSensor};
use tracing::debug;
use yaedock_proto::{GpuInfo, GpuStats, Stats};

use super::MetricSource;

/// Graphics cards, each read the way its driver allows:
///
/// - Intel (`i915`, `xe`): usage from the idle-time counter, and the clock.
/// - AMD (`amdgpu`): usage, video memory, temperature and clock from sysfs.
/// - NVIDIA (proprietary driver): usage, temperature, video memory and clock
///   through NVML, loaded at run time so other systems need nothing extra.
///
/// A GPU that is powered down (runtime suspended) is reported as sleeping and
/// not touched, so it is never woken up just to be read.
pub struct GpuSource {
    gpus: Vec<Gpu>,
    /// Kept open between readings only while no NVIDIA GPU can power down;
    /// an open NVML session would keep such a GPU awake.
    keep_nvml: bool,
    nvml: Option<Nvml>,
}

struct Gpu {
    info: GpuInfo,
    /// `/sys/bus/pci/devices/<address>`.
    device: PathBuf,
    pci: String,
    kind: Kind,
}

enum Kind {
    Intel {
        /// Milliseconds the GPU has spent idle, ever growing.
        idle: Option<PathBuf>,
        clock: Option<PathBuf>,
        last: Option<(Instant, u64)>,
    },
    Amd,
    Nvidia {
        /// Whether the GPU can power down when idle (RTD3).
        can_sleep: bool,
    },
    Other,
}

impl GpuSource {
    pub fn new() -> Self {
        Self::discover(Path::new("/sys/class/drm"), &pci_names())
    }

    fn discover(drm: &Path, names: &str) -> Self {
        let mut gpus: Vec<Gpu> = Vec::new();
        let Ok(entries) = fs::read_dir(drm) else {
            return Self {
                gpus,
                keep_nvml: false,
                nvml: None,
            };
        };
        let mut cards: Vec<PathBuf> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .and_then(|n| n.strip_prefix("card"))
                    .is_some_and(|n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()))
            })
            .collect();
        cards.sort();
        for card in cards {
            let Ok(device) = fs::canonicalize(card.join("device")) else {
                continue;
            };
            let pci = device
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            if gpus.iter().any(|g| g.pci == pci) {
                continue;
            }
            let read_id = |file: &str| {
                fs::read_to_string(device.join(file))
                    .ok()
                    .and_then(|s| u16::from_str_radix(s.trim().trim_start_matches("0x"), 16).ok())
            };
            let (Some(vendor), Some(model)) = (read_id("vendor"), read_id("device")) else {
                continue;
            };
            let driver = fs::read_link(device.join("driver"))
                .ok()
                .and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()))
                .unwrap_or_default();
            let kind = match driver.as_str() {
                "i915" | "xe" => Kind::Intel {
                    idle: first_existing(&[
                        card.join("gt/gt0/rc6_residency_ms"),
                        card.join("power/rc6_residency_ms"),
                        device.join("tile0/gt0/gtidle/idle_residency_ms"),
                    ]),
                    clock: first_existing(&[
                        card.join("gt/gt0/rps_act_freq_mhz"),
                        card.join("gt_act_freq_mhz"),
                        device.join("tile0/gt0/freq0/act_freq"),
                    ]),
                    last: None,
                },
                "amdgpu" => Kind::Amd,
                "nvidia" => Kind::Nvidia {
                    can_sleep: nvidia_can_sleep(&pci),
                },
                _ => Kind::Other,
            };
            gpus.push(Gpu {
                info: GpuInfo {
                    id: format!("pci:{pci}"),
                    name: gpu_name(names, vendor, model),
                },
                device,
                pci,
                kind,
            });
        }
        let keep_nvml = gpus.iter().any(|g| matches!(g.kind, Kind::Nvidia { .. }))
            && !gpus
                .iter()
                .any(|g| matches!(g.kind, Kind::Nvidia { can_sleep: true }));
        // NVML is opened on the first reading, once a phone is watching.
        Self {
            gpus,
            keep_nvml,
            nvml: None,
        }
    }

    /// The GPUs found, for the welcome message.
    pub fn gpus(&self) -> Vec<GpuInfo> {
        self.gpus.iter().map(|g| g.info.clone()).collect()
    }
}

impl Default for GpuSource {
    fn default() -> Self {
        Self::new()
    }
}

impl MetricSource for GpuSource {
    fn only_when_watched(&self) -> bool {
        true
    }

    /// Closes NVML and forgets the Intel idle counters while no phone is
    /// connected, so the GPUs are left completely alone.
    fn pause(&mut self) {
        self.nvml = None;
        for gpu in &mut self.gpus {
            if let Kind::Intel { last, .. } = &mut gpu.kind {
                *last = None;
            }
        }
    }

    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        if self.gpus.is_empty() {
            return Ok(());
        }
        let now = Instant::now();
        let mut readings = Vec::with_capacity(self.gpus.len());
        if self.keep_nvml && self.nvml.is_none() {
            self.nvml = open_nvml();
        }
        // Opened for this reading only, when an NVIDIA GPU may be asleep.
        let mut nvml_once = None;
        for gpu in &mut self.gpus {
            let mut reading = GpuStats {
                id: gpu.info.id.clone(),
                ..GpuStats::default()
            };
            if fs::read_to_string(gpu.device.join("power/runtime_status"))
                .is_ok_and(|s| s.trim() == "suspended")
            {
                reading.sleeping = true;
                if let Kind::Intel { last, .. } = &mut gpu.kind {
                    *last = None;
                }
                readings.push(reading);
                continue;
            }
            match &mut gpu.kind {
                Kind::Intel { idle, clock, last } => {
                    if let Some(idle_ms) = idle.as_deref().and_then(read_u64) {
                        if let Some((then, last_idle)) = *last {
                            let elapsed = now.duration_since(then).as_secs_f64() * 1000.0;
                            if elapsed > 0.0 {
                                let idle_share = idle_ms.saturating_sub(last_idle) as f64 / elapsed;
                                reading.usage =
                                    Some(((1.0 - idle_share) * 100.0).clamp(0.0, 100.0) as f32);
                            }
                        }
                        *last = Some((now, idle_ms));
                    }
                    reading.clock_mhz = clock.as_deref().and_then(read_u64).map(|mhz| mhz as u32);
                }
                Kind::Amd => {
                    reading.usage =
                        read_u64(&gpu.device.join("gpu_busy_percent")).map(|p| p as f32);
                    reading.vram_used = read_u64(&gpu.device.join("mem_info_vram_used"));
                    reading.vram_total = read_u64(&gpu.device.join("mem_info_vram_total"));
                    if let Some(hwmon) = hwmon_dir(&gpu.device) {
                        reading.temp =
                            read_u64(&hwmon.join("temp1_input")).map(|mc| mc as f32 / 1000.0);
                        reading.clock_mhz =
                            read_u64(&hwmon.join("freq1_input")).map(|hz| (hz / 1_000_000) as u32);
                    }
                }
                Kind::Nvidia { .. } => {
                    let nvml = match &self.nvml {
                        Some(nvml) => Some(nvml),
                        None => {
                            if nvml_once.is_none() {
                                nvml_once = open_nvml();
                            }
                            nvml_once.as_ref()
                        }
                    };
                    if let Some(device) =
                        nvml.and_then(|n| n.device_by_pci_bus_id(gpu.pci.as_str()).ok())
                    {
                        reading.usage = device.utilization_rates().ok().map(|u| u.gpu as f32);
                        reading.temp = device
                            .temperature(TemperatureSensor::Gpu)
                            .ok()
                            .map(|t| t as f32);
                        if let Ok(memory) = device.memory_info() {
                            reading.vram_used = Some(memory.used);
                            reading.vram_total = Some(memory.total);
                        }
                        reading.clock_mhz = device.clock_info(Clock::Graphics).ok();
                    }
                }
                Kind::Other => {}
            }
            readings.push(reading);
        }
        // Dropping the one-off session lets a sleep-capable GPU power down again.
        drop(nvml_once);
        stats.gpus = Some(readings);
        Ok(())
    }
}

fn open_nvml() -> Option<Nvml> {
    Nvml::init()
        .inspect_err(|err| debug!("NVML unavailable: {err}"))
        .ok()
}

/// Whether the NVIDIA driver reports that this GPU can power down when idle.
fn nvidia_can_sleep(pci: &str) -> bool {
    fs::read_to_string(format!("/proc/driver/nvidia/gpus/{pci}/power")).is_ok_and(|text| {
        text.lines()
            .find(|l| l.starts_with("Runtime D3 status:"))
            .is_some_and(|l| l.contains("Enabled"))
    })
}

fn hwmon_dir(device: &Path) -> Option<PathBuf> {
    fs::read_dir(device.join("hwmon"))
        .ok()?
        .flatten()
        .map(|e| e.path())
        .next()
}

fn first_existing(paths: &[PathBuf]) -> Option<PathBuf> {
    paths.iter().find(|p| p.exists()).cloned()
}

fn read_u64(path: &Path) -> Option<u64> {
    fs::read_to_string(path).ok()?.trim().parse().ok()
}

/// Where distributions put the PCI ID database that names devices, as
/// installed by `hwdata` or `pciutils`.
const PCI_IDS: [&str; 3] = [
    "/usr/share/hwdata/pci.ids",
    "/usr/share/misc/pci.ids",
    "/usr/share/pci.ids",
];

fn pci_names() -> String {
    PCI_IDS
        .iter()
        .find_map(|p| fs::read_to_string(p).ok())
        .unwrap_or_default()
}

/// Whether the PCI ID database is installed; without it GPUs get generic
/// names such as "Intel GPU".
pub fn pci_ids_found() -> bool {
    PCI_IDS.iter().any(|p| Path::new(p).is_file())
}

/// A short, readable name such as "NVIDIA GeForce GTX 1050 Mobile", from the
/// PCI ID database where it is listed as "GP107M [GeForce GTX 1050 Mobile]".
fn gpu_name(names: &str, vendor: u16, device: u16) -> String {
    let brand = match vendor {
        0x8086 => "Intel",
        0x10de => "NVIDIA",
        0x1002 => "AMD",
        _ => "",
    };
    let model = lookup(names, vendor, device).map(|raw| match (raw.find('['), raw.rfind(']')) {
        // The part in brackets is the marketing name.
        (Some(open), Some(close)) if open < close => raw[open + 1..close].to_string(),
        _ => raw.to_string(),
    });
    match (brand, model) {
        ("", Some(model)) => model,
        (brand, Some(model)) => format!("{brand} {model}"),
        ("", None) => format!("GPU {vendor:04x}:{device:04x}"),
        (brand, None) => format!("{brand} GPU"),
    }
}

/// Finds the device line under the vendor's section of a pci.ids file.
fn lookup(names: &str, vendor: u16, device: u16) -> Option<&str> {
    let vendor_prefix = format!("{vendor:04x}  ");
    let device_prefix = format!("\t{device:04x}  ");
    let mut in_vendor = false;
    for line in names.lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        if !line.starts_with('\t') {
            if in_vendor {
                return None;
            }
            in_vendor = line.starts_with(&vendor_prefix);
        } else if in_vendor && let Some(name) = line.strip_prefix(&device_prefix) {
            return Some(name.trim());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    const NAMES: &str = "\
# comment
1002  Advanced Micro Devices, Inc. [AMD/ATI]
\t73ff  Navi 23 [Radeon RX 6600/6600 XT/6600M]
10de  NVIDIA Corporation
\t1c8d  GP107M [GeForce GTX 1050 Mobile]
\t\t1043 15f1  GP107M [GeForce GTX 1050 Mobile]
8086  Intel Corporation
\t3e9b  CoffeeLake-H GT2 [UHD Graphics 630]
\t1234  Some Device Without Brackets
";

    #[test]
    fn names_gpus_like_people_do() {
        assert_eq!(
            gpu_name(NAMES, 0x10de, 0x1c8d),
            "NVIDIA GeForce GTX 1050 Mobile"
        );
        assert_eq!(gpu_name(NAMES, 0x8086, 0x3e9b), "Intel UHD Graphics 630");
        assert_eq!(
            gpu_name(NAMES, 0x1002, 0x73ff),
            "AMD Radeon RX 6600/6600 XT/6600M"
        );
        assert_eq!(
            gpu_name(NAMES, 0x8086, 0x1234),
            "Intel Some Device Without Brackets"
        );
        assert_eq!(gpu_name(NAMES, 0x8086, 0x9999), "Intel GPU");
        assert_eq!(gpu_name("", 0x1af4, 0x1050), "GPU 1af4:1050");
    }

    #[test]
    fn intel_usage_comes_from_idle_time() {
        let dir = std::env::temp_dir().join(format!("yaedock-gpu-{}", std::process::id()));
        let device = dir.join("device");
        fs::create_dir_all(device.join("power")).unwrap();
        fs::write(device.join("power/runtime_status"), "active\n").unwrap();
        let idle = dir.join("rc6_residency_ms");
        fs::write(&idle, "1000\n").unwrap();

        let start = Instant::now();
        let mut source = GpuSource {
            gpus: vec![Gpu {
                info: GpuInfo {
                    id: "pci:x".into(),
                    name: "Intel".into(),
                },
                device: device.clone(),
                pci: "x".into(),
                kind: Kind::Intel {
                    idle: Some(idle.clone()),
                    clock: None,
                    last: Some((start, 0)),
                },
            }],
            keep_nvml: false,
            nvml: None,
        };
        // Pretend the last reading was a second ago, with 1000 ms idle since then.
        if let Kind::Intel { last, .. } = &mut source.gpus[0].kind {
            *last = Some((start - std::time::Duration::from_millis(1000), 0));
        }
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        let usage = stats.gpus.unwrap()[0].usage.unwrap();
        assert!(usage < 5.0, "an idle GPU reads {usage}%");

        fs::write(device.join("power/runtime_status"), "suspended\n").unwrap();
        let mut stats = Stats::default();
        source.sample(&mut stats).unwrap();
        assert!(stats.gpus.unwrap()[0].sleeping);
        fs::remove_dir_all(&dir).unwrap();
    }
}
