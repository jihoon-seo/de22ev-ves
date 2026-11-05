# Changelog

## 0.2.0

From the second car check (2026-11-05) and the owner's wishes after it:

- Sound packs: four built-in engines (inline-4 turbo, V6, cross-plane V8, inline-6) rendered offline
  by tools/render, a Java port of DasEtwas/enginesound (MIT) with a throttle input for the overrun
  sound; the packs are CC0. PackVoice plays the two loops nearest the rpm per layer (on-load,
  off-load), pitch-shifted, crank-phase aligned for rendered packs, level-matched to the synth. The
  owner's packs can be imported from USB (VES/packs/<folder>) into three user slots. Setting
  `sound`; the synth stays the default until the packs are checked in the car.
- Like an ICE car (setting `behavior`, default `ice`): idle in P, N and D whenever Ready (free revs
  in P/N), quiet in R, engine braking when coasting; exhaust pops on lift-off (setting `pops`). The
  0.1 modes remain as `behavior=classic`.
- Fix: the sound stayed off for up to 60 s after voice recognition. MICOM re-sends AudioStart(0x45)
  when it restores our channel; that start is now recognised, and a lost navigation-chime yield
  expires after 5 s.
- Fix: the car check stalled at the 60 s hold when the voice-recognition screen covered it; it now
  re-attaches to a hold still running or moves past one that finished.
- Faster response: about 80 ms queued instead of the whole 186 ms AudioTrack buffer, and the normal
  pedal response follows in 200 ms instead of 500 ms.
- HMG-API's vehicle readings are used although hmgapid exits with code 1 after answering; the
  fallback keeps them fresh between its 3 s polls; the car check's drive log no longer stalls on them.
- Car check: one tone step instead of the channel matrix, and a 6 s free-rev demo per sound (parked
  only) to compare the synth and the packs.
- USB kit: an update is in use at once (it asks HMG-API to update the app and restart vesd; status
  UPDATED_IN_USE_NOW). Only a first install still needs the car restart, for the menu tile, star key
  action and split-screen widget.

## 0.1.2

From the first car check (2026-11-05):

- Louder and audible on the speaker that plays the navigation channel. At volume 100 the sound was
  almost or entirely inaudible: the level was 0.5 of full scale at most, and at low rpm nearly all of
  it was the 30-70 Hz firing frequency, which that speaker hardly plays (the 660/880 Hz test tones were
  heard clearly). Volume 100 is now full scale ((v/100)^1.5), a 120 Hz high-pass and +11 dB on
  400-2500 Hz go into a 1.6x soft clip, and the ICE exhaust rasp sits at 8x the firing rate
  (320-1400 Hz). At 15 km/h the sound above 300 Hz is about 5x what 0.1.1 played.
- Shares the navigation-effect channel (0x45) with the navigation. MICOM reports AudioStart/AudioEnd
  for every startAudio/endAudio of the type, ours or the navigation's, and endAudio ends whichever
  sound holds it: 0.1.1 cut the navigation's chimes and kept "playing" into a channel the navigation
  had closed. Our own events are now told apart by timing (ves.core.SharedChannel): a navigation
  chime makes us step aside without endAudio, and its end (or the channel closed under us) brings us
  back with a fresh startAudio.
- Channels 0x39 (TTS effect: took the music source over, Bluetooth audio didn't come back) and 0x3
  (navigation voice: reported as a navigation prompt) are no longer offered or accepted.
- Car check: continues at the step it was on after leaving the screen (to start the radio ...);
  opens a new session folder when continued after a restart; asks only for the two usable channels.
- Car check: it said there was no USB stick when the stick had no VES folder (HMG-API only lists a
  stick that already holds the mod's folder). The USB kit now carries the VES folder and the
  message says to create it.
- A constant -4.18 g longitudinal acceleration (no YRS12 data on this car) is ignored instead of
  read as regenerative braking; hscan reconnects while hscand is quiet after boot are logged once,
  not every 3 s; the car check records why HMG-API refused vehicle readings.

## 0.1.1

- Fix: the app always showed "서비스 연결 안 됨" and the car check stopped with "error Try again".
  On Android 4.4, LocalSocket.close() doesn't close a socket that LocalServerSocket.accept() returned,
  so the app never saw the end of vesd's answer and timed out, and vesd leaked a descriptor per
  request. vesd now ends each answer with shutdownOutput and closes the descriptor itself.
- Docs: HMG-API's Mods tab keeps a change only after "Save settings".

## 0.1.0

- First test version for DE22EV.KOR.0000.V039.001.260902: vesd service (hscan_mux decoding with an
  HMG-API readings fallback, procedural engine sound, navigation-channel playback that steps aside for
  voices), the "엔진 사운드" app with a guided car check that logs to USB, a split-screen widget, a
  star key action and a menu tile. Installed on one car; the car check has not been run yet.
