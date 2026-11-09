//! The cover of what plays: loaded once per cover, for every session to send.

use std::sync::Arc;

use base64::Engine;
use tokio::sync::watch;
use tracing::debug;
use yaedock_proto::{MediaArt, State};
use yaedock_sys::art;
use yaedock_sys::media::MediaBackend;

/// The cover loaded last, or `None` before the first.
pub type Latest = watch::Receiver<Option<Arc<MediaArt>>>;

/// Watches `states` and loads the cover each time `media.art` names a new
/// one. A cover that cannot be read is skipped; the phone keeps its music
/// note. Covers load one at a time, so a burst of track changes waits.
pub fn spawn(
    mut states: watch::Receiver<Arc<State>>,
    media: Option<Arc<dyn MediaBackend>>,
) -> Latest {
    let (tx, rx) = watch::channel(None);
    tokio::spawn(async move {
        let Some(media) = media else {
            // No covers, but the channel stays open for the sessions.
            let _keep = tx;
            return std::future::pending().await;
        };
        let mut tried: Option<String> = None;
        loop {
            let wanted = states
                .borrow_and_update()
                .media
                .as_ref()
                .and_then(|m| m.art.clone());
            if wanted.is_some() && wanted != tried {
                tried = wanted.clone();
                let id = wanted.unwrap_or_default();
                if let Some(url) = media.art_url(&id) {
                    match tokio::task::spawn_blocking(move || art::thumbnail(&url)).await {
                        Ok(Ok(jpeg)) => {
                            debug!(art = %id, bytes = jpeg.len(), "loaded a cover");
                            let _ = tx.send(Some(Arc::new(MediaArt {
                                id,
                                mime: "image/jpeg".into(),
                                data: base64::engine::general_purpose::STANDARD.encode(jpeg),
                            })));
                        }
                        Ok(Err(err)) => debug!(art = %id, "cannot load the cover: {err}"),
                        Err(err) => debug!("cover task failed: {err}"),
                    }
                }
                // The state may have moved on while this one loaded.
                continue;
            }
            if states.changed().await.is_err() {
                return;
            }
        }
    });
    rx
}

/// What a session still has to send: `latest` when it is the cover the phone
/// is shown (`wanted`) and the phone does not have it yet (`sent`).
pub fn due(latest: &Latest, wanted: Option<&str>, sent: Option<&str>) -> Option<Arc<MediaArt>> {
    let art = latest.borrow().clone()?;
    (Some(art.id.as_str()) == wanted && sent != wanted).then_some(art)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sends_the_current_cover_once() {
        let (tx, rx) = watch::channel(None);
        assert!(due(&rx, Some("a"), None).is_none());
        tx.send(Some(Arc::new(MediaArt {
            id: "a".into(),
            mime: "image/jpeg".into(),
            data: String::new(),
        })))
        .unwrap();
        assert!(due(&rx, Some("a"), None).is_some());
        assert!(due(&rx, Some("a"), Some("a")).is_none());
        // A cover for an older track is not sent.
        assert!(due(&rx, Some("b"), None).is_none());
        assert!(due(&rx, None, None).is_none());
    }
}
