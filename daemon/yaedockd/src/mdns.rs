//! Advertises the daemon on the LAN as `_yaedock._tcp.local.`.

use mdns_sd::{ServiceDaemon, ServiceInfo};
use yaedock_proto::PROTOCOL_VERSION;

const SERVICE_TYPE: &str = "_yaedock._tcp.local.";

/// Starts advertising; the service stays registered until the returned daemon
/// is shut down.
///
/// The TXT record carries the protocol version and the certificate fingerprint,
/// so a phone can recognise a PC it paired with even after its address changes.
pub fn advertise(hostname: &str, port: u16, fingerprint: &str) -> anyhow::Result<ServiceDaemon> {
    let name = if hostname.is_empty() {
        "yaedock"
    } else {
        hostname
    };
    let version = PROTOCOL_VERSION.to_string();
    let properties = [("version", version.as_str()), ("fp", fingerprint)];
    let service = ServiceInfo::new(
        SERVICE_TYPE,
        name,
        &format!("{name}.local."),
        (),
        port,
        &properties[..],
    )?
    .enable_addr_auto();
    let daemon = ServiceDaemon::new()?;
    daemon.register(service)?;
    Ok(daemon)
}
