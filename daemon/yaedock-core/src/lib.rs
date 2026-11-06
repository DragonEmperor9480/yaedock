//! Code shared by the daemon (the `yaedockd` crate) and the `yaedock` program:
//! where files live, the TLS identity, paired devices, pairing codes and the
//! metrics sampler, shortcut buttons, and updates.

pub mod control;
pub mod devices;
pub mod mangohud;
pub mod pairing;
pub mod paths;
pub mod sampler;
pub mod shortcuts;
pub mod tls;
pub mod update;

/// The port the daemon listens on unless told otherwise.
pub const DEFAULT_PORT: u16 = 9481;
