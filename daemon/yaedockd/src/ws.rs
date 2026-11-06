//! WebSocket transport over TLS: one JSON message per text frame.

use std::sync::Arc;
use std::time::Duration;

use futures_util::{Sink, SinkExt, StreamExt};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpListener;
use tokio::sync::mpsc;
use tokio::time::timeout;
use tokio_rustls::TlsAcceptor;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info};

use yaedock_proto::ServerMessage;

use crate::session::{self, Incoming, Shared};

/// How long a client gets to finish the TLS and WebSocket handshakes.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// How long one message may wait to be taken by the phone. A phone gone from
/// the network takes nothing; without this, sending would wait for the
/// kernel to give up on the connection, which takes about 15 minutes, and the
/// session would wait on it.
const WRITE_TIMEOUT: Duration = Duration::from_secs(20);

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
                Ok(Ok(ws)) => handle(ws, addr.to_string(), shared).await,
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
    peer: String,
    shared: Arc<Shared>,
) -> anyhow::Result<()>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let (mut sink, mut frames) = ws.split();
    let (incoming_tx, incoming) = mpsc::channel(16);
    let (outgoing, mut outgoing_rx) = mpsc::channel(16);
    tokio::spawn(session::run(shared, peer, incoming, outgoing));

    // Ends when the session finishes and drops its sender.
    let writer = write(&mut sink, &mut outgoing_rx, WRITE_TIMEOUT);
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

/// Sends the session's messages to the phone until the session ends, then
/// closes the connection. Fails when the phone takes nothing for `limit`.
async fn write<S>(
    sink: &mut S,
    outgoing: &mut mpsc::Receiver<ServerMessage>,
    limit: Duration,
) -> anyhow::Result<()>
where
    S: Sink<Message, Error = tokio_tungstenite::tungstenite::Error> + Unpin,
{
    let stalled = |_| anyhow::anyhow!("the phone stopped taking messages");
    while let Some(message) = outgoing.recv().await {
        let text = serde_json::to_string(&message)?;
        timeout(limit, sink.send(Message::text(text)))
            .await
            .map_err(stalled)??;
    }
    timeout(limit, sink.close()).await.map_err(stalled)??;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio_tungstenite::WebSocketStream;
    use tokio_tungstenite::tungstenite::protocol::Role;

    #[tokio::test]
    async fn gives_up_on_a_phone_that_stops_reading() {
        // A tiny pipe whose far end is never read, like a phone gone from the network.
        let (ours, _phone) = tokio::io::duplex(64);
        let mut sink = WebSocketStream::from_raw_socket(ours, Role::Server, None).await;
        let (tx, mut rx) = mpsc::channel(16);
        for _ in 0..16 {
            tx.send(ServerMessage::Ping).await.unwrap();
        }
        let limit = Duration::from_millis(100);
        let result = timeout(Duration::from_secs(5), write(&mut sink, &mut rx, limit))
            .await
            .expect("the writer gave up by itself");
        assert!(result.unwrap_err().to_string().contains("stopped taking"));
    }
}
