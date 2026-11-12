# Enhancement workbench — 0.6.0 candidate

Galaxy S22+ is the reported user device. Physical-device acceptance remains required.

The recording timeline shows the full-duration waveform and a bounded Hann/FFT256 spectral overview (128 time tiles). Tap to seek; Play/Pause/Resume and the position slider control authenticated playback. Drag to select a time interval, drag a highlighted edge to adjust it, or enter exact start/end milliseconds. Create clip from selection saves a separate PCM16 artifact with a shortened duration; the original remains immutable. The full-file overview samples brief windows and can miss events between them; it is not a continuous high-resolution spectrogram. The revised selection handles and compact transport layout require fresh device validation.

Five one-touch presets populate draft controls: Speech band, Rumble, Hum 50 Hz, Hum 60 Hz, Gentle denoise. Tap the selected preset to restore the preceding controls. Render commits an immutable derivative; toggling draft controls does not modify an existing render. Start from a prior revision or edit its stored recipe to replace a render without stacking the same effects twice.

The effect panel repeats a compact, read-only waveform overview and playback control so the selection stays visible while adjusting settings. Change the selection on the main timeline; playback uses the currently selected saved audio, and draft effect changes are heard after rendering rather than as live processing. This layout requires fresh device validation.

Every effect has an enable switch, integer slider and numerical input from −100 through 0 to +100. The draft groups controls into Gain & EQ, Filters, Restore, Time & space, and Pitch & stereo, and shows one effect panel at a time while listing all enabled effects. Invalid strength text displays a warning while the last valid value remains in the draft. Disabled and zero-strength processing are neutral with the optional limiter off. Actual parameter mapping is displayed beside each control and recorded with the software algorithm version. Ordered processing: time shift, short-plateau interpolation, preamp, low/mid/high EQ, parametric EQ, selected frequency band, speech band, hum notches, buzz notch, short room reflections, estimated echo, envelope gate, granular pitch, stereo width, optional soft peak limiter. Grouped-control and numerical-input tests require a fresh run.

| Control                | Mapping / interpretation                                                                                                           |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| Preamp                 | ±30 dB                                                                                                                             |
| Low / speech / high EQ | ±18 dB; 180 / 1000 / up to 4000 Hz                                                                                                 |
| Parametric EQ          | Adjustable center frequency and Q; ±18 dB. Center must be below Nyquist.                                                        |
| Selected band          | Adjustable low/high frequencies; positive blends an isolated band, negative emphasizes its removed residue.                    |
| Speech band            | Signed wet mix of 100–4000 Hz band; negative adds removed residue                                                                  |
| Hum                    | Four notches at 50/60 Hz harmonics; negative emphasizes residue                                                                    |
| Buzz                   | Adjustable 100–20000 Hz notch, strictly below Nyquist; negative emphasizes residue                                                 |
| Room                   | Positive adds 31/67/113 ms reflections; negative subtracts estimated delayed components                                            |
| Echo                   | Signed delayed feed-forward mix, up to 0.8 amplitude; 1–500 ms editable delay                                                      |
| Time shift             | ±100 ms; advance/delay with silence padding, unchanged duration                                                                    |
| Declip                 | Interpolates short saturated runs with unclipped endpoints within 32 samples each side; negative emphasizes interpolation residual |
| Denoise                | Broadband envelope gate with an adjustable −90 to −10 dBFS threshold (default −45); up to 24 dB attenuation. Negative emphasizes removed signal. |
| Pitch                  | ±12 semitones using two-grain delay crossfade; fixed duration with possible latency/transient artifacts                            |
| Stereo width           | Mid/side, zero to double side level; exactly two channels required                                                                 |

These are transparent preliminary techniques, not universal standards presets or certified enhancement. Denoise is an envelope gate, not speech/source isolation. Delay subtraction is approximate echo/room compensation, not blind acoustic reconstruction. Interpolation cannot recover lost waveform peaks. Pitch, stereo processing and filtering can change perception or remove relevant content. Retain and compare originals and intermediate uncompressed renders.

For a draft measured noise-floor setting, select up to 30 seconds of representative noise and choose **Measure selected noise floor**. The app measures broadband RMS independently on each channel, proposes the strongest channel's level as the gate threshold, and limits that threshold to −90 through −10 dBFS. It enables Denoise at its existing nonzero strength, or +50 if it was zero; adjust or disable it before rendering. It saves the measurement, interval, source hash and per-channel levels as an encrypted analysis artifact. A render using that threshold records the measurement as a parent; entering a manual threshold removes the measurement reference. Check the selection carefully: speech or other meaningful sounds in it may also be attenuated. Gain, EQ and other earlier effects can change the level reaching the gate. This is an amplitude threshold estimate, not a spectral noise profile. Synthetic and device validation of this draft remain pending.

For draft before/after clipping review, choose a decoded or enhanced revision under the A/B reference and select **Compare waveform and clipping**. The comparison reports each revision's near-full-scale sample count and overlays their full-duration peak envelopes when durations match. Counts include all channels, and different durations or channel counts affect them. Envelopes summarize peaks; they do not show individual reconstructed samples, prove clipping, or prove restoration accuracy. The reference analysis is linked to its original audio revision without changing the active revision. Synthetic and device validation remain pending.

[SWGDE Best Practices for Enhancement of Digital Audio](https://www.swgde.org/documents/published-complete-listing/20-a-001-2/) recommends signal-specific evaluation, repeatable processing documentation and input/output comparison rather than a universal preset. No compliance or admissibility claim is made.

## Multiple regions and overlap

Drag or type an interval, set effects, then Add selection with these effects. Each region captures its own rack. Select a listed region, edit, and Update selected region to replace it; Remove/Clear removes it from the draft. Up to 32 regions are supported. With regions present, only saved region racks are applied, in displayed order. Overlaps accumulate through sequential immutable render passes. Outside each pass's interval PCM samples are unchanged. This requires one stored intermediate per region, not a full-file RAM buffer. Cancelled or failed plans retain completed intermediate renders; retry starts from the recorded base artifact.

Without regions the rack applies to the typed selection or whole file. Clipping uses the active audio and creates a new timeline coordinate system starting at zero. Processing recipes record original selection coordinates. Transcriptions use the active rendered audio and require renewed review after changes.

## Processing changelog and restoration

Each derivative records actual signed controls, physical parameter mappings, interval, parent identity/hash linkage, result SHA-256, algorithm version, overload count and full workbench recipe. Revision history displays the operation and permits Restore; Undo follows the recorded audio parent, and redo presents saved child branches. Restoring moves the active pointer and appends a custody event without deleting later renders. Restored workbench recipes repopulate controls/regions and retain their base artifact. Old-format renders remain playable but do not have reconstructable rack recipes.

Saved renders are read from the encrypted vault; undo is not an in-memory audio cache. Rendering checks available storage and uses fixed PCM/delay buffers. Missing/corrupt media, unsupported decoders, device/key loss, thermal limits and available storage can prevent playback or rerender. No history eviction or silent deletion is performed. Physical S22+ performance, headphones/listening evaluation and the full release gates remain open.


## Detailed spectral inspection under development

A draft detail view computes consecutive Hann or Hamming windows over a selected short interval, with selectable FFT size, hop, frequency scale and display floor. It retains a bounded number of windows and may require a shorter selection. Display bands aggregate strongest channel/bin magnitude, not speech probability or power spectral density; log display cannot add FFT resolution, and the final partial hop is not padded. Time/frequency dragging enables the separate band-isolation effect in the draft; other effects still operate over the whole selected time interval. The full-file overview remains a sampled overview. Synthetic signal, UI and physical-device validation of this draft remain pending.

The window/hop terminology follows the [SciPy ShortTimeFFT documentation](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.ShortTimeFFT.html). EchoScript records its own parameters and display scaling; it does not use SciPy at runtime.
