# de22ev-ves

[한국어](README.ko.md)

An [HMG-API](https://github.com/VysseCodes/HMG-API) mod that adds a **virtual engine sound inside
the cabin** of a 1st-gen Kia Niro EV (Gen5W head unit): the sound is synthesized live from vehicle
speed, accelerator pedal and motor power, with on/off and volume controls.

> **Test version 0.1.2, for one car and firmware:** `DE22EV.KOR.0000.V039.001.260902` (onebin
> `de22ev_kr`). It ships with a guided car check whose results decide the audio route for 1.0.
> Unofficial; see [Disclaimer](#disclaimer).

## How it works

- **Signals (read only).** The head unit's `hscand` pushes the car's data records to clients of the
  abstract socket `hscan_mux`. `vesd` decodes the EV records it needs: accelerator pedal, gear and
  Ready (VCU), motor power and torque (MCU), speed (0.5 km/h), longitudinal acceleration and brake
  pressure. If the stream goes quiet it falls back to HMG-API's vehicle readings. Nothing is written
  to the car: no CAN, no vehicle commands.
- **Sound.** Procedural synthesis (no recordings): a 4-cylinder engine with a virtual rev counter
  (smooth, or with virtual gear shifts), or a futuristic EV sound. The car has no motor-rpm signal,
  so the rpm is computed from speed and load.
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

## Settings

| key | values |
|---|---|
| `enabled` | off (default) / on |
| `volume` | 0–100 (default 40) |
| `profile` | `ice_smooth`, `ice_shift`, `ev_future` |
| `mode` | `idle_regen` (quiet idle at a stop + regen sound), `stop_silent_regen`, `accel_only`, `drive_all` |
| `idle_level` | idle loudness, 10–60 % of the driving sound |
| `regen_level` | regen sound 0–100 % |
| `response` | pedal response `soft` / `normal` / `sport` |
| `fade_above` | fade out above 60/80/100/120 km/h, or off |
| `audio_type` | MICOM stream type for the channel (advanced; chosen from the car check) |

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
