//! Metric sources. Each source fills its part of a [`Stats`] sample.

mod diskstats;
mod hwmon;
mod netdev;
mod system;

use std::io;
use std::time::Instant;

use yaedock_proto::Stats;

pub use diskstats::DiskSource;
pub use hwmon::HwmonSource;
pub use netdev::NetSource;
pub use system::{SystemInfo, SystemSource};

/// Something that reads part of the system state once per tick.
pub trait MetricSource: Send {
    /// Reads the source and writes its metrics into `stats`.
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()>;
}

/// Turns a pair of ever-growing byte counters into bytes per second.
#[derive(Debug, Default)]
struct Rate {
    last: Option<(Instant, u64, u64)>,
}

impl Rate {
    /// Returns the per-second rate of each counter since the previous call,
    /// or zeros on the first call. A counter that went backwards (a device was
    /// removed or reset) counts as zero for that tick.
    fn update(&mut self, now: Instant, a: u64, b: u64) -> (u64, u64) {
        let rates = match self.last {
            Some((then, last_a, last_b)) => {
                let secs = now.duration_since(then).as_secs_f64();
                if secs > 0.0 {
                    (
                        (a.saturating_sub(last_a) as f64 / secs) as u64,
                        (b.saturating_sub(last_b) as f64 / secs) as u64,
                    )
                } else {
                    (0, 0)
                }
            }
            None => (0, 0),
        };
        self.last = Some((now, a, b));
        rates
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn rate_per_second() {
        let start = Instant::now();
        let mut rate = Rate::default();
        assert_eq!(rate.update(start, 1000, 500), (0, 0));
        assert_eq!(
            rate.update(start + Duration::from_secs(2), 3000, 400),
            (1000, 0)
        );
    }
}
