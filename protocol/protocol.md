# YaeDock protocol

Version 1.

The phone and PC exchange JSON messages over a TLS WebSocket (default port 9481), one message per text frame. Bluetooth RFCOMM (phase 3) carries the same messages as length-prefixed frames. Three things travel outside JSON, in binary frames whose first byte says what they carry: the mirrored screen's video (kind `1`, PC to phone, described under `start_screen`), the phone microphone's sound (kind `2` as it is, or kind `4` as Opus, phone to PC, described under `start_phone_mic`) and the phone camera's video (kind `3`, phone to PC, described under `start_phone_camera`) and the PC's sound for the phone as a speaker (kind `5`, PC to phone, described under `start_phone_speaker`).

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

## Over a USB cable

A phone plugged into the PC can talk to it over the cable instead of Wi-Fi, through Android Open Accessory (AOA). The messages are the same; only the way they are carried changes.

**Making the link.** The PC is the USB host. It switches the phone into accessory mode with AOA's vendor control requests:
1. 51 asks for the AOA protocol version.
2. 52 sends the accessory's strings:
   - manufacturer `YaeDock`;
   - model `YaeDock PC`;
   - description: the PC's hostname;
   - version: the protocol version; while pairing, followed by a space and the pairing link (see below);
   - URI: the website;
   - serial: the PC's certificate fingerprint, as lowercase hex.
3. 53 starts accessory mode.

The phone then comes back on the bus as `18d1:2d00` (or `2d01` when USB debugging is on), and the two sides talk over its first interface's bulk endpoints. The app knows which paired PC it is plugged into from the serial, and connects over the cable to that PC.

**Frames.** The cable carries a plain stream of bytes, so each frame starts with a 5-byte header: 1 byte of kind, then the payload's length as a big-endian `u32`.

| Kind | Payload |
| --- | --- |
| `0` | one JSON message, UTF-8, the same as a WebSocket text message |
| `1` | one binary frame, the same as a WebSocket binary message (video or sound, described with each feature below) |
| `2` | nothing: the phone has opened the link afresh |

- **Kind `2` (start):** the phone sends it first every time it opens the accessory. The PC ends any session already running on that cable and starts a new one, which expects `hello` as usual. A frame of an unknown kind, or longer than 16 MiB, means the stream is out of step: the PC drops the link.
- **Which phones the PC switches.** Accessory mode turns the phone's file transfer off until it is unplugged, so the PC only switches paired phones:
  - **First time:** a phone connected over Wi-Fi sends `usb_plugged` when Android says it was just plugged into a computer. The PC switches the phone plugged into it, choosing by maker when there are several.
  - **After that:** once a cable session has authenticated, the PC remembers that phone's USB serial number, and switches it as soon as it is plugged in, with or without Wi-Fi. Unpairing the phone forgets it, and so does a phone whose `hello` is refused on the cable.
  - **Pairing:** while `yaedock pair` waits for a phone, the user has asked for a new one, so the PC switches any Android phone plugged in that it does not know, including one already plugged in when pairing starts. Its version string is then `<protocol version> yaedock://pair?host=…&port=…&fp=…&code=…`: the same link as the QR code. The app pairs with it as if the code had been scanned, over the cable, and so learns the PC's address on the network too. The pairing link's `fp` must match the serial.
  - **Only to a PC that says so:** a phone sends `usb_plugged` and `usb_off` only to a PC whose `welcome` has `usb_cable: true`; older PCs would answer them with `bad_request`.
  - **Turned off:** a phone whose user turned the cable off sends `usb_off`, over any link, once per connection and again when the setting changes. The PC forgets the phone's serial numbers, and if the phone is in accessory mode it resets it, which gives the phone back its file transfer.
  - **Not used:** if the phone sends no start frame within 15 seconds of the switch, because the app is closed or its cable setting is off and no Wi-Fi link can carry `usb_off`, the PC resets the phone, forgets its serial number, and leaves it alone until it is unplugged: gone from the bus for more than 3 seconds, so a change of USB mode on the phone, such as picking file transfer, does not count. The phone is then switched again only after a `usb_plugged`, as the first time. With the cable setting off, the app also stops Android from offering it the accessory, so Android asks nothing. While `yaedock pair` waits there is no time limit.
  - `usb_plugged` from a phone that is not plugged into this PC changes nothing.
- **No TLS on the cable,** since it is a physical link between the two. `hello` still has to carry the paired device's token (or a pairing code), exactly as over Wi-Fi.

## Messages

| Direction | Type | Fields |
| --- | --- | --- |
| Phone → PC | `hello` | `version`, `device_id`, `device_name`, `token` (optional), `code` (optional) |
| PC → Phone | `welcome` | `version`, `hostname`, `os`, `cpu_model`, `cores`, `sensors`, `token` (optional), `state`, `gpus` (optional), `screenshot` (optional), `recorder` (optional), `screen` (optional), `system_actions` (optional), `usb_cable` (optional), `input` (optional), `gamepad` (optional), `daemon_version` (optional), `cannot_update` (optional) |
| PC → Phone | `stats` | `ts`, `uptime`, `cpu`, `mem`, `temps`, `net`, `disk`, `drives`, `gpus`, `game`, `battery` (groups optional) |
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
| Phone → PC | `start_screen` | `mode` (optional), `width` (optional), `height` (optional) |
| Phone → PC | `stop_screen` | none |
| PC → Phone | `screen` | `state`, `width` (optional), `height` (optional), `message` (optional), `locked` (optional) |
| PC → Phone | binary frame | the mirrored screen's video, see `start_screen` |
| Phone → PC | `start_phone_mic` | none |
| Phone → PC | `stop_phone_mic` | none |
| PC → Phone | `phone_mic` | `state`, `message` (optional) |
| Phone → PC | binary frame | the phone microphone's sound, see `start_phone_mic` |
| Phone → PC | `start_phone_camera` | `rotation` (optional) |
| Phone → PC | `stop_phone_camera` | none |
| Phone → PC | `usb_plugged` | none |
| Phone → PC | `usb_off` | none |
| PC → Phone | `phone_camera` | `state`, `message` (optional) |
| PC → Phone | `phone_camera_seen` | `time` |
| Phone → PC | binary frame | the phone camera's video, see `start_phone_camera` |
| Phone → PC | `start_phone_speaker` | none |
| Phone → PC | `stop_phone_speaker` | none |
| PC → Phone | `phone_speaker` | `state`, `message` (optional) |
| PC → Phone | binary frame | the PC's sound for the phone as a speaker, see `start_phone_speaker` |
| Phone → PC | `pointer_move` | `dx`, `dy` |
| Phone → PC | `pointer_scroll` | `dx` (optional), `dy` (optional) |
| Phone → PC | `pointer_button` | `button`, `down` |
| Phone → PC | `key` | `key`, `down` |
| Phone → PC | `type_text` | `text` |
| Phone → PC | `gamepad` | `buttons` (optional), `lx`, `ly`, `rx`, `ry`, `lt`, `rt` (each optional) |
| Phone → PC | `gamepad_off` | none |
| PC → Phone | `gamepad_rumble` | `strong`, `weak`, `ms` (optional) |
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

`sensors` lists the keys used in `stats.temps`. `state` has the same shape as a `state` message without `type`; its `displays` list gives the ids for `set_brightness`. `volume` is missing when no volume backend is available. `gpus` lists the graphics cards by `id` and `name`; it is left out when there are none (or the daemon predates GPU support). `system_actions` lists what `system_action` may do on this PC (any of `lock`, `suspend`, `reboot`, `power_off`), as logind allows the user; it is left out when empty. `screenshot: true` means `take_screenshot` works, and `recorder: true` means `start_recording` works (OBS Studio is installed and its WebSocket server is on, though OBS may be closed); each is left out otherwise. `screen: true` means `start_screen` works: GStreamer with its PipeWire plugin and an H.264 encoder is installed. `screen_extend: true` means it can also `extend`: the desktop's screen-sharing portal can make a virtual display (KDE Plasma and GNOME on Wayland); it is left out otherwise. `phone_mic: true` means `start_phone_mic` works: the PC's sound server answers `pactl`, so it can make a virtual microphone; it is left out otherwise. `phone_mic_opus: true` means the PC also takes that sound as Opus, because it has `libopus`; it is left out otherwise. `phone_camera: true` means `start_phone_camera` works: GStreamer with its PipeWire plugin and an H.264 decoder is installed; it is left out otherwise. `phone_speaker: true` means `start_phone_speaker` works: the PC's sound server answers `pactl`, so it can make a virtual output; it is left out otherwise. `input: true` means the PC takes `pointer_move`, `pointer_scroll`, `pointer_button`, `key` and `type_text`: it can open `/dev/uinput` to make a keyboard and a mouse; it is left out otherwise. `gamepad: true` means the PC takes `gamepad` and `gamepad_off` the same way, making a game controller; it is left out otherwise. `daemon_version` is the YaeDock version running on the PC, such as `0.1.0`; daemons older than updates leave it out and cannot `update_pc`. `cannot_update` says why `update_pc` cannot work on this PC, such as a program installed by a package manager, or a PC there is no release build for; it is left out when it can. `usb_speed_mbps` is the speed the USB cable runs at, in megabits a second (`480` for USB 2.0; `5000`, `10000` or `20000` for USB 3), sent only on a connection over the cable and only when the PC can tell.

```json
{"type":"welcome","version":1,"hostname":"laptop","os":"CachyOS","cpu_model":"AMD Ryzen 7 7840HS","cores":16,
 "sensors":["cpu_package","nvme"],"token":"q8Zr...","state":{"displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false}},
 "gpus":[{"id":"pci:0000:00:02.0","name":"Intel UHD Graphics 630"},{"id":"pci:0000:01:00.0","name":"NVIDIA GeForce GTX 1050 Mobile"}]}
```

### `stats`

Groups the phone has not subscribed to are left out. `net` and `disk` are summed over all interfaces and disks.

`drives` comes with the `disk` group: one entry per drive the PC has mounted, each with `name`, `mount`, `total` and `free` (bytes the user can still write, which can be less than the drive's unused space). A drive mounted in several places, such as one Btrfs filesystem's subvolumes, is listed once, under its shortest mount point. The drive the PC runs from comes first, named `System`; others are named after where they are mounted, which is usually their label. Boot partitions, network shares and read-only images are left out. Older daemons leave `drives` out.

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
 "drives":[{"name":"System","mount":"/","total":511101108224,"free":219902325555},{"name":"Backup","mount":"/run/media/me/Backup","total":2000365289472,"free":1973176455168}],
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

Shows the PC's screen on the phone. `mode` says what:

- `"mirror"` (the default): one of the PC's monitors, which the user at the PC picks.
- `"extend"`: a new display the desktop makes for the phone, beside its monitors, for as long as it is shown. It works only where `welcome` has `screen_extend: true`.

`width` and `height` are the largest picture the phone wants, in pixels; either may be left out. A mirrored monitor is scaled down to fit, keeping its shape, and never scaled up. A new display is made exactly that size (1920 by 1080 when none is given). Sizes are made even, and a side is at least 240 and at most 4096.

`start_screen` is answered with `screen` messages as things change:

- `state: "asking"`: the desktop is asking the user at the PC which monitor to share. Once allowed, the desktop may share the same monitor again without asking.
- `state: "on"`: video frames follow. `width` and `height` give the picture's size in pixels, after any scaling, when it is known. `locked: true` means the PC's screen is locked, so the desktop shares only black; another `on` message with `locked` left out says it is unlocked again. Daemons older than this never send it.
- `state: "off"`: mirroring ended. `message` says why when it was not the phone's `stop_screen`: sharing was refused or not answered within 2 minutes, something is missing on the PC, or the share ended there.

`stop_screen` ends it, and so does the phone disconnecting. A `start_screen` that asks for what already runs changes nothing; one that asks for something else replaces it.

```json
{"type":"start_screen","mode":"extend","width":2340,"height":954}
{"type":"start_screen","height":720}
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

### `start_phone_mic`, `stop_phone_mic`, `phone_mic` and sound frames

Makes the phone one of the PC's microphones. The PC adds a virtual input device named after the phone, such as "Pixel 6 microphone", which programs on the PC can pick like any other; it is not made the default. `start_phone_mic` is answered with `phone_mic`:

- `state: "on"`: the device is there, and the PC takes the phone's sound.
- `state: "off"`: the device is gone. `message` says why when it was not the phone's `stop_phone_mic`: another phone is the PC's microphone already (there is one such device), or the sound server refused.

`stop_phone_mic` takes the device away, and so does the phone disconnecting. A `start_phone_mic` while it is on changes nothing.

```json
{"type":"start_phone_mic"}
{"type":"phone_mic","state":"on"}
{"type":"stop_phone_mic"}
{"type":"phone_mic","state":"off"}
```

While it is on, the phone sends its sound as WebSocket binary frames:

| Bytes | Meaning |
| --- | --- |
| 0 | Kind: `2`, the phone microphone's sound. |
| 1 on | The sound: one channel at 48 000 samples a second, each sample a signed 16-bit little-endian number. A frame holds whole samples, usually 20 ms of them (1920 bytes). |

A PC whose `welcome` has `phone_mic_opus` also takes the sound compressed, which is about a twentieth of the size (32 kbit/s from the app, against 768 kbit/s):

| Bytes | Meaning |
| --- | --- |
| 0 | Kind: `4`, the phone microphone's sound as Opus. |
| 1 on | One Opus packet: one channel at 48 000 samples a second, usually 20 ms. No Ogg or other container around it. |

The phone picks either kind, and may change between them; the PC decodes Opus to the same samples. A phone must not send kind `4` to a PC that did not offer it.

Sound is never held to be sent late. A phone whose connection is slow drops frames rather than queueing them, and the PC drops what arrives in a clump beyond 100 ms of waiting sound, so the microphone does not fall behind after a stall. Frames sent while the microphone is off are ignored.

### `start_phone_camera`, `stop_phone_camera`, `phone_camera` and camera frames

Makes one of the phone's cameras one of the PC's. The PC adds a camera to PipeWire named after the phone, such as "Pixel 6 camera", which programs that look for cameras there (browsers, OBS Studio) can pick like any other. Programs that only know `/dev/video*` devices do not see it. `start_phone_camera` is answered with `phone_camera`:

- `state: "on"`: the camera is there, and the PC takes the phone's pictures.
- `state: "off"`: the camera is gone. `message` says why when it was not the phone's `stop_phone_camera`: another phone is the PC's camera already (there is one such camera), GStreamer could not start, or it stopped.

`rotation` is how far the PC turns the pictures clockwise to stand them upright: `0` (the default), `90`, `180` or `270` degrees; any other number counts as `0`. The phone's camera films sideways when the phone is held upright, and the phone cannot turn the pictures itself, so it says how it was held when the camera was lent. A PC whose GStreamer can't turn pictures (no VA-API and no `videoflip`) shows them as filmed.

`stop_phone_camera` takes the camera away, and so does the phone disconnecting. A `start_phone_camera` while it is on changes nothing when the `rotation` is the same; with another, the PC takes the camera away and makes it anew, which programs using it see as the camera being unplugged.

```json
{"type":"start_phone_camera","rotation":90}
{"type":"phone_camera","state":"on"}
{"type":"stop_phone_camera"}
{"type":"phone_camera","state":"off"}
```

While it is on, the phone sends each picture as one WebSocket binary frame:

| Bytes | Meaning |
| --- | --- |
| 0 | Kind: `3`, the phone camera's video. |
| 1 | Flags. Bit 0: a keyframe. |
| 2 to 9 | When the picture was filmed, in microseconds on the phone's clock, as a big-endian unsigned integer. |
| 10 on | The picture: one H.264 access unit in Annex B form (NAL units after `00 00 00 01` start codes), without B-frames. |

This is the layout of the screen's video frames. A keyframe needs no earlier picture and starts with the stream's settings (SPS and PPS), so the PC can start decoding at any of them; the phone sends one about every second. The PC shows the pictures 120 ms behind the quickest one's trip, spaced by the times they were filmed, so pictures that Wi-Fi delivers in clumps still move evenly; one that comes later than that is shown at once. The picture's size comes from the stream: the app sends 30 pictures a second of 720, 480, 360 or 240 lines, as chosen on the phone. Video is never held to be sent late: a phone whose connection is slow drops pictures up to its next keyframe, and so does a PC whose decoder falls a second behind. Frames sent while the camera is off are ignored.

The PC answers every picture with how far it has got: `phone_camera_seen` carries the `time` from that frame. Pictures already sent cannot be taken back, so a link that slows would otherwise deliver video that is seconds old. The phone sets `time` against the picture it is filming: when the PC is more than half a second behind, the phone sends nothing more until the PC has everything sent so far, then carries on from a new keyframe. A phone may ignore the message.

```json
{"type":"phone_camera_seen","time":81234567890}
```

### `start_phone_speaker`, `stop_phone_speaker`, `phone_speaker` and sound frames

Makes the phone one of the PC's speakers. The PC adds a virtual output device named after the phone, such as "Pixel 6 speaker", and makes it the default output, so programs playing to the default move to the phone; programs set to a particular output stay there. `start_phone_speaker` is answered with `phone_speaker`:

- `state: "on"`: the device is there and is the default, and the PC sends its sound.
- `state: "off"`: the device is gone. `message` says why when it was not the phone's `stop_phone_speaker`: another phone is the PC's speaker already (there is one such device), or the sound server refused.

`stop_phone_speaker` takes the device away, and so does the phone disconnecting. The output that was the default before becomes the default again, unless someone picked another meanwhile. A `start_phone_speaker` while it is on changes nothing.

```json
{"type":"start_phone_speaker"}
{"type":"phone_speaker","state":"on"}
{"type":"stop_phone_speaker"}
{"type":"phone_speaker","state":"off"}
```

While it is on, the PC sends its sound as WebSocket binary frames:

| Bytes | Meaning |
| --- | --- |
| 0 | Kind: `5`, the PC's sound for the phone. |
| 1 on | The sound: two channels at 48 000 samples a second, interleaved left then right, each sample a signed 16-bit little-endian number. A frame holds whole samples: what the PC's sound server handed over at once, sent as soon as it comes, usually about 21 ms (4096 bytes) and at most 80 ms. About 1.5 Mbit/s. |

Nothing is sent while nothing plays. Sound is never held to be sent late: it goes ahead of the screen's video, and the PC drops frames when more than about 170 ms of them wait to be sent, and the phone drops what would wait more than about 150 ms to be played, so the speaker does not fall behind after a stall. Frames that come while the phone did not ask for the speaker are ignored.

### `pointer_move`, `pointer_scroll`, `pointer_button`, `key` and `type_text`

The phone as the PC's trackpad and keyboard, sent only to a PC whose `welcome` has `input: true`. None is answered. On the first of them the PC makes a virtual keyboard and mouse for this phone, named after it; they go when the phone disconnects, and the PC lets go of every key and button the phone still holds first, so none stays pressed.

- `pointer_move` moves the pointer by `dx` and `dy` whole pixels, right and down. The phone adds up small moves and sends at most one of these a frame, keeping what is left over for the next.
- `pointer_scroll` scrolls by `dy` notches down and `dx` notches right (negative for up and left), as a mouse wheel does; fractions scroll smoothly. A missing axis is `0`.
- `pointer_button` presses (`down: true`) or lets go of `left`, `right` or `middle`. A click is one of each; a drag holds `left` while `pointer_move`s arrive.
- `key` presses or lets go of a named key: `esc`, `tab`, `enter`, `backspace`, `delete`, `insert`, `up`, `down`, `left`, `right`, `home`, `end`, `page_up`, `page_down`, `f1` to `f12`, `ctrl`, `alt`, `shift` or `super`. A name the PC does not know is ignored.
- `type_text` types `text`: each character with the keys that make it on a US keyboard layout, holding Shift where needed; `\n` is Enter and `\t` is Tab. Characters with no such keys are skipped. Keys held with `key` stay held, so `ctrl` down, then `type_text` with `c`, then `ctrl` up copies.

```json
{"type":"pointer_move","dx":-3,"dy":12}
{"type":"pointer_scroll","dy":1.5}
{"type":"pointer_button","button":"left","down":true}
{"type":"key","key":"ctrl","down":true}
{"type":"type_text","text":"c"}
{"type":"key","key":"ctrl","down":false}
```

### `gamepad`, `gamepad_off` and `gamepad_rumble`

The phone as the PC's game controller, sent only to a PC whose `welcome` has `gamepad: true`. On the first `gamepad` the PC makes a virtual Xbox-style controller named after the phone, such as "YaeDock controller (Pixel 6)", which games see as a real one.

- `gamepad` carries the whole controller at once, not a change: `buttons` lists those held (`a`, `b`, `x`, `y`, `lb`, `rb`, `view`, `menu`, `guide`, `left_stick`, `right_stick`, and the D-pad's `up`, `down`, `left`, `right`; names the PC does not know are ignored); `lx`, `ly`, `rx`, `ry` are the sticks from -1 to 1, up and left negative; `lt` and `rt` the triggers from 0 to 1. Anything left out is at rest. The phone sends one whenever something changes, at most once a frame, so a lost one is soon made good.
- `gamepad_off` says the controller page closed: the PC lets go of everything and removes the controller. It is removed also when the phone disconnects, after letting go.
- `gamepad_rumble` comes from the PC when a game makes the controller rumble: `strong` and `weak` are the two motors from 0 to 1, for `ms` milliseconds (0: until the next one). Both at 0 stops it.

```json
{"type":"gamepad","buttons":["a","up"],"lx":-0.7,"ly":0.2,"rt":1.0}
{"type":"gamepad_off"}
{"type":"gamepad_rumble","strong":0.5,"weak":0.25,"ms":200}
```

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
