#![forbid(unsafe_code)]
use echoscript_audio_core::{Filter, render_canonical_wav};
use std::{
    fs::{File, OpenOptions},
    io::{self, BufReader, BufWriter},
    path::Path,
};
fn main() {
    if let Err(error) = run() {
        eprintln!("Render failed: {error}");
        std::process::exit(1);
    }
}
fn run() -> io::Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() != 4 && args.len() != 7 {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Usage: echoscript-audio INPUT.wav NEW_OUTPUT.wav GAIN_DB [highpass|lowpass|notch HZ Q]",
        ));
    }
    let source = Path::new(&args[1]);
    let destination = Path::new(&args[2]);
    if source == destination || destination.exists() {
        return Err(io::Error::new(
            io::ErrorKind::AlreadyExists,
            "Output must be a new derivative path",
        ));
    }
    let parse = |s: &str| {
        s.parse::<f64>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "Invalid numeric parameter"))
    };
    let gain = parse(&args[3])?;
    let filter = if args.len() == 7 {
        let kind = match args[4].as_str() {
            "highpass" => Filter::HighPass,
            "lowpass" => Filter::LowPass,
            "notch" => Filter::Notch,
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "Unsupported filter",
                ));
            }
        };
        Some((kind, parse(&args[5])?, parse(&args[6])?))
    } else {
        None
    };
    let input = BufReader::new(File::open(source)?);
    let output = BufWriter::new(
        OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(destination)?,
    );
    match render_canonical_wav(input, output, gain, filter) {
        Ok(bytes) => {
            println!(
                "Rendered {bytes} bytes. Preserve source/derivative SHA-256 and operation parameters in the case record."
            );
            Ok(())
        }
        Err(error) => {
            let _ = std::fs::remove_file(destination);
            Err(error)
        }
    }
}
