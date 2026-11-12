# ves.resynth: engine recordings to sound packs

A host-only Java tool (Java 8 language level, no dependencies; it uses `ves.core` from `core/src`) that
analyses an engine recording, fits a model of its harmonic (engine-order) make-up and noise against rpm and
load, and resynthesizes a pack of crank-aligned loops for `ves.core.PackVoice` over the full rpm range,
including rpm the recording does not contain.

**Private data stays private.** The recordings, and everything derived from them (tracks, segment sidecars,
models, packs, A/B renders), never go into git: `.gitignore` covers `*.wav`, `pack.txt`, `*.vesmodel`,
`*.segments.txt`, `*.track.txt` and `/private/`, `tests/resynth/run.sh` fails if `git ls-files` lists any of
them, and the writers refuse any output path inside a git worktree. The tests use synthetic signals only, made
inside the tests with known parameters.

## Analysis

- **WAV in** (`WavIO`): RIFF/WAVE PCM 16-bit mono; LIST and other chunks are skipped, odd chunk sizes padded.
  Stereo, 8-bit or truncated files are an `IOException`.
- **Resampling** (`Resampler`): rational polyphase, Kaiser-windowed sinc; 48 kHz to 22.05 kHz passes up to
  9.9 kHz, the stop band (from 11.025 kHz) is about -90 dB. Analysis runs at 22.05 kHz, the pack rate.
- **Cycle frequency** (`Tracker`, `Track`): fc = rpm / 120 (one 4-stroke cycle). A harmonic-sum comb score on
  an STFT, followed through the frames by a Viterbi path that has to follow the spectral flow (the
  log-frequency peak map moves rigidly with the rpm), so it cannot drift onto another multiple of the comb.
  Per segment of sure flow, a subharmonic test in the crank-angle domain finds the finest comb (fc). Then
  phase refinement: the phase of the strongest orders in 4-cycle frames one cycle apart gives the angle error
  (order 200 needs about 1e-4 relative). A track file holds fc and the crank phase per 11.6 ms frame.
- **Order spectra** (`Orders`, `Angle`): computed order tracking. Resample to crank angle (S = 1024 samples per
  cycle), FFT over K = 4 whole cycles with a periodic Hann window: order n is bin 4n, half order n + 1/2 is
  bin 4n + 2, the rest is noise and jitter. Per frame: order level and phase, half orders, the local floor,
  the crank-wobble skirt around each order, and the noise PSD per 1/3-octave band.
- **Segments** (`Segments`): on-load / off-load / excluded, proposed from the level and the rpm slope and kept
  in an editable sidecar `<name>.segments.txt`. Shifts and lift-offs are cut out with 0.2 s either side, a
  quiet part-load hold is left out.

## Tests

`bash tests/resynth/run.sh [filter]` (also part of `tests/run-all.sh`): RS-W1/W2 (WAV, resampler), RS-T1/T2
(tracker on a synthetic launch with shifts, lift-off and coast; a V8 pattern with a weak order 1), RS-C1
(order levels within 0.5 dB through a 2500 rpm/s sweep, floor, a half order), RS-S1 (segments), RS-P2 (no
derived data tracked).
