//! Whether GStreamer answers at all. After GStreamer or a graphics driver is
//! updated, GStreamer looks over its plugins again the next time it runs, and
//! a plugin that hangs while loading (often one that talks to a graphics
//! driver) leaves every GStreamer command waiting for ever.

use std::fs::{self, File};
use std::io::ErrorKind;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

/// How GStreamer is doing on this PC.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Health {
    /// It answered.
    Answers,
    /// `gst-inspect-1.0` is not installed, or would not run.
    Missing,
    /// It did not answer in time. `plugin` is the file of the plugin it was
    /// loading when the wait ran out, when its log says.
    Stuck { plugin: Option<String> },
}

/// Asks GStreamer for its version, waiting at most `wait`.
///
/// A GStreamer that does not answer is left running: it may only be slow,
/// looking over its plugins for the first time after an update, and
/// stopping it would lose that work.
pub fn health(wait: Duration) -> Health {
    // Its log goes to a file rather than a pipe, so a scan left running
    // can still write to it after this returns.
    let log_path = std::env::temp_dir().join(format!("yaedock-gst-{}.log", std::process::id()));
    let Ok(log) = File::create(&log_path) else {
        return Health::Missing;
    };
    let spawned = Command::new("gst-inspect-1.0")
        .arg("--version")
        .env("GST_DEBUG", "GST_PLUGIN_LOADING:5")
        .env("GST_DEBUG_NO_COLOR", "1")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(log)
        .spawn();
    let health = match spawned {
        Err(err) if err.kind() == ErrorKind::NotFound => Health::Missing,
        Err(_) => Health::Missing,
        Ok(mut child) => {
            let start = Instant::now();
            loop {
                match child.try_wait() {
                    Ok(Some(status)) if status.success() => break Health::Answers,
                    Ok(Some(_)) | Err(_) => break Health::Missing,
                    Ok(None) if start.elapsed() >= wait => {
                        let log = fs::read_to_string(&log_path).unwrap_or_default();
                        break Health::Stuck {
                            plugin: stuck_plugin(&log),
                        };
                    }
                    Ok(None) => std::thread::sleep(Duration::from_millis(50)),
                }
            }
        }
    };
    let _ = fs::remove_file(&log_path);
    health
}

/// The file name of the plugin GStreamer began loading last and did not
/// finish, from its `GST_PLUGIN_LOADING` log.
fn stuck_plugin(log: &str) -> Option<String> {
    let mut loading: Option<&str> = None;
    for line in log.lines() {
        if let Some(path) = quoted_after(line, "attempt to load plugin \"") {
            loading = Some(path);
        } else if let Some(path) = quoted_after(line, "plugin \"")
            && line.contains("\" loaded")
            && loading == Some(path)
        {
            loading = None;
        }
    }
    loading.map(|path| path.rsplit('/').next().unwrap_or(path).to_string())
}

/// What follows `start` in `line`, up to the next quote.
fn quoted_after<'a>(line: &'a str, start: &str) -> Option<&'a str> {
    let rest = &line[line.find(start)? + start.len()..];
    Some(&rest[..rest.find('"')?])
}

#[cfg(test)]
mod tests {
    use super::*;

    const LOG: &str = "\
0:00:00.01 1 INFO GST_PLUGIN_LOADING gstplugin.c:880:_priv_gst_plugin_load_file_for_registry: attempt to load plugin \"/usr/lib/gstreamer-1.0/libgstmxf.so\"
0:00:00.02 1 INFO GST_PLUGIN_LOADING gstplugin.c:999:_priv_gst_plugin_load_file_for_registry: plugin \"/usr/lib/gstreamer-1.0/libgstmxf.so\" loaded
0:00:00.03 1 INFO GST_PLUGIN_LOADING gstplugin.c:880:_priv_gst_plugin_load_file_for_registry: attempt to load plugin \"/usr/lib/gstreamer-1.0/libgstnvcodec.so\"
";

    #[test]
    fn names_the_plugin_that_never_finished_loading() {
        assert_eq!(stuck_plugin(LOG).as_deref(), Some("libgstnvcodec.so"));
    }

    #[test]
    fn names_none_when_every_plugin_loaded() {
        let finished = format!(
            "{LOG}0:00:00.04 1 INFO GST_PLUGIN_LOADING gstplugin.c:999:x: plugin \"/usr/lib/gstreamer-1.0/libgstnvcodec.so\" loaded\n"
        );
        assert_eq!(stuck_plugin(&finished), None);
        assert_eq!(stuck_plugin(""), None);
    }
}
