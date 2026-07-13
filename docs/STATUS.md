# CarScan — 작업 상태 (핸드오프)

> **이어서 작업할 때**: 이 파일을 먼저 읽으면 어디까지 됐고 다음이 뭔지 알 수 있다.
> 세션이 끊기거나(토큰 한도 등) 새 세션에서 재개할 때의 단일 진실 공급원.
> 전체 설계는 `C:\Users\Mureung\.claude\plans\lucky-forging-pascal.md`.

**마지막 갱신**: 2026-07-13 / M2 진행 중

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

| 마일스톤 | 상태 | 비고 |
|---|---|---|
| **M0** 빌드 스켈레톤 | ✅ 완료·푸시 (`bf0491b`) | AGP 9 폴백 없이 통과 |
| **프리즈** 모델·트랜스포트 계약 | ✅ 완료·푸시 (`45ec13d`) | |
| **M1-B** `:core:vehicle` | ✅ 검수 통과, **커밋 대기** | 테스트 31개 초록, RED 증명 확인 |
| **M1-A** `:core:obd` 디코더 | 🔄 진행 중 (`m1-decoder`) | |
| **M2-D** BLE (Kable) | 🔄 진행 중 (`m2-ble`) | |
| **M2-E** SPP (Android) | 🔄 진행 중 (`m2-spp`) | |
| **M2-F** TCP + Fake + ELM 에뮬레이터 | 🔄 진행 중 (`m2-emulator`) | |
| M3 세션·폴러·DB | ⬜ 대기 | M1+M2 필요 |
| M4 디자인시스템·게이지 | ⬜ 대기 | M0+M2 에뮬레이터만 있으면 병렬 가능 |
| M5 대시보드·그래프 = MVP | ⬜ 대기 | |

### 지금 막힌 것
`core/transport/src/commonMain/.../fake/ScriptedPipe.kt:87` 컴파일 에러(`Unit?` 추론)로 `:core:transport` 가 깨져 있고, `:core:obd` 가 api-의존이라 `m1-decoder` 까지 연쇄로 막힌다. 파일 주인은 `m2-emulator`. 다른 에이전트가 남의 파일을 고치지 않는 것은 **의도된 계약**이다.

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
   실제 사례: `YearFilter.matches` 의 역구간 분기를 순진한 교집합으로 바꾸자 `:core:vehicle` 테스트 3개가 정확히 실패 → 유효한 테스트임이 증명됨.
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
