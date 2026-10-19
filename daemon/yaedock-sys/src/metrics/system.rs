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
        self.sys.refresh_memory_specifics(MemoryRefreshKind::nothing().with_ram());
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
