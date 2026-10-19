//! A small sampler for the live "This PC" card, independent of the daemon so
//! the card works even while the daemon is stopped.

use std::collections::VecDeque;

use yaedock_proto::Stats;
use yaedock_sys::metrics::{HwmonSource, MetricSource, NetSource, SystemSource};

const HISTORY: usize = 60;

pub struct LocalStats {
    sources: Vec<Box<dyn MetricSource>>,
    pub latest: Stats,
    pub cpu: VecDeque<u64>,
    pub net: VecDeque<u64>,
}

impl LocalStats {
    pub fn new() -> Self {
        let mut sources: Vec<Box<dyn MetricSource>> =
            vec![Box::new(SystemSource::new()), Box::new(NetSource::new())];
        if let Ok(hwmon) = HwmonSource::new() {
            sources.push(Box::new(hwmon));
        }
        let mut stats = Self {
            sources,
            latest: Stats::default(),
            cpu: VecDeque::new(),
            net: VecDeque::new(),
        };
        // The first reading primes CPU usage and the byte counters.
        stats.sample();
        stats.cpu.clear();
        stats.net.clear();
        stats
    }

    pub fn sample(&mut self) {
        let mut stats = Stats::default();
        for source in &mut self.sources {
            let _ = source.sample(&mut stats);
        }
        push(
            &mut self.cpu,
            stats.cpu.as_ref().map_or(0, |c| c.total.round() as u64),
        );
        push(&mut self.net, stats.net.as_ref().map_or(0, |n| n.rx + n.tx));
        self.latest = stats;
    }

    /// The CPU package temperature, or the hottest sensor.
    pub fn temperature(&self) -> Option<f32> {
        let temps = self.latest.temps.as_ref()?;
        temps
            .get("cpu_package")
            .copied()
            .or_else(|| temps.values().copied().reduce(f32::max))
    }
}

fn push(history: &mut VecDeque<u64>, value: u64) {
    history.push_back(value);
    while history.len() > HISTORY {
        history.pop_front();
    }
}
