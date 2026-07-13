# CarScan

An OBD-II vehicle sensor app (ELM327 adapters over BLE / Bluetooth Classic / Wi-Fi),
built as a Kotlin Multiplatform project targeting Android and iOS.

Status: **M0** — the build skeleton. The app renders "Hello CarScan" and nothing else.
What M0 proves is that AGP 9 + Kotlin Multiplatform + SQLDelight + Compose
Multiplatform actually build together.

## Toolchain

| | |
|---|---|
| Gradle | 9.3.1 |
| AGP | 9.1.1 |
| Kotlin | 2.4.0 |
| Compose Multiplatform | 1.11.1 |
| SQLDelight | 2.3.2 |
| JDK | **17 or newer** (Android Studio's bundled JBR 21 works) |
| compileSdk / targetSdk / minSdk | 37 / 36 / 26 |

Every version lives in `gradle/libs.versions.toml`. Nothing else declares one.

Two things about this toolchain are not preferences, they are forced:

- **AGP 9 forbids `com.android.library` and `com.android.application` in KMP
  modules.** Every KMP module uses `com.android.kotlin.multiplatform.library`,
  and the Android entry point is a separate plain application module
  (`:androidApp`). This is what shapes the module graph.
- **AGP 9 compiles Kotlin itself.** Applying `org.jetbrains.kotlin.android`
  anywhere is a build error, which is why there is no catalog alias for it.

`compileSdk` is 37 rather than 36 because Kable 0.44.0, Vico 2.5.2 and
androidx.core 1.19.0 all refuse to be consumed by a module compiled against 36.
`compileSdk` only decides which APIs are on the compile classpath; `targetSdk`,
which is what changes runtime behaviour, stays at 36.

## Building

Gradle needs JDK 17+. If `java -version` on your PATH is older, point `JAVA_HOME`
at the JDK bundled with Android Studio:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # Git Bash on Windows
```

```bash
./gradlew :core:database:generateCommonMainCarScanDbInterface   # SQLDelight codegen
./gradlew :androidApp:assembleDebug                             # -> androidApp/build/outputs/apk/debug/
./gradlew build -x lint                                         # everything, including tests
./gradlew :core:common:allTests                                 # test harness
```

## iOS on a non-Mac host

Kotlin/Native Apple targets need Xcode and **cannot be compiled anywhere but
macOS**. So the Apple targets are registered only when the host is a Mac (see
`build-logic/src/main/kotlin/carscan/BuildHost.kt`); on Windows and Linux the
build simply has no iOS targets and stays green.

To configure them anyway — which type-checks the iOS wiring without compiling it,
and works on any host:

```bash
./gradlew -Pcarscan.enableApple=true :composeApp:tasks --all
```

On a Mac, the real iOS verification is:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build
```

Neither of those has ever been run: this skeleton was built on Windows. The iOS
source sets (`composeApp/src/iosMain`, `core/database/src/iosMain`) and
`iosApp/iosApp.xcodeproj` are therefore **unverified** and should be expected to
need fixing on first contact with a Mac.

## Module graph

```
  :androidApp    com.android.application — MainActivity, manifest merge point
       |
  :composeApp    App(), the single NavHost, Koin root
       |
  :feature:{connect,dashboard,live,dtc,hud,trip,settings}
       |         features never depend on each other: a screen emits
       |         Effect.Navigate and only :composeApp owns the NavController
  :core:designsystem   :core:data   :core:units
                           |
              :core:obd  :core:vehicle  :core:database
                   |
              :core:transport
                   |
              :core:model  —  :core:common

  :core:monetization           interfaces only; iOS gets a no-op actual
  :platform:android-ads        AdMob, Play Billing        (com.android.library)
  :platform:android-service    foreground service         (com.android.library)
```

`:platform:*` are the only modules allowed to use `com.android.library`, because
they need manifest merging and the AGP KMP library plugin does not support it.
Keeping AdMob and Billing there means they can never reach the iOS klib path.

Build logic lives in `build-logic/` as four convention plugins — `carscan.kmp`,
`carscan.kmp.compose`, `carscan.feature`, `carscan.android.lib` — which is why
most module build files are three lines long.

## Licensing note

Vehicle-specific PID definitions come from [OBDb](https://github.com/OBDb),
licensed CC BY-SA 4.0. That data is always loaded as **opaque runtime assets**
and never code-generated into `.kt`, which would spread BY-SA into the app's own
source. Attribution goes in the About screen before any store upload.
