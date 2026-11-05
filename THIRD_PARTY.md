# Third-party notices

## enginesound (DasEtwas)

- Project: https://github.com/DasEtwas/enginesound
- Version used: commit `e5fcca587397c0c8ba9c9d24874b951fed74d260`
- License: MIT

Derived files in this repository (a Java port of the sound model in `src/gen.rs`, `fix_engine` in
`src/utils.rs` and the `.esc` engine configs; the throttle extension, config format, loop grid and
checks are new code in the same files and are MIT too):

- `tools/render/src/ves/render/*.java`: port of `gen.rs` (`Gen`, `LoopBuffer`, `DelayLine`,
  `WaveGuide`, `LowPassFilter`, `Noise`, `Cylinder`, `Engine`, `Generator`), `utils.rs`
  (`EngineConfig`), the RON `.esc` reader/converter (`Ron`, `EscConvert`), and the CLIs
  (`Render`, `Grid`, `Sweep`, `Wav`)
- `tools/render/src/ves/render/check/*.java`: analysis helpers for the tests (new code, same
  header and license)
- `tools/render/engines/i4t.txt` (from `example4.esc`), `v6.txt` (from `example5.esc`),
  `v8.txt` (from `example1.esc`), `i6.txt` (from `example2.esc` and `example5.esc`)

Rendered engine-sound packs made with `tools/render` are dedicated to the public domain under
CC0 1.0. The code above stays under the MIT terms below.

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
