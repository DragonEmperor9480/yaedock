//! Play history: puts each game run with MangoHud into the history as it
//! finishes, whether a phone is connected or not, and answers phones' questions
//! about it.

use std::path::PathBuf;
use std::sync::Arc;
use std::time::{Duration, SystemTime};

use tokio::sync::broadcast;
use tracing::{info, warn};
use yaedock_core::play::{self, Store};
use yaedock_core::{mangohud, paths};
use yaedock_proto::{GameRenamed, PlayHistory, PlaySessionDetail};

/// How often the MangoHud folder is looked at for games that have quit.
const LOOK_EVERY: Duration = Duration::from_secs(15);

pub struct Play {
    store: Arc<Store>,
    folder: PathBuf,
    /// Every session hears of a rename, to tell its phone.
    pub renamed: broadcast::Sender<GameRenamed>,
}

impl Play {
    /// Starts recording; None if there is nowhere to keep the history.
    pub fn spawn() -> Option<Arc<Self>> {
        let (store, folder) = match (Store::open(), paths::mangohud_logs()) {
            (Ok(store), Ok(folder)) => (Arc::new(store), folder),
            (Err(err), _) | (_, Err(err)) => {
                warn!("play history unavailable: {err:#}");
                return None;
            }
        };
        let (renamed, _) = broadcast::channel(8);
        let play = Arc::new(Self {
            store,
            folder,
            renamed,
        });
        tokio::spawn(record(play.store.clone(), play.folder.clone()));
        Some(play)
    }

    /// Whether phones can see the history: games are logged by MangoHud.
    pub fn available(&self) -> bool {
        mangohud::state() == mangohud::State::On
    }

    pub async fn history(&self, from: u64, to: u64) -> PlayHistory {
        let (store, folder) = (self.store.clone(), self.folder.clone());
        let sessions = tokio::task::spawn_blocking(move || {
            play::history(&store, &folder, from, to, SystemTime::now())
        })
        .await
        .unwrap_or_default();
        PlayHistory { from, to, sessions }
    }

    pub async fn session(&self, id: String) -> Option<PlaySessionDetail> {
        let (store, folder) = (self.store.clone(), self.folder.clone());
        tokio::task::spawn_blocking(move || play::session(&store, &folder, &id, SystemTime::now()))
            .await
            .ok()
            .flatten()
    }

    /// Renames `program` and tells every phone.
    pub async fn rename(&self, program: String, name: String) -> anyhow::Result<()> {
        let store = self.store.clone();
        let renamed = tokio::task::spawn_blocking(move || {
            store
                .rename(&program, &name)
                .map(|name| GameRenamed { program, name })
        })
        .await??;
        info!(program = %renamed.program, name = %renamed.name, "renamed a game");
        let _ = self.renamed.send(renamed);
        Ok(())
    }
}

/// Every [`LOOK_EVERY`], records the games that have quit, including any
/// played while the daemon was not running.
async fn record(store: Arc<Store>, folder: PathBuf) {
    let mut every = tokio::time::interval(LOOK_EVERY);
    loop {
        every.tick().await;
        let (store, folder) = (store.clone(), folder.clone());
        let result = tokio::task::spawn_blocking(move || {
            play::record_finished(&folder, &store, SystemTime::now())
        })
        .await;
        match result {
            Ok(Ok(0)) => {}
            Ok(Ok(added)) => info!("added {added} game runs to the play history"),
            Ok(Err(err)) => warn!("could not record play history: {err:#}"),
            Err(err) => warn!("play history recorder failed: {err}"),
        }
    }
}
