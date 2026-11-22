//! Frames on a link that is a plain byte stream, such as a USB cable, where
//! nothing marks where one message ends: each frame is a kind byte, a
//! big-endian `u32` length and that many bytes. See "Over a USB cable" in
//! `protocol/protocol.md`.

/// A JSON message, as UTF-8.
pub const TEXT: u8 = 0;
/// A binary frame, the same as a WebSocket binary message: video or sound.
pub const BINARY: u8 = 1;
/// The phone opened the link afresh: whatever came before is over.
pub const START: u8 = 2;

/// The kind byte and the length.
pub const HEADER: usize = 5;
/// The largest frame either side accepts. A keyframe of the screen at 4K is
/// well under this; anything larger means the stream is out of step.
pub const MOST: usize = 16 * 1024 * 1024;

/// One frame on the link.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    Text(String),
    Binary(Vec<u8>),
    Start,
}

/// Why a stream of frames could not be read.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FrameError {
    #[error("a frame of unknown kind {0}")]
    UnknownKind(u8),
    #[error("a frame of {0} bytes, more than allowed")]
    TooLong(usize),
    #[error("a text frame that is not UTF-8")]
    NotText,
}

/// The bytes of a frame of `kind` holding `payload`.
pub fn encode(kind: u8, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(HEADER + payload.len());
    bytes.push(kind);
    bytes.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    bytes.extend_from_slice(payload);
    bytes
}

impl Frame {
    pub fn to_bytes(&self) -> Vec<u8> {
        match self {
            Frame::Text(text) => encode(TEXT, text.as_bytes()),
            Frame::Binary(data) => encode(BINARY, data),
            Frame::Start => encode(START, &[]),
        }
    }
}

/// Collects bytes as they arrive, in pieces of any size, and hands back
/// whole frames.
#[derive(Debug, Default)]
pub struct Splitter {
    waiting: Vec<u8>,
}

impl Splitter {
    pub fn push(&mut self, bytes: &[u8]) {
        self.waiting.extend_from_slice(bytes);
    }

    /// The next whole frame, `None` until one has fully arrived. After an
    /// error the stream is out of step and should be dropped.
    pub fn next_frame(&mut self) -> Result<Option<Frame>, FrameError> {
        if self.waiting.len() < HEADER {
            return Ok(None);
        }
        let kind = self.waiting[0];
        if kind > START {
            return Err(FrameError::UnknownKind(kind));
        }
        let len = u32::from_be_bytes(self.waiting[1..HEADER].try_into().expect("4 bytes")) as usize;
        if len > MOST {
            return Err(FrameError::TooLong(len));
        }
        if self.waiting.len() < HEADER + len {
            return Ok(None);
        }
        let payload: Vec<u8> = self.waiting.drain(..HEADER + len).skip(HEADER).collect();
        Ok(Some(match kind {
            TEXT => Frame::Text(String::from_utf8(payload).map_err(|_| FrameError::NotText)?),
            BINARY => Frame::Binary(payload),
            _ => Frame::Start,
        }))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_come_back_as_they_went() {
        let frames = [
            Frame::Start,
            Frame::Text(r#"{"type":"ping"}"#.into()),
            Frame::Binary(vec![3, 1, 0, 0, 0, 0, 0, 0, 0, 7, 9]),
            Frame::Text(String::new()),
        ];
        let bytes: Vec<u8> = frames.iter().flat_map(Frame::to_bytes).collect();
        let mut splitter = Splitter::default();
        splitter.push(&bytes);
        for frame in &frames {
            assert_eq!(splitter.next_frame().unwrap().as_ref(), Some(frame));
        }
        assert_eq!(splitter.next_frame().unwrap(), None);
    }

    #[test]
    fn frames_may_arrive_a_byte_at_a_time() {
        let bytes = Frame::Text("héllo".into()).to_bytes();
        let mut splitter = Splitter::default();
        for (i, byte) in bytes.iter().enumerate() {
            assert_eq!(splitter.next_frame().unwrap(), None, "after {i} bytes");
            splitter.push(&[*byte]);
        }
        assert_eq!(
            splitter.next_frame().unwrap(),
            Some(Frame::Text("héllo".into()))
        );
    }

    #[test]
    fn the_header_is_the_kind_then_the_length() {
        assert_eq!(encode(BINARY, &[9, 9]), [1, 0, 0, 0, 2, 9, 9]);
        assert_eq!(Frame::Start.to_bytes(), [2, 0, 0, 0, 0]);
    }

    #[test]
    fn a_stream_out_of_step_is_refused() {
        let mut splitter = Splitter::default();
        splitter.push(&[7, 0, 0, 0, 0]);
        assert_eq!(splitter.next_frame(), Err(FrameError::UnknownKind(7)));
        let mut splitter = Splitter::default();
        splitter.push(&[0, 0xff, 0, 0, 0]);
        assert!(matches!(splitter.next_frame(), Err(FrameError::TooLong(_))));
        let mut splitter = Splitter::default();
        splitter.push(&encode(TEXT, &[0xff]));
        assert_eq!(splitter.next_frame(), Err(FrameError::NotText));
    }
}
