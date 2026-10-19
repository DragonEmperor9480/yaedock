mod sampler;

use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;
use yaedock_proto::ServerMessage;

#[derive(Parser)]
#[command(version, about = "Stream PC stats to the YaeDock phone app")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Print a `stats` message every tick, as the phone would receive it
    Stats {
        /// Stop after this many messages
        #[arg(short = 'n', long)]
        count: Option<u64>,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env())
        .with_writer(std::io::stderr)
        .init();

    match Cli::parse().command {
        Command::Stats { count } => print_stats(count).await,
    }
}

async fn print_stats(count: Option<u64>) -> anyhow::Result<()> {
    let (sources, _) = sampler::default_sources();
    let mut snapshots = sampler::spawn(sources, sampler::DEFAULT_TICK);
    let mut printed = 0;
    while count.is_none_or(|count| printed < count) {
        snapshots.changed().await?;
        let snapshot = snapshots.borrow_and_update().as_ref().clone();
        println!("{}", serde_json::to_string(&ServerMessage::Stats(snapshot))?);
        printed += 1;
    }
    Ok(())
}
