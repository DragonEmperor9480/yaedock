//! Protocol message types shared by the daemon and the test client.
//!
//! Every message is a JSON object with a `type` field. See `protocol/protocol.md`
//! for the wire format; this crate and that document must change together.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

/// Protocol version sent in `hello` and `welcome`.
pub const PROTOCOL_VERSION: u32 = 1;

/// Messages sent by the phone to the PC.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ClientMessage {
    Hello(Hello),
    Subscribe(Subscribe),
    SetBrightness(SetBrightness),
    SetVolume(SetVolume),
    Ping,
    Pong,
}

/// Messages sent by the PC to the phone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerMessage {
    Welcome(Welcome),
    Stats(Stats),
    State(State),
    Error(Error),
    Ping,
    Pong,
}

/// First message on every connection.
///
/// A new phone sends the one-time pairing `code` from the QR code; a paired
/// phone sends the `token` it received in its first `welcome`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hello {
    pub version: u32,
    pub device_id: String,
    pub device_name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
}

/// Reply to a successful `hello`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Welcome {
    pub version: u32,
    pub hostname: String,
    pub os: String,
    pub cpu_model: String,
    pub cores: u32,
    /// Temperature sensor names, the keys used in `Stats::temps`.
    pub sensors: Vec<String>,
    /// Long-lived device token, present only when pairing with a code.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token: Option<String>,
    pub state: State,
}

/// One sample of system metrics. Groups the phone has not subscribed to are omitted.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Stats {
    /// Unix time in milliseconds.
    pub ts: u64,
    /// Seconds since boot.
    pub uptime: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cpu: Option<Cpu>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mem: Option<Mem>,
    /// Degrees Celsius by sensor name.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub temps: Option<BTreeMap<String, f32>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub net: Option<Net>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub disk: Option<Disk>,
}

/// CPU usage in percent, 0 to 100.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Cpu {
    pub total: f32,
    pub cores: Vec<f32>,
}

/// Memory in bytes.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Mem {
    pub used: u64,
    pub total: u64,
}

/// Network throughput in bytes per second, summed over all interfaces.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Net {
    pub rx: u64,
    pub tx: u64,
}

/// Disk throughput in bytes per second, summed over all disks.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Disk {
    pub read: u64,
    pub write: u64,
}

/// Current brightness and volume, sent after any change from any source.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct State {
    pub displays: Vec<DisplayState>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub volume: Option<VolumeState>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct DisplayState {
    pub id: String,
    pub name: String,
    /// Brightness in percent, 0 to 100.
    pub percent: u8,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct VolumeState {
    /// Volume in percent, 0 to 150.
    pub percent: u8,
    pub muted: bool,
}

/// Metric groups a phone can subscribe to.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MetricGroup {
    Cpu,
    Mem,
    Temps,
    Net,
    Disk,
}

/// Choose which metric groups to receive and how often.
///
/// `interval_ms` is rounded up to a multiple of the daemon's sampling tick.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Subscribe {
    pub groups: Vec<MetricGroup>,
    pub interval_ms: u32,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetBrightness {
    pub display: String,
    /// Brightness in percent, 0 to 100.
    pub percent: u8,
}

/// Change the volume, the mute flag, or both. Omitted fields are left as they are.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SetVolume {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub percent: Option<u8>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub muted: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Error {
    pub code: ErrorCode,
    pub message: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    /// The message could not be parsed or was not expected at this point.
    BadRequest,
    /// The token or pairing code was missing or wrong.
    Unauthorized,
    /// The phone speaks a protocol version the daemon does not support.
    UnsupportedVersion,
    UnsupportedDisplay,
    /// A brightness or volume backend failed to apply the change.
    BackendFailed,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn stats_round_trip() {
        let msg = ServerMessage::Stats(Stats {
            ts: 1_760_838_600_000,
            uptime: 3600,
            cpu: Some(Cpu {
                total: 23.5,
                cores: vec![18.0, 29.0],
            }),
            mem: Some(Mem {
                used: 6_549_000_000,
                total: 16_642_000_000,
            }),
            temps: Some(BTreeMap::from([("cpu_package".into(), 54.0)])),
            net: Some(Net {
                rx: 120_400,
                tx: 8_800,
            }),
            disk: None,
        });
        let value = serde_json::to_value(&msg).unwrap();
        assert_eq!(value["type"], "stats");
        assert!(value.get("disk").is_none());
        assert_eq!(serde_json::from_value::<ServerMessage>(value).unwrap(), msg);
    }

    #[test]
    fn hello_without_optional_fields() {
        let msg: ClientMessage = serde_json::from_value(json!({
            "type": "hello", "version": 1, "device_id": "abc", "device_name": "Pixel"
        }))
        .unwrap();
        let ClientMessage::Hello(hello) = msg else {
            panic!("not a hello")
        };
        assert_eq!(hello.token, None);
        assert_eq!(hello.code, None);
    }

    #[test]
    fn unit_messages_and_enums() {
        assert_eq!(
            serde_json::to_value(ClientMessage::Ping).unwrap(),
            json!({"type": "ping"})
        );
        let sub: ClientMessage = serde_json::from_value(json!({
            "type": "subscribe", "groups": ["cpu", "temps"], "interval_ms": 2000
        }))
        .unwrap();
        assert_eq!(
            sub,
            ClientMessage::Subscribe(Subscribe {
                groups: vec![MetricGroup::Cpu, MetricGroup::Temps],
                interval_ms: 2000
            })
        );
        let err = ServerMessage::Error(Error {
            code: ErrorCode::UnsupportedDisplay,
            message: "no such display".into(),
        });
        assert_eq!(
            serde_json::to_value(err).unwrap()["code"],
            "unsupported_display"
        );
    }
}
