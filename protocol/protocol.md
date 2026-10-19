# YaeDock protocol

The phone and PC exchange JSON messages over a TLS WebSocket (default port 47820). Bluetooth RFCOMM (phase 3) carries the same messages as length-prefixed frames.

Every message is a JSON object with a `type` field. The protocol version is sent in the first `hello`.

| Direction | Type | Payload |
| --- | --- | --- |
| Phone → PC | `hello` | protocol version, device id, token |
| PC → Phone | `welcome` | hostname, OS, CPU model, core count, sensor list, current brightness and volume |
| PC → Phone | `stats` | timestamp, CPU total and per core, RAM used/total, temps by sensor, net rx/tx bytes/s, disk read/write bytes/s |
| Phone → PC | `set_brightness` | display id, percent 0 to 100 |
| Phone → PC | `set_volume` | sink id (optional), percent 0 to 150, mute flag |
| PC → Phone | `state` | current brightness and volume, sent after any change from any source |
| Phone → PC | `subscribe` | which metric groups to send and the rate in ms |
| Both | `ping` / `pong` | keepalive every 10 s; drop the link after 30 s silence |
| PC → Phone | `error` | code and message, for example `unsupported_display` |

Example `stats` message:

```json
{"type":"stats","ts":1760838600,"cpu":{"total":23.4,"cores":[18.0,31.2,22.5,21.9]},"mem":{"used":6.1,"total":15.5},"temps":{"cpu_package":54.0,"nvme":41.0},"net":{"rx":120400,"tx":8800},"disk":{"read":0,"write":524288}}
```

Still open: how the one-time pairing code is sent on first connect, units for `mem`, and how displays are listed for `set_brightness`. JSON Schema files for each message will live next to this file.
