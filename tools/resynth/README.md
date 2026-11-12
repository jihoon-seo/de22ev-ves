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

## Model

`Fitter` fits, separately for on-load and off-load frames (a `Model.Layer` each), in one regularized
least-squares pass (normal equations, Cholesky, a few hundred unknowns):

    log A(n, r) = s_n + h(n fc(r)) + l(r)        dB, fc = r / 120, l(r) = a + b ln(r / rRef)

s_n is the order's own level (the cylinder comb), h a resonance curve over frequency (linear between 25 Hz
nodes up to the cap), l the level against rpm. The exact gauge freedoms (constants moved between s, h and l)
and the near one (a log-tilt: h + beta ln f, s_n - beta ln n, b - beta) are fixed explicitly: mean s = 0,
mean h = 0 and no ln-n tilt in s; a small second-difference penalty keeps h smooth where data are thin.
Orders within 10 dB of the local floor are masked; an order masked in most frames is absent and never
synthesized. Above the observed orders, s_n follows a tail that is linear in ln n (fitted over the last
observed octave) plus the mean residual of n mod cylinders, so the comb carries on down to idle, where far
more orders fall below the cap. Noise: log N(band, r) = q(band) + m ln r per 1/3-octave band; the crank-wobble
skirt per band; half orders above -30 dB re the strongest order; per-order phases from the low end on load.
A model is a private text file (`*.vesmodel`).

`Fitter.fitM0` is the baseline M0: the order envelope over frequency at the lowest measured rpm, held in Hz,
its level scaled by the overall level slope.

## Pack

`PackRenderer` renders a model into a pack for `ves.core.PackVoice` (`aligned yes`): on and off loops at
800 (K even), 900, 1000, 1125, 1260, 1400, 1575, 1764, 2000, 2250, 2520, 2800, 3150, 3500, 3920, 4410, 4900,
5400, 6000, 6615 and 7350 rpm, 42 loops. Every loop holds K whole engine cycles from crank phase 0:
N * rpm = K * 2646000 exactly (2646000 = 120 * 22050), K even so half orders close too, about 0.9-1.07 s; the
whole pack is about 0.88M samples (PackLoader allows 1.25M) and 1.8 MB (PackStore takes up to 3 MB). The
synthesis is additive in the loop-length DFT at 22050 Hz: order n on bin K n, so a loop is exactly periodic
and wraps without a seam. Orders below 40 Hz are dropped, nothing goes above 8.5 kHz (the top loop plays up
to 1.22x faster at 9000 rpm). Every order has the same phase in every loop and both layers (the measured one
where it is coherent, R >= 0.5, else seeded random), so PackVoice's linear crossfade between neighbours adds
in phase. Noise is shaped per 1/3-octave band in the same DFT, plus the crank-wobble skirt beside each order.
Each loop peaks at -1 dBFS. The render is seeded and uses StrictMath: two renders are byte-identical. The
pack folder holds only `pack.txt` and the loops.

## A/B

- `ThirdOctave`: 100 ms frames, power per 1/3-octave band from 100 Hz to 6.3 kHz, distance = mean over frames
  and bands of the level difference in dB (identical input: 0 dB; a +3 dB shelf: 3 dB).
- `TrackSynth`: model-direct resynthesis along the recording's own track (its crank phase, the on or off layer
  as the segments say, the model's noise), for A/B against the recording at 22.05 kHz.

## Command line

Build and run (Git Bash; relative paths, the repo path has spaces):

    find core/src/ves/core tools/resynth/src -name '*.java' > /tmp/rs.txt
    javac --release 8 -encoding UTF-8 -d build/resynth/classes @/tmp/rs.txt
    java -cp build/resynth/classes ves.resynth.Main <command> ...

| command | does |
|---|---|
| `track <in.wav> <out.track.txt> [cyl=N]` | cycle-frequency track (any rate in, analysed at 22.05 kHz); prints the segments and comb decisions |
| `segments <in.wav> <track> <out.segments.txt>` | proposes on/off/excluded segments; review and edit the sidecar |
| `fit <in.wav> <track> <segments> <out.vesmodel> [cyl=N] [seed=N] [model=auto\|m1\|m0]` | fits on and off layers; prints the in-range RMSE and the low-end holdout of M1 and M0 (fit on the upper 60 % of log-rpm, scored below); `auto` keeps M1 unless M0 holds out better |
| `render <model> <pack-dir> name=<name> [off=<model>] [noise=off] [seed=N]` | writes pack.txt and the 42 loops; `off=` takes the off-load layer from another model |
| `ab bands <a.wav> <b.wav>` | mean 1/3-octave distance |
| `ab track <in.wav> <track> <segments> <model> <out-dir> <name> [off=<model>]` | writes `<name>_orig22k.wav` and `<name>_resynth_track.wav`, prints the on-load and off-load distances |
| `demo <pack-dir> <out-dir> <name> [track=<track> segments=<segments>]` | through PackVoice (with its output stage): `<name>_demo_sweep.wav` (800-7000-800 rpm on load, the same off load, 3 s at 800) and, with a track, `<name>_demo_track.wav` along it |

Every writer refuses a path inside a git worktree (exit 2). Keep the private data in a folder of its own,
outside any checkout.

## Tests

`bash tests/resynth/run.sh [filter]` (also part of `tests/run-all.sh`): RS-W1/W2 (WAV, resampler), RS-T1/T2
(tracker on a synthetic launch with shifts, lift-off and coast; a V8 pattern with a weak order 1), RS-C1
(order levels within 0.5 dB through a 2500 rpm/s sweep, floor, a half order), RS-S1 (segments), RS-P2 (no
derived data tracked), RS-F1..F5 (fit recovery in range and at 800-2000 rpm, gauge invariance, the order model
against M0 at 800 rpm, masked orders stay silent, a holdout below the fitted rpm), RS-R1..R9 (a fixture
pack: exact lengths and limits, PackLoader accepts it, seamless wraps, equal phases, PackVoice at a midpoint
rpm, nothing above the cap, byte-identical renders, levels against a built-in pack, a 800-7350 rpm sweep),
RS-P1/P1b (every writer refuses a folder inside a git worktree, exit 2), RS-A1/A2 (the 1/3-octave distance; the
fixture model driven along its own track).
