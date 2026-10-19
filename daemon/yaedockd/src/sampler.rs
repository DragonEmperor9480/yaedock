//! The sampler task: reads every metric source once per tick and publishes a
//! [`Snapshot`] on a watch channel that session tasks read from.

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use tokio::sync::watch;
use tokio::time::{self, Instant, MissedTickBehavior};
use tracing::warn;
use yaedock_proto::Stats;
use yaedock_sys::metrics::{DiskSource, HwmonSource, MetricSource, NetSource, SystemSource};

/// A full sample with every metric group filled in. Sessions filter it per subscription.
pub type Snapshot = Stats;

pub const DEFAULT_TICK: Duration = Duration::from_secs(1);

/// The metric sources available on this system, plus the temperature sensor names.
pub fn default_sources() -> (Vec<Box<dyn MetricSource>>, Vec<String>) {
    let mut sources: Vec<Box<dyn MetricSource>> = vec![Box::new(SystemSource::new())];
    let mut sensors = Vec::new();
    match HwmonSource::new() {
        Ok(hwmon) => {
            sensors = hwmon.sensor_names();
            sources.push(Box::new(hwmon));
        }
        Err(err) => warn!("temperatures unavailable: {err}"),
    }
    match DiskSource::new() {
        Ok(disk) => sources.push(Box::new(disk)),
        Err(err) => warn!("disk I/O unavailable: {err}"),
    }
    sources.push(Box::new(NetSource::new()));
    (sources, sensors)
}

/// Starts the sampler task and returns the channel it publishes on.
///
/// The channel starts with an empty snapshot; the first real one arrives after one tick.
pub fn spawn(sources: Vec<Box<dyn MetricSource>>, tick: Duration) -> watch::Receiver<Arc<Snapshot>> {
    let (tx, rx) = watch::channel(Arc::new(Snapshot::default()));
    tokio::spawn(run(sources, tick, tx));
    rx
}

async fn run(
    mut sources: Vec<Box<dyn MetricSource>>,
    tick: Duration,
    tx: watch::Sender<Arc<Snapshot>>,
) {
    // Prime CPU usage and the byte counters so the first published rates are real.
    let mut failing = vec![false; sources.len()];
    sample(&mut sources, &mut failing);

    let mut interval = time::interval_at(Instant::now() + tick, tick);
    interval.set_missed_tick_behavior(MissedTickBehavior::Delay);
    loop {
        interval.tick().await;
        let snapshot = sample(&mut sources, &mut failing);
        if tx.send(Arc::new(snapshot)).is_err() {
            // Every receiver is gone.
            return;
        }
    }
}

fn sample(sources: &mut [Box<dyn MetricSource>], failing: &mut [bool]) -> Snapshot {
    let mut snapshot = Snapshot {
        ts: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_millis() as u64),
        ..Snapshot::default()
    };
    for (source, failing) in sources.iter_mut().zip(failing) {
        // Warn once when a source starts failing, not on every tick.
        match source.sample(&mut snapshot) {
            Ok(()) => *failing = false,
            Err(err) if !*failing => {
                warn!("metric source failed: {err}");
                *failing = true;
            }
            Err(_) => {}
        }
    }
    snapshot
}
