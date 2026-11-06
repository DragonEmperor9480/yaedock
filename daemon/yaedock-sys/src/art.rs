//! Album covers: what a media player gives as `mpris:artUrl`, made into a
//! small JPEG for the phone.

use std::io::{self, Cursor, Read};
use std::time::Duration;

use base64::Engine;
use image::ImageReader;
use image::codecs::jpeg::JpegEncoder;

/// The longest side of the thumbnail, in pixels: sharp on a phone's media
/// card, about 15 to 30 KB as JPEG.
pub const SIZE: u32 = 256;

/// Covers bigger than this are refused rather than read.
const MAX_BYTES: u64 = 8 * 1024 * 1024;

/// How long a download may take, from connecting to the last byte.
const TIMEOUT: Duration = Duration::from_secs(5);

const QUALITY: u8 = 82;

/// Reads the cover at `url` and returns it as a JPEG of at most [SIZE] pixels
/// on each side. `url` is a `file://` path (most local players, and browsers,
/// which save the cover of what plays), an `http(s)://` link (Spotify), or a
/// `data:` URL.
pub fn thumbnail(url: &str) -> io::Result<Vec<u8>> {
    let bytes = fetch(url)?;
    let image = ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()?
        .decode()
        .map_err(invalid)?;
    // Only ever smaller: enlarging a small cover would only blur it.
    let small = if image.width() > SIZE || image.height() > SIZE {
        image.thumbnail(SIZE, SIZE)
    } else {
        image
    }
    .into_rgb8();
    let mut jpeg = Vec::new();
    JpegEncoder::new_with_quality(&mut jpeg, QUALITY)
        .encode_image(&small)
        .map_err(invalid)?;
    Ok(jpeg)
}

fn fetch(url: &str) -> io::Result<Vec<u8>> {
    if let Some(path) = url.strip_prefix("file://") {
        let path = percent_decode(path);
        let file = std::fs::File::open(path)?;
        return read_capped(file);
    }
    if let Some(rest) = url.strip_prefix("data:") {
        let (meta, data) = rest
            .split_once(',')
            .ok_or_else(|| invalid("a data URL without data"))?;
        if !meta.ends_with(";base64") {
            return Err(invalid("a data URL that is not base64"));
        }
        return base64::engine::general_purpose::STANDARD
            .decode(data.trim())
            .map_err(invalid);
    }
    if url.starts_with("https://") || url.starts_with("http://") {
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .timeout_global(Some(TIMEOUT))
            .max_redirects(3)
            .build()
            .into();
        let response = agent
            .get(url)
            .header("User-Agent", concat!("YaeDock/", env!("CARGO_PKG_VERSION")))
            .call()
            .map_err(io::Error::other)?;
        return read_capped(response.into_body().into_reader());
    }
    Err(invalid("not a file, data or web address"))
}

fn read_capped(reader: impl Read) -> io::Result<Vec<u8>> {
    let mut bytes = Vec::new();
    reader.take(MAX_BYTES + 1).read_to_end(&mut bytes)?;
    if bytes.len() as u64 > MAX_BYTES {
        return Err(invalid("the cover is too big"));
    }
    Ok(bytes)
}

/// `%20` and the like in a file URL, as players write paths with spaces.
fn percent_decode(text: &str) -> String {
    let bytes = text.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        let hex = || std::str::from_utf8(bytes.get(i + 1..i + 3)?).ok();
        match (bytes[i], hex().and_then(|h| u8::from_str_radix(h, 16).ok())) {
            (b'%', Some(byte)) => {
                out.push(byte);
                i += 3;
            }
            (byte, _) => {
                out.push(byte);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn invalid(error: impl ToString) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::{ImageFormat, RgbImage};

    fn png(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = Vec::new();
        RgbImage::from_pixel(width, height, image::Rgb([200, 40, 90]))
            .write_to(&mut Cursor::new(&mut bytes), ImageFormat::Png)
            .unwrap();
        bytes
    }

    fn size(jpeg: &[u8]) -> (u32, u32) {
        let image = image::load_from_memory(jpeg).unwrap();
        (image.width(), image.height())
    }

    #[test]
    fn shrinks_a_file_keeping_its_shape() {
        let dir = std::env::temp_dir().join(format!("yaedock-art-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("cover art.png");
        std::fs::write(&path, png(1200, 675)).unwrap();
        let url = format!("file://{}", path.display().to_string().replace(' ', "%20"));
        let jpeg = thumbnail(&url).unwrap();
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(&jpeg[..2], b"\xff\xd8");
        assert_eq!(size(&jpeg), (256, 144));
    }

    #[test]
    fn reads_data_urls() {
        let data = base64::engine::general_purpose::STANDARD.encode(png(64, 64));
        let jpeg = thumbnail(&format!("data:image/png;base64,{data}")).unwrap();
        // Small covers stay as they are.
        assert_eq!(size(&jpeg), (64, 64));
    }

    #[test]
    fn refuses_what_is_not_a_cover() {
        assert!(thumbnail("ftp://example.com/a.png").is_err());
        assert!(thumbnail("data:text/plain,hello").is_err());
        assert!(thumbnail("file:///nonexistent/yaedock/cover.png").is_err());
    }

    #[test]
    fn decodes_percent_escapes() {
        assert_eq!(percent_decode("/home/a%20b/c%2Cd.png"), "/home/a b/c,d.png");
        assert_eq!(percent_decode("/50%/x"), "/50%/x");
    }
}
