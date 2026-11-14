# Audacity synthetic sandbox trial

Date: 14 November 2026. Scope: **limited editor workflow**, not an audio-quality leaderboard or forensic qualification. Seven other products in the [comparison](COMPETITIVE-ANALYSIS-2026-11-14.md) were documentation-only because their supported platform, host, license or system access was unavailable. No trial accounts or paid subscriptions were created.

## Setup

Used the official Audacity 4.0.2 x86_64 Linux AppImage from the vendor's GitHub release. Download size: 73,726,456 bytes. SHA-256: `a5f8787aa13cdb500a60fc013403b986776876b960cca962bc6e7fff066172f3`, matching the official release asset digest. The general Linux download page referenced 4.0.1; the official 4.0.2 release record was used instead.

The application ran with isolated XDG configuration/cache/data directories. Before launching it, a Linux seccomp rule denied creation of IPv4/IPv6 sockets; a socket probe failed with PermissionError as required. Unix-domain sockets remained available for the desktop. This restricts the application's ordinary network sockets; it is not a general filesystem/process-containment audit or a proof about every desktop service. No cloud account was used; the first-run UUID option was disabled. No case audio, provider key or personal material was supplied.

## Fixture and actual actions

The generated mono WAV contains 12 seconds at 16 kHz, signed 16-bit PCM: a 1 kHz tone from 2 to 10 seconds, continuous 60 Hz hum, and Gaussian noise with seed 1234. It contains no speech. Signal amplitude is deliberately below clipping. Its [fixture generator](https://github.com/TwoWetKnuckles/echoscript/blob/docs/product-discovery-20261114/docs/research/trial-fixture.py) and [measurement record](https://github.com/TwoWetKnuckles/echoscript/blob/docs/product-discovery-20261114/docs/research/trial-results.json) are included.

Observed through the native interface:

1. Completed first-run choices, skipped account creation and disabled UUID.
2. Opened the synthetic WAV and selected its full 12-second duration.
3. Switched from waveform to spectral display; the bounded tone was visibly distinct from the background.
4. Inspected categorized effect menus. Effect-dialog navigation was awkward under automation; opened reverb/time-pitch dialogs were cancelled. No effect was committed, and no enhancement quality is claimed.
5. Chose export to the computer, then full project, WAV, mono, 16 kHz and signed 16-bit PCM. No cloud export was selected.
6. Independently compared the resulting file and PCM to the input.

## Results

| Measurement                 | Input                                                              | Local export           |
| --------------------------- | ------------------------------------------------------------------ | ---------------------- |
| Bytes                       | 384,044                                                            | 384,044                |
| Frames                      | 192,000                                                            | 192,000                |
| Channels / rate / precision | Mono / 16 kHz / 16 bit                                             | Mono / 16 kHz / 16 bit |
| Peak                        | -13.640 dBFS                                                       | -13.640 dBFS           |
| RMS                         | -22.081 dBFS                                                       | -22.081 dBFS           |
| SHA-256                     | `1809a959e2a9fd637eb947b9a48d7b2460aab6a3dfaf163ede9d99f2bfc41d36` | Same                   |

Changed PCM samples: **0**. Maximum absolute PCM delta: **0**. The source file remained unchanged. This result applies to this unprocessed fixture and these export settings; it does not establish preservation for all formats, effects, dither settings or application versions.

## Product lessons and limits

The prominent spectral toggle and straightforward local import are useful references for EchoScript. The separate cloud/local export choice demonstrates why an evidence workbench should make the destination explicit. Export fields for rate, channel count and encoding should remain visible in a simple interface because they affect the artifact.

A narrow effects workflow needs predictable selection and parameter state; nested desktop menus are a poor mobile model. In this trial, the automation's pointer navigation opened unintended effect dialogs, which were cancelled. This is an automation observation rather than a claim that ordinary mouse users encounter the same problem.

Not tested: speech enhancement, denoising performance, ASR, speaker labeling, playback audibility, undo of committed effects, project crash recovery, encryption, accessibility acceptance or performance on Android. No claims of transcription accuracy, recovered speech or forensic admissibility follow from the trial.

Sources: [official release and digest](https://github.com/audacity/audacity/releases/tag/Audacity-4.0.2), [current privacy notice](https://www.audacityteam.org/legal/privacy-notice/). The trial procedure and measurements above are direct observations rather than vendor claims.
