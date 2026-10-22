# YaeDock

YaeDock turns an Android phone into a live stats panel and control dock for a Linux PC. A small Rust program (`yaedock`) runs a daemon that streams CPU, memory, temperature, network and disk stats to the phone, and the phone sends back brightness and volume changes.

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
  yaedockd/        the daemon, as a library
  yaedock/         the one program: menu, pairing, service control, and the daemon (`yaedock daemon`)
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

## Install

YaeDock for the PC is one program. Build it (or download it), then:

```sh
chmod +x yaedock
./yaedock --install        # installs to ~/.local/bin and opens the setup guide
```

The setup guide checks what works on your PC, starts the daemon in the background (also at every login), and shows the QR code to pair your phone. `yaedock --uninstall` removes it again.

## Usage

```sh
yaedock                    # interactive menu: status, pairing, paired phones, start/stop
yaedock setup              # the setup guide again
yaedock service enable     # run the daemon in the background, now and at every login
yaedock pair               # pair a phone: scan the QR code with the YaeDock app
yaedock status             # is the daemon running, which phones are connected
yaedock check              # which features work on this PC, and how to fix the rest
yaedock devices            # list paired phones (yaedock unpair ID to remove one)
yaedock shortcuts add "Steam" "steam"   # a button on the phone that runs a command here
yaedock fps on             # show game frame rates on the phone (through MangoHud)
yaedock obs on             # record the screen from the phone (through OBS Studio; close OBS first)
```

The daemon is part of the same program. It normally runs as a systemd user service; `yaedock daemon` runs it in the current terminal instead. Linked or copied under the name `yaedockd`, the program also accepts the traditional `yaedockd run`.

The phone connects to TCP port 9481 on the PC. If a firewall is active, allow it, for example `sudo ufw allow 9481/tcp`.

## License

YaeDock is free software, licensed under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE).
