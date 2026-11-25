#!/bin/sh
# Installs YaeDock on this PC: downloads the program for this processor from
# the newest release on GitHub, checks it against the release's SHA256SUMS,
# and runs its installer, which puts it in ~/.local/bin and opens the setup
# guide. Nothing here needs root.
#
#   curl -fsSL https://github.com/DragonEmperor9480/yaedock/releases/latest/download/install.sh | sh
#
# Arguments go on to `yaedock --install`; --yes also starts YaeDock in the
# background and at login without asking:
#
#   curl -fsSL …/install.sh | sh -s -- --yes
#
# YAEDOCK_VERSION=0.5.0 installs that release instead of the newest.

set -eu

REPO="DragonEmperor9480/yaedock"

say() { printf '%s\n' "$*"; }
fail() { printf 'yaedock install: %s\n' "$*" >&2; exit 1; }

# Fetches $1 into the file $2.
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fL --proto '=https' --tlsv1.2 --retry 3 --progress-bar -o "$2" "$1"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$2" "$1"
    else
        fail "needs curl or wget to download YaeDock; install one of them first"
    fi
}

# The release's program for this processor.
program_name() {
    case "$(uname -m)" in
        x86_64 | amd64) echo yaedock ;;
        aarch64 | arm64 | armv8*) echo yaedock-aarch64 ;;
        *) fail "there is no YaeDock program for $(uname -m) yet, only x86_64 and 64-bit ARM (aarch64); build it from source instead: https://github.com/$REPO#build" ;;
    esac
}

main() {
    [ "$(uname -s)" = Linux ] || fail "YaeDock runs on Linux, not $(uname -s)"
    if [ "$(id -u)" -eq 0 ]; then
        fail "run this as your own user, not as root or with sudo: YaeDock installs for one user and runs as them"
    fi
    if ! command -v systemctl >/dev/null 2>&1 || [ ! -d /run/systemd/system ]; then
        fail "YaeDock needs systemd, which this system does not run"
    fi

    name=$(program_name)
    if [ -n "${YAEDOCK_VERSION:-}" ]; then
        base="https://github.com/$REPO/releases/download/v${YAEDOCK_VERSION#v}"
    else
        base="https://github.com/$REPO/releases/latest/download"
    fi

    tmp=$(mktemp -d "${TMPDIR:-/tmp}/yaedock-install.XXXXXX")
    trap 'rm -rf "$tmp"' EXIT INT TERM

    say "Downloading YaeDock for $(uname -m)…"
    fetch "$base/$name" "$tmp/$name" || fail "could not download $base/$name"
    fetch "$base/SHA256SUMS" "$tmp/SHA256SUMS" >/dev/null 2>&1 \
        || fail "could not download $base/SHA256SUMS"

    expected=$(awk -v n="$name" '{ f = $2; sub(/^\*/, "", f) } f == n { print $1; exit }' "$tmp/SHA256SUMS")
    [ -n "$expected" ] || fail "SHA256SUMS does not list $name"
    actual=$(sha256sum "$tmp/$name" | awk '{ print $1 }')
    [ "$expected" = "$actual" ] || fail "the download does not match SHA256SUMS; nothing was installed"
    say "✓ Checked the download"

    chmod +x "$tmp/$name"
    # Piped into sh, this script's input is the script; the installer's menu
    # needs the terminal instead.
    if (: </dev/tty) 2>/dev/null; then
        "$tmp/$name" --install "$@" </dev/tty
    else
        "$tmp/$name" --install "$@"
    fi
}

# All in one function, so a download cut short runs nothing.
main "$@"
