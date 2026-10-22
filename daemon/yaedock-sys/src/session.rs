//! Locking the screen, sleeping, restarting and shutting down, through logind
//! on the system bus. logind lets the user of an active session do these
//! without root.

use std::io;

use yaedock_proto::SystemAction;
use zbus::blocking::Connection;

use crate::ControlError;

/// Locks, sleeps, restarts or shuts down the PC.
pub trait SessionBackend: Send + Sync {
    /// What this user may do now.
    fn available(&self) -> Vec<SystemAction>;

    fn run(&self, action: SystemAction) -> Result<(), ControlError>;
}

const LOGIND: &str = "org.freedesktop.login1";
const MANAGER_PATH: &str = "/org/freedesktop/login1";
const MANAGER: &str = "org.freedesktop.login1.Manager";
/// The caller's session, or, for a process outside any session (a user
/// service, like the daemon), the user's graphical session.
const SESSION_PATH: &str = "/org/freedesktop/login1/session/auto";
const SESSION: &str = "org.freedesktop.login1.Session";

pub struct LogindBackend {
    bus: Connection,
}

impl LogindBackend {
    /// `None` without a system bus.
    pub fn new() -> Option<Self> {
        Some(Self {
            bus: Connection::system().ok()?,
        })
    }

    /// logind's answer to `CanSuspend` and the like: "yes" means allowed
    /// without a password; "challenge" would ask on the PC, so it counts as no.
    fn allowed(&self, method: &str) -> bool {
        self.bus
            .call_method(Some(LOGIND), MANAGER_PATH, Some(MANAGER), method, &())
            .and_then(|reply| reply.body().deserialize::<String>())
            .is_ok_and(|answer| answer == "yes")
    }

    fn has_session(&self) -> bool {
        self.bus
            .call_method(
                Some(LOGIND),
                SESSION_PATH,
                Some("org.freedesktop.DBus.Properties"),
                "Get",
                &(SESSION, "Id"),
            )
            .is_ok()
    }
}

impl SessionBackend for LogindBackend {
    fn available(&self) -> Vec<SystemAction> {
        let mut actions = Vec::new();
        if self.has_session() {
            actions.push(SystemAction::Lock);
        }
        for (method, action) in [
            ("CanSuspend", SystemAction::Suspend),
            ("CanReboot", SystemAction::Reboot),
            ("CanPowerOff", SystemAction::PowerOff),
        ] {
            if self.allowed(method) {
                actions.push(action);
            }
        }
        actions
    }

    fn run(&self, action: SystemAction) -> Result<(), ControlError> {
        // `false`: never ask for a password on the PC, where nobody may be.
        let result = match action {
            SystemAction::Lock => {
                self.bus
                    .call_method(Some(LOGIND), SESSION_PATH, Some(SESSION), "Lock", &())
            }
            SystemAction::Suspend => self.manager("Suspend"),
            SystemAction::Reboot => self.manager("Reboot"),
            SystemAction::PowerOff => self.manager("PowerOff"),
        };
        result.map(drop).map_err(|err| {
            // Such as "Operation denied due to active block inhibitor" when an
            // app holds off shutdown.
            let why = match &err {
                zbus::Error::MethodError(_, Some(message), _) => message.clone(),
                other => other.to_string(),
            };
            io::Error::other(why).into()
        })
    }
}

impl LogindBackend {
    fn manager(&self, method: &str) -> zbus::Result<zbus::message::Message> {
        self.bus
            .call_method(Some(LOGIND), MANAGER_PATH, Some(MANAGER), method, &(false,))
    }
}
