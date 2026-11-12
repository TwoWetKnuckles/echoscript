# Enhancement workbench — 0.6.0 candidate

Galaxy S22+ is the reported user device. Physical-device acceptance remains required.

The recording timeline shows the full-duration waveform and a bounded Hann/FFT256 spectral overview (128 time tiles). Tap to seek; Play/Pause/Resume and the position slider control authenticated playback. Drag to select a time interval, or enter exact start/end milliseconds. Create clip from selection saves a separate PCM16 artifact with a shortened duration; the original remains immutable. This overview is not a high-resolution frequency-selection editor.

Five one-touch presets populate draft controls: Speech band, Rumble, Hum 50 Hz, Hum 60 Hz, Gentle denoise. Tap the selected preset to restore the preceding controls. Render commits an immutable derivative; toggling draft controls does not modify an existing render. Start from a prior revision or edit its stored recipe to replace a render without stacking the same effects twice.

Every effect has an enable switch, integer slider and numerical input from −100 through 0 to +100. Disabled and zero-strength processing are neutral with the optional limiter off. Actual parameter mapping is displayed beside each control and recorded with the software algorithm version. Ordered processing: time shift, short-plateau interpolation, preamp, low/mid/high EQ, speech band, hum notches, buzz notch, short room reflections, estimated echo, envelope gate, granular pitch, stereo width, optional soft peak limiter.

| Control                | Mapping / interpretation                                                                                                           |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Preamp                 | ±30 dB                                                                                                                             |
| Low / speech / high EQ | ±18 dB; 180 / 1000 / up to 4000 Hz                                                                                                 |
| Speech band            | Signed wet mix of 100–4000 Hz band; negative adds removed residue                                                                  |
| Hum                    | Four notches at 50/60 Hz harmonics; negative emphasizes residue                                                                    |
| Buzz                   | Adjustable 100–20000 Hz notch, strictly below Nyquist; negative emphasizes residue                                                 |
| Room                   | Positive adds 31/67/113 ms reflections; negative subtracts estimated delayed components                                            |
| Echo                   | Signed delayed feed-forward mix, up to 0.8 amplitude; 1–500 ms editable delay                                                      |
| Time shift             | ±100 ms; advance/delay with silence padding, unchanged duration                                                                    |
| Declip                 | Interpolates short saturated runs with unclipped endpoints within 32 samples each side; negative emphasizes interpolation residual |
| Denoise                | Envelope gate below −45 dBFS, up to 24 dB attenuation; negative emphasizes removed signal                                          |
| Pitch                  | ±12 semitones using two-grain delay crossfade; fixed duration with possible latency/transient artifacts                            |
| Stereo width           | Mid/side, zero to double side level; exactly two channels required                                                                 |

These are transparent preliminary techniques, not universal standards presets or certified enhancement. Denoise is an envelope gate, not speech/source isolation. Delay subtraction is approximate echo/room compensation, not blind acoustic reconstruction. Interpolation cannot recover lost waveform peaks. Pitch, stereo processing and filtering can change perception or remove relevant content. Retain and compare originals and intermediate uncompressed renders.

[SWGDE Best Practices for Enhancement of Digital Audio](https://www.swgde.org/documents/published-complete-listing/20-a-001-2/) recommends signal-specific evaluation, repeatable processing documentation and input/output comparison rather than a universal preset. No compliance or admissibility claim is made.

## Multiple regions and overlap

Drag or type an interval, set effects, then Add selection with these effects. Each region captures its own rack. Select a listed region, edit, and Update selected region to replace it; Remove/Clear removes it from the draft. Up to 32 regions are supported. With regions present, only saved region racks are applied, in displayed order. Overlaps accumulate through sequential immutable render passes. Outside each pass's interval PCM samples are unchanged. This requires one stored intermediate per region, not a full-file RAM buffer. Cancelled or failed plans retain completed intermediate renders; retry starts from the recorded base artifact.

Without regions the rack applies to the typed selection or whole file. Clipping uses the active audio and creates a new timeline coordinate system starting at zero. Processing recipes record original selection coordinates. Transcriptions use the active rendered audio and require renewed review after changes.

## Processing changelog and restoration

Each derivative records actual signed controls, physical parameter mappings, interval, parent identity/hash linkage, result SHA-256, algorithm version, overload count and full workbench recipe. Revision history displays the operation and permits Restore; Undo/Redo navigate saved audio revisions in creation order (branches remain visible). Restoring moves the active pointer and appends a custody event without deleting later renders. Restored workbench recipes repopulate controls/regions and retain their base artifact. Old-format renders remain playable but do not have reconstructable rack recipes.

Saved renders are read from the encrypted vault; undo is not an in-memory audio cache. Rendering checks available storage and uses fixed PCM/delay buffers. Missing/corrupt media, unsupported decoders, device/key loss, thermal limits and available storage can prevent playback or rerender. No history eviction or silent deletion is performed. Physical S22+ performance, headphones/listening evaluation and the full release gates remain open.
