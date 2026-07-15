# CarScan — 작업 상태 (핸드오프)

> **이어서 작업할 때**: 이 파일을 먼저 읽으면 어디까지 됐고 다음이 뭔지 알 수 있다.
> 세션이 끊기거나(토큰 한도 등) 새 세션에서 재개할 때의 단일 진실 공급원.
> 전체 설계는 `C:\Users\Mureung\.claude\plans\lucky-forging-pascal.md`.

**마지막 갱신**: 2026-07-15 / **M0~M5 완료 + M6 대부분 + M7 차량 선택·신호셋 다운로드·HUD 완료**. 차량 선택(차고) + 차종별 OBDb 신호셋 로딩 + 온디맨드 다운로드·ETag 캐시 + 앞유리 HUD까지 됨. 남은 것: 실제 광고·결제 배선(스토어 계정 필요), 실기기 검증.

---

## 빌드 방법 (매번 필요)

PATH의 `java`는 11이라 Gradle 9가 거부한다. 반드시:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash
./gradlew :core:obd:allTests
```

Apple 타깃은 **macOS 호스트에서만** 등록된다(`build-logic/.../BuildHost.kt`). Windows에선 iOS가 아예 빌드되지 않으므로 테스트 태스크는 `androidHostTest` 하나다.

## Git — 회사 계정 격리 (절대 어기지 말 것)

- 이 저장소는 **`garsu-garsu` 개인 계정** 전용. **회사 계정(`woocheolKim`) 사용 금지.**
- 저장소 로컬 신원이 이미 설정돼 있다: `garsu-garsu <265960098+garsu-garsu@users.noreply.github.com>`
- 전역 `gh` 활성 계정은 회사 계정이므로 **`gh auth switch` 를 쓰지 말 것.** 대신 토큰만 주입한다:
  ```bash
  TOKEN=$(gh auth token --user garsu-garsu)
  git -c credential.helper="!f() { echo username=garsu-garsu; echo password=$TOKEN; }; f" push origin main
  ```
- 푸시 전 항상 확인: `git log --format='%an <%ae>' | sort -u` → 회사 이메일이 한 건도 없어야 한다.
- 원격: `https://garsu-garsu@github.com/garsu-garsu/CarScan.git` (public)

---

## 진행 상황

| 마일스톤 | 상태 | 테스트 |
|---|---|---|
| **M0** 빌드 스켈레톤 | ✅ | AGP 9 폴백 없이 |
| **M1** 디코더 + 시그널셋 | ✅ | obd 141 · vehicle 31 |
| **M2** 트랜스포트 3종 + 에뮬레이터 | ✅ | transport 95 |
| **M3** 세션·폴러 · DB·리포지토리 | ✅ | (obd 141) · database 34 · data 38 |
| **M4** 게이지·라이브차트 | ✅ | designsystem 134 |
| **M5** units·theme·i18n·connect·dashboard·live | ✅ | units 140 · connect 21 · dashboard 58 · live 25 |
| **M5** :composeApp 조립 + ElmObdConnector | ✅ | composeApp 24 — **APK 16MB 빌드됨** |
| **M6** 설정·About/라이선스·i18n·수익화 모델·빌드 게이트 | 🔶 대부분 | settings 7 · monetization 신규 |
| **M6** 실제 광고·결제 배선 + Play 내부 테스트 | ⬜ 보류(스토어 계정 필요) | |
| **M7** 차량 선택(차고) + 차종별 OBDb 신호셋 로딩 | ✅ | garage · composeApp 신호셋 로딩 |
| **M7** 신호셋 다운로드 계층(번들+온디맨드 캐시) | ✅ | cache 5 · downloader/provider 11 · garage 5 |
| **M7** HUD(앞유리 게이지) | ✅ | hud 6 |
| **M7** 실기기 검증 | ⬜ 다음 | DTC·안드로이드 오토는 **제외** |

**총 700개 이상 테스트, 실패 0. 재현된 변이 40건 이상.**

### M7 완료된 것 / 다음
- **완료(스켈레톤 B)**: `:feature:garage` 차량 선택 화면(설정→"차량" 행 진입, 선택 시 Vehicle 행 기록 + activeVehicleId 설정), 큐레이션 4종 번들 에셋(Kia-EV6·Ioniq-5·Elantra·Ford-F-150, OBDb main에서 실시간 페치·커밋 SHA 고정·BY-SA 저작자표시), `BundledSignalsetSource` 확장(선택 차량 → 표준∪차종 신호셋 union, **forever-cache 제거로 차량 변경 시 옛 신호셋 반환 버그 차단**), 카탈로그·VehicleRepository DI 배선. 다운스트림(대시보드 타일 피커·poller)은 이미 union을 소비하므로 코드 변경 0.
- **DTC 조회/삭제는 제외** — 표준 OBD는 배출가스 DTC만, 나머지는 제조사 UDS(우리도 OBDb도 없음). 체크엔진 조회/삭제만 저비용 독립 기능으로 나중에 붙일 여지는 있음.
- **안드로이드 오토 제외** — 템플릿이 커스텀 게이지 렌더링 불가 + OBD 미승인 카테고리. 차 안 게이지는 **HUD**로.
- **신호셋 다운로드 계층 완료**: `SignalsetProvider`(번들→캐시→다운로드 우선순위), `SignalsetCache`(signalset 테이블 위 리포지토리), `KtorSignalsetDownloader`(raw.githubusercontent OBDb, 조건부 GET+ETag→304 재검증). **다운로드는 차량 선택 시점(온라인)에, 연결 시점(어댑터 Wi-Fi=오프라인)엔 캐시/번들만** — Wi-Fi 어댑터 시나리오 대응. 다운로드 전용 카탈로그 7종 추가(RAV4·Civic·Model3·Golf·Mach-E·Niro·Kona, 전부 OBDb 실재 확인). 저장 테이블은 M3에 이미 있던 것 그대로 활용.
- **HUD 완료**: `:feature:hud` — 속도(크게)+RPM을 우리 게이지(`Gauge`+`GaugeThemes.Hud` 고대비 팔레트, 검은 배경) 재사용해 그림. **좌우 반전**(`graphicsLayer scaleX=-1`, 앞유리 반사용), 밝기 최대·화면 켜둠·가로 고정은 플랫폼 expect/actual(android=Activity window 플래그, ios=밝기+idleTimer, jvm=no-op). **라이브러리 안 씀** — Compose/KMP용 HUD 라이브러리가 없고, 있는 건 안드로이드 View 위젯이라 게이지처럼 직접 조합. 폴러 굶김 방지 `visibility.setVisible` 호출. 대시보드 "HUD" 액션으로 진입(탭 아님=전체화면). 죽어있던 `keepScreenOn` 설정의 첫 소비자.
- **다음**: 실기기 검증.
- **아직 없는 것(후속)**: 전체 OBDb 카탈로그 인덱스(742종 열거), 카탈로그의 호스팅 갱신(현재 카탈로그는 소스 하드코딩), 연식별 신호셋 variant 선택(현재 default.json만).
- **번역**: de·es·pt-BR·ru·uk 양호, ko·pl 원어민 검수 권장(garage_* · garage_download_* 새 용어).

### M6 완료된 것 / 보류된 것
- **완료**: 설정 화면(수량별 단위·게이지 스타일·테마·화면 켜둠·기록 토글), About/라이선스 화면(**OBDb CC BY-SA 4.0 저작자표시 — 스토어 업로드 법적 관문**), 8개 로케일 신규 문자열 21개(파리티 게이트 통과), 수익화 엔타이틀먼트 모델(`:core:monetization` — 영구/구독/유예/보류/환불 규칙, 오프라인 정확성, 순수 리졸버), 데이터 위생 빌드 게이트.
- **보류(사용자 입력 필요)**: 실제 AdMob/Play Billing 배선은 실 앱 ID·Play Console 상품·서명 키가 필요하고 **공개 레포에 커밋 불가**. `:platform:android-ads` 는 컴파일되는 no-op 스텁까지만. DI에도 아직 연결 안 함(소비자 없음). 스토어 계정 준비되면 진행.
- **번역 품질**: de·es 자신 있음, pt-BR·pl 양호, ru·uk·ko 는 새로 만든 기술 용어에 원어민 검수 권장.

### 실기기 실행 — 첫 성공 (2026-07-15)
**앱이 처음으로 실기기에서 돌았다** — 삼성 갤럭시 탭 A9(`SM-X135F`, 한국어 로케일). 실행되고, 한국어 UI가 정상 렌더되고, **실제 BLE 스캔이 동작해 주변 블루투스 기기를 나열**한다.

이 첫 실행이 곧바로 치명 버그를 잡았다: **Compose 리소스(모든 문자열·번들 OBDb 에셋)가 APK에 하나도 안 들어가** 첫 문자열에서 `MissingResourceException`으로 죽었다(JetBrains **CMP-9547** — AGP 9 `com.android.kotlin.multiplatform.library` + Compose 리소스). 호스트/에뮬레이터 테스트는 JVM 클래스패스에서 리소스를 읽어 이걸 구조적으로 못 잡았다. `carscan.kmp` 에 `enableAndroidResources` 플래그를 켜서 수정(커밋 8f62873). **교훈: 호스트 테스트 초록불이 "설치·실행 가능"을 증명하지 않는다** — `adb install` + 실행 + `unzip -l <apk> | grep -c composeResources` 로 검증할 것.

**아직 남은 실물 검증**: 실제 ELM327 어댑터 연결·실차 신호 수신은 아직. iOS는 여전히 한 번도 컴파일된 적 없다(macOS 필요).

### 남은 기술 부채 (M5/M6에서 처리)
- **로케일 인식 숫자 포매터가 없다.** `commonMain`에 없고 `String.format`은 `java.*`라 iOS에서 안 된다. 지금 게이지 숫자의 소수점은 `.` 하드코딩. **8개 로케일 중 6개(ru/de/pl/pt-BR/es/uk)가 쉼표를 쓴다.** `:core:units`에 expect/actual 포매터 필요. 반드시 반올림 의미론을 유지할 것.
- `dout`(진단 세션 복원) 미구현 — OBDb에 쓰는 차종이 있는지 확인 필요.
- `:core:obd`의 `PollerHealth` → `:core:data`의 `SessionHealth` 매핑을 `:composeApp` Koin 모듈에서 바인딩해야 함 (M5).
- iOS는 여전히 **한 번도 컴파일된 적 없음**.

### 지금 막힌 것
없음.

---

## 재개 절차

1. 이 파일과 플랜을 읽는다.
2. `git log --oneline -5` 로 마지막 커밋 확인.
3. `git status` 로 커밋 안 된 에이전트 산출물 확인.
4. 아래 **검수 절차**로 미커밋 작업물을 검증한 뒤 커밋.
5. 위 표에서 다음 마일스톤으로 진행.

## 검수 절차 (에이전트 산출물을 믿지 말 것)

에이전트 자기 보고를 그대로 받지 않는다. 리드가 직접:

1. **테스트를 독립 실행**한다 (`./gradlew :core:xxx:allTests`).
2. **RED를 재현한다** — 프로덕션 코드를 일부러 망가뜨렸을 때 테스트가 실제로 실패하는지 확인. 안 터지면 그 테스트는 아무것도 지키지 못하므로 반려.

   **⚠️ 변이 테스트에는 반드시 `--rerun-tasks` 를 붙일 것.** Kotlin 증분 컴파일이 낡은 클래스를 물고 있으면, 변이를 넣었는데 *예전의 올바른* 클래스로 테스트가 돌아 **통과해버린다.** 그러면 "이 테스트는 아무것도 안 지킨다"고 오판해서 **멀쩡한 테스트를 지우게 된다.** 이건 실제로 한 번 발생했다(`m4-gauges` 보고).

   **또한 컴파일 통과 ≠ 검증.** `compileKotlinJvm` 이 초록이어도 실행 시 죽을 수 있다 — Compose UI 테스트가 `androidHostTest` 에서 `Build.FINGERPRINT` null 로 죽는 걸 컴파일만으로는 절대 볼 수 없다. **반드시 테스트를 *실행*할 것.**

   지금까지 재현된 변이 (전부 정확히 RED):
   - `YearFilter.matches` 역구간 → 순진한 교집합: vehicle 테스트 3개 실패
   - `IsoTpReassembler` 시퀀스 검사 제거 (= OBDb 레퍼런스와 동일 동작): obd 테스트 2개 실패
   - `ElmSession` 뮤텍스 제거: `200 exchanges from 20 coroutines never cross their answers` 실패
   - `PidScheduler` AT 어피니티 제거: `six commands across two headers cost exactly two ATSH per round` 실패
   - `LivePlot` revision 읽기를 draw→composition 으로 한 줄 이동: `"차트가 600번 리컴포즈됨"` 실패
   - `SampleWriter` 배치 트랜잭션 제거: `200 samples/sec for 10s costs 10 transactions, not 2000` 실패
3. **라이선스**: OBDb 데이터를 가져왔다면 `SOURCE.md` 에 저장소 + 커밋 SHA + CC BY-SA 4.0 고지·링크·수정 여부가 있어야 한다.
4. **스테이징 주의**: `git add <디렉터리>` 는 아직 작업 중인 다른 에이전트의 미완성 파일까지 쓸어담는다. **파일을 명시해서** add 할 것.

---

## ⚠️ 증분 컴파일이 거짓말한다 — 이 프로젝트에서 세 번 물렸다

**Gradle/Kotlin 증분 컴파일은 낡은 산출물을 조용히 재사용한다.** 이게 *검증 자체를 무의미하게* 만든다:

1. **변이 테스트가 거짓 GREEN을 낸다.** 프로덕션 코드를 망가뜨렸는데 예전의 *올바른* 클래스가 클래스패스에 남아 테스트가 통과한다 → "이 테스트는 아무것도 안 지킨다"고 오판해 **멀쩡한 테스트를 지우게 된다.**
2. **`Res` 문자열 접근자가 없다고 나온다.** `Res` 는 XML에서 **코드 생성**되므로, 빌드 디렉터리가 따뜻한 feature 모듈은 몇 시간 전부터 존재하는 키에 대해 `Unresolved reference` 를 계속 뱉는다. 에이전트 둘이 연속으로 "키가 없다"고 보고했고 둘 다 이것이었다.
3. **복원이 no-op이 되어 변이가 누적된다.** `mktemp -d` 가 이 환경에서 실패하는데(TMPDIR이 없는 디렉터리를 가리킴) 실패를 확인 안 하면 백업이 안 되고, 변이가 겹쳐 쌓여 결과가 통째로 오염된다.

**규칙**
- 변이 테스트: **반드시 `--rerun-tasks`**.
- "키/심볼이 없다"고 결론짓기 전: **반드시 `--rerun-tasks` 로 한 번 더**. 그리고 XML이 아니라 **생성된 접근자 파일**을 확인할 것 —
  `core/designsystem/build/generated/compose/resourceGenerator/kotlin/commonMainResourceAccessors/.../String0.commonMain.kt`
- 임시 디렉터리를 쓰는 하네스: **디렉터리가 실제로 생겼는지 확인**할 것.
- **컴파일 통과 ≠ 검증.** `compileKotlinJvm` 이 초록이어도 실행 시 죽을 수 있다(Compose UI 테스트가 `androidHostTest` 에서 `Build.FINGERPRINT` null 로 사망). **반드시 테스트를 *실행*할 것.**

## `Unresolved reference` 를 만났을 때 — 증상으로 원인을 가려라

세 가지가 똑같이 생겼는데 대응이 정반대다. 틀린 대응은 시간만 태우고 같은 답을 준다.

| 증상 | 원인 | 대응 |
|---|---|---|
| **실제 Kotlin 심볼 하나**가 미해결 | 진짜 import 누락 / API 이름 변경 | **파일을 읽어라.** `--rerun-tasks` 는 4분 태우고 같은 걸 알려준다 |
| **생성된 심볼**(`Res.string.*`) | 코드젠 캐시가 낡음. `Res` 는 XML에서 생성되므로 빌드 디렉터리가 따뜻하면 몇 시간 전부터 있는 키도 "없다"고 한다 | **`--rerun-tasks`** |
| **패키지 전체**(`Unresolved reference 'units'`, `'db'`)가 여러 상위 모듈에서 동시에 | **다른 에이전트의 동시 Gradle 실행이 공유 빌드 디렉터리의 산출물을 짓밟음** | **그냥 다시 돌려라.** 확인법: 문제의 상위 모듈만 **단독 컴파일** — 7초면 답이 나온다 |

세 번째를 진짜 고장으로 오진하면 남의 멀쩡한 모듈을 파헤치게 된다. Windows에서 에이전트를 병렬로 돌리는 한 계속 발생한다.

## 정정된 오류 (다시 반복하지 말 것)

- **ISO-TP 단일 프레임의 PCI 하위 니블 = 페이로드 바이트 수.** 리드가 에이전트 브리프에 `010C` 응답 앵커를 `7E8 03 41 0C 1A F8` 로 적었는데 **틀렸다.** `41 0C 1A F8` 은 4바이트이므로 PCI는 **`04`** 여야 한다. `03` 이면 재조립기가 3바이트만 집어 `41 0C 1A` 로 잘리고 조용히 쓰레기 값이 나온다. 저장소의 실제 OBDb 픽스처(`core/obd/src/commonTest/resources/fixtures/Ford-F-150/.../7E0.010C.yaml` → `7E804410C0A1E`)가 정답이다. **브리프의 손으로 쓴 예시보다 벤더링된 픽스처를 신뢰할 것.** (RPM 산수 `((0x1A*256)+0xF8)/4 = 1726` 자체는 맞았다.)
- **`ObdTransport.incoming` 은 콜드 + 단일 소비자여야 한다.** 최초 프리즈에서 이걸 명시하지 않았다. `SharedFlow` 로 구현하면 구독 전에 도착한 바이트가 조용히 버려져서 `open()` 직후 첫 응답이 *가끔* 유실되고, 반이중이라 그 한 번의 유실이 세션 전체의 프롬프트 스트림을 어긋나게 만든다. 계약에 KDoc으로 못 박았다. **BLE 구현이 이를 지키는지 반드시 확인할 것.**

## 절대 잊으면 안 되는 결정들

- **OBDb `suggestedMetric` 은 34개뿐이고 엔진 RPM이 없다.** 그래서 대시보드는 `MetricKey`(메트릭 또는 시그널 ID)로 주소를 잡는다.
- **`YearFilter` 에서 `from >= to` 는 역구간**(가운데가 뚫린 범위)이다. Ford F-150의 `{to:2003, from:2011}` = "2003 이하 **또는** 2011 이상". 교집합으로 읽으면 그 차 커맨드의 절반이 조용히 사라진다.
- **OBDb JSON을 `.kt` 로 코드젠하지 않는다.** CC BY-SA 데이터의 파생물이 되어 상용 앱 소스를 오염시킨다. 항상 불투명 에셋으로만 다룬다. **M6부터 자동 감시**: `:core:vehicle:verifyNoSignalDataInSource`(`check` 에 연결) 가 메인 소스에 시그널-ID 형태 리터럴이 임계치(10) 넘게 나오면 빌드를 실패시킨다. 데이터를 코드로 심으면 즉시 RED.
- **ELM327은 엄격한 반이중.** 뮤텍스 밖 `write()` 한 번이면 프롬프트 스트림이 영구히 어긋난다.
- **1Hz 위로는 영속화하지 않는다.** 20Hz 라이브 그래프는 메모리 링버퍼가 그린다.
- **백업 파일은 사용자 암호로 암호화한다.** 기기 저장 키로 암호화하면 폰을 바꿨을 때 복호화가 불가능해져 백업의 목적이 무너진다.
- **트립/차량/대시보드 ID는 UUID.** autoincrement면 다른 기기 백업을 불러올 때 반드시 충돌한다.
- **iOS는 블루투스 클래식(SPP)이 영구 불가.** Apple이 MFi 미인증 기기를 막는다.
