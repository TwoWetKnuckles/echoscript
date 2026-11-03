//! Deterministic bounded-buffer PCM primitives, separate from evidence preservation.
#![forbid(unsafe_code)]
use std::io::{self, Read, Write};

#[derive(Clone, Copy, Debug)]
pub enum Filter {
    HighPass,
    LowPass,
    Notch,
}

#[derive(Clone, Debug)]
pub struct Biquad {
    b: [f64; 3],
    a: [f64; 2],
    state: [f64; 4],
}
impl Biquad {
    pub fn new(kind: Filter, sample_rate: u32, frequency: f64, q: f64) -> io::Result<Self> {
        if !(8_000..=192_000).contains(&sample_rate)
            || !frequency.is_finite()
            || frequency <= 0.0
            || frequency >= f64::from(sample_rate) / 2.0
            || !q.is_finite()
            || !(0.05..=100.0).contains(&q)
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Invalid filter parameters",
            ));
        }
        let angle = 2.0 * std::f64::consts::PI * frequency / f64::from(sample_rate);
        let cosine = angle.cos();
        let alpha = angle.sin() / (2.0 * q);
        let a0 = 1.0 + alpha;
        let b = match kind {
            Filter::HighPass => [(1.0 + cosine) / 2.0, -(1.0 + cosine), (1.0 + cosine) / 2.0],
            Filter::LowPass => [(1.0 - cosine) / 2.0, 1.0 - cosine, (1.0 - cosine) / 2.0],
            Filter::Notch => [1.0, -2.0 * cosine, 1.0],
        };
        Ok(Self {
            b: b.map(|x| x / a0),
            a: [-2.0 * cosine / a0, (1.0 - alpha) / a0],
            state: [0.0; 4],
        })
    }
    pub fn process(&mut self, x: f64) -> f64 {
        let [x1, x2, y1, y2] = self.state;
        let y = self.b[0] * x + self.b[1] * x1 + self.b[2] * x2 - self.a[0] * y1 - self.a[1] * y2;
        self.state = [x, x1, y, y1];
        y
    }
}

/// PCM16 gain, with an optional filter per channel. Buffers remain 64 KiB.
pub fn render_canonical_wav<R: Read, W: Write>(
    mut input: R,
    mut output: W,
    gain_db: f64,
    filter: Option<(Filter, f64, f64)>,
) -> io::Result<u64> {
    if !gain_db.is_finite() || !(-60.0..=40.0).contains(&gain_db) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Gain outside policy",
        ));
    }
    let mut header = [0u8; 44];
    input.read_exact(&mut header)?;
    let u16_at = |at| u16::from_le_bytes([header[at], header[at + 1]]);
    let u32_at =
        |at| u32::from_le_bytes(header[at..at + 4].try_into().expect("fixed header bounds"));
    let channels = u16_at(22);
    let rate = u32_at(24);
    let length = u32_at(40);
    if &header[0..4] != b"RIFF"
        || &header[8..16] != b"WAVEfmt "
        || &header[36..40] != b"data"
        || u32_at(16) != 16
        || u16_at(20) != 1
        || u16_at(34) != 16
        || !(1..=8).contains(&channels)
        || !(8_000..=192_000).contains(&rate)
        || length == 0
        || u16_at(32) != channels * 2
        || u32_at(28) != rate * u32::from(channels) * 2
        || u32_at(4)
            != length
                .checked_add(36)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "WAV size overflow"))?
        || !length.is_multiple_of(u32::from(channels) * 2)
    {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Expected canonical PCM16 WAV",
        ));
    }
    let mut filters: Vec<Option<Biquad>> = (0..channels)
        .map(|_| {
            filter
                .map(|(kind, hz, q)| Biquad::new(kind, rate, hz, q))
                .transpose()
        })
        .collect::<io::Result<_>>()?;
    output.write_all(&header)?;
    let gain = 10f64.powf(gain_db / 20.0);
    let mut buffer = [0u8; 65536];
    let mut remaining = u64::from(length);
    let mut sample = 0usize;
    while remaining > 0 {
        let take = remaining.min(buffer.len() as u64) as usize;
        input.read_exact(&mut buffer[..take])?;
        for bytes in buffer[..take].as_chunks_mut::<2>().0 {
            let original = f64::from(i16::from_le_bytes([bytes[0], bytes[1]])) / 32768.0;
            let channel = sample % usize::from(channels);
            let filtered = filters[channel]
                .as_mut()
                .map_or(original, |f| f.process(original));
            let value = (filtered * gain * 32768.0).round().clamp(-32768.0, 32767.0) as i16;
            bytes.copy_from_slice(&value.to_le_bytes());
            sample += 1;
        }
        output.write_all(&buffer[..take])?;
        remaining -= take as u64;
    }
    let mut extra = [0u8; 1];
    if input.read(&mut extra)? != 0 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "Unexpected trailing source bytes",
        ));
    }
    output.flush()?;
    Ok(u64::from(length) + 44)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn filter_reduces_known_hum() {
        let mut f = Biquad::new(Filter::Notch, 16000, 60.0, 10.0).unwrap();
        let mut sum = 0.0;
        for i in 0..32000 {
            let x = (2.0 * std::f64::consts::PI * 60.0 * f64::from(i) / 16000.0).sin();
            let y = f.process(x);
            if i >= 16000 {
                sum += y * y;
            }
        }
        assert!(sum / 16000.0 < 0.00001);
    }
    #[test]
    fn invalid_parameters_rejected() {
        assert!(Biquad::new(Filter::HighPass, 16000, 9000.0, 0.7).is_err());
        assert!(Biquad::new(Filter::Notch, 16000, 60.0, f64::NAN).is_err());
    }
    #[test]
    fn filter_state_is_stream_order_deterministic() {
        let mut a = Biquad::new(Filter::LowPass, 16000, 1000.0, 0.707).unwrap();
        let mut b = a.clone();
        for i in 0..100000 {
            let x = f64::from(i % 17) / 17.0;
            assert_eq!(a.process(x).to_bits(), b.process(x).to_bits());
        }
    }
}
