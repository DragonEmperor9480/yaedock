//! Opus, the codec the phone's microphone is sent in, through the system's
//! `libopus`.
//!
//! The library is loaded when first needed rather than linked, so a PC
//! without it still runs everything else, and building needs nothing new.
//! Nearly every desktop has it: PipeWire and FFmpeg depend on it.

use std::ffi::{c_int, c_void};
use std::sync::OnceLock;

use libloading::Library;

/// Samples a second of the sound: Opus works at this rate inside.
pub const RATE: i32 = 48_000;
/// The longest stretch one packet can hold: 120 ms.
const MOST_SAMPLES: usize = 5_760;
/// The most bytes a packet is given when encoding.
const MOST_PACKET: usize = 1_500;
/// `OPUS_APPLICATION_VOIP`: tuned for speech.
const VOIP: c_int = 2048;

type Create = unsafe extern "C" fn(i32, c_int, *mut c_int) -> *mut c_void;
type CreateEncoder = unsafe extern "C" fn(i32, c_int, c_int, *mut c_int) -> *mut c_void;
type Decode = unsafe extern "C" fn(*mut c_void, *const u8, i32, *mut i16, c_int, c_int) -> c_int;
type Encode = unsafe extern "C" fn(*mut c_void, *const i16, c_int, *mut u8, i32) -> i32;
type Destroy = unsafe extern "C" fn(*mut c_void);

/// The functions used, kept with the library they come from.
struct Opus {
    decoder_create: Create,
    decode: Decode,
    decoder_destroy: Destroy,
    encoder_create: CreateEncoder,
    encode: Encode,
    encoder_destroy: Destroy,
    /// Never unloaded: the functions above point into it.
    _library: Library,
}

/// The loaded library, or `None` when this PC has none. Setting
/// `YAEDOCK_NO_OPUS` makes it look absent, for trying the fallback.
fn opus() -> Option<&'static Opus> {
    static OPUS: OnceLock<Option<Opus>> = OnceLock::new();
    OPUS.get_or_init(|| {
        if std::env::var_os("YAEDOCK_NO_OPUS").is_some() {
            return None;
        }
        // SAFETY: libopus runs no code of consequence when loaded, and the
        // types above are those of its documented C functions.
        unsafe {
            let library = ["libopus.so.0", "libopus.so"]
                .into_iter()
                .find_map(|name| Library::new(name).ok())?;
            Some(Opus {
                decoder_create: *library.get::<Create>(b"opus_decoder_create\0").ok()?,
                decode: *library.get::<Decode>(b"opus_decode\0").ok()?,
                decoder_destroy: *library.get::<Destroy>(b"opus_decoder_destroy\0").ok()?,
                encoder_create: *library
                    .get::<CreateEncoder>(b"opus_encoder_create\0")
                    .ok()?,
                encode: *library.get::<Encode>(b"opus_encode\0").ok()?,
                encoder_destroy: *library.get::<Destroy>(b"opus_encoder_destroy\0").ok()?,
                _library: library,
            })
        }
    })
    .as_ref()
}

/// Whether this PC can decode Opus: `libopus` is installed.
pub fn available() -> bool {
    opus().is_some()
}

/// Turns Opus packets of one channel back into sound.
pub struct Decoder {
    opus: &'static Opus,
    state: *mut c_void,
}

// SAFETY: the state is only ever used through `&mut self`, and libopus keeps
// nothing per thread.
unsafe impl Send for Decoder {}

impl Decoder {
    /// A decoder for one channel at [`RATE`], or `None` without `libopus`.
    pub fn new() -> Option<Self> {
        let opus = opus()?;
        let mut error = 0;
        // SAFETY: called as documented; a null state is checked for.
        let state = unsafe { (opus.decoder_create)(RATE, 1, &mut error) };
        (!state.is_null() && error == 0).then_some(Self { opus, state })
    }

    /// The sound in `packet`, as signed 16-bit little-endian samples, or
    /// `None` when it is not an Opus packet.
    pub fn decode(&mut self, packet: &[u8]) -> Option<Vec<u8>> {
        // An empty packet asks libopus to make up lost sound; none is lost here.
        if packet.is_empty() {
            return None;
        }
        let mut samples = [0i16; MOST_SAMPLES];
        // SAFETY: the buffers are as long as the lengths given with them.
        let decoded = unsafe {
            (self.opus.decode)(
                self.state,
                packet.as_ptr(),
                i32::try_from(packet.len()).ok()?,
                samples.as_mut_ptr(),
                MOST_SAMPLES as c_int,
                0,
            )
        };
        let decoded = usize::try_from(decoded).ok()?;
        Some(
            samples[..decoded]
                .iter()
                .flat_map(|sample| sample.to_le_bytes())
                .collect(),
        )
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        // SAFETY: the state came from `opus_decoder_create` and is not used again.
        unsafe { (self.opus.decoder_destroy)(self.state) }
    }
}

/// Turns sound of one channel into Opus packets: what the phone does, here
/// for the test client.
pub struct Encoder {
    opus: &'static Opus,
    state: *mut c_void,
}

// SAFETY: as for `Decoder`.
unsafe impl Send for Encoder {}

impl Encoder {
    /// An encoder for speech in one channel at [`RATE`], or `None` without
    /// `libopus`.
    pub fn new() -> Option<Self> {
        let opus = opus()?;
        let mut error = 0;
        // SAFETY: called as documented; a null state is checked for.
        let state = unsafe { (opus.encoder_create)(RATE, 1, VOIP, &mut error) };
        (!state.is_null() && error == 0).then_some(Self { opus, state })
    }

    /// One packet for `samples`, which must be a length Opus takes: 20 ms
    /// (960 samples) is the usual one.
    pub fn encode(&mut self, samples: &[i16]) -> Option<Vec<u8>> {
        let mut packet = vec![0u8; MOST_PACKET];
        // SAFETY: the buffers are as long as the lengths given with them.
        let length = unsafe {
            (self.opus.encode)(
                self.state,
                samples.as_ptr(),
                c_int::try_from(samples.len()).ok()?,
                packet.as_mut_ptr(),
                MOST_PACKET as i32,
            )
        };
        packet.truncate(usize::try_from(length).ok()?);
        Some(packet)
    }
}

impl Drop for Encoder {
    fn drop(&mut self) {
        // SAFETY: the state came from `opus_encoder_create` and is not used again.
        unsafe { (self.opus.encoder_destroy)(self.state) }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A second of a 440 Hz tone, in 20 ms pieces.
    fn tone() -> Vec<Vec<i16>> {
        let samples: Vec<i16> = (0..RATE)
            .map(|n| {
                let turn = f64::from(n) * 440.0 * std::f64::consts::TAU / f64::from(RATE);
                (turn.sin() * 12_000.0) as i16
            })
            .collect();
        samples.chunks(960).map(<[i16]>::to_vec).collect()
    }

    #[test]
    fn sound_survives_the_trip_through_opus() {
        // A PC without libopus has nothing to test.
        let (Some(mut encoder), Some(mut decoder)) = (Encoder::new(), Decoder::new()) else {
            return;
        };
        let mut packed = 0;
        let mut loudest = 0i16;
        let mut samples = 0;
        for piece in tone() {
            let packet = encoder.encode(&piece).expect("a packet");
            packed += packet.len();
            let sound = decoder.decode(&packet).expect("sound");
            samples += sound.len() / 2;
            for sample in sound.as_chunks::<2>().0 {
                loudest = loudest.max(i16::from_le_bytes(*sample).abs());
            }
        }
        // All of it comes back, about as loud, from far fewer bytes.
        assert_eq!(samples, RATE as usize);
        assert!((8_000..16_000).contains(&loudest), "{loudest}");
        assert!(packed < RATE as usize * 2 / 8, "{packed}");
    }

    #[test]
    fn what_is_not_opus_is_refused() {
        let Some(mut decoder) = Decoder::new() else {
            return;
        };
        assert_eq!(decoder.decode(&[]), None);
    }
}
