# de22ev-ves

[English](README.md)

1세대 기아 니로 EV(Gen5W 헤드유닛)에 **실내 가상 엔진 사운드**를 더하는
[HMG-API](https://github.com/VysseCodes/HMG-API) 모드입니다. 속도, 가속 페달, 모터 출력으로 소리를
실시간으로 만들고, 켜기/끄기와 볼륨을 조절할 수 있습니다.

> **시험판 0.2.8, 시험한 차량과 펌웨어 하나:** `DE22EV.KOR.0000.V039.001.260902` (onebin `de22ev_kr`).
> 안내형 차량 점검이 들어 있고, 그 결과로 1.0의 오디오 경로를 정합니다. 비공식 프로젝트입니다.
> [면책](#면책)을 읽어 주세요.

## 동작

- **신호 (읽기만).** 헤드유닛의 `hscand`가 차량 데이터 레코드를 추상 소켓 `hscan_mux`로 보냅니다.
  `vesd`는 필요한 EV 레코드만 해석합니다: 가속 페달·기어·Ready(VCU), 모터 출력·토크(MCU),
  속도(0.5 km/h), 종방향 가속도, 브레이크 압력. 스트림이 끊기면 HMG-API 차량 값으로 대신합니다.
  차에는 아무것도 쓰지 않습니다(CAN, 차량 명령 없음).
- **소리.** 소리 팩: 고정 회전수·부하(가속, 감속, 그 사이)별 루프를 가상 회전수에 맞춰 섞고 음높이를
  바꾸며, 팩에 따라 녹음된 팝과 변속음도 냅니다. 8가지 팩이 들어 있습니다(`packs/`): VEX 4기통(기본),
  VEX V10, AE86 4A-GE, AMG M156 V8, 911 GT3 992, GT-R VR38, S2000 F20C, S2000 Baked. **이 소리들은
  히릿의 저작물**(히릿의 엔진 물리 시뮬레이터로 만든 소리)이며 `tools/resynth`로 가공해 허락을 받아 이
  모드에서 씁니다. MIT 라이선스 대상이 아닙니다([THIRD_PARTY.md](THIRD_PARTY.md)). 직접 만든 팩을 USB에서
  사용자 칸 8개로 가져올 수 있습니다([소리 팩](#소리-팩)). 0.2.0-0.2.7의 합성음과 CC0 팩은 더 이상 고를 수
  없고, 합성음은 어떤 팩도 읽지 못할 때만 납니다. 이 차에는 모터 회전수 신호가 없어서 속도와 부하로 회전수를
  계산합니다(부드럽게 또는 가상 변속).
- **동작.** 기본은 내연기관처럼: Ready면 P/N/D에서 공회전(P/N에서는 페달로 회전수가 오름), R에서는
  작게, 발을 떼고 달리면 엔진 브레이크 소리, 페달을 뗄 때 배기 팝(설정). Ready가 아니면 무음.
  0.1의 동작 모드는 `behavior=classic`으로 남아 있습니다.
- **오디오 경로.** 순정 내비 안내음과 같은 방식입니다: Android 스트림 12의 `AudioTrack`과 MICOM
  섞임 채널의 `MicomAudioManager.startAudio`. 그래서 라디오·블루투스·Android Auto 음악 위에 섞여
  나옵니다. 소리가 있을 때만 채널을 열고 2초 조용하면 닫습니다. 내비 안내, 전화, 음성인식 같은
  음성이 나오면 150ms 안에 줄이고, 끝나고 1.5초 뒤 다시 냅니다.
- **조작.** "엔진 사운드" 앱(큰 버튼, 메뉴 타일), 분할 화면 위젯, ★ 버튼 동작, HMG-API Mods 탭.
  볼륨은 모드 자체 볼륨(0–100, 상한 있음)이라 차 볼륨 노브와 따로 움직입니다. 설치 직후에는 꺼짐.
- 차 밖 보행자 경고음(VESS), 앰프의 ASD 설정, 시스템 파일은 건드리지 않습니다.

## 소리 팩

팩은 `pack.txt`와 PCM 16비트 모노 22050 Hz WAV 루프가 든 폴더입니다:

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

가속(on) 루프 3개 이상, 1200 rpm 이하부터 4000 rpm 이상까지. 감속(off) 루프와 그 사이의 부하 층 둘까지
(`load=0.45`: 0 = off, 1 = on, 층마다 루프 2개 이상)는 선택. `event pop|bubble|shift_up|shift_down <파일>.wav`
(0.02–2초, 종류마다 16개까지, 선택)는 팝 설정의 터짐마다(`pop_rpm`, 기본 4000 이상은 pop, 아래는 bubble)와
`ice_shift`의 가상 변속 때 한 번씩 재생됩니다. 루프 하나는
0.2–2초, 최대 96개·200만 샘플(4 MB), 파일 이름은 `[a-z0-9_-].wav`. 루프마다 같은 크기로 맞춰 재생하며,
`level -4.5` 줄(dB, -24~12, 선택)로 팩 전체를 줄이거나 키울 수 있습니다 (차 스피커에서 크게 들리거나
찌그러지는 팩에). USB의 `VES/packs/` 아래에 폴더를 두고 앱에서
"USB에서 소리 팩 가져오기"를 누르면 vesd가 검사해 사용자 칸에 복사하고 복사본을 다시 검사합니다.
가져온 팩은 그 차에만 있습니다. 쓸 권리가 있는 녹음만 쓰세요.

## 설정

| 키 | 값 |
|---|---|
| `enabled` | 끔(기본) / 켬 |
| `volume` | 0–100 (기본 40). 0.2.7부터 모든 값이 0.2.6보다 6 dB 큼 |
| `sound` | `vex4`(기본), `vex_v10`, `ae86`, `amg_v8`, `gt3`, `gtr`, `s2000`, `s2000_baked`, `user1`–`user8`. 빈 칸이나 깨진 팩은 `vex4`로 |
| `profile` | 회전수 움직임: `ice_smooth`, `ice_shift`(가상 변속, 변속음이 있는 팩은 변속음), `ev_future` |
| `behavior` | `ice`(기본: Ready면 항상 소리) / `classic`(아래 `mode`를 따름) |
| `prompts` | 안내 음성·알림음 때: `duck`(기본, 30%로 계속) / `pause`(멈춤) / `voice`(실험: 순정 내비 음성 중에도 0.4초 뒤 채널을 되찾아 30%로 계속. 내비 안내가 끊길 수 있고, 끊긴 것 같으면 그 주행이 끝날 때까지 쓰지 않음). 전화·음성인식 때는 항상 멈춤 |
| `pops` | 페달을 뗄 때 배기 팝: `off`, `soft`(기본), `strong` |
| `mode` | `classic`일 때: `idle_regen`(정차 시 작은 공회전 + 회생음), `stop_silent_regen`, `accel_only`, `drive_all` |
| `idle_level` | 공회전 크기, 주행 소리의 10–60% |
| `regen_level` | 회생음 0–100% |
| `response` | 페달 반응 `soft` / `normal` / `sport` |
| `fade_above` | 60/80/100/120 km/h부터 줄여 끄기, 또는 끔 |
| `speed_boost` | 40 km/h부터 110 km/h까지 차츰 키우기(+3/+6/+9 dB): `off`, `low`, `mid`, `high`(기본). 볼륨이 높으면 덜 커짐 |

P, N, R이거나 Ready가 아니면 항상 무음입니다.

## 설치

릴리스에 `de22ev-ves-<버전>.USB.zip`(NEMESIS USB 키트: 설치/상태/제거)과 서명된 모드 zip이
있습니다. `usb/사용방법.txt`를 보세요.

## 빌드와 테스트

```
ANDROID_JAR=.../platforms/android-19/android.jar BUILD_TOOLS=.../build-tools/<ver> SIGN_KEY=... ./build.sh
./tests/run-all.sh
```

PC 테스트는 소리 핵심부(해석, 합성, 동작 모드, 오디오 채널 상태 기계, CPU 예산), "읽기만" 허용 목록,
mod.json·설정·앱의 일치, 가짜 `hmgapid`로 USB 키트를 확인합니다. `core/src/ves/tools`에는
`Sweep`(가상 주행 기록)과 `Replay`(기록이나 `hscan.raw`를 WAV로)가 있습니다.

## 면책

개인 취미로 만든 비공식 프로젝트이며 현대자동차그룹, 기아, 현대모비스, HMG-API/NEMESIS 제작자와 관련이
없습니다. 차량 한 대에서만 시험했습니다. 경고음을 가리지 않도록 볼륨을 적당히 두세요. 사용에 따른 책임은
사용자에게 있으며 어떠한 보증도 없습니다([LICENSE](LICENSE)).

## 라이선스

코드: [MIT](LICENSE). `packs/`의 내장 소리는 히릿의 저작물로 허락을 받아 쓰며 MIT가 아닙니다. 다른 곳에서
쓰려면 히릿의 허락이 필요합니다([THIRD_PARTY.md](THIRD_PARTY.md)).
