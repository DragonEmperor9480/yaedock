# YaeDock

Turn an Android phone into a live stats panel and control dock for your Linux PC.

> Version 0.6.0: early, but working. Please report anything that breaks.

## Features

- **Stats:** CPU, each GPU, memory, temperatures, network, disk speed, how full each drive is, and battery
- **Games:** frame rate and 1% / 0.1% lows through MangoHud, and a play history: what you played, when, for how long and how smoothly (`yaedock play`)
- **Controls:** volume, per-app volume, mic, brightness, power profiles, lock, sleep and power off
- **Media:** what's playing on the PC with its cover and a seek bar, optionally in the phone's notification shade too
- **Capture:** silent screenshots (Spectacle) and screen recording (OBS Studio)
- **Shortcuts:** your own buttons that run commands on the PC
- **Dock mode:** landscape layout, a night mode for OLED screens, keeps the screen on while charging
- **Make it yours:** themes, app icons and 11 clock faces for the dock and the night screen, or your own clock as an SVG (start from [`clocks/example.svg`](clocks/example.svg))
- **Updates:** `yaedock update` on the PC; the app updates itself, and the PC too, from About
- **Private:** works only on your local network, encrypted, no accounts or cloud

## Requirements

- A Linux PC with systemd
- An Android 8.0+ phone on the same network

### Packages

Stats, brightness, volume and media controls work with nothing extra. These packages turn on the rest:

| For | Packages |
|---|---|
| The PC's screen on the phone, and the phone as a camera | GStreamer with its tools, base, good and bad plugins, its PipeWire plugin, and H.264 (VA-API for Intel and AMD graphics, libav, x264 or OpenH264) |
| The phone as a microphone or speaker | PipeWire's PulseAudio server and `pactl` |
| The phone's microphone, compressed | libopus |
| Switching power profiles | power-profiles-daemon, or tuned-ppd on Fedora |
| Silent screenshots | Spectacle |
| Recording the screen | OBS Studio |
| GPU names | hwdata |

Everything at once, on:

**Arch, CachyOS, Manjaro, EndeavourOS**
```sh
sudo pacman -S --needed gstreamer gst-plugins-base gst-plugins-good gst-plugins-bad gst-plugin-pipewire gst-plugin-va gst-libav gst-plugins-ugly pipewire-pulse libpulse opus power-profiles-daemon spectacle obs-studio hwdata
```

**Debian, Ubuntu, Linux Mint, Pop!_OS**
```sh
sudo apt-get install gstreamer1.0-tools gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-plugins-bad gstreamer1.0-pipewire gstreamer1.0-libav gstreamer1.0-plugins-ugly pipewire-pulse pulseaudio-utils libopus0 power-profiles-daemon kde-spectacle obs-studio hwdata
```

**Fedora**
```sh
sudo dnf install gstreamer1 gstreamer1-plugins-base gstreamer1-plugins-good gstreamer1-plugins-bad-free pipewire-gstreamer gstreamer1-plugin-openh264 pipewire-pulseaudio pulseaudio-utils opus tuned-ppd spectacle obs-studio hwdata
```

**openSUSE**
```sh
sudo zypper install gstreamer-utils gstreamer-plugins-base gstreamer-plugins-good gstreamer-plugins-bad gstreamer-plugin-pipewire gstreamer-plugin-openh264 pipewire-pulseaudio pulseaudio-utils libopus0 power-profiles-daemon spectacle obs-studio hwdata
```

Spectacle and OBS Studio are large; leave them out if you won't take screenshots or record from the phone. Most desktops already have most of these.

You don't need to work this out yourself: `yaedock check` says what your PC lacks and gives the command that installs just that, for your package manager. In the menu, pick the problem with ↑↓ and press `a` to run it.

## Install

On the PC, as yourself (not with sudo):

```sh
curl -fsSL https://github.com/DragonEmperor9480/yaedock/releases/latest/download/install.sh | sh
```

It downloads YaeDock for your processor (x86_64 or 64-bit ARM), checks it against the release's `SHA256SUMS`, and installs it in `~/.local/bin`. To install by hand, download `yaedock` (or `yaedock-aarch64` on ARM) from the [latest release](https://github.com/DragonEmperor9480/yaedock/releases/latest) and run it with `--install`; a program you built yourself installs the same way.

The setup guide checks your PC, starts YaeDock in the background and shows a QR code. Open the app on your phone and scan it. Done.

Get the app for your phone: [arm64](https://github.com/DragonEmperor9480/yaedock/releases/latest/download/yaedock-app-arm64.apk) fits almost every phone; there are also [arm32](https://github.com/DragonEmperor9480/yaedock/releases/latest/download/yaedock-app-arm32.apk) for older phones and [x86_64](https://github.com/DragonEmperor9480/yaedock/releases/latest/download/yaedock-app-x86_64.apk) for emulators and Chromebooks.

Run `yaedock` any time for the menu, or `yaedock check` to see what works on your PC and how to fix the rest.

To update, run `yaedock update` on the PC, or open About in the app and tap Check now: it updates the app, and can update the PC too. The app only checks GitHub when you ask, unless you turn on its daily check.

## Build

```sh
# PC
cd daemon && cargo build --release

# Phone (JDK 17+, Android SDK)
cd android && ./gradlew assembleDebug
```

## License

[GPL-3.0-or-later](LICENSE) · Made by [DragonEmperor9480](https://github.com/DragonEmperor9480)
