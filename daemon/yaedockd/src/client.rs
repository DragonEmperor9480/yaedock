//! A test client that connects like the phone would and prints every message.

use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::tungstenite::Message;
use yaedock_proto::{ClientMessage, Hello, PROTOCOL_VERSION, ServerMessage};

/// Connects to `url`, says hello, sends each of `send` after the welcome, and
/// prints received messages as JSON lines. Stops after `count` messages if given.
pub async fn run(url: &str, send: &[String], count: Option<u64>) -> anyhow::Result<()> {
    let (mut ws, _) = tokio_tungstenite::connect_async(url).await?;
    let hello = ClientMessage::Hello(Hello {
        version: PROTOCOL_VERSION,
        device_id: "yaedockd-client".into(),
        device_name: "yaedockd test client".into(),
        token: None,
        code: None,
    });
    ws.send(Message::text(serde_json::to_string(&hello)?))
        .await?;

    let mut received = 0;
    while count.is_none_or(|count| received < count) {
        let Some(frame) = ws.next().await else { break };
        let Message::Text(text) = frame? else {
            continue;
        };
        println!("{}", text.as_str());
        received += 1;
        match serde_json::from_str(&text)? {
            ServerMessage::Welcome(_) => {
                for message in send {
                    // Check it parses, so typos fail here rather than as a bad_request.
                    serde_json::from_str::<ClientMessage>(message)?;
                    ws.send(Message::text(message.clone())).await?;
                }
            }
            ServerMessage::Ping => {
                let pong = serde_json::to_string(&ClientMessage::Pong)?;
                ws.send(Message::text(pong)).await?;
            }
            _ => {}
        }
    }
    ws.close(None).await?;
    Ok(())
}
