use std::fs;
use std::io;
use std::path::Path;
use std::time::Instant;

use yaedock_proto::{Net, Stats};

use super::{MetricSource, Rate};

/// Network throughput summed over physical interfaces.
///
/// Virtual interfaces (loopback, bridges, veth, VPN tunnels) are skipped,
/// since their traffic also crosses a physical interface.
#[derive(Default)]
pub struct NetSource {
    rate: Rate,
}

impl NetSource {
    pub fn new() -> Self {
        Self::default()
    }
}

impl MetricSource for NetSource {
    fn sample(&mut self, stats: &mut Stats) -> io::Result<()> {
        let text = fs::read_to_string("/proc/net/dev")?;
        let (rx, tx) = sum_bytes(&text, is_physical);
        let (rx, tx) = self.rate.update(Instant::now(), rx, tx);
        stats.net = Some(Net { rx, tx });
        Ok(())
    }
}

fn is_physical(iface: &str) -> bool {
    fs::canonicalize(Path::new("/sys/class/net").join(iface))
        .is_ok_and(|target| !target.starts_with("/sys/devices/virtual"))
}

/// Sums received and transmitted bytes over the interfaces `include` accepts.
fn sum_bytes(netdev: &str, include: impl Fn(&str) -> bool) -> (u64, u64) {
    let mut total = (0, 0);
    // The first two lines are headers.
    for line in netdev.lines().skip(2) {
        let Some((iface, counters)) = line.split_once(':') else {
            continue;
        };
        if !include(iface.trim()) {
            continue;
        }
        let fields: Vec<&str> = counters.split_whitespace().collect();
        if fields.len() < 9 {
            continue;
        }
        total.0 += fields[0].parse::<u64>().unwrap_or(0);
        total.1 += fields[8].parse::<u64>().unwrap_or(0);
    }
    total
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sums_included_interfaces() {
        let text = "\
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:  5000      10    0    0    0     0          0         0     5000      10    0    0    0     0       0          0
 wlan0: 120400    100    0    0    0     0          0         0     8800      50    0    0    0     0       0          0
  eno2:   1000     10    0    0    0     0          0         0      200       2    0    0    0     0       0          0";
        assert_eq!(sum_bytes(text, |iface| iface != "lo"), (121400, 9000));
    }
}
