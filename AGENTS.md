# AGENTS.md

## Project

DeviceScope (`com.devicescope.app`): single-module Android app in Kotlin + Jetpack Compose. It reads device diagnostics locally and never uses the network. README and all user-facing copy are in Spanish; keep new UI text in Spanish.

## Commands

Windows (this repo's environment):

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lint
.\gradlew.bat :app:testDebugUnitTest
```

Use `./gradlew` on macOS/Linux. Run a single test with `--tests "com.devicescope.app.YourTest"`.

- Requires JDK 17 and Android SDK 35. There is no `local.properties`; set `ANDROID_HOME` or create the gitignored `local.properties` with `sdk.dir=...`, otherwise Gradle fails with "SDK location not found".
- There is no CI, formatter, or static-analysis config beyond Android lint.
- No test sources exist yet (`app/src/test` and `app/src/androidTest` are absent) even though JUnit/Espresso dependencies are declared. Add new unit tests under `app/src/test/java/com/devicescope/app/`.

## Architecture

- One activity: `MainActivity` → `DeviceScopeApp` (Compose `NavHost`). Routes are `dashboard`, `diagnostics`, and `module/{moduleId}`.
- `data/` holds system readers/models: `DeviceDiagnostic.kt` (general snapshot + health score), `ApkCompatibilityAnalyzer.kt`, and the module registry in `DeviceModule.kt` (`deviceModules`, `ModuleIds`).
- `ui/screens/` has one screen per module; `ui/components/` and `ui/theme/` are shared UI.
- The `general` module has its own route (`GeneralDiagnosticsScreen`). Every other module is dispatched by `when (module.id)` in `ModuleDetailScreen.kt`; unknown IDs silently fall back to a generic guide. Adding a real module means: add a `ModuleIds` constant + `deviceModules` entry, add a `when` branch, and create the screen.
- No ViewModel, repository, or DI framework. Screens call system services directly from `LaunchedEffect`/click coroutines and use `withContext(Dispatchers.IO)` for file/APK/SD I/O. Keep this pattern unless asked to refactor.
- The general diagnostic snapshot lives in `DeviceScopeApp` via `remember`, so it resets on process death. This is existing behavior.

## Hard constraints

- The manifest intentionally has **no `INTERNET` permission**. Do not add networking, telemetry, or cloud dependencies.
- Privacy is part of the product: never read ICCID, IMSI, phone number, or location coordinates; do not scan Wi-Fi/Bluetooth networks. Optional runtime permissions are requested only where needed.
- No location permission is declared. Wi-Fi SSID is available only on Android 13+ through `NEARBY_WIFI_DEVICES` (`neverForLocation`); older versions show RSSI without SSID.
- `minSdk = 26`, `compileSdk`/`targetSdk = 35`. Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, Compose BOM 2024.12.01. Versions are hardcoded in `build.gradle.kts` / `app/build.gradle.kts`; there is no version catalog.
- UI strings are hardcoded in Kotlin; `res/values/strings.xml` contains only `app_name`. Follow that convention rather than introducing `stringResource` ad hoc.
