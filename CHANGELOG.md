# Changelog

## 0.2.7

From the drive with 0.2.6 (2026-11-13):

- Fix: the sound cut out for about 0.75 s roughly every two minutes with no navigation voice. The
  navigation plays its effect sounds on our MICOM type (0x45), and when one ends MICOM closes the
  channel; vesd dropped its track, waited 500 ms and faded back in. It now keeps the track and the
  level, waits 100 ms for a voice prompt and, if none comes, takes the channel back (about 0.1 s).
- Fix: pulling away from standstill, the level and (with "shifting") the pitch dipped before
  rising with the speed. The drive level now holds with the pedal down, and first gear holds the
  launch rpm until the road speed catches up. The upshift at about 20 km/h stays.
- Fix: switching sound packs stalled the audio thread (the loop levels were worked out from all
  samples there); they are now worked out when the pack loads.
- Louder: the whole volume range is 6 dB louder. Above full scale the extra goes through an upward
  makeup stage before the soft clip, so it adds no new hard clipping; a full-pedal pull, already at
  full scale, gains little.
- New setting speed_boost ("고속에서 키우기", off / low / mid / high = 0 / 3 / 6 / 9 dB, default
  high): the level rises from 40 to 110 km/h against wind and tyre noise.
- New experimental choice for prompts, "voice" ("안내 음성 중에도 작게 (실험)", off by default): MICOM
  takes our channel at every navigation voice prompt; with this choice vesd takes it back 400 ms
  into the prompt and plays at 30 % until it ends. It may cut prompts; if MICOM refuses, ends it
  again, or a prompt ends right after vesd took the channel, vesd gives up for the rest of the drive
  (logged as "policy: voice regrab given up").
- Replay reports rms_dbfs, rms_mark_dbfs, limit_fraction, max_gap_ms and regrabs; Sweep has launch,
  highway and chimes runs.
- tools/resynth: noise=<dB> (-40..0) sets the level of the rendered noise; a pack that fails a check
  no longer leaves a half-written folder.

## 0.2.6

- Eight user slots for sound packs (user1 to user8, was 3). The app's sound button opens a list of
  all 13 choices with each slot's pack name, instead of stepping through them one tap at a time.
- Fix: in aligned packs, a loop whose length was rounded down to a sample lost its last engine
  cycle (43 cycles at 6500 rpm are 17504.3 samples: 42 played), so the wrap missed the loop's seam.
  It now plays every whole cycle.
- Optional `level <dB>` line in pack.txt (-24 to 12): turns a whole pack down or up after the
  per-loop level matching, for packs that sound louder or clip on the car's speaker.
- tools/resynth: a host tool that tracks the engine cycle in a recording, measures its order
  spectra, fits an order x resonance model, extrapolates it down to idle and renders an aligned
  pack with a level line. It is for packs you keep on your own car: recordings and everything made
  from them stay out of git (.gitignore, and the tool refuses to write inside a git worktree).

## 0.2.5

From the first drives with 0.2.4 (2026-11-06: the sound starts right after install):

- Fix: the built-in sound packs (i4t, v6, v8, i6) never loaded on the car since 0.2.0 ("v8: empty",
  the synth played). HMG-API makes a mod's folder readable by root only and opens just the service's
  own jar to its user, and vesd runs as system. The packs now travel inside vesd.jar. Packs imported
  from USB into the user slots were not affected.
- The status line uses the ICE behaviour's words: "후진: 작게" (R plays quietly), "공회전 (가속)",
  "엔진 브레이크", "기어 신호 없음". "P/N/R: 무음" is left for the classic behaviour only.
- If the car refuses the audio channel (startAudio false, seen once in a log), vesd no longer
  counts it as open: it checks MICOM's audio stack, and otherwise tries again after 1, 2, 4, 8, 16
  and then every 30 s (status "소리 채널 재시도 중").

## 0.2.4

- Fix: 0.2.3 still never started the sound, and the status froze (drives 2026-11-05/06: stuck on
  "주행 중 · 재생", or on "안내 음성 중 (작게)" with no prompt playing). Android 4.4 starts a
  streaming AudioTrack only when its buffer is exactly full (framesReady() >= frameCount), and
  0.2.3 filled whole 220-frame blocks only: 3960 of the car's 4096 frames. The audio loop then
  waited for room forever without rendering, so the engine model, the prompt policy and the
  status stopped at that moment, and a prompt or chime that started later never ended in the
  status (it was judged with the frozen clock). The touch click had nothing to do with it.
  0.2.3's host test started its model track a block short of full, so it passed.
- The buffer is now filled exactly: the last block is written in part (TrackFeeder), to the
  track's real frame count (AudioTrack.getNativeFrameCount).
- A fresh AudioTrack for each sound start (released when the sound stops), instead of pausing,
  flushing and reusing one.
- A track that stays full and doesn't play for a second is replaced (backing off 1, 2, 4 ... 30 s),
  a failed write too; meanwhile the engine model and the prompt policy keep running, so the
  status stays live and the channel is still closed when it should be.
- The app's status shows "소리 처리 멈춤" when vesd's sound loop stops and "소리 출력 안 됨" when
  the sound is on but the track doesn't play, instead of the state it froze in. The STATUS answer
  has an `audio` line (loop and play age, track frames, resets), and vesd logs a line a minute
  while the sound is on.
- The USB kit's update saves the running vesd's logs and logcat lines to the USB before it
  restarts the service.

## 0.2.3

- Fix: from 0.2.0 the sound never started and the status stayed on "공회전" (drive 2026-11-05).
  Android 4.4 starts a streaming AudioTrack only once its buffer is full, and 0.2.0's write pacer
  kept only 80 ms of the 186 ms buffer queued, so playback never began and vesd waited for room
  forever (no rendering, the model frozen at its first state). The pacer now fills the whole
  buffer while the play position doesn't move (start, or after an underrun) and paces to about
  80 ms once it plays. L4 models AudioFlinger's fill-to-start in the host tests.

## 0.2.2

The owner's wishes after the first real drive (2026-11-05):

- Under navigation prompts and alerts the engine sound plays on at 30 % instead of stopping (setting
  `prompts` = duck, the default, or pause). Calls, ringing and voice recognition still stop it. The
  stock navigation's voice still silences it: MICOM takes our channel for the prompt. It never
  opens the channel while a prompt plays, and waits 0.5 s after the channel is taken (the prompt's
  own start arrives a few ms later).
- Back 0.5 s after a prompt instead of 1.5 s.

## 0.2.1

From the first real drive with 0.1.2 (2026-11-05):

- The audio channel setting is gone: the sound plays on the navigation-effect channel 0x45 only.
  On the system-alarm channel 0x46 the stock navigation's voice prompts did not play at all. A
  stored 0x46 is ignored, which also ends the silence that followed a channel change mid-drive
  (the path was closed for the old channel and not reopened until the next pause).

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
