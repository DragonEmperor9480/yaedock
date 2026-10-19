//! WebSocket transport: one JSON message per text frame.

use std::sync::Arc;

use futures_util::{SinkExt, StreamExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info};

use crate::session::{self, Incoming, Shared};

/// Accepts connections forever, running a session for each.
pub async fn serve(listener: TcpListener, shared: Arc<Shared>) -> std::io::Result<()> {
    loop {
        let (stream, addr) = listener.accept().await?;
        let shared = shared.clone();
        tokio::spawn(async move {
            debug!(%addr, "connection opened");
            if let Err(err) = handle(stream, shared).await {
                info!(%addr, "connection failed: {err}");
            }
            debug!(%addr, "connection closed");
        });
    }
}

async fn handle(stream: TcpStream, shared: Arc<Shared>) -> anyhow::Result<()> {
    let ws = tokio_tungstenite::accept_async(stream).await?;
    let (mut sink, mut frames) = ws.split();
    let (incoming_tx, incoming) = mpsc::channel(16);
    let (outgoing, mut outgoing_rx) = mpsc::channel(16);
    tokio::spawn(session::run(shared, incoming, outgoing));

    // Ends when the session finishes and drops its sender.
    let writer = async {
        while let Some(message) = outgoing_rx.recv().await {
            let text = serde_json::to_string(&message)?;
            sink.send(Message::text(text)).await?;
        }
        sink.close().await?;
        anyhow::Ok(())
    };
    // Ends when the phone closes the connection, which also ends the session.
    let reader = async {
        while let Some(frame) = frames.next().await {
            let incoming = match frame? {
                Message::Text(text) => match serde_json::from_str(&text) {
                    Ok(message) => Incoming::Message(message),
                    Err(err) => Incoming::Invalid(err.to_string()),
                },
                Message::Binary(_) => Incoming::Invalid("binary frames are not supported".into()),
                Message::Close(_) => break,
                // tungstenite answers WebSocket pings itself.
                _ => continue,
            };
            if incoming_tx.send(incoming).await.is_err() {
                break;
            }
        }
        anyhow::Ok(())
    };

    tokio::select! {
        result = writer => result,
        result = reader => result,
    }
}
