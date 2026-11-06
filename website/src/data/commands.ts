// Every `yaedock` command, for the docs page. Written from the program's own
// `--help` (daemon/yaedock/src/main.rs); update both together.

export interface Option {
  /** As typed, for example "-n, --count <COUNT>". */
  flag: string;
  text: string;
  /** Shown as "Default: …". */
  default?: string;
}

export interface Example {
  command: string;
  /** What it prints, trimmed to the useful part. */
  output?: string;
  note?: string;
}

export interface Command {
  /** Anchor id, for example "service-start". */
  id: string;
  /** As typed after `yaedock`, for example "service start". */
  name: string;
  summary: string;
  usage: string;
  /** Longer notes, one paragraph each. */
  details?: string[];
  args?: Option[];
  options?: Option[];
  examples?: Example[];
}

export interface Group {
  id: string;
  title: string;
  intro: string;
  commands: Command[];
}

export const groups: Group[] = [
  {
    id: "setup",
    title: "Install and set up",
    intro: "Put YaeDock on this PC, check what works, and take it away again. None of these need root.",
    commands: [
      {
        id: "menu",
        name: "",
        summary: "Open the interactive menu.",
        usage: "yaedock",
        details: [
          "Run without a command in a terminal, yaedock opens a menu for everything below: pairing, phones, the background service and the system check. In a script or a pipe it prints the help instead.",
        ],
      },
      {
        id: "install",
        name: "install",
        summary: "Install this program for your user in ~/.local/bin.",
        usage: "yaedock --install [-y]",
        details: [
          "Copies the program to ~/.local/bin/yaedock and links yaedockd to it. If the background service is already set up, it is pointed at the new copy and restarted, so this is also how you update.",
          "It warns when ~/.local/bin is not on your PATH, with the line to add for your shell, and when the firewall would stop phones from connecting, with the command that opens the port.",
          "In a terminal, it then opens the menu to finish setting up. `yaedock install` works too.",
        ],
        options: [{ flag: "-y, --yes", text: "Also run the daemon in the background and start it at login, without asking" }],
        examples: [
          { command: "./yaedock --install", note: "First install, from the downloaded file" },
          { command: "yaedock --install -y", note: "Install or update, set up the service, and skip the menu" },
        ],
      },
      {
        id: "uninstall",
        name: "uninstall",
        summary: "Remove the installed program and the background service.",
        usage: "yaedock --uninstall [--purge]",
        details: [
          "Stops and removes the systemd user service, then deletes yaedock and yaedockd from ~/.local/bin. Paired phones and the certificate stay, so a reinstall picks up where you left off. `yaedock uninstall` works too.",
        ],
        options: [{ flag: "--purge", text: "Also delete paired phones and the certificate" }],
      },
      {
        id: "setup-guide",
        name: "setup",
        summary: "Open the setup guide: check this PC, install the daemon, pair a phone.",
        usage: "yaedock setup",
      },
      {
        id: "check",
        name: "check",
        summary: "Check which YaeDock features work on this PC, and how to fix the rest.",
        usage: "yaedock check [--json]",
        details: [
          "Looks at systemd, stats and temperatures, GPU names and usage, the battery, brightness, volume (with output switching and per-app volume), the microphone, media, power profiles, screenshots, recording, PC power actions, shortcut buttons, the network, the port, the firewall, discovery, game frame rates, the certificate and the daemon. It only looks; it never changes anything.",
          "Each line says what works, and for anything that does not, the command that fixes it. `yaedock doctor` does the same.",
          "The app runs the same check from Settings > PC check, so you can see from the phone why a feature is missing.",
        ],
        options: [{ flag: "--json", text: "Print the results as JSON, as the phone receives them" }],
        examples: [
          {
            command: "yaedock check",
            output: `Checking this PC…

✓ systemd       User services are available
✓ Stats         CPU (8 threads), RAM (15.5 GiB), disk and network
✓ Brightness    Built-in display at 75%, changed through logind
✓ Volume        PipeWire (wpctl) at 31%, live updates
✓ Firewall      ufw allows TCP 9481
…

18 working, 0 with limits, 0 not working`,
          },
        ],
      },
    ],
  },
  {
    id: "phones",
    title: "Phones",
    intro: "Pair a phone, see which ones are paired and connected, and forget one.",
    commands: [
      {
        id: "pair",
        name: "pair",
        summary: "Pair a new phone: show a QR code and wait for the phone to scan it.",
        usage: "yaedock pair [--port <PORT>] [--host <HOST>]",
        details: [
          "Shows a QR code with this PC's address, its certificate fingerprint and a one-time code. Scan it in the app with Add a PC. The code works once and expires after 5 minutes; Ctrl+C stops waiting.",
          "The daemon has to be running, in the background or with `yaedock daemon`.",
        ],
        options: [
          { flag: "--port <PORT>", text: "Port the daemon listens on", default: "9481" },
          { flag: "--host <HOST>", text: "Address the phone should connect to", default: "this PC's LAN address" },
        ],
        examples: [
          { command: "yaedock pair" },
          { command: "yaedock pair --host 192.168.1.20", note: "When the PC has more than one network and picks the wrong one" },
        ],
      },
      {
        id: "devices",
        name: "devices",
        summary: "List paired phones.",
        usage: "yaedock devices",
        details: ["Prints each phone's id, its name and when it paired. The id is what `yaedock unpair` takes."],
      },
      {
        id: "unpair",
        name: "unpair",
        summary: "Forget a paired phone.",
        usage: "yaedock unpair <ID>",
        details: ["The phone can no longer connect, even with its saved token. To use it again, pair it again."],
        args: [{ flag: "<ID>", text: "Device id, as shown by `yaedock devices`" }],
      },
      {
        id: "status",
        name: "status",
        summary: "Show whether the daemon is running and which phones are connected.",
        usage: "yaedock status",
        details: ["Prints the daemon's version, process id, address, uptime and certificate fingerprint, then each connected phone and how long it has been connected."],
      },
    ],
  },
  {
    id: "service",
    title: "Background service",
    intro: "The daemon runs as a systemd user service, yaedockd.service, never as root. `yaedock --install -y` sets all of this up; these commands manage it by hand.",
    commands: [
      { id: "service-start", name: "service start", summary: "Start the daemon in the background.", usage: "yaedock service start" },
      { id: "service-stop", name: "service stop", summary: "Stop the background daemon.", usage: "yaedock service stop" },
      { id: "service-restart", name: "service restart", summary: "Restart the background daemon, for example after updating it.", usage: "yaedock service restart" },
      { id: "service-enable", name: "service enable", summary: "Start the daemon now and every time you log in.", usage: "yaedock service enable" },
      { id: "service-disable", name: "service disable", summary: "Stop starting the daemon at login.", usage: "yaedock service disable" },
      {
        id: "service-status",
        name: "service status",
        summary: "Show whether the service is installed, running and enabled.",
        usage: "yaedock service status",
        examples: [{ command: "yaedock service status", output: "Installed:        yes\nRunning:          yes\nStarts at login:  yes" }],
      },
      { id: "service-logs", name: "service logs", summary: "Follow the daemon's log.", usage: "yaedock service logs", details: ["Shows the log from journald and keeps following it until Ctrl+C."] },
      {
        id: "service-install",
        name: "service install",
        summary: "Install the systemd user service.",
        usage: "yaedock service install",
        details: ["Writes ~/.config/systemd/user/yaedockd.service, which runs the copy in ~/.local/bin. start and enable do this by themselves when needed. A service that came from a package is left alone."],
      },
      { id: "service-uninstall", name: "service uninstall", summary: "Stop the service and remove it.", usage: "yaedock service uninstall" },
    ],
  },
  {
    id: "features",
    title: "Features",
    intro: "Turn on the optional features and set up what the phone can do.",
    commands: [
      {
        id: "fps",
        name: "fps",
        summary: "Show game frame rates on the phone, through MangoHud.",
        usage: "yaedock fps [on | off | show | hide]",
        details: [
          "`on` adds a marked block to your MangoHud config that turns on its logging, which the daemon reads. Start games with MangoHud, for example with the Steam launch option `mangohud %command%`. `off` removes the block and leaves the rest of the config as it was.",
          "`hide` keeps the frame rates coming but makes the overlay invisible on the PC, and `show` brings it back. Running games pick this up straight away. The phone's MangoHud overlay switch does the same.",
          "With no argument, it says whether frame rates are on and whether the overlay is hidden.",
        ],
        args: [{ flag: "[ACTION]", text: "on, off, show or hide; none shows the current state" }],
        examples: [{ command: "yaedock fps on" }, { command: "yaedock fps hide" }],
      },
      {
        id: "obs",
        name: "obs",
        summary: "Record the screen from the phone, through OBS Studio.",
        usage: "yaedock obs [on | off]",
        details: [
          "`on` turns on OBS Studio's WebSocket server, with a random password if it has none. The daemon reaches it on this PC only, so the port needs no firewall rule. The password stays in OBS's settings. Close OBS before running it, and make sure your scene has a screen capture source.",
          "`off` turns the WebSocket server off again. With no argument, it shows the current state.",
        ],
        args: [{ flag: "[ACTION]", text: "on or off; none shows the current state" }],
        examples: [{ command: "yaedock obs on" }],
      },
      {
        id: "shortcuts",
        name: "shortcuts",
        summary: "Shortcut buttons on the phone that run commands on this PC.",
        usage: "yaedock shortcuts [add <NAME> <COMMAND> | remove <NAME>]",
        details: [
          "Without a subcommand, it lists your shortcuts and where they are stored (~/.config/yaedock/shortcuts.toml).",
          "The phone only ever sees a button's name, never its command, so it can run nothing you did not set up here. Each shortcut runs in the background with your desktop session's environment, and keeps running if the daemon restarts. New buttons show on the phone within a second.",
        ],
        examples: [
          { command: "yaedock shortcuts" },
          { command: 'yaedock shortcuts add "Steam" "steam"' },
          { command: 'yaedock shortcuts add "Night light" "gsettings set org.gnome.settings-daemon.plugins.color night-light-enabled true"' },
          { command: 'yaedock shortcuts remove "Steam"' },
        ],
      },
      {
        id: "shortcuts-add",
        name: "shortcuts add",
        summary: "Add a button, or change the command of one with the same name.",
        usage: "yaedock shortcuts add <NAME> <COMMAND>",
        args: [
          { flag: "<NAME>", text: "The button's label on the phone" },
          { flag: "<COMMAND>", text: "The command to run, as you would type it in a terminal (quote it)" },
        ],
      },
      {
        id: "shortcuts-remove",
        name: "shortcuts remove",
        summary: "Remove a button.",
        usage: "yaedock shortcuts remove <NAME>",
        args: [{ flag: "<NAME>", text: "The button's label" }],
      },
      {
        id: "brightness",
        name: "brightness",
        summary: "Show display brightness, or set it.",
        usage: "yaedock brightness [PERCENT] [-d <DISPLAY>]",
        details: ["Without a percentage, lists each display's id, name and brightness. Laptop screens work today; external monitors are planned."],
        args: [{ flag: "[PERCENT]", text: "New brightness in percent, 0 to 100" }],
        options: [{ flag: "-d, --display <DISPLAY>", text: "Display id; needed only when there is more than one display" }],
        examples: [{ command: "yaedock brightness" }, { command: "yaedock brightness 60" }],
      },
      {
        id: "volume",
        name: "volume",
        summary: "Show the default output's volume, or change it.",
        usage: "yaedock volume [PERCENT] [--mute | --unmute]",
        details: ["Works with PipeWire, or PulseAudio. Above 100% the sound is amplified in software and can distort."],
        args: [{ flag: "[PERCENT]", text: "New volume in percent, 0 to 150" }],
        options: [
          { flag: "--mute", text: "Mute the output" },
          { flag: "--unmute", text: "Unmute the output" },
        ],
        examples: [{ command: "yaedock volume 40" }, { command: "yaedock volume --mute" }],
      },
    ],
  },
  {
    id: "debugging",
    title: "Testing and debugging",
    intro: "Run the daemon by hand and look at what the phone receives.",
    commands: [
      {
        id: "daemon",
        name: "daemon",
        summary: "Run the daemon in this terminal.",
        usage: "yaedock daemon [--listen <ADDRESS>]",
        details: [
          "The background service runs this too. Stop the service first (`yaedock service stop`), or the port is already taken. Set RUST_LOG, for example RUST_LOG=debug, for more detail.",
          "Started under the name yaedockd, as the installed link is, the program takes `yaedockd run` instead.",
        ],
        options: [{ flag: "--listen <ADDRESS>", text: "Address to listen on", default: "0.0.0.0:9481" }],
        examples: [{ command: "RUST_LOG=debug yaedock daemon" }],
      },
      {
        id: "stats",
        name: "stats",
        summary: "Print a stats message every tick, as the phone would receive it.",
        usage: "yaedock stats [-n <COUNT>]",
        details: ["Reads the sensors directly; the daemon does not need to be running."],
        options: [{ flag: "-n, --count <COUNT>", text: "Stop after this many messages" }],
        examples: [{ command: "yaedock stats -n 1" }],
      },
      {
        id: "client",
        name: "client",
        summary: "Connect to a daemon like the phone would and print every message.",
        usage: "yaedock client [URL] [OPTIONS]",
        details: ["For testing the protocol: pair with a code from `yaedock pair`, connect again with the token it gets, and send messages as JSON."],
        args: [{ flag: "[URL]", text: "Daemon address", default: "wss://127.0.0.1:9481" }],
        options: [
          { flag: "--fingerprint <FINGERPRINT>", text: "SHA-256 fingerprint of the daemon's certificate", default: "this PC's own daemon" },
          { flag: "--code <CODE>", text: "One-time pairing code from `yaedock pair`" },
          { flag: "--token <TOKEN>", text: "Token received when pairing" },
          { flag: "--device-id <DEVICE_ID>", text: "Device id to identify as", default: "yaedock-client" },
          { flag: "-s, --send <SEND>", text: "A JSON message to send after the welcome; may be repeated" },
          { flag: "-n, --count <COUNT>", text: "Stop after this many messages" },
        ],
      },
    ],
  },
];

/** Where YaeDock keeps its files on the PC. */
export const files = [
  { path: "~/.local/bin/yaedock", text: "The program, put there by `yaedock --install`. yaedockd next to it is a link to the same file." },
  { path: "~/.config/yaedock/cert.der, key.der", text: "This PC's TLS certificate and key. Phones check its fingerprint, which the pairing QR code carries." },
  { path: "~/.config/yaedock/devices.toml", text: "Paired phones." },
  { path: "~/.config/yaedock/shortcuts.toml", text: "Your shortcut buttons." },
  { path: "~/.config/systemd/user/yaedockd.service", text: "The background service, written by `yaedock service install`." },
  { path: "$XDG_RUNTIME_DIR/yaedock", text: "Short-lived files, such as the current pairing code." },
  { path: "~/.cache/yaedock/mangohud", text: "Frame logs from MangoHud while game frame rates are on. Deleted an hour after each game ends." },
];
