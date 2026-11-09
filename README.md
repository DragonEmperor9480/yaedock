# YaeDock

Turn an Android phone into a live stats panel and control dock for your Linux PC.

> Version 0.2.0: early, but working. Please report anything that breaks.

## Features

- **Stats:** CPU, each GPU, memory, temperatures, network, disk and battery
- **Games:** frame rate and 1% / 0.1% lows through MangoHud
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

## Install

There are no prebuilt downloads yet; build from source (below). Then on the PC:

```sh
./yaedock --install
```

The setup guide checks your PC, starts YaeDock in the background and shows a QR code. Open the app on your phone and scan it. Done.

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
