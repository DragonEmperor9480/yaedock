//! Screen recording through OBS Studio, controlled over obs-websocket (built
//! into OBS 28 and later) on this PC only.

use std::env;
use std::fs;
use std::io;
use std::net::{Ipv4Addr, SocketAddr, TcpStream};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD as BASE64;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tungstenite::{Message, WebSocket};
use yaedock_proto::Recording;

use crate::ControlError;

/// Records the screen with a recorder app's own settings.
pub trait RecorderBackend: Send + Sync {
    /// What this backend uses, for reports such as `yaedock check`.
    fn name(&self) -> &'static str;

    /// The recorder's state, or `None` while the app is closed.
    fn status(&self) -> Result<Option<Recording>, ControlError>;

    /// Starts recording, opening the app first if it is closed.
    fn start(&self) -> Result<(), ControlError>;

    /// Stops recording and returns where the video was saved.
    fn stop(&self) -> Result<PathBuf, ControlError>;
}

const FLATPAK_ID: &str = "com.obsproject.Studio";
/// How long OBS gets to open when recording starts with OBS closed.
const LAUNCH_TIMEOUT: Duration = Duration::from_secs(30);
const IO_TIMEOUT: Duration = Duration::from_secs(5);

/// How OBS Studio is installed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ObsInstall {
    /// A distribution package, `obs` on `PATH`.
    Native,
    Flatpak,
}

impl ObsInstall {
    /// Finds OBS Studio, preferring a native install.
    pub fn find() -> Option<Self> {
        let path = env::var_os("PATH").unwrap_or_default();
        if env::split_paths(&path).any(|dir| dir.join("obs").is_file()) {
            return Some(Self::Native);
        }
        let home = env::var_os("HOME").map(PathBuf::from).unwrap_or_default();
        let flatpak = [
            PathBuf::from("/var/lib/flatpak/app").join(FLATPAK_ID),
            home.join(".local/share/flatpak/app").join(FLATPAK_ID),
        ];
        flatpak
            .iter()
            .any(|dir| dir.is_dir())
            .then_some(Self::Flatpak)
    }

    /// OBS's own settings folder.
    fn config_dir(self) -> Option<PathBuf> {
        let home = PathBuf::from(env::var_os("HOME")?);
        Some(match self {
            Self::Native => env::var_os("XDG_CONFIG_HOME")
                .filter(|v| !v.is_empty())
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join(".config"))
                .join("obs-studio"),
            Self::Flatpak => home
                .join(".var/app")
                .join(FLATPAK_ID)
                .join("config/obs-studio"),
        })
    }

    /// The obs-websocket settings file, which OBS writes when it exits.
    pub fn websocket_config(self) -> Option<PathBuf> {
        Some(
            self.config_dir()?
                .join("plugin_config/obs-websocket/config.json"),
        )
    }

    fn launch_command(self) -> Vec<&'static str> {
        let mut command = match self {
            Self::Native => vec!["obs"],
            Self::Flatpak => vec!["flatpak", "run", FLATPAK_ID],
        };
        command.extend(["--minimize-to-tray", "--startrecording"]);
        command
    }
}

/// The obs-websocket server settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WebSocketSettings {
    pub enabled: bool,
    pub port: u16,
    /// Empty when no password is required.
    pub password: String,
}

impl WebSocketSettings {
    pub fn read(install: ObsInstall) -> io::Result<Self> {
        let path = install
            .websocket_config()
            .ok_or_else(|| io::Error::other("HOME is not set"))?;
        Self::parse(&fs::read_to_string(path)?)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "unreadable OBS settings"))
    }

    fn parse(text: &str) -> Option<Self> {
        let config: Value = serde_json::from_str(text).ok()?;
        let auth = config["auth_required"].as_bool().unwrap_or(true);
        Some(Self {
            enabled: config["server_enabled"].as_bool().unwrap_or(false),
            port: config["server_port"]
                .as_u64()
                .and_then(|p| u16::try_from(p).ok())
                .unwrap_or(4455),
            password: if auth {
                config["server_password"].as_str().unwrap_or("").to_string()
            } else {
                String::new()
            },
        })
    }
}

/// Whether an OBS process is running, with its WebSocket server on or not.
pub fn obs_running() -> bool {
    let Ok(entries) = fs::read_dir("/proc") else {
        return false;
    };
    entries.flatten().any(|entry| {
        fs::read_to_string(entry.path().join("comm")).is_ok_and(|comm| comm.trim() == "obs")
    })
}

/// OBS Studio, if it is installed and its WebSocket server is turned on.
pub fn detect() -> Option<Box<dyn RecorderBackend>> {
    let install = ObsInstall::find()?;
    WebSocketSettings::read(install)
        .is_ok_and(|s| s.enabled)
        .then(|| Box::new(ObsBackend::new(install)) as Box<dyn RecorderBackend>)
}

pub struct ObsBackend {
    install: ObsInstall,
    /// Kept open between calls; dropped when OBS goes away.
    connection: Mutex<Option<Obs>>,
}

impl ObsBackend {
    pub fn new(install: ObsInstall) -> Self {
        Self {
            install,
            connection: Mutex::new(None),
        }
    }

    /// Runs `call` on the connection, connecting first if needed. `Ok(None)`
    /// means OBS is not reachable (closed, or its server is off).
    fn with_obs<T>(&self, call: impl FnOnce(&mut Obs) -> io::Result<T>) -> io::Result<Option<T>> {
        let mut connection = self.connection.lock().unwrap_or_else(|e| e.into_inner());
        if connection.is_none() {
            // No settings yet (OBS never opened) or the server turned off: not
            // reachable, which is no error.
            let Some(settings) = WebSocketSettings::read(self.install)
                .ok()
                .filter(|s| s.enabled)
            else {
                return Ok(None);
            };
            match Obs::connect(&settings) {
                Ok(obs) => *connection = Some(obs),
                Err(err) if err.kind() == io::ErrorKind::ConnectionRefused => return Ok(None),
                Err(err) => return Err(err),
            }
        }
        let obs = connection.as_mut().expect("just connected");
        let result = call(obs);
        if result.is_err() {
            // OBS may have closed; reconnect next time.
            *connection = None;
        }
        result.map(Some)
    }

    /// Opens OBS minimized, recording from the start, and waits until it is.
    fn launch(&self) -> io::Result<()> {
        // A transient user unit of its own, so OBS outlives a daemon restart.
        let status = Command::new("systemd-run")
            .args(["--user", "--collect", "--quiet", "--"])
            .args(self.install.launch_command())
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()?;
        if !status.success() {
            return Err(io::Error::other("could not start OBS"));
        }
        let started = Instant::now();
        while started.elapsed() < LAUNCH_TIMEOUT {
            thread::sleep(Duration::from_millis(500));
            if let Ok(Some(true)) = self.with_obs(|obs| Ok(obs.status()?.active)) {
                return Ok(());
            }
        }
        Err(io::Error::other(
            "OBS opened but did not start recording; check it on the PC",
        ))
    }
}

impl RecorderBackend for ObsBackend {
    fn name(&self) -> &'static str {
        match self.install {
            ObsInstall::Native => "OBS Studio",
            ObsInstall::Flatpak => "OBS Studio (Flatpak)",
        }
    }

    fn status(&self) -> Result<Option<Recording>, ControlError> {
        Ok(self.with_obs(Obs::status)?)
    }

    fn start(&self) -> Result<(), ControlError> {
        match self.with_obs(|obs| obs.request("StartRecord", Value::Null).map(drop)) {
            Ok(Some(())) => Ok(()),
            Ok(None) if obs_running() => Err(io::Error::other(
                "OBS is open but its WebSocket server is off; run `yaedock obs on` with OBS closed",
            )
            .into()),
            Ok(None) => Ok(self.launch()?),
            // Already recording is what was asked for.
            Err(err) if err.to_string().contains("already active") => Ok(()),
            Err(err) => Err(err.into()),
        }
    }

    fn stop(&self) -> Result<PathBuf, ControlError> {
        let data = self
            .with_obs(|obs| obs.request("StopRecord", Value::Null))?
            .ok_or_else(|| io::Error::other("OBS is not running"))?;
        let path = data["outputPath"]
            .as_str()
            .ok_or_else(|| io::Error::other("OBS did not say where it saved the recording"))?;
        Ok(PathBuf::from(path))
    }
}

/// An identified obs-websocket (protocol version 5) connection.
struct Obs {
    socket: WebSocket<TcpStream>,
    next_id: u64,
}

impl Obs {
    fn connect(settings: &WebSocketSettings) -> io::Result<Self> {
        let address = SocketAddr::from((Ipv4Addr::LOCALHOST, settings.port));
        let stream = TcpStream::connect_timeout(&address, IO_TIMEOUT)?;
        stream.set_read_timeout(Some(IO_TIMEOUT))?;
        stream.set_write_timeout(Some(IO_TIMEOUT))?;
        let url = format!("ws://127.0.0.1:{}", settings.port);
        let (socket, _) =
            tungstenite::client(url, stream).map_err(|e| io::Error::other(e.to_string()))?;
        let mut obs = Self { socket, next_id: 0 };

        let hello = obs.receive(0)?;
        // No events: the daemon reads the state when it needs it.
        let mut identify = json!({"rpcVersion": 1, "eventSubscriptions": 0});
        if let Some(auth) = hello.get("authentication") {
            identify["authentication"] = auth_response(
                &settings.password,
                auth["salt"].as_str().unwrap_or(""),
                auth["challenge"].as_str().unwrap_or(""),
            )
            .into();
        }
        obs.send(1, identify)?;
        obs.receive(2)?;
        Ok(obs)
    }

    fn status(&mut self) -> io::Result<Recording> {
        let data = self.request("GetRecordStatus", Value::Null)?;
        Ok(Recording {
            active: data["outputActive"].as_bool().unwrap_or(false),
            paused: data["outputPaused"].as_bool().unwrap_or(false),
            duration_ms: data["outputDuration"].as_u64().unwrap_or(0),
        })
    }

    /// Sends a request and returns its response data.
    fn request(&mut self, kind: &str, data: Value) -> io::Result<Value> {
        self.next_id += 1;
        let id = self.next_id.to_string();
        self.send(
            6,
            json!({"requestType": kind, "requestId": id, "requestData": data}),
        )?;
        loop {
            let response = self.receive(7)?;
            if response["requestId"] != id.as_str() {
                continue;
            }
            let status = &response["requestStatus"];
            if status["result"].as_bool() != Some(true) {
                let comment = status["comment"].as_str().unwrap_or("request failed");
                return Err(io::Error::other(format!("OBS: {comment}")));
            }
            return Ok(response["responseData"].clone());
        }
    }

    fn send(&mut self, op: u8, data: Value) -> io::Result<()> {
        let text = json!({"op": op, "d": data}).to_string();
        self.socket.send(Message::text(text)).map_err(ws_error)
    }

    /// Reads messages until one with opcode `op`, returning its data.
    fn receive(&mut self, op: u64) -> io::Result<Value> {
        loop {
            match self.socket.read().map_err(ws_error)? {
                Message::Text(text) => {
                    let mut message: Value = serde_json::from_str(&text)
                        .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                    if message["op"].as_u64() == Some(op) {
                        return Ok(message["d"].take());
                    }
                }
                Message::Close(frame) => {
                    // obs-websocket closes with 4009 for a wrong password.
                    let why = match frame {
                        Some(frame) if u16::from(frame.code) == 4009 => {
                            "OBS rejected the WebSocket password".to_string()
                        }
                        Some(frame) => format!("OBS closed the connection: {}", frame.reason),
                        None => "OBS closed the connection".to_string(),
                    };
                    return Err(io::Error::new(io::ErrorKind::ConnectionAborted, why));
                }
                _ => {}
            }
        }
    }
}

/// obs-websocket's challenge response:
/// `base64(sha256(base64(sha256(password + salt)) + challenge))`.
fn auth_response(password: &str, salt: &str, challenge: &str) -> String {
    let secret = BASE64.encode(Sha256::digest(format!("{password}{salt}")));
    BASE64.encode(Sha256::digest(format!("{secret}{challenge}")))
}

fn ws_error(err: tungstenite::Error) -> io::Error {
    match err {
        tungstenite::Error::Io(err) => err,
        other => io::Error::other(other.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn answers_the_challenge() {
        // The example from the obs-websocket protocol documentation.
        assert_eq!(
            auth_response(
                "supersecretpassword",
                "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
                "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
            ),
            "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4="
        );
    }

    #[test]
    fn reads_websocket_settings() {
        let on = r#"{"alerts_enabled":false,"auth_required":true,"first_load":false,
            "server_enabled":true,"server_password":"abc","server_port":4456}"#;
        assert_eq!(
            WebSocketSettings::parse(on),
            Some(WebSocketSettings {
                enabled: true,
                port: 4456,
                password: "abc".into()
            })
        );
        let open = r#"{"auth_required":false,"server_enabled":false,"server_password":"abc"}"#;
        assert_eq!(
            WebSocketSettings::parse(open),
            Some(WebSocketSettings {
                enabled: false,
                port: 4455,
                password: String::new()
            })
        );
        assert_eq!(WebSocketSettings::parse("nope"), None);
    }
}
