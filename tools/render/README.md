# ves.render: offline engine-sound renderer

A host-only Java tool (Java 8 language level, no dependencies) that renders engine-sound loops on
the PC. The output is a "pack": one WAV loop per (rpm, load) grid point plus a `pack.txt`
manifest. A runtime grid player crossfades between the loops. Nothing here ships to the car.

The sound model is a port of **[DasEtwas/enginesound](https://github.com/DasEtwas/enginesound)**
(Rust, MIT, Copyright (c) 2020 DasEtwas), upstream commit
`e5fcca587397c0c8ba9c9d24874b951fed74d260`, `src/gen.rs` (plus `fix_engine` from `src/utils.rs`).
That model is loosely based on Baldan, Lachambre, Delle Monache and Boussard, "Physically informed
car engine sound synthesis for virtual and augmented environments" (2015).

## Model (as upstream)

Each cylinder has a crank offset and produces `piston_motion` (cos over the cycle) plus a
`fuel_ignition` pulse. Valve curves (`intake_valve`, `exhaust_valve`) modulate the cylinder-side
reflection (alpha) of an intake and an exhaust waveguide. Each exhaust waveguide continues into an
extractor waveguide to a shared exhaust collector, which feeds a straight pipe and parallel muffler
elements. A waveguide is two delay lines with end reflections, soft-limited at amplitude 20. The
intake collector picks up low-passed intake noise. Crankshaft speed wobbles by low-passed noise.
The output mixes intake, engine vibration (the low-passed cylinder sum) and exhaust, scaled by the
master volume, minus a 0.5 Hz low pass (the DC blocker). `crankshaft_pos` runs 0..1 over one
4-stroke cycle (two revolutions, 120/rpm seconds).

| Java (`ves.render`)                | upstream `gen.rs`                         |
|------------------------------------|-------------------------------------------|
| `Generator.generate / gen / reset` | `Generator::generate / gen / reset`       |
| `Engine`, `Engine.Muffler`         | `Engine`, `Muffler`                       |
| `Cylinder.pop / push`              | `Cylinder::pop / push`                    |
| `WaveGuide`, `DelayLine`, `LoopBuffer`, `LowPassFilter`, `Noise` | same names |
| `Gen.exhaustValve / intakeValve / pistonMotion / fuelIgnition` | free functions |
| `EngineConfig.build`, `EngineConfig.samples` | deserialize + `utils::fix_engine` |

The arithmetic is single precision in the same order, so at throttle 1 the model behaves like
upstream. Deviations, all deliberate:

- **Noise seed.** Upstream seeds `XorShiftRng` from the system clock. Here the 128-bit state comes
  from an explicit 64-bit seed (splitmix64, one stream each for intake noise and crankshaft noise).
  The xorshift128 recurrence and the `[-1, 1]` mapping are the same.
- **`mul_add`.** Java 8 has no `Math.fma`. `LowPassFilter.filter` computes in double: the product
  is exact and only the final add rounds (twice, so in rare ties 1 ulp off a true fma).
- **sin/cos** go through `StrictMath` and are rounded to float. This is within 1 ulp of Rust's
  `f32::sin`, not necessarily bit-identical.
- **Empty delay lines.** A delay shorter than one sample gets 1 sample (upstream would panic). The
  .esc delays were tuned at the GUI's 48 kHz and are truncated to whole samples at the render rate,
  as upstream does.
- Left out: the recorder, the GUI and its clipping flag, `get_changed` (slider resizing).

## Throttle / load (extension)

Upstream has no throttle: `gen.rs` always renders a firing engine at a set rpm. ves.render adds
`throttle` in [0, 1] (1 = on-load, 0 = overrun / fuel cut). Each affected term is scaled by
`s = 1 - (1 - off) * (1 - throttle)`. That is exactly 1.0 at throttle 1 (bit-identical to upstream)
and `off` at throttle 0:

| key (`[throttle]`)  | scales                         | why                                         | default |
|---------------------|--------------------------------|---------------------------------------------|---------|
| `off_ignition`      | `ignition_factor`              | fuel cut, only a residual pulse is left     | 0.4     |
| `off_piston`        | `piston_motion_factor`         | lower cylinder pressure under vacuum        | 0.85    |
| `off_intake`        | `intake_volume`                | closed throttle plate hides induction roar  | 0.3     |
| `off_intake_noise`  | `intake_noise_factor`          | little air flow, little hiss / whoosh       | 0.3     |
| `off_lowpass_hz`    | output blended with a one-pole low pass by `(1 - throttle)` | duller overrun | 2000 |

At throttle 1 the low pass is bypassed. The filter keeps running, so a throttle change never
jumps. Partial throttle interpolates linearly.

## Engine config format

Plain text, one `key = value` per line, `[section]` blocks, `#` comments, LF or CRLF. Unknown
or duplicate keys are errors. Numbers are parsed as Java floats (same values as Rust `f32`).

```
[engine]            # gen.rs Engine scalars
rpm = 800                                   # default rpm (Grid/Render/Sweep set their own)
intake_volume = 0.27
exhaust_volume = 0.68
engine_vibrations_volume = 0.05
intake_noise_factor = 0.3
intake_noise_lp.delay = 0.0000914           # LowPassFilter as upstream: delay = 1 / cutoff Hz
engine_vibration_filter.delay = 0.0108
intake_valve_shift = -0.02                  # valve timing, fraction of a cycle (-0.5..0.5)
exhaust_valve_shift = 0
crankshaft_fluctuation = 0.26
crankshaft_fluctuation_lp.delay = 0.0175
volume = 0.1                                # optional, Generator.volume (upstream CLI default)
dc_lp.delay = 2                             # optional, the 0.5 Hz DC blocker of main.rs

[cylinders]         # count = N; every other key: one value for all, or a list of N
count = 4
firing_order = 1, 3, 4, 2                   # or crank_offset = 0, 0.75, 0.5, 0.25
firing_angle = 0, 180, 360, 540             # optional, degrees in firing order (default even)
exhaust_waveguide.delay = 0.000146, 0.000167, 0.000167, 0.000188   # seconds, both chambers
exhaust_waveguide.beta = 0.06               # (x.chamber0.delay / x.chamber1.delay if they differ)
intake_waveguide.delay = 0.000146
intake_waveguide.beta = 0.024
extractor_waveguide.delay = 0.0012, 0.0016, 0.0016, 0.0012
extractor_waveguide.alpha = 0
extractor_waveguide.beta = -0.4
intake_open_refl = 0.01
intake_closed_refl = 0.75
exhaust_open_refl = -0.0007
exhaust_closed_refl = 0.74
piston_motion_factor = 1.38
ignition_factor = 5
ignition_time = 0.11                        # fraction of a cycle

[muffler]
straight_pipe.delay = 0.000625
straight_pipe.alpha = 0.058
straight_pipe.beta = -0.018
elements = 4                                # list keys below have 4 values (or one)
element.delay = 0.000354, 0.000417, 0.000458, 0.0005
element.alpha = 0
element.beta = -0.04

[throttle]          # optional, see above
off_ignition = 0.4
```

`exhaust_waveguide.alpha` and `intake_waveguide.alpha` are optional: the valves overwrite them on
every sample. A cylinder fires when `fract(crank_pos + crank_offset)` passes 0.5. A cylinder firing
at angle `a` (degrees of the 720-degree cycle) therefore gets `crank_offset = fract(-a / 720)`.

Upstream `.esc` (RON) files load directly: any path ending in `.esc` is converted on the fly. The
converter handles the subset the upstream files use (anonymous structs, lists, numbers,
comments). `ves.render.EscConvert <in.esc> <out.txt>` writes the text form, keeping the numbers'
source text.

## Engines

`engines/*.txt`: each file's header says which upstream example it is based on and what changed.

| file  | character                       | based on            | firing                       |
|-------|---------------------------------|---------------------|------------------------------|
| `i4t` | inline-4 turbo, raspy hot hatch | example4            | 1-3-4-2, even                |
| `v6`  | 60 degree V6, smooth growl      | example5            | 1-2-3-4-5-6, even, per-bank pipes |
| `v8`  | cross-plane V8, burble          | example1            | 1-8-4-3-6-5-7-2, per-bank pipes |
| `i6`  | inline-6, smooth rising wail    | example2 + example5 muffler | 1-5-3-6-2-4, equal pipes |

## Rendering

```
javac --release 8 -encoding UTF-8 -d build/render/classes $(find tools/render/src -name '*.java')
CP=build/render/classes

# one fixed-rpm render (2 s warm-up dropped, peak-normalised to -1 dBFS, mono PCM16)
java -cp $CP ves.render.Render tools/render/engines/v8.txt 3000 1 5 v8_3000.wav [samplerate=22050] [seed=1]

# the loop grid: rpm {800,1100,1500,2000,2600,3300,4000,4800,5600,6500} x load {on, off}
java -cp $CP ves.render.Grid tools/render/engines/v8.txt build/render/v8 [samplerate=22050] [seed=1]

# a demo sweep straight from the model: 800 -> 6000 rpm on load, back down on overrun
java -cp $CP ves.render.Sweep tools/render/engines/v8.txt v8_sweep.wav [seconds=12] [rpm_lo=800] [rpm_hi=6000]

tests/render/run.sh      # compile, render all packs, check (ENGINESOUND_SRC=<upstream> adds parity)
```

Grid loops: a fresh engine per loop and 2 s of warm-up, so the waveguides are in steady state.
The model then runs on until its crank phase (`crankshaft_pos`) wraps to 0, so every loop, on
and off, starts at the start of an engine cycle (within one sample). Each loop then holds N whole
cycles, `round(N * 120 * sr / rpm)` samples, with N chosen to get closest to 0.8 s. A player that
reads loop k at `(cycle_phase mod N_k) * 120 * sr / rpm_k` is therefore in phase across loops and
layers (`aligned yes` in pack.txt). The 5 ms after the loop end (again crank phase 0) are
crossfaded into its start. Without the fade, noise and slow filter states left seam jumps up to 7x
the largest jump inside a loop; with it they are at most 0.9x. One gain per pack
puts the loudest loop at -1 dBFS and keeps relative levels (on vs off, low vs high rpm) as the
model makes them. Files are `<engine>_<rpm>_<on|off>.wav`. `pack.txt` has the lines `name`,
`cylinders`, `idle 800`, `redline 6500`, `aligned yes` and
`loop <file> rpm=<rpm> load=<on|off> start=0 end=<samples>`. The format is what
`core/src/ves/core/PackLoader.java` reads (mono PCM16 22050 Hz, 0.2 to 2 s per loop), and
tests/render/run.sh loads every pack through it.

Output is deterministic: the same arguments give byte-identical files. The RNG is seeded
explicitly, nothing depends on the clock or on hash ordering, and only `StrictMath` is used.

## Licenses

- **Code** (`tools/render/src`, `tests/render`): MIT. It is derived from DasEtwas/enginesound
  (MIT), whose notice must be kept. See below and [THIRD_PARTY.md](../../THIRD_PARTY.md).
- **Engine configs** (`tools/render/engines`): MIT, derived from the upstream example `.esc`
  files.
- **Rendered packs** (the WAV loops and `pack.txt` this tool writes): dedicated to the public
  domain under **CC0 1.0** (https://creativecommons.org/publicdomain/zero/1.0/). The code that
  makes them stays MIT.

Upstream notice:

```
MIT License

Copyright (c) 2020 DasEtwas

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
