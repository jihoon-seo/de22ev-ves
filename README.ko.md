# de22ev-ves

[English](README.md)

1세대 기아 니로 EV(Gen5W 헤드유닛)에 **실내 가상 엔진 사운드**를 더하는
[HMG-API](https://github.com/VysseCodes/HMG-API) 모드입니다. 속도, 가속 페달, 모터 출력으로 소리를
실시간으로 만들고, 켜기/끄기와 볼륨을 조절할 수 있습니다.

> **시험판 0.1.0, 시험한 차량과 펌웨어 하나:** `DE22EV.KOR.0000.V039.001.260902` (onebin `de22ev_kr`).
> 안내형 차량 점검이 들어 있고, 그 결과로 1.0의 오디오 경로를 정합니다. 비공식 프로젝트입니다.
> [면책](#면책)을 읽어 주세요.

## 동작

- **신호 (읽기만).** 헤드유닛의 `hscand`가 차량 데이터 레코드를 추상 소켓 `hscan_mux`로 보냅니다.
  `vesd`는 필요한 EV 레코드만 해석합니다: 가속 페달·기어·Ready(VCU), 모터 출력·토크(MCU),
  속도(0.5 km/h), 종방향 가속도, 브레이크 압력. 스트림이 끊기면 HMG-API 차량 값으로 대신합니다.
  차에는 아무것도 쓰지 않습니다(CAN, 차량 명령 없음).
- **소리.** 녹음 없이 계산으로 만듭니다: 가상 회전수를 쓰는 4기통 엔진(부드럽게 또는 가상 변속),
  또는 EV 미래형. 이 차에는 모터 회전수 신호가 없어서 속도와 부하로 회전수를 계산합니다.
- **오디오 경로.** 순정 내비 안내음과 같은 방식입니다: Android 스트림 12의 `AudioTrack`과 MICOM
  섞임 채널의 `MicomAudioManager.startAudio`. 그래서 라디오·블루투스·Android Auto 음악 위에 섞여
  나옵니다. 소리가 있을 때만 채널을 열고 2초 조용하면 닫습니다. 내비 안내, 전화, 음성인식 같은
  음성이 나오면 150ms 안에 줄이고, 끝나고 1.5초 뒤 다시 냅니다.
- **조작.** "엔진 사운드" 앱(큰 버튼, 메뉴 타일), 분할 화면 위젯, ★ 버튼 동작, HMG-API Mods 탭.
  볼륨은 모드 자체 볼륨(0–100, 상한 있음)이라 차 볼륨 노브와 따로 움직입니다. 설치 직후에는 꺼짐.
- 차 밖 보행자 경고음(VESS), 앰프의 ASD 설정, 시스템 파일은 건드리지 않습니다.

## 설정

| 키 | 값 |
|---|---|
| `enabled` | 끔(기본) / 켬 |
| `volume` | 0–100 (기본 40) |
| `profile` | `ice_smooth`, `ice_shift`, `ev_future` |
| `mode` | `idle_regen`(정차 시 작은 공회전 + 회생음), `stop_silent_regen`, `accel_only`, `drive_all` |
| `idle_level` | 공회전 크기, 주행 소리의 10–60% |
| `regen_level` | 회생음 0–100% |
| `response` | 페달 반응 `soft` / `normal` / `sport` |
| `fade_above` | 60/80/100/120 km/h부터 줄여 끄기, 또는 끔 |
| `audio_type` | 채널의 MICOM 스트림 종류(고급, 차량 점검 결과로 고름) |

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

[MIT](LICENSE).
