//! WebSocket transport over TLS: one JSON message per text frame.

use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info};

use crate::session::{self, Incoming, Shared};

/// How long a client gets to finish the TLS and WebSocket handshakes.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

/// Accepts connections forever, running a session for each.
pub async fn serve(
    listener: TcpListener,
    tls: TlsAcceptor,
    shared: Arc<Shared>,
) -> std::io::Result<()> {
    loop {
        let (stream, addr) = listener.accept().await?;
        let shared = shared.clone();
        let tls = tls.clone();
        tokio::spawn(async move {
            debug!(%addr, "connection opened");
            let handshake = async {
                let stream = tls.accept(stream).await?;
                anyhow::Ok(tokio_tungstenite::accept_async(stream).await?)
            };
            let result = match timeout(HANDSHAKE_TIMEOUT, handshake).await {
                Ok(Ok(ws)) => handle(ws, shared).await,
                Ok(Err(err)) => Err(err),
                Err(_) => Err(anyhow::anyhow!("handshake timed out")),
            };
            if let Err(err) = result {
                info!(%addr, "connection failed: {err}");
            }
            debug!(%addr, "connection closed");
        });
    }
}

async fn handle<S>(
    ws: tokio_tungstenite::WebSocketStream<S>,
    shared: Arc<Shared>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
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
