//! The phone as the PC's game controller: a virtual Xbox-style pad made with
//! `uinput`, which games and Steam see as a real one. Games that rumble it
//! send that back through the same device, and it goes on to the phone.
//! Needs the same access to `/dev/uinput` as the keyboard and mouse.

use std::collections::HashMap;
use std::io::Read;
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use yaedock_proto::{GamepadButton, GamepadState};

use crate::input::{Device, EV_KEY, Spec};

// From linux/input-event-codes.h and linux/uinput.h.
const EV_ABS: u16 = 0x03;
const EV_FF: u16 = 0x15;
const EV_UINPUT: u16 = 0x0101;
const UI_FF_UPLOAD: u16 = 1;
const UI_FF_ERASE: u16 = 2;
const FF_RUMBLE: u16 = 0x50;
const BUS_USB: u16 = 0x03;

const BTN_A: u16 = 0x130;
const BTN_B: u16 = 0x131;
const BTN_X: u16 = 0x133;
const BTN_Y: u16 = 0x134;
const BTN_TL: u16 = 0x136;
const BTN_TR: u16 = 0x137;
const BTN_SELECT: u16 = 0x13a;
const BTN_START: u16 = 0x13b;
const BTN_MODE: u16 = 0x13c;
const BTN_THUMBL: u16 = 0x13d;
const BTN_THUMBR: u16 = 0x13e;

const ABS_X: u16 = 0x00;
const ABS_Y: u16 = 0x01;
const ABS_Z: u16 = 0x02;
const ABS_RX: u16 = 0x03;
const ABS_RY: u16 = 0x04;
const ABS_RZ: u16 = 0x05;
const ABS_HAT0X: u16 = 0x10;
const ABS_HAT0Y: u16 = 0x11;

/// The buttons in the order they are sent, with what each is on the phone.
const BUTTONS: [(GamepadButton, u16); 11] = [
    (GamepadButton::A, BTN_A),
    (GamepadButton::B, BTN_B),
    (GamepadButton::X, BTN_X),
    (GamepadButton::Y, BTN_Y),
    (GamepadButton::Lb, BTN_TL),
    (GamepadButton::Rb, BTN_TR),
    (GamepadButton::View, BTN_SELECT),
    (GamepadButton::Menu, BTN_START),
    (GamepadButton::Guide, BTN_MODE),
    (GamepadButton::LeftStick, BTN_THUMBL),
    (GamepadButton::RightStick, BTN_THUMBR),
];

/// How many rumble effects a game may load at once.
const EFFECTS: u32 = 16;

/// A rumble a game asked for: each motor from 0 to 1, for `ms` (0: until the next).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rumble {
    pub strong: f32,
    pub weak: f32,
    pub ms: u32,
}

/// Whether this user can make a controller: the same access as the keyboard and mouse.
pub fn detect() -> bool {
    crate::input::detect()
}

/// What to write for `state`: every button, the D-pad, the sticks and the
/// triggers, in the ranges an Xbox 360 pad uses on Linux. The kernel passes on
/// only what changed.
fn events(state: &GamepadState) -> Vec<(u16, u16, i32)> {
    let held = |button| state.buttons.contains(&button);
    let mut events: Vec<(u16, u16, i32)> = BUTTONS
        .iter()
        .map(|&(button, code)| (EV_KEY, code, held(button).into()))
        .collect();
    let axis = |negative, positive| i32::from(held(positive)) - i32::from(held(negative));
    let stick = |value: f32| (value.clamp(-1.0, 1.0) * 32767.0).round() as i32;
    let trigger = |value: f32| (value.clamp(0.0, 1.0) * 255.0).round() as i32;
    events.extend([
        (
            EV_ABS,
            ABS_HAT0X,
            axis(GamepadButton::Left, GamepadButton::Right),
        ),
        (
            EV_ABS,
            ABS_HAT0Y,
            axis(GamepadButton::Up, GamepadButton::Down),
        ),
        (EV_ABS, ABS_X, stick(state.lx)),
        (EV_ABS, ABS_Y, stick(state.ly)),
        (EV_ABS, ABS_RX, stick(state.rx)),
        (EV_ABS, ABS_RY, stick(state.ry)),
        (EV_ABS, ABS_Z, trigger(state.lt)),
        (EV_ABS, ABS_RZ, trigger(state.rt)),
    ]);
    events
}

/// A phone's controller on the PC. Lets go of everything when dropped, so no
/// button stays held after the phone leaves.
pub struct VirtualGamepad {
    device: Device,
    stop: Arc<AtomicBool>,
    rumbles: Option<JoinHandle<()>>,
}

impl VirtualGamepad {
    /// Makes the controller, named after `phone`; `on_rumble` is called from
    /// a thread of its own whenever a game makes it rumble. Blocks for a
    /// moment, so games have found it before the first button goes.
    pub fn open(
        phone: &str,
        on_rumble: impl FnMut(Rumble) + Send + 'static,
    ) -> std::io::Result<Self> {
        let keys: Vec<u16> = BUTTONS.iter().map(|&(_, code)| code).collect();
        let stick = |code| (code, -32768, 32767, 16, 128);
        let device = Device::create_from(&Spec {
            name: &format!("YaeDock controller ({phone})"),
            // An Xbox 360 pad's ids, so games and SDL know its layout.
            id: libc::input_id {
                bustype: BUS_USB,
                vendor: 0x045e,
                product: 0x028e,
                version: 0x0110,
            },
            keys: &keys,
            rels: &[],
            abs: &[
                stick(ABS_X),
                stick(ABS_Y),
                stick(ABS_RX),
                stick(ABS_RY),
                (ABS_Z, 0, 255, 0, 0),
                (ABS_RZ, 0, 255, 0, 0),
                (ABS_HAT0X, -1, 1, 0, 0),
                (ABS_HAT0Y, -1, 1, 0, 0),
            ],
            rumble_effects: EFFECTS,
        })?;
        let file = device.file().try_clone()?;
        let stop = Arc::new(AtomicBool::new(false));
        let stopped = stop.clone();
        let rumbles = std::thread::Builder::new()
            .name("gamepad-rumble".into())
            .spawn(move || serve_rumble(file, &stopped, on_rumble))?;
        std::thread::sleep(Duration::from_millis(200));
        Ok(Self {
            device,
            stop,
            rumbles: Some(rumbles),
        })
    }

    /// Sets the whole controller to `state`.
    pub fn set(&mut self, state: &GamepadState) -> std::io::Result<()> {
        self.device.send(&events(state))
    }

    /// Lets go of every button, and centres the sticks.
    pub fn release(&mut self) -> std::io::Result<()> {
        self.set(&GamepadState::default())
    }
}

impl Drop for VirtualGamepad {
    fn drop(&mut self) {
        if let Err(error) = self.release() {
            tracing::warn!("could not let go of the phone's controller: {error}");
        }
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.rumbles.take() {
            let _ = thread.join();
        }
    }
}

/// Answers games loading and erasing rumble effects, and passes on each one
/// they play, until `stop`.
fn serve_rumble(mut file: std::fs::File, stop: &AtomicBool, mut on_rumble: impl FnMut(Rumble)) {
    let fd = file.as_raw_fd();
    // Each loaded effect's motors and length, by its id.
    let mut effects: HashMap<i16, (u16, u16, u16)> = HashMap::new();
    let size = std::mem::size_of::<libc::input_event>();
    let mut buffer = vec![0u8; size * 16];
    while !stop.load(Ordering::Relaxed) {
        let mut poll = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        // SAFETY: `poll` is one valid pollfd for an open file.
        let ready = unsafe { libc::poll(&mut poll, 1, 100) };
        if ready < 0 {
            if std::io::Error::last_os_error().kind() == std::io::ErrorKind::Interrupted {
                continue;
            }
            break;
        }
        if ready == 0 {
            continue;
        }
        let read = match file.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => read,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => break,
        };
        for chunk in buffer[..read].chunks_exact(size) {
            // SAFETY: the kernel wrote whole `input_event`s, which are plain data.
            let event: libc::input_event =
                unsafe { std::ptr::read_unaligned(chunk.as_ptr().cast()) };
            match (event.type_, event.code) {
                (EV_UINPUT, UI_FF_UPLOAD) => upload(fd, event.value as u32, &mut effects),
                (EV_UINPUT, UI_FF_ERASE) => erase(fd, event.value as u32, &mut effects),
                (EV_FF, code) => {
                    let Ok(id) = i16::try_from(code) else {
                        continue;
                    };
                    let Some(&(strong, weak, length)) = effects.get(&id) else {
                        continue;
                    };
                    let playing = event.value > 0;
                    on_rumble(Rumble {
                        strong: if playing {
                            f32::from(strong) / 65535.0
                        } else {
                            0.0
                        },
                        weak: if playing {
                            f32::from(weak) / 65535.0
                        } else {
                            0.0
                        },
                        ms: if playing { length.into() } else { 0 },
                    });
                }
                _ => {}
            }
        }
    }
}

/// Takes an effect a game loads, keeping it if it is a rumble.
fn upload(fd: libc::c_int, request: u32, effects: &mut HashMap<i16, (u16, u16, u16)>) {
    // SAFETY: plain data; all zeroes is a valid value.
    let mut upload: libc::uinput_ff_upload = unsafe { std::mem::zeroed() };
    upload.request_id = request;
    let begin = libc::_IOWR::<libc::uinput_ff_upload>(b'U' as u32, 200);
    let end = libc::_IOW::<libc::uinput_ff_upload>(b'U' as u32, 201);
    // SAFETY: `upload` lives across the call, and the request takes it.
    if unsafe { libc::ioctl(fd, begin, &mut upload) } < 0 {
        return;
    }
    if upload.effect.type_ == FF_RUMBLE {
        // SAFETY: a rumble effect's union holds an `ff_rumble_effect` first.
        let rumble: libc::ff_rumble_effect =
            unsafe { std::ptr::read_unaligned(upload.effect.u.as_ptr().cast()) };
        effects.insert(
            upload.effect.id,
            (
                rumble.strong_magnitude,
                rumble.weak_magnitude,
                upload.effect.replay.length,
            ),
        );
        upload.retval = 0;
    } else {
        upload.retval = -libc::EINVAL;
    }
    // SAFETY: as above.
    unsafe { libc::ioctl(fd, end, &upload) };
}

/// Forgets an effect a game is done with.
fn erase(fd: libc::c_int, request: u32, effects: &mut HashMap<i16, (u16, u16, u16)>) {
    // SAFETY: plain data; all zeroes is a valid value.
    let mut erase: libc::uinput_ff_erase = unsafe { std::mem::zeroed() };
    erase.request_id = request;
    let begin = libc::_IOWR::<libc::uinput_ff_erase>(b'U' as u32, 202);
    let end = libc::_IOW::<libc::uinput_ff_erase>(b'U' as u32, 203);
    // SAFETY: `erase` lives across the call, and the request takes it.
    if unsafe { libc::ioctl(fd, begin, &mut erase) } < 0 {
        return;
    }
    if let Ok(id) = i16::try_from(erase.effect_id) {
        effects.remove(&id);
    }
    erase.retval = 0;
    // SAFETY: as above.
    unsafe { libc::ioctl(fd, end, &erase) };
}

#[cfg(test)]
mod tests {
    use super::*;

    fn value(events: &[(u16, u16, i32)], type_: u16, code: u16) -> i32 {
        events
            .iter()
            .find(|&&(t, c, _)| t == type_ && c == code)
            .map(|&(_, _, v)| v)
            .unwrap()
    }

    #[test]
    fn at_rest_everything_is_let_go_and_centred() {
        let events = events(&GamepadState::default());
        assert!(events.iter().all(|&(_, _, v)| v == 0));
        assert_eq!(events.len(), BUTTONS.len() + 8);
    }

    #[test]
    fn maps_buttons_sticks_and_triggers_as_an_xbox_pad_does() {
        let state = GamepadState {
            buttons: vec![GamepadButton::A, GamepadButton::Menu, GamepadButton::Up],
            lx: -1.0,
            ly: 0.5,
            rx: 2.0,
            rt: 1.0,
            lt: 0.5,
            ..Default::default()
        };
        let events = events(&state);
        assert_eq!(value(&events, EV_KEY, BTN_A), 1);
        assert_eq!(value(&events, EV_KEY, BTN_B), 0);
        assert_eq!(value(&events, EV_KEY, BTN_START), 1);
        assert_eq!(value(&events, EV_ABS, ABS_HAT0Y), -1);
        assert_eq!(value(&events, EV_ABS, ABS_HAT0X), 0);
        assert_eq!(value(&events, EV_ABS, ABS_X), -32767);
        assert_eq!(value(&events, EV_ABS, ABS_Y), 16384);
        // Out of range is held to the edge.
        assert_eq!(value(&events, EV_ABS, ABS_RX), 32767);
        assert_eq!(value(&events, EV_ABS, ABS_RZ), 255);
        assert_eq!(value(&events, EV_ABS, ABS_Z), 128);
    }

    #[test]
    fn opposite_d_pad_buttons_cancel_out() {
        let state = GamepadState {
            buttons: vec![GamepadButton::Left, GamepadButton::Right],
            ..Default::default()
        };
        assert_eq!(value(&events(&state), EV_ABS, ABS_HAT0X), 0);
    }

    #[test]
    fn makes_and_removes_the_controller() {
        if !detect() {
            return;
        }
        let pad = VirtualGamepad::open("test", |_| {}).unwrap();
        let devices = std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default();
        assert!(devices.contains("YaeDock controller (test)"));
        drop(pad);
        let devices = std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default();
        assert!(!devices.contains("YaeDock controller (test)"));
    }

    /// Plays a rumble on the controller as a game would, where this user may
    /// open its event device, and sees it come out.
    #[test]
    fn passes_on_a_games_rumble() {
        use std::io::Write;
        if !detect() {
            return;
        }
        let (tx, rx) = std::sync::mpsc::channel();
        let pad = VirtualGamepad::open("rumble test", move |rumble| {
            let _ = tx.send(rumble);
        })
        .unwrap();
        let devices = std::fs::read_to_string("/proc/bus/input/devices").unwrap_or_default();
        let node = devices
            .split("\n\n")
            .find(|block| block.contains("YaeDock controller (rumble test)"))
            .and_then(|block| {
                block
                    .split(|c: char| c.is_whitespace() || c == '=')
                    .find(|word| word.starts_with("event"))
            })
            .map(|event| format!("/dev/input/{event}"));
        // udev gives the person at the PC access a moment after it appears.
        let open = |node: &str| {
            std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(node)
        };
        let game = node.and_then(|node| {
            (0..30).find_map(|_| {
                open(&node)
                    .inspect_err(|_| std::thread::sleep(Duration::from_millis(100)))
                    .ok()
            })
        });
        let Some(mut game) = game else {
            // This user cannot open game controllers directly; games run by the
            // person at the PC can.
            drop(pad);
            return;
        };
        // SAFETY: plain data; all zeroes is a valid value.
        let mut effect: libc::ff_effect = unsafe { std::mem::zeroed() };
        effect.type_ = FF_RUMBLE;
        effect.id = -1;
        effect.replay.length = 300;
        let rumble = libc::ff_rumble_effect {
            strong_magnitude: 0xffff,
            weak_magnitude: 0x8000,
        };
        // SAFETY: the union holds an `ff_rumble_effect` first for a rumble.
        unsafe { std::ptr::write_unaligned(effect.u.as_mut_ptr().cast(), rumble) };
        let upload = libc::_IOW::<libc::ff_effect>(b'E' as u32, 0x80);
        // SAFETY: `effect` lives across the call, and the request takes it.
        assert!(unsafe { libc::ioctl(game.as_raw_fd(), upload, &mut effect) } >= 0);
        // SAFETY: plain data; the kernel fills in the time.
        let mut play: libc::input_event = unsafe { std::mem::zeroed() };
        play.type_ = EV_FF;
        play.code = effect.id as u16;
        play.value = 1;
        // SAFETY: plain data, written as the bytes the kernel expects.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                (&play as *const libc::input_event).cast::<u8>(),
                std::mem::size_of::<libc::input_event>(),
            )
        };
        game.write_all(bytes).unwrap();
        let got = rx.recv_timeout(Duration::from_secs(2)).unwrap();
        assert_eq!(got.strong, 1.0);
        assert!((got.weak - 0.5).abs() < 0.01);
        assert_eq!(got.ms, 300);
        drop(game);
        drop(pad);
    }
}
