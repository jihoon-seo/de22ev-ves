# de22ev-ves

[한국어](README.ko.md)

An [HMG-API](https://github.com/VysseCodes/HMG-API) mod that adds a **virtual engine sound inside
the cabin** of a 1st-gen Kia Niro EV (Gen5W head unit): the sound is synthesized live from vehicle
speed, accelerator pedal and motor power, with on/off and volume controls.

> **Test version 0.2.6, for one car and firmware:** `DE22EV.KOR.0000.V039.001.260902` (onebin
> `de22ev_kr`). It ships with a guided car check whose results decide the audio route for 1.0.
> Unofficial; see [Disclaimer](#disclaimer).

## How it works

- **Signals (read only).** The head unit's `hscand` pushes the car's data records to clients of the
  abstract socket `hscan_mux`. `vesd` decodes the EV records it needs: accelerator pedal, gear and
  Ready (VCU), motor power and torque (MCU), speed (0.5 km/h), longitudinal acceleration and brake
  pressure. If the stream goes quiet it falls back to HMG-API's vehicle readings. Nothing is written
  to the car: no CAN, no vehicle commands.
- **Sound.** Either the procedural synth (a 4-cylinder engine or a futuristic EV sound) or a sound
  pack: loops at fixed rpm, on-load and overrun, crossfaded and pitch-shifted to the virtual rpm.
  Four packs are built in (inline-4 turbo, V6, cross-plane V8, inline-6), rendered offline by
  `tools/render`, a Java port of [DasEtwas/enginesound](https://github.com/DasEtwas/enginesound)
  (MIT); the rendered packs are CC0. Own packs can be imported from USB into eight user slots (see
  [Sound packs](#sound-packs)). The car has no motor-rpm signal, so the rpm is computed from speed
  and load (smoothly, or with virtual gear shifts).
- **Behaviour.** By default like an ICE car: whenever Ready, it idles in P, N and D (and revs with
  the pedal in P/N), plays quietly in R, and coasting plays engine braking, with optional exhaust
  pops on lift-off. Only Ready off silences it. The 0.1 modes remain as `behavior=classic`.
- **Audio route.** Like the stock navigation prompts: an `AudioTrack` on Android stream 12 plus
  `MicomAudioManager.startAudio` on the MICOM's mixing channel, so it plays over radio, Bluetooth and
  Android Auto music. The channel is opened only while there is sound and closed after 2 s of
  silence. Navigation prompts, calls, voice recognition and similar voices make it fade out within
  150 ms and resume 1.5 s after they end.
- **Controls.** The "엔진 사운드" app (big buttons; also a menu tile), a split-screen panel widget,
  a ★ key action, and HMG-API's Mods tab. Volume is the mod's own (0–100, capped), independent of
  the car's volume knob. Off after install.
- It never touches the exterior pedestrian warning sound (VESS), the amplifier's ASD settings or
  any system file.

## Sound packs

A pack is a folder with `pack.txt` and PCM 16-bit mono 22050 Hz WAV loops:

```
name   my car
cylinders 4
idle   800
redline 6500
loop idle.wav rpm=800 load=on
loop r2000_on.wav rpm=2000 load=on
loop r2000_off.wav rpm=2000 load=off
...
```

At least 3 on-load loops reaching from at most 1200 to at least 4000 rpm; off-load (overrun) loops
are optional. Loops are 0.2–2 s, at most 64 of them and 2.5 MB of audio; file names
`[a-z0-9_-].wav`. Each loop is brought to the same level; an optional `level -4.5` line (dB,
-24 to 12) turns the whole pack down or up, for packs that sound louder or clip on the car's
speaker. Put the folder under `VES/packs/` on the USB stick and use "USB에서 소리 팩
가져오기" in the app: vesd validates it, copies it into a user slot and checks the copy. Packs you
import stay on your car; only use recordings you may use.

## Settings

| key | values |
|---|---|
| `enabled` | off (default) / on |
| `volume` | 0–100 (default 40) |
| `sound` | `synth` (default), `i4t`, `v6`, `v8`, `i6`, `user1`–`user8` |
| `profile` | rpm model (and the synth's voice): `ice_smooth`, `ice_shift`, `ev_future` |
| `behavior` | `ice` (default: sound whenever Ready) / `classic` (the `mode` below) |
| `prompts` | under prompts and alerts: `duck` (default, 30 %) / `pause` / `voice` (experimental: also 30 % under the stock navigation's voice, taking the channel back 0.4 s into it; it may cut navigation prompts, and if a prompt seems cut vesd gives up for the rest of the drive); calls and voice recognition always pause |
| `pops` | exhaust pops on lift-off: `off`, `soft` (default), `strong` |
| `mode` | with `classic`: `idle_regen` (quiet idle at a stop + regen sound), `stop_silent_regen`, `accel_only`, `drive_all` |
| `idle_level` | idle loudness, 10–60 % of the driving sound |
| `regen_level` | regen sound 0–100 % |
| `response` | pedal response `soft` / `normal` / `sport` |
| `fade_above` | fade out above 60/80/100/120 km/h, or off |

P, N, R or not Ready is always silent.

## Install

Releases carry `de22ev-ves-<version>.USB.zip` (NEMESIS USB kit: install / status / uninstall) and the
signed mod zip. See `usb/USAGE.en.txt`.

## Build and test

```
ANDROID_JAR=.../platforms/android-19/android.jar BUILD_TOOLS=.../build-tools/<ver> SIGN_KEY=... ./build.sh
./tests/run-all.sh
```

The host tests cover the sound core (decoding, synthesis, behaviour modes, the audio-channel state
machine, a CPU budget), the "read only" allow-list, consistency of mod.json / settings / app, and
the USB kit with a mock `hmgapid`. `core/src/ves/tools` has `Sweep` (synthetic drive traces) and
`Replay` (trace or raw `hscan.raw` capture to WAV).

## Disclaimer

An unofficial hobby project, not affiliated with or endorsed by Hyundai Motor Group, Kia, Hyundai
Mobis, or the HMG-API / NEMESIS authors. Tested on one car only. Keep the volume reasonable: it must
never cover warnings. Use at your own risk; no warranty (see [LICENSE](LICENSE)).

## License

[MIT](LICENSE).
