# YaeDock protocol

Version 1.

The phone and PC exchange JSON messages over a TLS WebSocket (default port 9481), one message per text frame. Bluetooth RFCOMM (phase 3) carries the same messages as length-prefixed frames.

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
| PC → Phone | `welcome` | `version`, `hostname`, `os`, `cpu_model`, `cores`, `sensors`, `token` (optional), `state`, `gpus` (optional) |
| PC → Phone | `stats` | `ts`, `uptime`, `cpu`, `mem`, `temps`, `net`, `disk`, `gpus` (groups optional) |
| PC → Phone | `state` | `displays`, `volume` (optional) |
| Phone → PC | `subscribe` | `groups`, `interval_ms` |
| Phone → PC | `set_brightness` | `display`, `percent` |
| Phone → PC | `set_volume` | `percent` (optional), `muted` (optional) |
| Both | `ping` / `pong` | none |
| PC → Phone | `error` | `code`, `message` |

### `hello`

```json
{"type":"hello","version":1,"device_id":"5f2c9a","device_name":"Pixel 6","code":"482913"}
```

### `welcome`

`sensors` lists the keys used in `stats.temps`. `state` has the same shape as a `state` message without `type`; its `displays` list gives the ids for `set_brightness`. `volume` is missing when no volume backend is available. `gpus` lists the graphics cards by `id` and `name`; it is left out when there are none (or the daemon predates GPU support).

```json
{"type":"welcome","version":1,"hostname":"laptop","os":"CachyOS","cpu_model":"AMD Ryzen 7 7840HS","cores":16,
 "sensors":["cpu_package","nvme"],"token":"q8Zr...","state":{"displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false}},
 "gpus":[{"id":"pci:0000:00:02.0","name":"Intel UHD Graphics 630"},{"id":"pci:0000:01:00.0","name":"NVIDIA GeForce GTX 1050 Mobile"}]}
```

### `stats`

Groups the phone has not subscribed to are left out. `net` and `disk` are summed over all interfaces and disks.

`gpus` has one entry per GPU from the welcome, matched by `id`. Every reading is optional, because GPUs and drivers report different things: `usage` (percent), `temp` (°C), `vram_used` and `vram_total` (bytes; integrated GPUs share system RAM and leave them out), `clock_mhz`. `sleeping: true` means the GPU is powered down to save energy; the daemon does not wake it up to read it.

```json
{"type":"stats","ts":1760838600000,"uptime":3600,
 "cpu":{"total":23.4,"cores":[18.0,31.2,22.5,21.9]},
 "mem":{"used":6549000000,"total":16642000000},
 "temps":{"cpu_package":54.0,"nvme":41.0},
 "net":{"rx":120400,"tx":8800},
 "disk":{"read":0,"write":524288},
 "gpus":[{"id":"pci:0000:00:02.0","usage":12.0,"clock_mhz":350},{"id":"pci:0000:01:00.0","usage":31.0,"temp":45.0,"vram_used":389021696,"vram_total":4294967296,"clock_mhz":1392}]}
```

### `state`

Sent to every connected phone after any brightness or volume change, whether it came from a phone or from the PC itself.

```json
{"type":"state","displays":[{"id":"backlight:amdgpu_bl1","name":"Built-in display","percent":60}],"volume":{"percent":45,"muted":false}}
```

### `subscribe`

`groups` is any of `cpu`, `mem`, `temps`, `net`, `disk`, `gpu`. `interval_ms` is rounded up to a multiple of the daemon's sampling tick, so a phone can ask for fewer updates but not faster sampling.

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

The daemon answers both `set_*` commands with a `state` broadcast, or with `error` if the change failed.

### `error`

| Code | Meaning |
| --- | --- |
| `bad_request` | The message could not be parsed or was not expected at this point |
| `unauthorized` | The token or pairing code was missing or wrong |
| `unsupported_version` | The daemon does not speak the phone's protocol version |
| `unsupported_display` | `set_brightness` named a display the daemon does not know |
| `backend_failed` | A brightness or volume backend failed to apply the change |

```json
{"type":"error","code":"unsupported_display","message":"no display with id hdmi-1"}
```

JSON Schema files for each message will live next to this file.
