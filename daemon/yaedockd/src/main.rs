mod sampler;

use anyhow::bail;
use clap::{Parser, Subcommand};
use tracing_subscriber::EnvFilter;
use yaedock_proto::ServerMessage;
use yaedock_sys::brightness::{BacklightBackend, BrightnessBackend};
use yaedock_sys::volume;

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
    /// Show display brightness, or set it
    Brightness {
        /// New brightness in percent, 0 to 100
        #[arg(value_parser = clap::value_parser!(u8).range(0..=100))]
        percent: Option<u8>,
        /// Display id; needed only when there is more than one display
        #[arg(short, long)]
        display: Option<String>,
    },
    /// Show the default output's volume, or change it
    Volume {
        /// New volume in percent, 0 to 150
        #[arg(value_parser = clap::value_parser!(u8).range(0..=volume::MAX_PERCENT as i64))]
        percent: Option<u8>,
        /// Mute the output
        #[arg(long, conflicts_with = "unmute")]
        mute: bool,
        /// Unmute the output
        #[arg(long)]
        unmute: bool,
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
        Command::Brightness { percent, display } => brightness(percent, display),
        Command::Volume {
            percent,
            mute,
            unmute,
        } => set_volume(percent, (mute || unmute).then_some(mute)),
    }
}

async fn print_stats(count: Option<u64>) -> anyhow::Result<()> {
    let (sources, _) = sampler::default_sources();
    let mut snapshots = sampler::spawn(sources, sampler::DEFAULT_TICK);
    let mut printed = 0;
    while count.is_none_or(|count| printed < count) {
        snapshots.changed().await?;
        let snapshot = snapshots.borrow_and_update().as_ref().clone();
        println!(
            "{}",
            serde_json::to_string(&ServerMessage::Stats(snapshot))?
        );
        printed += 1;
    }
    Ok(())
}

fn brightness(percent: Option<u8>, display: Option<String>) -> anyhow::Result<()> {
    let backend = BacklightBackend::new();
    if let Some(percent) = percent {
        let display = match display {
            Some(display) => display,
            None => match backend.displays()?.as_slice() {
                [only] => only.id.clone(),
                [] => bail!("no display with adjustable brightness found"),
                _ => bail!("more than one display; choose one with --display"),
            },
        };
        backend.set(&display, percent)?;
    }
    for display in backend.displays()? {
        println!("{}\t{}\t{}%", display.id, display.name, display.percent);
    }
    Ok(())
}

fn set_volume(percent: Option<u8>, muted: Option<bool>) -> anyhow::Result<()> {
    let Some(backend) = volume::detect() else {
        bail!("no volume control found; is PipeWire (wpctl) or PulseAudio (pactl) running?");
    };
    backend.set(percent, muted)?;
    let state = backend.get()?;
    let muted = if state.muted { " (muted)" } else { "" };
    println!("{}%{muted}", state.percent);
    Ok(())
}
