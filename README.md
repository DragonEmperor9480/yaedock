# YaeDock

YaeDock turns an Android phone into a live stats panel and control dock for a Linux PC. A small Rust daemon (`yaedockd`) streams CPU, memory, temperature, network and disk stats to the phone, and the phone sends back brightness and volume changes.

> **Status:** early development (phase 1, Wi-Fi MVP). Nothing is usable yet.

## Features (planned for the MVP)

- Live CPU (total and per core), RAM, temperatures, network and disk I/O, updated every second
- Brightness slider for the laptop backlight
- Volume slider and mute (PipeWire or PulseAudio)
- Two-way sync: changes made on the PC move the phone's sliders
- Discovery over mDNS and one-time pairing by QR code
- Encrypted connection (TLS with a pinned certificate)
- Automatic reconnect after Wi-Fi drops or the PC sleeps

Later: a full-screen dock mode, Bluetooth transport, and media and multi-monitor controls.

## Requirements

- **PC:** any Linux distribution with systemd. The daemon runs as a systemd user service and never needs root.
- **Phone:** Android 8.0 (API 26) or newer, on the same local network as the PC.

## Repository layout

```
daemon/            Rust workspace
  yaedockd/        the daemon (`yaedockd run`), normally started by systemd
  yaedock/         the tool for pairing phones and managing the daemon
  yaedock-core/    code shared by both
  yaedock-proto/   protocol message types
  yaedock-sys/     metric sources and brightness/volume backends
android/           Android app (Kotlin, Jetpack Compose)
protocol/          protocol description and message schemas
packaging/         systemd user unit and udev rule
```

## Building

Daemon:

```sh
cd daemon
cargo build --release
```

Android app: open `android/` in Android Studio, or build with Gradle (needs JDK 17+ and an Android SDK with platform 37; point `sdk.dir` in `android/local.properties` at the SDK if `ANDROID_HOME` is not set to it):

```sh
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## License

YaeDock is free software, licensed under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE).
