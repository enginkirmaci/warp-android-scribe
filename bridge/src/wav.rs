//! The little bit of WAV parsing the bridge needs: is this a WAV, and how long
//! is it? The recorder on the phone writes 16 kHz mono PCM16, but old recordings
//! and other sources can be stereo or 48 kHz, so the duration comes from the
//! file's own byte rate rather than a hardcoded sample rate.

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WavInfo {
    pub sample_rate: u32,
    pub channels: u16,
    pub bits_per_sample: u16,
    pub data_bytes: u64,
    pub duration_secs: f64,
}

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum WavError {
    #[error("audio is not a RIFF/WAVE file")]
    NotWave,
    #[error("WAV has no fmt chunk")]
    MissingFormat,
    #[error("WAV has no data chunk")]
    MissingData,
    #[error("WAV format is invalid")]
    InvalidFormat,
    #[error("WAV duration is invalid")]
    InvalidDuration,
}

pub fn inspect(bytes: &[u8]) -> Result<WavInfo, WavError> {
    if bytes.len() < 12 || &bytes[0..4] != b"RIFF" || &bytes[8..12] != b"WAVE" {
        return Err(WavError::NotWave);
    }

    let mut pos = 12usize;
    let mut format = None;
    let mut data_size = None;

    while pos + 8 <= bytes.len() {
        let id = &bytes[pos..pos + 4];
        let declared = u32::from_le_bytes(bytes[pos + 4..pos + 8].try_into().unwrap()) as usize;
        let body = pos + 8;
        let available = bytes.len().saturating_sub(body);
        let size = declared.min(available);

        match id {
            b"fmt " => {
                if size < 16 {
                    return Err(WavError::InvalidFormat);
                }
                let audio_format = u16::from_le_bytes(bytes[body..body + 2].try_into().unwrap());
                let channels = u16::from_le_bytes(bytes[body + 2..body + 4].try_into().unwrap());
                let sample_rate = u32::from_le_bytes(bytes[body + 4..body + 8].try_into().unwrap());
                let byte_rate = u32::from_le_bytes(bytes[body + 8..body + 12].try_into().unwrap());
                let bits = u16::from_le_bytes(bytes[body + 14..body + 16].try_into().unwrap());

                if !matches!(audio_format, 1 | 0xFFFE)
                    || channels == 0
                    || sample_rate == 0
                    || bits == 0
                {
                    return Err(WavError::InvalidFormat);
                }
                let byte_rate = if byte_rate > 0 {
                    byte_rate
                } else {
                    sample_rate
                        .saturating_mul(u32::from(channels))
                        .saturating_mul(u32::from(bits / 8))
                };
                if byte_rate == 0 {
                    return Err(WavError::InvalidFormat);
                }
                format = Some((sample_rate, channels, bits, byte_rate));
            }
            b"data" => {
                if size == 0 {
                    return Err(WavError::MissingData);
                }
                data_size = Some(size);
                // A WAV normally puts fmt before data. Both chunks are found by
                // now in that case, and there is no need to walk the payload.
                if format.is_some() {
                    break;
                }
            }
            _ => {}
        }

        let padded = size + (size & 1);
        let next = body.saturating_add(padded);
        if next <= pos {
            break;
        }
        pos = next;
    }

    let (sample_rate, channels, bits_per_sample, byte_rate) =
        format.ok_or(WavError::MissingFormat)?;
    let data_bytes = data_size.ok_or(WavError::MissingData)?;
    let duration_secs = data_bytes as f64 / f64::from(byte_rate);
    if !duration_secs.is_finite() || duration_secs <= 0.0 {
        return Err(WavError::InvalidDuration);
    }

    Ok(WavInfo {
        sample_rate,
        channels,
        bits_per_sample,
        data_bytes: data_bytes as u64,
        duration_secs,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wav(seconds: f64) -> Vec<u8> {
        let frames = (16_000.0 * seconds) as u32;
        let data = frames * 2;
        let mut bytes = Vec::with_capacity(44 + data as usize);
        bytes.extend_from_slice(b"RIFF");
        bytes.extend_from_slice(&(36 + data).to_le_bytes());
        bytes.extend_from_slice(b"WAVE");
        bytes.extend_from_slice(b"fmt ");
        bytes.extend_from_slice(&16u32.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&1u16.to_le_bytes());
        bytes.extend_from_slice(&16_000u32.to_le_bytes());
        bytes.extend_from_slice(&32_000u32.to_le_bytes());
        bytes.extend_from_slice(&2u16.to_le_bytes());
        bytes.extend_from_slice(&16u16.to_le_bytes());
        bytes.extend_from_slice(b"data");
        bytes.extend_from_slice(&data.to_le_bytes());
        bytes.resize(44 + data as usize, 0);
        bytes
    }

    #[test]
    fn reads_a_pcm16_mono_file() {
        let info = inspect(&wav(2.5)).unwrap();
        assert_eq!(info.sample_rate, 16_000);
        assert_eq!(info.channels, 1);
        assert_eq!(info.bits_per_sample, 16);
        assert!((info.duration_secs - 2.5).abs() < 0.001);
    }

    #[test]
    fn rejects_junk() {
        assert_eq!(inspect(b"not a wav"), Err(WavError::NotWave));
        assert_eq!(inspect(b"RIFF\0\0\0\0WAVE"), Err(WavError::MissingFormat));
    }

    #[test]
    fn clamps_a_data_chunk_to_the_body() {
        let mut bytes = wav(1.0);
        let data = bytes.len() - 44;
        bytes[40..44].copy_from_slice(&(data as u32 + 100_000).to_le_bytes());
        let info = inspect(&bytes).unwrap();
        assert!((info.duration_secs - 1.0).abs() < 0.001);
    }

    #[test]
    fn skips_unknown_chunks() {
        let mut bytes = wav(1.0);
        // Insert a LIST chunk between fmt and data.
        let mut with_list = bytes.split_off(36);
        bytes.extend_from_slice(b"LIST");
        bytes.extend_from_slice(&4u32.to_le_bytes());
        bytes.extend_from_slice(b"INFO");
        bytes.append(&mut with_list);
        let info = inspect(&bytes).unwrap();
        assert!((info.duration_secs - 1.0).abs() < 0.001);
    }
}
