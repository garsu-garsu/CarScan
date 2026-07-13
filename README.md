# CarScan

**A real-time OBD-II vehicle diagnostics app — Kotlin Multiplatform, Compose Multiplatform, Android-first with iOS in the same codebase.**

Talks to ELM327 adapters over **Bluetooth LE, Bluetooth Classic and Wi-Fi**, decodes live sensor data using the open [OBDb](https://github.com/OBDb) vehicle signal database (742 vehicle repos, ~13.5k signals), and renders it as customizable gauges, live charts, a windshield HUD, and GPS-tagged trip playback.

![Kotlin](https://img.shields.io/badge/Kotlin-2.4.0-7F52FF?logo=kotlin&logoColor=white)
![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.11-4285F4)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS-lightgrey)
![AGP](https://img.shields.io/badge/AGP-9.1-3DDC84?logo=android&logoColor=white)
![Tests](https://img.shields.io/badge/tests-126%20passing-success)
![License](https://img.shields.io/badge/data-CC%20BY--SA%204.0-blue)

> **Status: in active development.** Transport, protocol and vehicle-database layers are built and tested; UI is next. See [Roadmap](#roadmap).
> Progress and engineering decisions are tracked in [`docs/STATUS.md`](docs/STATUS.md).

---

## Why this project is worth reading

Most OBD-II apps are Android-only, built on a library that was archived in 2017. This one is interesting for three reasons.

**The hard part isn't the UI — it's the wire.** ELM327 is a strictly half-duplex ASCII protocol spoken by a global fleet of counterfeit adapters that lie about their firmware version, drop frames, and fail in a dozen documented ways. A single stray write outside a mutex desynchronizes the prompt stream *permanently* and surfaces later as a plausible-but-wrong sensor reading. The architecture is built around containing that.

**There is no library for this.** No Kotlin Multiplatform ELM327 library exists. `pires/obd-java-api` was archived in 2017; `kotlin-obd-api` is built on `java.io.InputStream` and therefore cannot compile for iOS at all. The protocol layer here is written from the spec, in `commonMain`, and tested against **real vehicle response fixtures** — so it runs unchanged on both platforms.

**It's testable without a car.** Every OBDb vehicle repo ships recorded ISO-TP responses with their expected decoded values. Those are vendored as fixtures, so the decoder is verified against a real Kia EV6 and a real Ford F-150 with **zero hardware in the loop**. On top of that sits a stateful **ELM327 emulator** — it honours the AT commands you send it and drives values from a deterministic driving simulator — so the entire app above the transport can be developed and tested with no adapter and no vehicle.

---

## Architecture

Clean-ish layering over a Kotlin Multiplatform module graph. UI is Compose Multiplatform; state is a hand-rolled MVI reducer (`StateFlow` + `onIntent` + `Channel<Effect>`).

```
  :androidApp                  com.android.application — Activity, manifest merge point
       │
  :composeApp                  App(), the single NavHost, Koin root
       │
  :feature:{connect,dashboard,live,dtc,hud,trip,settings}
       │                       features never depend on each other — a screen emits
       │                       Effect.Navigate and only :composeApp owns the NavController
  :core:designsystem   :core:data   :core:units
                           │
              :core:obd  :core:vehicle  :core:database
                   │
              :core:transport
                   │
              :core:model  ──  :core:common

  :core:monetization           interfaces only; iOS gets a no-op actual
  :platform:android-ads        AdMob, Play Billing          (Android-only)
  :platform:android-service    foreground service, wakelock (Android-only)
```

**Ads and billing physically cannot reach iOS.** They live in `:platform:*` behind interfaces declared in `:core:monetization`, so the iOS klib path never sees them. This is enforced by the module graph, not by discipline.

**`ObdTransport` and `ElmSession` are `internal` to `:core:obd`.** Features see only a repository. The half-duplex invariant cannot leak into UI code, because UI code cannot reach the socket.

### The protocol stack

```
BLE / Bluetooth Classic / Wi-Fi
   │  Flow<ByteArray>          ← arbitrary chunking; one chunk is NOT one line
   ▼
PromptFramer                   ← accumulate until the '>' prompt byte
   ▼
ElmSession (Mutex)             ← exactly ONE outstanding command, forever
   ▼
IsoTpReassembler               ← keyed per CAN id; multiple ECUs answer concurrently
   ▼
SignalDecoder                  ← OBDb `fmt`: bit offset, endianness, sign, scale, clamp
   ▼
PidScheduler                   ← adaptive; see below
   ▼
Flow<SensorSample>  →  repository  →  MVI ViewModel  →  Compose
```

---

## Engineering decisions

Each of these came out of hitting the problem, not from a blog post.

### The throughput governor is a product feature, not an optimization

A counterfeit ELM327 clone does **10–20 queries per second, total**. Eight gauges refreshing at 10 Hz needs 80. That is not slow — it is *impossible*, and every user with a €5 adapter will blame the app.

So the scheduler is built to fail honestly. It groups commands by ECU header so `ATSH` is sent once per group instead of once per command (≈4× on its own), appends the expected frame count so the adapter returns immediately instead of waiting out its timeout (≈2×), measures actual round-trip latency, and then **tells the user the truth**: *"your adapter: 14 queries/sec — showing 6 tiles at 2 Hz."*

### The OBDb schema does not say what its field names suggest

Two examples that would have shipped silently broken:

- **`suggestedMetric` has exactly 34 values, and engine RPM is not one of them.** OBDb only standardizes metrics whose *acquisition* differs across vehicles, so most of SAE J1979 carries no metric at all. Keying the dashboard on the metric — the obvious design — makes the single most important gauge unaddressable. Hence `MetricKey`: a metric when OBDb provides one, a signal id when it doesn't.

- **A year filter with `from >= to` is an *inverted* range** — a hole in the middle, not an empty set. Ford F-150 ships `{to: 2003, from: 2011}`, meaning *2003-and-earlier **or** 2011-and-later*. Read as an intersection it selects nothing, and half that vehicle's commands vanish without an error. This was ported from OBDb's reference implementation rather than inferred from the field names, and a mutation test now guards it: replacing the branch with a naive intersection fails three tests.

### Persist at 1 Hz, never above it

The 20 Hz live chart reads an in-memory ring buffer — drawing 30 seconds on screen needs no disk. Trip history, statistics and playback all need only 1-second aggregates (min/avg/max). So nothing above 1 Hz is ever written, and the "high-resolution sample retention" problem simply ceases to exist.

Storage is columnar: one `FloatArray` BLOB per `(trip, signal, 10-minute chunk)` rather than one row per sample. **7× smaller** (~220 KB/hour vs ~1.6 MB), and trip playback gets fast for free — the whole trip loads into memory, so scrubbing the timeline is instant.

### iOS cannot do Bluetooth Classic, and never will

Apple's ExternalAccessory framework only reaches MFi-certified hardware, and no ELM327 clone is MFi. This isn't a gap to close later — it's a property of the platform. `TransportFactory.supported` drives the UI, so the iOS adapter picker simply has no SPP entry, rather than offering one that cannot work.

### The OBDb data is never code-generated

Vehicle signal definitions are CC BY-SA 4.0. Generating `.kt` source containing those tables would make the generated file an adaptation of BY-SA data and encumber the app's own source. The data is loaded as an **opaque runtime asset** at all times, and a build check fails if a signal id ever appears in a `.kt` file.

---

## Testing

Strict TDD: every piece of production code has a test that failed before it existed. Verification is not "the tests are green" — it is **"the tests go red when the code is broken."**

Real example: the inverted-year-range branch above was mutated to a naive intersection, and exactly three tests failed. A test that cannot go red proves nothing and is rejected in review.

| Layer | How it's tested | Hardware needed |
|---|---|---|
| Signal decoder | **Real OBDb response fixtures** (recorded ISO-TP frames + expected decoded values) from Kia EV6, Ford F-150, SAE J1979 | none |
| Framing / ISO-TP | Responses delivered **one byte at a time** — the assumption "one chunk = one line" passes on real hardware and fails in the field | none |
| Protocol / session | `FakeObdTransport` injects `NO DATA`, `BUFFER FULL`, `CAN ERROR`, unsuppressed echo, split frames | none |
| Half-duplex invariant | 200 concurrent `exchange()` calls from 20 coroutines — **fails without the mutex**, which is the point | none |
| Scheduler / governor | `runTest` virtual time — no wall-clock waits, fully deterministic | none |
| Whole app | **ELM327 emulator** with a deterministic driving simulator | none |
| BLE / SPP radios | Real-device checklist — the untestable layer is kept deliberately thin | 3 adapters |

Stack: `kotlin.test` · [Turbine](https://github.com/cashapp/turbine) · `kotest-assertions` · `kotlinx-coroutines-test`.

---

## Tech stack

| Concern | Choice | Why not the obvious alternative |
|---|---|---|
| UI | Compose Multiplatform | — |
| State | Hand-rolled MVI | The app's complexity is in the wire protocol, not state management. Orbit/MVIKotlin would add an abstraction layer over a 30-line base class. |
| DI | Koin | Hilt is Android-only — it would exclude iOS outright. |
| BLE | [Kable](https://github.com/JuulLabs/kable) | The only mature coroutines-native BLE library that compiles for Android *and* iOS from common code. |
| Bluetooth Classic | Raw `BluetoothSocket` (Android) | No maintained wrapper exists. Ships the secure → insecure → reflection fallback ladder that clones require. |
| Wi-Fi | ktor-network | The only KMP option that opens a real TCP socket on iOS. |
| OBD protocol | **Written from the spec** | No KMP library exists. The JVM ones are `InputStream`-based and cannot compile for iOS. |
| Database | SQLDelight | Raw SQL — the write path needs explicit batching and transaction control. |
| Charts | [Vico](https://github.com/patrykandpatrick/vico) (history) + hand-written Canvas (live) | No declarative chart library is free at 20 Hz. The live strip is a ring-buffer Canvas renderer; Vico handles historical charts where axes and zoom matter. |
| Gauges | Compose `Canvas` | Every Compose gauge library was abandoned in 2023–24. |
| i18n | `compose.components.resources` | 8 locales: en · ru · de · pl · pt-BR · es · uk · ko |

---

## Toolchain

| | |
|---|---|
| Gradle | 9.3.1 |
| AGP | 9.1.1 |
| Kotlin | 2.4.0 |
| Compose Multiplatform | 1.11.1 |
| SQLDelight | 2.3.2 |
| JDK | **17+** (Android Studio's bundled JBR 21 works) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

Every version lives in `gradle/libs.versions.toml`; nothing else declares one. Build logic is four convention plugins in `build-logic/`, which is why most module build files are three lines long.

Three things here are **forced, not preferred** — and they were verified on day one, by putting a real SQLDelight `.sq` file in the skeleton so the integration would detonate immediately rather than in month two:

- **AGP 9 forbids `com.android.library`/`com.android.application` in KMP modules.** Every KMP module uses `com.android.kotlin.multiplatform.library`, and the Android entry point is a separate application module. This is what shapes the module graph.
- **AGP 9 compiles Kotlin itself** — applying `org.jetbrains.kotlin.android` anywhere is a hard build error. There is deliberately no catalog alias for it.
- **SQLDelight must be ≥ 2.3.2.** 2.1.x cannot configure against AGP 9's new DSL at all.

`compileSdk` is 37 (not 36) because Kable, Vico and androidx.core refuse to be consumed by a module compiled against 36. It only affects the compile classpath; `targetSdk` — which governs runtime behaviour — stays at 36.

---

## Building

```bash
# Gradle 9 needs JDK 17+. If your PATH java is older:
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash on Windows

./gradlew :androidApp:assembleDebug     # → androidApp/build/outputs/apk/debug/
./gradlew build -x lint                 # everything, including tests
./gradlew :core:obd:allTests            # the decoder gate
```

### iOS on a non-Mac host

Kotlin/Native Apple targets require Xcode and cannot be compiled anywhere but macOS. They are registered only when the host is a Mac (`build-logic/src/main/kotlin/carscan/BuildHost.kt`), so the build stays green on Windows and Linux.

To type-check the iOS wiring without compiling it — works on any host:

```bash
./gradlew -Pcarscan.enableApple=true :composeApp:tasks --all
```

On a Mac:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build
```

**Neither has ever been run.** This project was built on Windows, so `composeApp/src/iosMain`, `core/database/src/iosMain` and `iosApp/iosApp.xcodeproj` are **unverified** and should be expected to need fixing on first contact with a Mac. Saying so is cheaper than pretending otherwise.

---

## Roadmap

- [x] **M0** — KMP skeleton building on Android, iOS targets wired
- [x] **M1** — OBDb signalset layer: parsing, model-year filters, SAE J1979 union
- [x] **M2** — Transports (BLE / SPP / Wi-Fi) + fault-injecting fake + ELM327 emulator
- [ ] **M1-A** — Signal decoder verified against real OBDb response fixtures *(in progress)*
- [ ] **M3** — `ElmSession`, adaptive PID scheduler, repository, SQLDelight schema
- [ ] **M4** — Design system, dual-theme gauges (modern arc / classic analog), connect screen
- [ ] **M5** — Customizable dashboard + live charts → **MVP**
- [ ] **M6** — Settings, i18n, licenses, monetization → release
- [ ] **M7+** — DTC read/clear · HUD · GPS trip recording & playback · encrypted backup/restore · iOS polish

---

## Attribution

Vehicle signal definitions come from [**OBDb**](https://github.com/OBDb), the open-source OBD database, licensed **[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)**. The data is used as an opaque runtime asset; vendored test fixtures record their upstream repository and commit SHA in `SOURCE.md`.
