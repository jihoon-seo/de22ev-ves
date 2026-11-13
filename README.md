# de22ev-ves

[한국어](README.ko.md)

An [HMG-API](https://github.com/VysseCodes/HMG-API) mod that adds a **virtual engine sound inside
the cabin** of a 1st-gen Kia Niro EV (Gen5W head unit): the sound is synthesized live from vehicle
speed, accelerator pedal and motor power, with on/off and volume controls.

> **Test version 0.2.10, for one car and firmware:** `DE22EV.KOR.0000.V039.001.260902` (onebin
> `de22ev_kr`). Unofficial; see [Disclaimer](#disclaimer).

## How it works

- **Signals (read only).** The head unit's `hscand` pushes the car's data records to clients of the
  abstract socket `hscan_mux`. `vesd` decodes the EV records it needs: accelerator pedal, gear and
  Ready (VCU), motor power and torque (MCU), speed (0.5 km/h), longitudinal acceleration and brake
  pressure. If the stream goes quiet it falls back to HMG-API's vehicle readings. Nothing is written
  to the car: no CAN, no vehicle commands.
- **Sound.** Sound packs: loops at fixed rpm, by load (on-load, overrun, and in between), crossfaded
  and pitch-shifted to the virtual rpm, with optional recorded pops and shift sounds. Eight packs are
  built in (`packs/`): VEX 4-cyl (default), VEX V10, AE86 4A-GE, AMG M156 V8, 911 GT3 992, GT-R VR38,
  S2000 F20C and S2000 Baked. **Their sounds are by 히릿** (made with their engine physics simulator),
  processed with `tools/resynth`, and used in this mod with their permission; they are not covered by the
  MIT licence (see [THIRD_PARTY.md](THIRD_PARTY.md)). Own packs can be imported from USB into eight
  user slots (see [Sound packs](#sound-packs)). The procedural synth and the CC0 packs of 0.2.0-0.2.7
  are no longer offered; the synth only plays if no pack can be loaded at all. The car has no motor-rpm signal, so the rpm is computed from speed
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
are optional, and so are up to two layers in between (`load=0.45`: 0 = off, 1 = on, at least 2 loops
each). Optional one-shot sounds: `event pop|bubble|shift_up|shift_down <file>.wav` (0.02–2 s, up to 16 of a
kind) play at the pops setting's bursts (pops from `pop_rpm`, default 4000, bubbles below) and on the
virtual gear changes of `ice_shift`. Loops are 0.2–2 s, at most 96 of them and 2,000,000 samples (4 MB); file names
`[a-z0-9_-].wav`. Each loop is brought to the same level; an optional `level -4.5` line (dB,
-24 to 12) turns the whole pack down or up, for packs that sound louder or clip on the car's
speaker. Put the folder under `VES/packs/` on the USB stick and use "USB에서 소리 팩
가져오기" in the app: vesd validates it, copies it into a user slot and checks the copy. Packs you
import stay on your car; only use recordings you may use.

## Settings

| key | values |
|---|---|
| `enabled` | off (default) / on |
| `volume` | 0–100 (default 40); since 0.2.7 every value is 6 dB louder than in 0.2.6 |
| `sound` | `vex4` (default), `vex_v10`, `ae86`, `amg_v8`, `gt3`, `gtr`, `s2000`, `s2000_baked`, `user1`–`user8`; an empty or broken slot plays `vex4` |
| `profile` | rpm model (and the synth's voice): `ice_smooth`, `ice_shift`, `ev_future` |
| `behavior` | `ice` (default: sound whenever Ready) / `classic` (the `mode` below) |
| `prompts` | under navigation chimes and alerts: `duck` (default, 30 %) / `pause`. The stock navigation's voice takes the channel, so it pauses either way (the 0.2.7-0.2.9 `voice` experiment cut prompts and is gone; a stored `voice` reads as `duck`); calls and voice recognition always pause |
| `pops` | exhaust pops on lift-off: `off`, `soft` (default), `strong` |
| `mode` | with `classic`: `idle_regen` (quiet idle at a stop + regen sound), `stop_silent_regen`, `accel_only`, `drive_all` |
| `idle_level` | idle loudness, 10–60 % of the driving sound |
| `regen_level` | regen sound 0–100 % |
| `response` | pedal response `soft` / `normal` / `sport` |
| `fade_above` | fade out above 60/80/100/120 km/h, or off |
| `speed_boost` | louder with speed, from 40 km/h up to +3/+6/+9 dB at 110 km/h: `off`, `low`, `mid`, `high` (default); less of it at a high volume |

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

Code: [MIT](LICENSE). The built-in sounds in `packs/` are by 히릿 and used with permission; they are
not MIT, and using them anywhere else needs their permission ([THIRD_PARTY.md](THIRD_PARTY.md)).
