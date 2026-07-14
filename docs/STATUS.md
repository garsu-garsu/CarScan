# CarScan — 작업 상태 (핸드오프)

> **이어서 작업할 때**: 이 파일을 먼저 읽으면 어디까지 됐고 다음이 뭔지 알 수 있다.
> 세션이 끊기거나(토큰 한도 등) 새 세션에서 재개할 때의 단일 진실 공급원.
> 전체 설계는 `C:\Users\Mureung\.claude\plans\lucky-forging-pascal.md`.

**마지막 갱신**: 2026-07-14 / **M0~M4 완료**, M5(대시보드·라이브 화면 = MVP) 대기

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
| **M0** 빌드 스켈레톤 | ✅ `bf0491b` | AGP 9 폴백 없이 통과 |
| **프리즈** 모델·트랜스포트 | ✅ `45ec13d` | |
| **M1** 디코더 + 시그널셋 | ✅ `f7a1e8a`, `429a6fb` | obd 140 · vehicle 31 |
| **M2** 트랜스포트 3종 + 에뮬레이터 | ✅ `429a6fb` | transport 95 |
| **M3** 세션·폴러 | ✅ `d333ed7` | (obd 140에 포함) |
| **M3** DB·리포지토리 | ✅ `0a3064f` | database 34 · data 37 |
| **M4** 게이지·라이브차트 | ✅ `1550029`, `2eea7f9` | designsystem 102 |
| **M5** 대시보드 + 라이브 화면 = **MVP** | ⬜ 다음 | |
| M6 설정·i18n·라이선스·수익화 | ⬜ | |

**총 439개 테스트, 실패 0.**

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

## 정정된 오류 (다시 반복하지 말 것)

- **ISO-TP 단일 프레임의 PCI 하위 니블 = 페이로드 바이트 수.** 리드가 에이전트 브리프에 `010C` 응답 앵커를 `7E8 03 41 0C 1A F8` 로 적었는데 **틀렸다.** `41 0C 1A F8` 은 4바이트이므로 PCI는 **`04`** 여야 한다. `03` 이면 재조립기가 3바이트만 집어 `41 0C 1A` 로 잘리고 조용히 쓰레기 값이 나온다. 저장소의 실제 OBDb 픽스처(`core/obd/src/commonTest/resources/fixtures/Ford-F-150/.../7E0.010C.yaml` → `7E804410C0A1E`)가 정답이다. **브리프의 손으로 쓴 예시보다 벤더링된 픽스처를 신뢰할 것.** (RPM 산수 `((0x1A*256)+0xF8)/4 = 1726` 자체는 맞았다.)
- **`ObdTransport.incoming` 은 콜드 + 단일 소비자여야 한다.** 최초 프리즈에서 이걸 명시하지 않았다. `SharedFlow` 로 구현하면 구독 전에 도착한 바이트가 조용히 버려져서 `open()` 직후 첫 응답이 *가끔* 유실되고, 반이중이라 그 한 번의 유실이 세션 전체의 프롬프트 스트림을 어긋나게 만든다. 계약에 KDoc으로 못 박았다. **BLE 구현이 이를 지키는지 반드시 확인할 것.**

## 절대 잊으면 안 되는 결정들

- **OBDb `suggestedMetric` 은 34개뿐이고 엔진 RPM이 없다.** 그래서 대시보드는 `MetricKey`(메트릭 또는 시그널 ID)로 주소를 잡는다.
- **`YearFilter` 에서 `from >= to` 는 역구간**(가운데가 뚫린 범위)이다. Ford F-150의 `{to:2003, from:2011}` = "2003 이하 **또는** 2011 이상". 교집합으로 읽으면 그 차 커맨드의 절반이 조용히 사라진다.
- **OBDb JSON을 `.kt` 로 코드젠하지 않는다.** CC BY-SA 데이터의 파생물이 되어 상용 앱 소스를 오염시킨다. 항상 불투명 에셋으로만 다룬다.
- **ELM327은 엄격한 반이중.** 뮤텍스 밖 `write()` 한 번이면 프롬프트 스트림이 영구히 어긋난다.
- **1Hz 위로는 영속화하지 않는다.** 20Hz 라이브 그래프는 메모리 링버퍼가 그린다.
- **백업 파일은 사용자 암호로 암호화한다.** 기기 저장 키로 암호화하면 폰을 바꿨을 때 복호화가 불가능해져 백업의 목적이 무너진다.
- **트립/차량/대시보드 ID는 UUID.** autoincrement면 다른 기기 백업을 불러올 때 반드시 충돌한다.
- **iOS는 블루투스 클래식(SPP)이 영구 불가.** Apple이 MFi 미인증 기기를 막는다.
