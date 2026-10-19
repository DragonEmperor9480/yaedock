//! Code shared by the daemon (`yaedockd`) and the command-line tool (`yaedock`):
//! where files live, the TLS identity, paired devices, pairing codes and the
//! metrics sampler.

pub mod control;
pub mod devices;
pub mod pairing;
pub mod paths;
pub mod sampler;
pub mod tls;

/// The port the daemon listens on unless told otherwise.
pub const DEFAULT_PORT: u16 = 9481;
