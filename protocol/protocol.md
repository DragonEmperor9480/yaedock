# YaeDock protocol

Version 1.

The phone and PC exchange JSON messages over a TLS WebSocket (default port 9481), one message per text frame. Bluetooth RFCOMM (phase 3) carries the same messages as length-prefixed frames. The mirrored screen's video is the one thing outside JSON: it travels in binary frames, described under `start_screen`.

Every message is a JSON object with a `type` field. Fields marked optional are left out when they have no value. The Rust definitions live in `daemon/yaedock-proto`; any change here must update that crate and the Android serialization in the same commit.

## Units

- Times: `ts` is Unix time in milliseconds; `uptime` is seconds.
- Memory and throughput: bytes, and bytes per second. The phone formats them for display.
- CPU: percent, 0 to 100. Temperatures: degrees Celsius.
- Brightness: percent, 0 to 100. Volume: percent, 0 to 150.

## Pairing QR code

`yaedock pair` shows a QR code holding a URI:

```
yaedock://pair?host=192.168.1.20&port=9481&fp=0f8b3585...9353&code=482913
```

- `host`, `port`: where to connect.
- `fp`: lowercase hex SHA-256 of the daemon's TLS certificate (DER). The phone accepts only this certificate, on this and every later connection.
- `code`: the one-time 6-digit pairing code. It expires after 5 minutes, works once, and is locked after 5 wrong attempts.

## Connection flow

1. The phone connects and sends `hello`.
   - First connection: `hello` carries the 6-digit `code` from the pairing QR code. The daemon replies with `welcome` containing a long-lived `token`, which the phone stores.
   - Later connections: `hello` carries the stored `token` and no code.
2. If the token or code is wrong, or the version is unsupported, the daemon sends `error` (`unauthorized` or `unsupported_version`) and closes the connection.
3. After `welcome`, the daemon sends `stats` every tick (default 1 s, all groups) until the phone sends `subscribe`.
4. Either side sends `ping` after 10 s without sending anything; the other answers `pong`. A link with no traffic for 30 s is dropped.

## Messages

| Direction | Type | Fields |
| --- | --- | --- |
| Phone → PC | `hello` | `version`, `device_id`, `device_name`, `token` (optional), `code` (optional) |
| PC → Phone | `welcome` | `version`, `hostname`, `os`, `cpu_model`, `cores`, `sensors`, `token` (optional), `state`, `gpus` (optional), `screenshot` (optional), `recorder` (optional), `screen` (optional), `system_actions` (optional), `daemon_version` (optional), `cannot_update` (optional) |
| PC → Phone | `stats` | `ts`, `uptime`, `cpu`, `mem`, `temps`, `net`, `disk`, `gpus`, `game`, `battery` (groups optional) |
| PC → Phone | `state` | `displays`, `volume`, `mic`, `outputs`, `apps`, `media`, `power`, `shortcuts`, `recording`, `overlay` (all but `displays` optional) |
| Phone → PC | `subscribe` | `groups`, `interval_ms` |
| Phone → PC | `set_brightness` | `display`, `percent` |
| Phone → PC | `set_volume` | `percent` (optional), `muted` (optional) |
| Phone → PC | `set_mic` | `percent` (optional), `muted` (optional) |
| Phone → PC | `set_output` | `output` |
| Phone → PC | `set_app_volume` | `app`, `percent` (optional), `muted` (optional) |
| Phone → PC | `media` | `action`, `player` (optional) |
| Phone → PC | `seek` | `position_ms`, `player` (optional) |
| Phone → PC | `set_power_profile` | `profile` |
| Phone → PC | `set_overlay` | `visible` |
| Phone → PC | `take_screenshot` | none |
| PC → Phone | `screenshot` | `path` |
| Phone → PC | `start_recording` | none |
| Phone → PC | `stop_recording` | `close_app` (optional) |
| PC → Phone | `recording_saved` | `path` |
| Phone → PC | `start_screen` | none |
| Phone → PC | `stop_screen` | none |
| PC → Phone | `screen` | `state`, `width` (optional), `height` (optional), `message` (optional) |
| PC → Phone | binary frame | the mirrored screen's video, see `start_screen` |
| PC → Phone | `media_art` | `id`, `mime`, `data` |
| Phone → PC | `system_action` | `action` |
| Phone → PC | `run_shortcut` | `id` |
| Phone → PC | `run_check` | none |
| PC → Phone | `check_report` | `checks` |
| Phone → PC | `update_pc` | `version` |
| PC → Phone | `update_progress` | `stage`, `percent` (optional) |
| Both | `ping` / `pong` | none |
| PC → Phone | `error` | `code`, `message` |

### `hello`

```json
{"type":"hello","version":1,"device_id":"5f2c9a","device_name":"Pixel 6","code":"482913"}
```

### `welcome`

`sensors` lists the keys used in `stats.temps`. `state` has the same shape as a `state` message without `type`; its `displays` list gives the ids for `set_brightness`. `volume` is missing when no volume backend is available. `gpus` lists the graphics cards by `id` and `name`; it is left out when there are none (or the daemon predates GPU support). `system_actions` lists what `system_action` may do on this PC (any of `lock`, `suspend`, `reboot`, `power_off`), as logind allows the user; it is left out when empty. `screenshot: true` means `take_screenshot` works, and `recorder: true` means `start_recording` works (OBS Studio is installed and its WebSocket server is on, though OBS may be closed); each is left out otherwise. `screen: true` means `start_screen` works: GStreamer with its PipeWire plugin and an H.264 encoder is installed. `daemon_version` is the YaeDock version running on the PC, such as `0.1.0`; daemons older than updates leave it out and cannot `update_pc`. `cannot_update` says why `update_pc` cannot work on this PC, such as a program installed by a package manager, or a PC there is no release build for; it is left out when it can.

```json
{"type":"welcome","version":1,"hostname":"laptop","os":"CachyOS","cpu_model":"AMD Ryzen 7 7840HS","cores":16,
 "sensors":["cpu_package","nvme"],"token":"q8Zr...","state":{"displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false}},
 "gpus":[{"id":"pci:0000:00:02.0","name":"Intel UHD Graphics 630"},{"id":"pci:0000:01:00.0","name":"NVIDIA GeForce GTX 1050 Mobile"}]}
```

### `stats`

Groups the phone has not subscribed to are left out. `net` and `disk` are summed over all interfaces and disks.

`gpus` has one entry per GPU from the welcome, matched by `id`. Every reading is optional, because GPUs and drivers report different things: `usage` (percent), `temp` (°C), `vram_used` and `vram_total` (bytes; integrated GPUs share system RAM and leave them out), `clock_mhz`. `sleeping: true` means the GPU is powered down to save energy; the daemon does not wake it up to read it.

`game` is present only while a game is running and its frame rate is known: `name` (the program name), `fps`, `frametime` in milliseconds, and the lows over the last minute of frames: `low_1`, the frame rate the slowest 1% of frames stay under, and `low_0_1` for the slowest 0.1%. Each low is left out until the minute holds enough frames for it. The daemon reads these from MangoHud's log, so the game has to run with MangoHud and logging turned on (`yaedock fps on`).

`battery` is present only on PCs with a battery (all batteries together): `percent` (0 to 100), `state` (`charging`, `discharging`, `full`, `not_charging` when plugged in but held below full, or `unknown`), and, when known, `seconds_left` (until empty while discharging, until full while charging), `power_w` and `health` (full capacity in percent of the capacity when new).

```json
{"type":"stats","ts":1760838600000,"uptime":3600,
 "cpu":{"total":23.4,"cores":[18.0,31.2,22.5,21.9]},
 "mem":{"used":6549000000,"total":16642000000},
 "temps":{"cpu_package":54.0,"nvme":41.0},
 "net":{"rx":120400,"tx":8800},
 "disk":{"read":0,"write":524288},
 "gpus":[{"id":"pci:0000:00:02.0","usage":12.0,"clock_mhz":350},{"id":"pci:0000:01:00.0","usage":31.0,"temp":45.0,"vram_used":389021696,"vram_total":4294967296,"clock_mhz":1392}],
 "game":{"name":"eldenring.exe","fps":59.7,"frametime":16.7,"low_1":48.2},
 "battery":{"percent":64.0,"state":"discharging","seconds_left":9120,"power_w":11.3,"health":91.5}}
```

### `state`

Sent to every connected phone after any change, whether it came from a phone or from the PC itself. What the PC cannot control is left out.

- `volume`: the default audio output. `mic`: the default microphone, with the same fields.
- `outputs`: the audio outputs to choose from, each with an `id` (the audio server's name for it), a `name`, the `port` it plays through when known (such as "Headphones"), and `default: true` on the one playing now. `volume` is always the default output's.
- `apps`: programs playing sound right now, each with its own `id` (the audio server's stream index), `name`, `percent` and `muted`. Left out when nothing plays.
- `media`: the media player to control (MPRIS): the one playing, or else the last one that played. `player` identifies it in `media` commands, `app` is its own name; `title` and `artist` are left out when unknown. `art` names the cover of what plays, which arrives in a `media_art` message; it is left out when the player gives none. `length_ms` and `position_ms` are the track's length and how far it has played, left out when unknown; the daemon sends a new position only when it jumps (a seek, a new track, a pause), so while `playing` the phone advances it by itself. `playing`, `can_play_pause`, `can_next`, `can_previous` and `can_seek` are booleans; `can_seek` is true only when the length is known. When KDE's browser integration mirrors a browser's media (`plasma-browser-integration`), the daemon picks the mirrored player, which has the page's own title and cover, and leaves out the browser's own copy.
- `shortcuts`: the shortcut buttons set up on the PC (`yaedock shortcuts add`), each with an `id` and a `name`. The commands they run stay on the PC.
- `recording`: the screen recorder (OBS Studio), present while OBS is running: `active`, `paused` (left out when false) and `duration_ms`, the length so far as OBS measures it. While recording, the daemon sends a `state` every second as the length grows.
- `power`: the power profile (power-profiles-daemon or tuned-ppd): the `active` profile and the `available` ones, such as `power-saver`, `balanced` and `performance`.
- `overlay`: whether MangoHud's overlay shows on the PC, present while game frame rates are on (`yaedock fps on`).

```json
{"type":"state","displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false},
 "mic":{"percent":100,"muted":true},
 "outputs":[{"id":"alsa_output.pci-0000_00_1f.3.analog-stereo","name":"Built-in Audio Analog Stereo","port":"Headphones","default":true}],
 "apps":[{"id":551,"name":"Firefox","percent":100,"muted":false}],
 "media":{"player":"spotify","app":"Spotify","title":"Song","artist":"Band","art":"9f2c1a7e44b0d6e1","length_ms":214000,"position_ms":61500,"playing":true,"can_play_pause":true,"can_next":true,"can_previous":true,"can_seek":true},
 "power":{"active":"balanced","available":["power-saver","balanced","performance"]}}
```

### `media_art`

The cover named by `state.media.art`: a JPEG of at most 256 pixels on each side, base64-encoded in `data`, with `mime` saying its type (`image/jpeg` for now). The daemon sends each phone a cover once when it first shows, after `welcome` if one is showing then, and again after a reconnect. `id` is the same as `state.media.art`; the same cover keeps its id, so a phone can keep the covers it has. A cover the PC cannot read is never sent, and the phone shows no cover for it.

The daemon reads covers from the player's `mpris:artUrl`: a local file, a `data:` URL, or a web address (as Spotify gives), downloaded with a 5 second limit. The phone never sees the address.

```json
{"type":"media_art","id":"9f2c1a7e44b0d6e1","mime":"image/jpeg","data":"/9j/4AAQSkZJRgABAQAAAQABAAD..."}
```

### `subscribe`

`groups` is any of `cpu`, `mem`, `temps`, `net`, `disk`, `gpu`, `game`, `battery`. `interval_ms` is rounded up to a multiple of the daemon's sampling tick, so a phone can ask for fewer updates but not faster sampling. A phone in the background should subscribe to no groups and a long interval: stats then carry only `ts` and `uptime`, and the daemon reads the GPUs and the game's frame rate only while some phone subscribes to `gpu` or `game`. Every phone starts subscribed to every group, every tick.

```json
{"type":"subscribe","groups":["cpu","mem","temps"],"interval_ms":2000}
```

### `set_brightness`

```json
{"type":"set_brightness","display":"backlight:amdgpu_bl1","percent":75}
```

### `set_volume`

Either field may be left out to keep its current value.

```json
{"type":"set_volume","percent":50}
{"type":"set_volume","muted":true}
```

### `set_output`

Makes another output the default, so sound plays there; `output` is an `id` from `state.outputs`. Programs playing now move along with it.

```json
{"type":"set_output","output":"bluez_output.AA_BB_CC_DD_EE_FF.1"}
```

### `set_mic` and `set_app_volume`

Like `set_volume`, for the default microphone and for one program's stream (`app` is an `id` from `state.apps`).

```json
{"type":"set_mic","muted":true}
{"type":"set_app_volume","app":551,"percent":40}
```

### `media`

`action` is `play_pause`, `next` or `previous`. `player` is `state.media.player`; without it the daemon uses the player the state shows.

```json
{"type":"media","action":"next"}
```

### `seek`

Moves what plays to `position_ms` from the start of the track, clamped to its length. `player` is as in `media`. Players without `can_seek` answer with an `error`.

```json
{"type":"seek","position_ms":90000}
```

### `set_power_profile`

`profile` is one of `state.power.available`.

```json
{"type":"set_power_profile","profile":"performance"}
```

### `set_overlay`

Shows or hides MangoHud's overlay on the PC, in running games too. A hidden overlay is drawn fully transparent, so `stats.game` keeps arriving. Answered with `error` while game frame rates are off.

```json
{"type":"set_overlay","visible":false}
```

### `take_screenshot` and `screenshot`

The PC saves a screenshot of all screens without any sound, flash or notification on the PC, and answers with `screenshot` giving where it was saved, or with `error`. It needs Spectacle, which is fully silent on KDE Plasma and on X11.

```json
{"type":"take_screenshot"}
{"type":"screenshot","path":"/home/me/Pictures/Screenshots/Screenshot_20261019_180211.png"}
```

### `start_recording`, `stop_recording` and `recording_saved`

Screen recording through OBS Studio, with OBS's own scene, quality and output settings. `start_recording` opens OBS (minimized to the tray) if it is closed. `stop_recording` is answered with `recording_saved`, giving where OBS saved the video. With `close_app: true` the daemon also closes OBS once the video is saved, unless OBS is busy with something else (streaming, its virtual camera or its replay buffer). Failures are answered with `error`. Recordings started or stopped in OBS itself show up in `state.recording`.

```json
{"type":"start_recording"}
{"type":"stop_recording","close_app":true}
{"type":"recording_saved","path":"/home/me/Videos/2026-10-22 18-30-12.mkv"}
```

### `start_screen`, `stop_screen`, `screen` and video frames

Mirrors one of the PC's monitors to the phone. `start_screen` is answered with `screen` messages as things change:

- `state: "asking"`: the desktop is asking the user at the PC which monitor to share. Once allowed, the desktop may share the same monitor again without asking.
- `state: "on"`: video frames follow. `width` and `height` give the picture's size in pixels, when the desktop says.
- `state: "off"`: mirroring ended. `message` says why when it was not the phone's `stop_screen`: sharing was refused or not answered within 2 minutes, something is missing on the PC, or the share ended there.

`stop_screen` ends it, and so does the phone disconnecting. A `start_screen` while one runs changes nothing.

```json
{"type":"start_screen"}
{"type":"screen","state":"asking"}
{"type":"screen","state":"on","width":1920,"height":1080}
{"type":"stop_screen"}
{"type":"screen","state":"off"}
```

Each picture arrives as one WebSocket binary frame:

| Bytes | Meaning |
| --- | --- |
| 0 | Kind: `1`, screen video. Frames of another kind are ignored. |
| 1 | Flags. Bit 0: a keyframe. |
| 2 to 9 | When the picture was captured, in microseconds on the encoder's clock, as a big-endian unsigned integer. |
| 10 on | The picture: one H.264 access unit in Annex B form (NAL units after `00 00 00 01` start codes), without B-frames. |

A keyframe needs no earlier picture and carries the stream's settings (SPS and PPS), so decoding starts at one; the first frame after `on` is a keyframe, and one follows every 120 frames (two seconds at the usual 60 frames a second). A phone or network too slow for the stream misses frames rather than falling behind: the daemon then sends nothing until the next keyframe.

### `run_shortcut`

Runs one of the PC's shortcuts; `id` is an `id` from `state.shortcuts`. The phone can only name a shortcut, never send a command, so it cannot run anything the user did not set up on the PC. The command starts in the background; there is no reply unless it cannot be started, which is answered with `error`.

```json
{"type":"run_shortcut","id":"steam"}
```

### `system_action`

Locks the screen, puts the PC to sleep, restarts it or shuts it down, through logind (no root needed). `action` is one of `welcome.system_actions`. There is no reply on success, since the PC locks or goes away; a failure (for example an app blocking shutdown) is answered with `error`. The phone asks for confirmation before `reboot` and `power_off`.

```json
{"type":"system_action","action":"suspend"}
```

### `run_check` and `check_report`

Runs the system check, the same one as `yaedock check` on the PC, so the phone can show why a feature is missing and how to fix it. It takes about a second and answers with `check_report`, or with `error` if the check could not run (including while one is already running). It only looks at the PC and never changes anything.

Each entry of `checks` has an `area` (what was checked, such as `Brightness` or `Game FPS`), an `outcome` (`ok`, `warn` for working with a limitation, or `fail`), a one-line `summary`, and for a `warn` or `fail` usually a `fix` saying what to do. `command` is a command to run on the PC that fixes it; it may need root, so the phone only shows it. The list and the area names may change between versions; the phone shows them as they come.

A daemon older than this message answers `run_check` with `bad_request`.

```json
{"type":"run_check"}
{"type":"check_report","checks":[
  {"area":"Brightness","outcome":"ok","summary":"Built-in display at 75%, changed through logind"},
  {"area":"Screenshot","outcome":"warn","summary":"Off; silent screenshots need Spectacle","fix":"Install Spectacle (the `spectacle` package) to take screenshots from the phone. It is fully silent on KDE Plasma and on X11."}
]}
```

### `update_pc` and `update_progress`

Updates the PC to the release `version`, which the phone found on GitHub Releases. The daemon checks the release list itself and refuses (with `error`) a version that is not the newest release or not newer than its own, and refuses while an update is already running. It downloads the release's `yaedock`, checks it against the release's `SHA256SUMS`, makes sure it starts, and replaces the program it runs from.

`update_progress` reports each `stage`: `downloading` (with `percent` when the size is known; not every percent is sent), `verifying`, `installing`, and `restarting`. After `restarting` the background service restarts on the new version, which closes the connection; the phone reconnects as usual and its new `welcome` carries the new `daemon_version`. A daemon started by hand in a terminal is not restarted: it answers with an `error` saying the update is installed and to restart it.

A daemon older than this message answers `update_pc` with `bad_request`.

```json
{"type":"update_pc","version":"0.2.0"}
{"type":"update_progress","stage":"downloading","percent":42}
{"type":"update_progress","stage":"restarting"}
```

The daemon answers the `set_*` and `media` commands with a `state` broadcast, or with `error` if the change failed.

### `error`

| Code | Meaning |
| --- | --- |
| `bad_request` | The message could not be parsed or was not expected at this point |
| `unauthorized` | The token or pairing code was missing or wrong |
| `unsupported_version` | The daemon does not speak the phone's protocol version |
| `unsupported_display` | `set_brightness` named a display the daemon does not know |
| `backend_failed` | A brightness, volume, media or power backend failed to apply the change, or the PC has none |

```json
{"type":"error","code":"unsupported_display","message":"no display with id hdmi-1"}
```

JSON Schema files for each message will live next to this file.
