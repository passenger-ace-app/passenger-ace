# 조수석 에이스

혼자 운전하는 사람에게 옆자리 사람처럼 이야기를 들려주다가, 갈림길을 놓치기 전에 미리 알려 주고, 안내가 끝나면 끊긴 문장부터 이어 가는 차량용 앱입니다.

- 이야기: 저작권이 끝난 고전 단편 20편과 연재 6회(녹음 파일이 앱 안에 있어 인터넷 없이 재생)
- 내비가 말하면 멈추고, 안내가 끝나면 끊긴 문장 처음부터 다시 읽습니다.
- 갈림길 미리 알림: 내비 회전 안내(NaviHelper `onTBTInfo`)를 받아 큰 갈림길 전에 한 번 말합니다.
- 옆자리 안내: 정차 중 "물어보기"로 김 서림·폭우 같은 상황을 물으면 답하고, "네"라고 하면 앞유리 김 서림 제거를 켭니다. 주행 중에는 연료 부족, 전조등, 깜빡이 같은 상황을 먼저 말합니다.
- 차 음성 비서(Gleo Interface): `app/src/main/assets/schema.json`에 길 문제 묻기(`ASK_ROAD_HELP`)를 등록했습니다. 이야기가 켜져 있을 때 비서가 부르면 앱이 준비된 답을 녹음으로 말합니다.

## APK

- release APK: 이 저장소의 [Releases](../../releases/latest)에 있는 `KwonJuyeol_PassengerAce_release.apk`
- APK가 약 109MB라 GitHub 파일 한도(100MB)를 넘어 Releases에 올렸습니다.

## 실행 방법

1. Pleos Connect 에뮬레이터를 준비합니다. 공식 안내: https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator
2. 에뮬레이터를 켜고 APK를 설치합니다.
   ```bash
   adb install -r KwonJuyeol_PassengerAce_release.apk
   ```
3. 앱 목록에서 "조수석 에이스"를 엽니다. 첫 화면에서 이야기를 고르면 바로 읽기 시작합니다.
4. 내비 연동: 에뮬레이터의 내장 내비에서 목적지를 정하고 길 안내를 시작합니다.
   - 내비 음성이 나오면 이야기가 멈추고, 끝나면 끊긴 문장부터 이어 갑니다.
   - 큰 갈림길 전에는 미리 알림 카드와 말이 나옵니다.
5. 주행 상태: 기어를 D로 두면 앱 화면이 물러나고 소리만 이어집니다. 에뮬레이터를 창 없이 켰다면 Virtual Console을 씁니다.
   ```bash
   adb forward tcp:8088 tcp:8088
   # 1초마다 세션 유지: curl -X POST "http://localhost:8088/browser/session?client=test"
   curl -X POST "http://localhost:8088/vehicle/gear?gear=Drive"
   curl -X POST "http://localhost:8088/vehicle/controls?speed=60"
   ```
6. 물어보기: 기어 P에서 "물어보기" 단추를 누르고 "앞유리에 김이 서려요"처럼 말합니다. 에뮬레이터는 PC 마이크를 씁니다.
7. 운전대 버튼: 미디어 다음 키로 다음 이야기로 넘어갑니다(`adb shell input keyevent KEYCODE_MEDIA_NEXT`).

## 테스트 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터 | Pleos Connect 에뮬레이터 `release_connect_v2.1.0`(시스템 이미지 pleos_car_emulator x86_64 Rev.1, Android Automotive API 34), 화면 2560x1440, 160dpi |
| 빌드 | Windows 11, JDK 21, Gradle 8.14.3, compileSdk 35, minSdk 29 |
| SDK | Pleos Connect SDK: NaviHelper 3.0.5, SpeechToText 3.3.1, Vehicle 2.3.2 |
| 단위 시험 | 141개(`./gradlew testDebugUnitTest`) |

## 필요한 설정

- 인터넷이 필요 없습니다. 이야기와 안내 말은 모두 앱 안의 녹음 파일입니다(음성 인식 SDK가 붙여 오는 인터넷 권한도 뺐습니다).
- 권한: 알림(헤드업 카드), 음성 인식(STT), 내비 경로(NAVI_ROUTE), 차 상태(기어·연료·창문·공조·전조등), 공조 조작(김 서림 제거)
- Connect SDK와 NaviHelper는 공개 문서에 있는 API만 씁니다.

## 소스 빌드

```bash
JAVA_HOME=<JDK 17 또는 21 폴더> ./gradlew assembleDebug testDebugUnitTest
```

- `local.properties`에 Android SDK 경로를 적습니다(저장소에 넣지 않음).
- release 서명 키는 저장소에 없습니다. `./gradlew assembleRelease`로 서명 안 된 APK를 만든 뒤 각자의 키로 서명합니다.
- 화면 시험 그림은 `app/build/screens/`에 나옵니다.

## 폴더

| 폴더 | 내용 |
|---|---|
| `app` | 본 앱(패키지 `app.passengerace`) |
| `navsim` | 개발 때 쓴 내비 음성 흉내 앱. 심사에는 필요 없습니다 |
| `tools/make_assets.py` | 녹음 원본을 앱 자산으로 옮기는 스크립트 |
| `docs/시험-기록.md` | 에뮬레이터 시험 기록 |

## 만든 것의 출처

- 이야기 글: 저작권이 끝난 원전의 줄거리를 따라 새로 썼습니다.
- 목소리: ElevenLabs TTS(유료 구독)
- 표지 그림: ChatGPT 이미지 생성
- 글꼴: Pretendard(SIL OFL 1.1, `app/src/main/assets/licenses/`)
