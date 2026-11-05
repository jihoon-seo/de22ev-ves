# Changelog

## Unreleased

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
