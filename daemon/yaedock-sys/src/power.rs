//! Power profiles, through power-profiles-daemon's D-Bus interface (also
//! offered by tuned-ppd).

use std::collections::HashMap;
use std::io;

use yaedock_proto::PowerProfiles;
use zbus::blocking::Connection;
use zbus::zvariant::{OwnedValue, Value};

use crate::ControlError;

/// Reads and changes the power profile.
pub trait PowerBackend: Send + Sync {
    /// What this backend uses, for reports such as `yaedock check`.
    fn name(&self) -> &'static str;

    fn get(&self) -> Result<PowerProfiles, ControlError>;

    /// Switches to `profile`, one of `PowerProfiles::available`.
    fn set(&self, profile: &str) -> Result<(), ControlError>;
}

/// The bus names and paths the interface goes by: the current one, then the
/// one older versions use.
const SERVICES: [(&str, &str); 2] = [
    (
        "org.freedesktop.UPower.PowerProfiles",
        "/org/freedesktop/UPower/PowerProfiles",
    ),
    ("net.hadess.PowerProfiles", "/net/hadess/PowerProfiles"),
];

pub struct PowerProfilesBackend {
    bus: Connection,
    service: &'static str,
    path: &'static str,
}

/// Finds a running power profile service, or `None`.
pub fn detect() -> Option<Box<dyn PowerBackend>> {
    let bus = Connection::system().ok()?;
    SERVICES.into_iter().find_map(|(service, path)| {
        let backend = PowerProfilesBackend {
            bus: bus.clone(),
            service,
            path,
        };
        backend
            .get()
            .ok()
            .map(|_| Box::new(backend) as Box<dyn PowerBackend>)
    })
}

impl PowerProfilesBackend {
    fn property(&self, name: &str) -> zbus::Result<OwnedValue> {
        let reply = self.bus.call_method(
            Some(self.service),
            self.path,
            Some("org.freedesktop.DBus.Properties"),
            "Get",
            &(self.service, name),
        )?;
        reply.body().deserialize()
    }
}

impl PowerBackend for PowerProfilesBackend {
    fn name(&self) -> &'static str {
        "power-profiles-daemon"
    }

    fn get(&self) -> Result<PowerProfiles, ControlError> {
        let active = String::try_from(self.property("ActiveProfile").map_err(bus_error)?)
            .map_err(|err| bus_error(err.into()))?;
        let profiles: Vec<HashMap<String, OwnedValue>> = self
            .property("Profiles")
            .map_err(bus_error)?
            .try_into()
            .map_err(|err: zbus::zvariant::Error| bus_error(err.into()))?;
        let available = profiles
            .into_iter()
            .filter_map(|mut profile| String::try_from(profile.remove("Profile")?).ok())
            .collect();
        Ok(PowerProfiles { active, available })
    }

    fn set(&self, profile: &str) -> Result<(), ControlError> {
        if !self.get()?.available.iter().any(|p| p == profile) {
            let message = format!("no power profile named {profile}");
            return Err(io::Error::new(io::ErrorKind::NotFound, message).into());
        }
        self.bus
            .call_method(
                Some(self.service),
                self.path,
                Some("org.freedesktop.DBus.Properties"),
                "Set",
                &(self.service, "ActiveProfile", Value::from(profile)),
            )
            .map_err(bus_error)?;
        Ok(())
    }
}

fn bus_error(err: zbus::Error) -> ControlError {
    io::Error::other(err.to_string()).into()
}
