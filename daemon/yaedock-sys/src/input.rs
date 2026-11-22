//! The phone as the PC's keyboard and mouse: two virtual input devices made
//! with `uinput`, which every desktop treats as real ones, on Wayland and X11
//! alike. Needs write access to `/dev/uinput`, which desktop systems give the
//! person at the PC through a udev `uaccess` rule.

use std::collections::BTreeSet;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::time::Duration;

use yaedock_proto::{Key, MouseButton};

const UINPUT: &str = "/dev/uinput";

// From linux/input-event-codes.h.
pub(crate) const EV_SYN: u16 = 0x00;
pub(crate) const EV_KEY: u16 = 0x01;
const EV_REL: u16 = 0x02;
const EV_ABS: u16 = 0x03;
const EV_FF: u16 = 0x15;
const FF_RUMBLE: u16 = 0x50;
const SYN_REPORT: u16 = 0;
const REL_X: u16 = 0x00;
const REL_Y: u16 = 0x01;
const REL_HWHEEL: u16 = 0x06;
const REL_WHEEL: u16 = 0x08;
const REL_WHEEL_HI_RES: u16 = 0x0b;
const REL_HWHEEL_HI_RES: u16 = 0x0c;
const BTN_LEFT: u16 = 0x110;
const BTN_RIGHT: u16 = 0x111;
const BTN_MIDDLE: u16 = 0x112;
const BUS_VIRTUAL: u16 = 0x06;

const KEY_ESC: u16 = 1;
const KEY_MINUS: u16 = 12;
const KEY_EQUAL: u16 = 13;
const KEY_BACKSPACE: u16 = 14;
const KEY_TAB: u16 = 15;
const KEY_LEFTBRACE: u16 = 26;
const KEY_RIGHTBRACE: u16 = 27;
const KEY_ENTER: u16 = 28;
const KEY_LEFTCTRL: u16 = 29;
const KEY_SEMICOLON: u16 = 39;
const KEY_APOSTROPHE: u16 = 40;
const KEY_GRAVE: u16 = 41;
const KEY_LEFTSHIFT: u16 = 42;
const KEY_BACKSLASH: u16 = 43;
const KEY_COMMA: u16 = 51;
const KEY_DOT: u16 = 52;
const KEY_SLASH: u16 = 53;
const KEY_LEFTALT: u16 = 56;
const KEY_SPACE: u16 = 57;
const KEY_F1: u16 = 59;
const KEY_F11: u16 = 87;
const KEY_F12: u16 = 88;
const KEY_HOME: u16 = 102;
const KEY_UP: u16 = 103;
const KEY_PAGEUP: u16 = 104;
const KEY_LEFT: u16 = 105;
const KEY_RIGHT: u16 = 106;
const KEY_END: u16 = 107;
const KEY_DOWN: u16 = 108;
const KEY_PAGEDOWN: u16 = 109;
const KEY_INSERT: u16 = 110;
const KEY_DELETE: u16 = 111;
const KEY_LEFTMETA: u16 = 125;

/// One wheel notch in hi-res wheel units.
const NOTCH: i32 = 120;

/// Whether this user can make input devices.
pub fn detect() -> bool {
    OpenOptions::new()
        .read(true)
        .write(true)
        .open(UINPUT)
        .is_ok()
}

/// The evdev code for a named key, or `None` for one this PC does not know.
pub fn key_code(key: Key) -> Option<u16> {
    Some(match key {
        Key::Esc => KEY_ESC,
        Key::Tab => KEY_TAB,
        Key::Enter => KEY_ENTER,
        Key::Backspace => KEY_BACKSPACE,
        Key::Delete => KEY_DELETE,
        Key::Insert => KEY_INSERT,
        Key::Up => KEY_UP,
        Key::Down => KEY_DOWN,
        Key::Left => KEY_LEFT,
        Key::Right => KEY_RIGHT,
        Key::Home => KEY_HOME,
        Key::End => KEY_END,
        Key::PageUp => KEY_PAGEUP,
        Key::PageDown => KEY_PAGEDOWN,
        Key::F1 => KEY_F1,
        Key::F2 => KEY_F1 + 1,
        Key::F3 => KEY_F1 + 2,
        Key::F4 => KEY_F1 + 3,
        Key::F5 => KEY_F1 + 4,
        Key::F6 => KEY_F1 + 5,
        Key::F7 => KEY_F1 + 6,
        Key::F8 => KEY_F1 + 7,
        Key::F9 => KEY_F1 + 8,
        Key::F10 => KEY_F1 + 9,
        Key::F11 => KEY_F11,
        Key::F12 => KEY_F12,
        Key::Ctrl => KEY_LEFTCTRL,
        Key::Alt => KEY_LEFTALT,
        Key::Shift => KEY_LEFTSHIFT,
        Key::Super => KEY_LEFTMETA,
        Key::Unknown => return None,
    })
}

fn button_code(button: MouseButton) -> u16 {
    match button {
        MouseButton::Left => BTN_LEFT,
        MouseButton::Right => BTN_RIGHT,
        MouseButton::Middle => BTN_MIDDLE,
    }
}

/// The key that types `c` on a US keyboard, and whether Shift goes with it.
pub fn char_to_key(c: char) -> Option<(u16, bool)> {
    const ROWS: [(&str, u16); 3] = [("qwertyuiop", 16), ("asdfghjkl", 30), ("zxcvbnm", 44)];
    const DIGITS: &str = "1234567890";
    const SHIFTED_DIGITS: &str = "!@#$%^&*()";
    // Each symbol key: its plain character, its shifted one, and its code.
    const SYMBOLS: [(char, char, u16); 11] = [
        ('-', '_', KEY_MINUS),
        ('=', '+', KEY_EQUAL),
        ('[', '{', KEY_LEFTBRACE),
        (']', '}', KEY_RIGHTBRACE),
        (';', ':', KEY_SEMICOLON),
        ('\'', '"', KEY_APOSTROPHE),
        ('`', '~', KEY_GRAVE),
        ('\\', '|', KEY_BACKSLASH),
        (',', '<', KEY_COMMA),
        ('.', '>', KEY_DOT),
        ('/', '?', KEY_SLASH),
    ];
    match c {
        ' ' => return Some((KEY_SPACE, false)),
        '\n' => return Some((KEY_ENTER, false)),
        '\t' => return Some((KEY_TAB, false)),
        _ => {}
    }
    let lower = c.to_ascii_lowercase();
    for (row, first) in ROWS {
        if let Some(i) = row.find(lower) {
            return Some((first + i as u16, c.is_ascii_uppercase()));
        }
    }
    if let Some(i) = DIGITS.find(c) {
        return Some((2 + i as u16, false));
    }
    if let Some(i) = SHIFTED_DIGITS.find(c) {
        return Some((2 + i as u16, true));
    }
    SYMBOLS.iter().find_map(|&(plain, shifted, code)| {
        if c == plain {
            Some((code, false))
        } else if c == shifted {
            Some((code, true))
        } else {
            None
        }
    })
}

/// Turns wheel notches, which may be fractions, into hi-res wheel units, and
/// says when a whole notch has gone by for programs that read only those.
#[derive(Debug, Default)]
struct Wheel {
    /// Hi-res units not yet sent, under one.
    rest: f32,
    /// Hi-res units sent since the last whole notch.
    since_notch: i32,
}

impl Wheel {
    /// Returns the hi-res units to send and the whole notches to send with them.
    fn add(&mut self, notches: f32) -> (i32, i32) {
        let units = notches * NOTCH as f32 + self.rest;
        let whole = units.trunc();
        self.rest = units - whole;
        let hi_res = whole as i32;
        self.since_notch += hi_res;
        let notches = self.since_notch / NOTCH;
        self.since_notch -= notches * NOTCH;
        (hi_res, notches)
    }
}

/// What a uinput device is and has.
pub(crate) struct Spec<'a> {
    pub name: &'a str,
    pub id: libc::input_id,
    pub keys: &'a [u16],
    pub rels: &'a [u16],
    /// Absolute axes: code, lowest, highest, fuzz and flat.
    pub abs: &'a [(u16, i32, i32, i32, i32)],
    /// How many rumble effects games may load; 0 for none.
    pub rumble_effects: u32,
}

/// One uinput device; it disappears when dropped.
pub(crate) struct Device {
    file: File,
}

impl Device {
    fn create(name: &str, product: u16, keys: &[u16], rels: &[u16]) -> std::io::Result<Self> {
        Self::create_from(&Spec {
            name,
            id: libc::input_id {
                bustype: BUS_VIRTUAL,
                vendor: 0x1209,
                product,
                version: 1,
            },
            keys,
            rels,
            abs: &[],
            rumble_effects: 0,
        })
    }

    pub(crate) fn create_from(spec: &Spec) -> std::io::Result<Self> {
        let Spec {
            name,
            id,
            keys,
            rels,
            abs,
            rumble_effects,
        } = *spec;
        // Read too: a game's rumble comes back on the same file.
        let file = OpenOptions::new().read(true).write(true).open(UINPUT)?;
        let fd = file.as_raw_fd();
        let ioctl = |request: libc::Ioctl, arg: libc::c_int| -> std::io::Result<()> {
            // SAFETY: `fd` is an open uinput device, and these requests take an int.
            if unsafe { libc::ioctl(fd, request, arg) } < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        };
        let set_evbit = libc::_IOW::<libc::c_int>(b'U' as u32, 100);
        let set_keybit = libc::_IOW::<libc::c_int>(b'U' as u32, 101);
        let set_relbit = libc::_IOW::<libc::c_int>(b'U' as u32, 102);
        if !keys.is_empty() {
            ioctl(set_evbit, EV_KEY.into())?;
            for &key in keys {
                ioctl(set_keybit, key.into())?;
            }
        }
        if !rels.is_empty() {
            ioctl(set_evbit, EV_REL.into())?;
            for &rel in rels {
                ioctl(set_relbit, rel.into())?;
            }
        }
        if !abs.is_empty() {
            ioctl(set_evbit, EV_ABS.into())?;
            let set_absbit = libc::_IOW::<libc::c_int>(b'U' as u32, 103);
            let abs_setup = libc::_IOW::<libc::uinput_abs_setup>(b'U' as u32, 4);
            for &(code, minimum, maximum, fuzz, flat) in abs {
                ioctl(set_absbit, code.into())?;
                let axis = libc::uinput_abs_setup {
                    code,
                    absinfo: libc::input_absinfo {
                        value: 0,
                        minimum,
                        maximum,
                        fuzz,
                        flat,
                        resolution: 0,
                    },
                };
                // SAFETY: `axis` lives across the call, and the request takes it.
                if unsafe { libc::ioctl(fd, abs_setup, &axis) } < 0 {
                    return Err(std::io::Error::last_os_error());
                }
            }
        }
        if rumble_effects > 0 {
            ioctl(set_evbit, EV_FF.into())?;
            ioctl(
                libc::_IOW::<libc::c_int>(b'U' as u32, 107),
                FF_RUMBLE.into(),
            )?;
        }
        // SAFETY: `uinput_setup` is plain data; all zeroes is a valid value.
        let mut setup: libc::uinput_setup = unsafe { std::mem::zeroed() };
        setup.id = id;
        setup.ff_effects_max = rumble_effects;
        for (slot, byte) in setup
            .name
            .iter_mut()
            .zip(name.bytes().take(libc::UINPUT_MAX_NAME_SIZE - 1))
        {
            *slot = byte as libc::c_char;
        }
        let dev_setup = libc::_IOW::<libc::uinput_setup>(b'U' as u32, 3);
        let dev_create = libc::_IO(b'U' as u32, 1);
        // SAFETY: `setup` lives across the call, and the requests match their arguments.
        unsafe {
            if libc::ioctl(fd, dev_setup, &setup) < 0 || libc::ioctl(fd, dev_create) < 0 {
                return Err(std::io::Error::last_os_error());
            }
        }
        Ok(Self { file })
    }

    /// The device's file, for reading what games send it.
    pub(crate) fn file(&self) -> &File {
        &self.file
    }

    /// Writes `events` and a `SYN_REPORT`, as one report.
    pub(crate) fn send(&mut self, events: &[(u16, u16, i32)]) -> std::io::Result<()> {
        let mut all = Vec::with_capacity(events.len() + 1);
        for &(type_, code, value) in events.iter().chain([&(EV_SYN, SYN_REPORT, 0)]) {
            // SAFETY: `input_event` is plain data; the kernel fills in the time.
            let mut event: libc::input_event = unsafe { std::mem::zeroed() };
            event.type_ = type_;
            event.code = code;
            event.value = value;
            all.push(event);
        }
        // SAFETY: the events are plain data, read as the bytes the kernel expects.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                all.as_ptr().cast::<u8>(),
                std::mem::size_of_val(all.as_slice()),
            )
        };
        self.file.write_all(bytes)
    }
}

impl Drop for Device {
    fn drop(&mut self) {
        // SAFETY: the device is open; destroying it is what closing would do anyway.
        unsafe { libc::ioctl(self.file.as_raw_fd(), libc::_IO(b'U' as u32, 2)) };
    }
}

/// A phone's keyboard and mouse on the PC. Lets go of everything it holds
/// when dropped, so no key or button stays pressed after the phone leaves.
pub struct VirtualInput {
    keyboard: Device,
    mouse: Device,
    held_keys: BTreeSet<u16>,
    held_buttons: BTreeSet<u16>,
    wheel: Wheel,
    hwheel: Wheel,
}

impl VirtualInput {
    /// Makes the devices, named after `phone`. Blocks for a moment, so the
    /// desktop has found them before the first key goes.
    pub fn open(phone: &str) -> std::io::Result<Self> {
        let mut keys: Vec<u16> = (KEY_ESC..=KEY_F1 + 9).collect();
        keys.extend([KEY_F11, KEY_F12]);
        keys.extend(KEY_HOME..=KEY_DELETE);
        keys.push(KEY_LEFTMETA);
        let keyboard = Device::create(&format!("YaeDock keyboard ({phone})"), 1, &keys, &[])?;
        let mouse = Device::create(
            &format!("YaeDock mouse ({phone})"),
            2,
            &[BTN_LEFT, BTN_RIGHT, BTN_MIDDLE],
            &[
                REL_X,
                REL_Y,
                REL_WHEEL,
                REL_HWHEEL,
                REL_WHEEL_HI_RES,
                REL_HWHEEL_HI_RES,
            ],
        )?;
        std::thread::sleep(Duration::from_millis(200));
        Ok(Self {
            keyboard,
            mouse,
            held_keys: BTreeSet::new(),
            held_buttons: BTreeSet::new(),
            wheel: Wheel::default(),
            hwheel: Wheel::default(),
        })
    }

    pub fn move_by(&mut self, dx: i32, dy: i32) -> std::io::Result<()> {
        if dx == 0 && dy == 0 {
            return Ok(());
        }
        self.mouse.send(&[(EV_REL, REL_X, dx), (EV_REL, REL_Y, dy)])
    }

    /// Scrolls by notches, `dy` down and `dx` right.
    pub fn scroll(&mut self, dx: f32, dy: f32) -> std::io::Result<()> {
        let mut events = Vec::new();
        // The wheel counts up as it turns away from the user: scrolling up.
        let (hi_res, notches) = self.wheel.add(-dy);
        if hi_res != 0 {
            events.push((EV_REL, REL_WHEEL_HI_RES, hi_res));
        }
        if notches != 0 {
            events.push((EV_REL, REL_WHEEL, notches));
        }
        let (hi_res, notches) = self.hwheel.add(dx);
        if hi_res != 0 {
            events.push((EV_REL, REL_HWHEEL_HI_RES, hi_res));
        }
        if notches != 0 {
            events.push((EV_REL, REL_HWHEEL, notches));
        }
        if events.is_empty() {
            return Ok(());
        }
        self.mouse.send(&events)
    }

    pub fn button(&mut self, button: MouseButton, down: bool) -> std::io::Result<()> {
        let code = button_code(button);
        if down {
            self.held_buttons.insert(code);
        } else {
            self.held_buttons.remove(&code);
        }
        self.mouse.send(&[(EV_KEY, code, down.into())])
    }

    pub fn key(&mut self, key: Key, down: bool) -> std::io::Result<()> {
        let Some(code) = key_code(key) else {
            return Ok(());
        };
        if down {
            self.held_keys.insert(code);
        } else {
            self.held_keys.remove(&code);
        }
        self.keyboard.send(&[(EV_KEY, code, down.into())])
    }

    /// Types `text` on a US layout, skipping what it has no key for.
    pub fn type_text(&mut self, text: &str) -> std::io::Result<()> {
        for c in text.chars() {
            let Some((code, shift)) = char_to_key(c) else {
                continue;
            };
            // Shift held already, from the phone's own Shift key, serves as is.
            let press_shift = shift && !self.held_keys.contains(&KEY_LEFTSHIFT);
            if press_shift {
                self.keyboard.send(&[(EV_KEY, KEY_LEFTSHIFT, 1)])?;
            }
            self.keyboard.send(&[(EV_KEY, code, 1)])?;
            self.keyboard.send(&[(EV_KEY, code, 0)])?;
            if press_shift {
                self.keyboard.send(&[(EV_KEY, KEY_LEFTSHIFT, 0)])?;
            }
        }
        Ok(())
    }

    /// Lets go of every key and button still held.
    pub fn release_all(&mut self) -> std::io::Result<()> {
        for code in std::mem::take(&mut self.held_keys) {
            self.keyboard.send(&[(EV_KEY, code, 0)])?;
        }
        for code in std::mem::take(&mut self.held_buttons) {
            self.mouse.send(&[(EV_KEY, code, 0)])?;
        }
        Ok(())
    }
}

impl Drop for VirtualInput {
    fn drop(&mut self) {
        if let Err(error) = self.release_all() {
            tracing::warn!("could not let go of the phone's keys: {error}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn types_a_us_layout() {
        assert_eq!(char_to_key('q'), Some((16, false)));
        assert_eq!(char_to_key('A'), Some((30, true)));
        assert_eq!(char_to_key('m'), Some((50, false)));
        assert_eq!(char_to_key('1'), Some((2, false)));
        assert_eq!(char_to_key('0'), Some((11, false)));
        assert_eq!(char_to_key('!'), Some((2, true)));
        assert_eq!(char_to_key(')'), Some((11, true)));
        assert_eq!(char_to_key('?'), Some((KEY_SLASH, true)));
        assert_eq!(char_to_key('"'), Some((KEY_APOSTROPHE, true)));
        assert_eq!(char_to_key('~'), Some((KEY_GRAVE, true)));
        assert_eq!(char_to_key(' '), Some((KEY_SPACE, false)));
        assert_eq!(char_to_key('\n'), Some((KEY_ENTER, false)));
        assert_eq!(char_to_key('é'), None);
        assert_eq!(char_to_key('😀'), None);
    }

    #[test]
    fn every_printable_ascii_character_has_a_key() {
        for c in ' '..='~' {
            assert!(char_to_key(c).is_some(), "{c:?}");
        }
    }

    #[test]
    fn every_named_key_has_a_code() {
        let keys = [
            Key::Esc,
            Key::Tab,
            Key::Enter,
            Key::Backspace,
            Key::Delete,
            Key::Insert,
            Key::Up,
            Key::Down,
            Key::Left,
            Key::Right,
            Key::Home,
            Key::End,
            Key::PageUp,
            Key::PageDown,
            Key::F1,
            Key::F10,
            Key::F11,
            Key::F12,
            Key::Ctrl,
            Key::Alt,
            Key::Shift,
            Key::Super,
        ];
        for key in keys {
            assert!(key_code(key).is_some(), "{key:?}");
        }
        assert_eq!(key_code(Key::F10), Some(68));
        assert_eq!(key_code(Key::Unknown), None);
    }

    #[test]
    fn wheel_sends_whole_notches_as_they_add_up() {
        let mut wheel = Wheel::default();
        assert_eq!(wheel.add(0.5), (60, 0));
        assert_eq!(wheel.add(0.5), (60, 1));
        assert_eq!(wheel.add(2.0), (240, 2));
        assert_eq!(wheel.add(-0.25), (-30, 0));
        // Fractions of a hi-res unit carry over rather than being lost.
        let mut slow = Wheel::default();
        let sent: i32 = (0..10).map(|_| slow.add(0.001).0).sum();
        assert_eq!(sent, 1);
    }

    /// Makes the devices on a PC that allows it, and checks they go away.
    /// Presses nothing: the devices are real, on the desktop running the test.
    #[test]
    fn makes_and_removes_devices() {
        if !detect() {
            return;
        }
        let input = VirtualInput::open("test").unwrap();
        let devices = std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default();
        assert!(devices.contains("YaeDock keyboard (test)"));
        assert!(devices.contains("YaeDock mouse (test)"));
        drop(input);
        let devices = std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default();
        assert!(!devices.contains("YaeDock keyboard (test)"));
    }
}
