# AGENTS.md

## Project

DeviceScope (`com.devicescope.app`): single-module Android app in Kotlin + Jetpack Compose. **One product feature only**: an exhaustive offline diagnostic of every phone component. README, UI copy and docs are in Spanish; keep new UI text in Spanish.

No `INTERNET` permission, no telemetry, no cloud. The report is generated entirely on-device from system APIs, `/proc`, `getprop`, `build.prop`, sysfs and short interactive tests.

## Commands

Windows:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lint
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Use `./gradlew` on macOS/Linux. Run a single test with `--tests "com.devicescope.app.YourTest"`.

- Requires JDK 17 and Android SDK 35. There is no `local.properties`; set `ANDROID_HOME` or create the gitignored `local.properties` with `sdk.dir=...`.
- No CI, formatter or static-analysis config beyond Android lint.
- Unit tests: `app/src/test/java/com/devicescope/app/data/`. Instrumentation tests: `app/src/androidTest/`.

## Architecture

- One activity: `MainActivity` → `DeviceScopeApp` (`NavHost`: `dashboard`, `fullDiagnostic`).
- `data/`:
  - `DiagnosticModels.kt`: `DiagnosticStatus`, `DiagnosticMetric`, `DiagnosticSection`, `VoltageRail`, `ThermalZone`, `RawPropertyGroup`, `FullDiagnosticReport` (+ `toText()`/`toJson()`), `ProbePermissions`, `DiagnosticSectionIds`.
  - `DiagnosticAudit.kt`: **pure Kotlin** consistency engine. `IdentitySnapshot` → `List<AuditFinding>`. Also `sectionStatus()` and `overall()`. Unit-testable without Android.
  - `FullDiagnosticBuilder.kt`: the only reader. `build(context, permissions)` runs on `Dispatchers.IO`, reads everything and returns `FullDiagnosticReport`. Helpers: `requestedPermissions()` and `permissionsFrom(context)`.
  - `DeviceReaders.kt` / `PowerEstimator.kt`: reused low-level readers (`BatteryDiagnosticReader`, `UsbDiagnosticReader`, `StorageDiagnosticReader`, `DeviceReading`, `PowerEstimator`).
  - `DeviceModule.kt`: catalog with exactly one `DeviceModule` (`ModuleIds.FULL_DIAGNOSTIC`).
- `ui/screens/`: `DashboardScreen` (single card + last report summary), `FullDiagnosticScreen` (state, sections, export, permissions), `DiagnosticDialogs.kt` (mic, speakers, touch grid, color test, 10 s charge measurement).
- `ui/components/`: `ScreenHeader`, `StatusPill` (uses `DiagnosticStatus`), `DiagnosticUi` (`SectionCard`, `MetricRow`, `FindingsBlock`, `NoteBlock`, `ActionButton`, `SectionIcon`).
- No ViewModel, repository or DI. Screen state lives in `remember`; the report is passed back to `DeviceScopeApp` for the dashboard summary.
- Interactive test results are merged into sections in `FullDiagnosticScreen` via `sessionResults: Map<String, List<DiagnosticMetric>>` and re-scored with `DiagnosticAudit.sectionStatus()`.
- Export uses `ActivityResultContracts.CreateDocument` (TXT and JSON). No `ACTION_SEND`, no network.

## Hard constraints

- **Never add `android.permission.INTERNET`**, telemetry or cloud dependencies.
- Privacy: never read ICCID, IMSI, phone number, location coordinates, contacts or SMS. Do not scan WiFi/Bluetooth networks. Audio recorded for the mic test stays in memory.
- Runtime permissions used: `READ_PHONE_STATE`, `RECORD_AUDIO`, `NEARBY_WIFI_DEVICES` (`neverForLocation`), `BLUETOOTH_CONNECT`. Denied permissions must produce explicit "Requiere permiso" metrics, never fabricated values.
- `minSdk = 26`, `compileSdk`/`targetSdk = 35`. Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, Compose BOM 2024.12.01. `kotlinx-coroutines-android:1.7.3` is declared explicitly.
- UI strings hardcoded in Kotlin; `res/values/strings.xml` only has `app_name`.
- `FullDiagnosticBuilder` reads sysfs paths that SELinux may block. Always wrap in `runCatching` and degrade to `BatteryManager` values. Never claim to read PMIC/SoC core voltage rails on a non-rooted device.
- Anti-spoof findings are **coherence checks**, not proof of authenticity. Say so in UI copy and docs.
- Do not reintroduce fake health scores, fixed status pills, static guides, multiple modules, APK-compatibility screens or paid-license code.
