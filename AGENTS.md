# AGENTS.md — OpenBibleAudio

Guidance for AI coding agents (and humans) working in this repository.

## Read the specs first

**`specifications/`** holds the design for this project and is the source of truth for behavior
and architecture. Start with
[`specifications/0000-vision-and-architecture.md`](specifications/0000-vision-and-architecture.md),
which covers the vision, the numbered architectural decisions (AD-1 … AD-15) and the phased roadmap.

- Before implementing a feature, find (or write) the spec for its phase and follow it.
- Don't silently deviate from an architectural decision. If one needs to change, update spec
  0000 in the same change and add a Decision log entry (see §5 of that spec).
- Refer to decisions by ID in code comments and commit messages where it helps ("per AD-6").

## Project layout

| Module | Kind | Purpose |
|---|---|---|
| `:app` | Android application (Compose, Media3) | The app: playback service / Android Auto, import, plans, podcasts, UI |
| `:core:model` | Pure Kotlin/JVM | Domain model shared with the importer CLI (books/canon, …). **No Android dependencies.** |

Planned modules (per AD-12): `:core:importer`, `:tools:importer-cli`. Keep domain logic that
doesn't need Android in pure-Kotlin modules so it can be unit-tested on the JVM.

Toolchain: AGP 9.5 (built-in Kotlin, KGP 2.2.10), Gradle 9.6 (wrapper), daemon JVM 25 (auto-provisioned
via `gradle/gradle-daemon-jvm.properties`), compileSdk/targetSdk 37, minSdk 28. Dependency versions
live in `gradle/libs.versions.toml`. Add new dependencies there, not inline.

## Environment setup

- **`JAVA_HOME` must point at a JDK** for `gradlew` to launch. Android Studio's bundled JBR
  works. Gradle then provisions the JDK 25 daemon itself (`gradle/gradle-daemon-jvm.properties`).
  If `JAVA_HOME` is unset in your shell, ask the user where their JDK is. Don't guess, and
  don't hard-code a path into project files.
- **Android SDK location** comes from `local.properties` (`sdk.dir`, machine-specific and not
  committed) or `ANDROID_HOME`. To print it: `android info sdk`.
- `adb` (SDK `platform-tools`) is expected on `PATH`.
- The **Android CLI** (`android`) is expected on `PATH`. Pass `--no-metrics` to skip usage
  reporting. Its build references use the form `"?:<gradle path or task>"`.

## Build

```bash
./gradlew assembleDebug              # debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew build                      # assemble all variants + unit tests + lint (full local CI)
android build "?:app:assembleDebug"  # same via Android CLI (any Gradle task works as the target)
```

## Test

**Every change should keep `./gradlew test` green, and new logic should come with unit tests.**

```bash
./gradlew test                       # ALL JVM unit tests: :core:model:test + :app:testDebugUnitTest
./gradlew :core:model:test           # pure-Kotlin module tests (fast, no Android)
./gradlew :app:testDebugUnitTest     # app local unit tests (JVM)

# Single class / method (Gradle test filter; wildcards allowed)
./gradlew :core:model:test --tests "com.openbibleaudio.core.model.BookTest"
./gradlew :core:model:test --tests "*BookTest.fromUsfm*"

./gradlew lint                       # Android lint → app/build/reports/lint-results-debug.html
./gradlew check                      # unit tests + lint

# Instrumented tests: preferred, needs no running emulator
./gradlew pixel9Api37DebugAndroidTest   # Gradle Managed Device (see below)

# Instrumented tests on whatever device/emulator is already attached
./gradlew connectedDebugAndroidTest     # results: app/build/outputs/androidTest-results/connected/
```

**Gradle Managed Device.** `pixel9Api37` (Pixel 9, API 37, `google_apis_playstore` image) is
declared under `testOptions.managedDevices` in `app/build.gradle.kts`. Gradle creates, boots and
tears down a headless emulator for the run, so no AVD or serial is needed. The first run on a
machine downloads the system image if it's missing (accept SDK licenses with
`sdkmanager --licenses` if prompted). It takes ~2 minutes, so use it to verify instrumented
tests, not for every edit. Report: `app/build/reports/androidTests/managedDevice/debug/allDevices/index.html`.
The `pageAlignment` and `testedAbi` warnings it prints are expected. `testedAbi` is left unset
so the default host ABI works on both x86_64 and arm64 machines.

Test locations and conventions:
- `core/*/src/test/kotlin/…`: JVM tests using `kotlin.test` (JUnit 4 runner). Prefer putting
  logic and tests here.
- `app/src/test/java/…`: JVM unit tests for app code (JUnit 4). `unitTests.isIncludeAndroidResources`
  is enabled, so Robolectric can be added when needed.
- `app/src/androidTest/java/…`: instrumented tests (AndroidX Test / Compose UI test).
- Reports: `<module>/build/reports/tests/…/index.html`; XML in `<module>/build/test-results/`.

## Run & debug on an emulator / device

For interactive running and debugging, as opposed to automated tests (which should use the managed
device above), use a regular emulator or device. AVD names and device serials vary by machine,
so discover them rather than assuming:

```bash
android emulator list                       # pick an AVD name → <avd>
android emulator start --headless <avd>
adb devices                                 # device serial → <serial> (e.g. emulator-5554)
android run "?:app"                         # build, install, launch MainActivity
                                            #   add --device=<serial> if more than one device is attached
android screen capture --output=<dir>/screen.png   # screenshot (--annotate labels UI elements)
android layout --pretty                     # UI hierarchy JSON of the foreground app
android emulator stop <avd>
```

Known quirk: `android emulator start` may not return even after the emulator has booted. In
that case, run it in the background and poll `adb shell getprop sys.boot_completed` until it
prints `1`.

Logs: `adb logcat --pid=$(adb shell pidof com.openbibleaudio.openbibleaudio)`.

**Android Auto** (the primary UI) is tested with the **Desktop Head Unit (DHU)** from the SDK's
`extras/google/auto`, against a device or emulator running Android Auto in developer mode. See
AD-14 in spec 0000.

## Compose previews (no device needed)

```bash
android preview list "?:app"
android preview render "?:app" --fqn=com.openbibleaudio.openbibleaudio.MainActivityKt.GreetingPreview --output=<dir>
```

Write output to a scratch/temp directory, not inside the repo.

## Known gotchas

- `./gradlew tasks --all` fails while configuring the **release** variant's Compose Preview task
  (release unit tests are disabled by default in AGP 9). It's harmless and only affects task
  listing. `./gradlew :app:tasks` without `--all`, `build`, `test` and `check` all work.
- Don't remove `testOptions.unitTests.isIncludeAndroidResources = true` from `app/build.gradle.kts`.
  Without it, AGP's Compose Preview tasks fail configuration, which breaks `check`, `lint` and `tasks`.
- The Android CLI copies an `init.gradle.kts` into `.gradle/` (gitignored). That's expected.

## Conventions

- Kotlin official code style (`kotlin.code.style=official`).
- Package roots: `com.openbibleaudio.openbibleaudio` (app) and `com.openbibleaudio.core.<module>` (core modules).
- Book identity is always the USFM code / `Book` enum from `:core:model` (AD-11). Never use display names as keys.
- Commit or push only when asked. Work on a branch, not `master`/`main`.
