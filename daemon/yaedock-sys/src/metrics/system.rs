use std::io;

use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind, System};
use yaedock_proto::{Cpu, Mem, Stats};

use super::MetricSource;

/// CPU usage, memory and uptime from `sysinfo`.
pub struct SystemSource {
    sys: System,
}

impl SystemSource {
    pub fn new() -> Self {
        let sys = System::new_with_specifics(
            RefreshKind::nothing()
                .with_cpu(CpuRefreshKind::nothing().with_cpu_usage())
                .with_memory(MemoryRefreshKind::nothing().with_ram()),
        );
        Self { sys }
    }
}

impl Default for SystemSource {
    fn default() -> Self {
        Self::new()
    }
}

impl MetricSource for SystemSource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        self.sys.refresh_cpu_usage();
        self.sys
            .refresh_memory_specifics(MemoryRefreshKind::nothing().with_ram());
        stats.uptime = System::uptime();
        stats.cpu = Some(Cpu {
            total: self.sys.global_cpu_usage(),
            cores: self.sys.cpus().iter().map(|cpu| cpu.cpu_usage()).collect(),
        });
        stats.mem = Some(Mem {
            used: self.sys.used_memory(),
            total: self.sys.total_memory(),
        });
        Ok(())
    }
}

/// Facts about the PC that do not change while the daemon runs.
#[derive(Debug, Clone)]
pub struct SystemInfo {
    pub hostname: String,
    pub os: String,
    pub cpu_model: String,
    /// Logical CPUs, the length of `Stats::cpu.cores`.
    pub cores: u32,
}

impl SystemInfo {
    pub fn read() -> Self {
        let sys =
            System::new_with_specifics(RefreshKind::nothing().with_cpu(CpuRefreshKind::nothing()));
        Self {
            hostname: System::host_name().unwrap_or_default(),
            os: System::name().unwrap_or_else(|| "Linux".into()),
            cpu_model: sys
                .cpus()
                .first()
                .map(|cpu| cpu.brand().trim().to_string())
                .unwrap_or_default(),
            cores: sys.cpus().len() as u32,
        }
    }
}
