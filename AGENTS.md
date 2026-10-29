# AGENTS.md

## Project

DeviceScope (`com.devicescope.app`): Android app in Kotlin + Jetpack Compose with **two modules**:

1. **Full diagnostic** (`fullDiagnostic`) — exhaustive offline analysis of every phone component. No network at all.
2. **PC-Móvil** (`pcMovil` + 8 tool screens) — keyboard, mouse, camera as webcam, mic/speakers, file transfer, VPN, PC resources and an RF scanner. LAN-only, against a user-run Python agent in `pc-agent/`.

README, UI copy and docs are in Spanish; keep new UI text in Spanish.

The full report is generated entirely on-device from system APIs, `/proc`, `getprop`, `build.prop`, sysfs and short interactive tests. PC-Móvil never contacts a cloud service: it opens plain TCP/UDP sockets to the paired PC on the local network.

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
- Unit tests: `app/src/test/java/com/devicescope/app/data/` and
  `app/src/test/java/com/devicescope/app/pcmovil/` (pure-JVM: `MiniJson`, `PcProtocol`, `IpPacketCodec`).
  Instrumentation tests: `app/src/androidTest/`.
- PC agent: `cd pc-agent && python -m unittest discover -s tests -v` (protocol + loopback integration:
  pairing, stats, files, real VPN relay, session limits). `python agent.py --diagnose` prints detected capabilities.

## Architecture

- One activity: `MainActivity` → `DeviceScopeApp` (`NavHost`: `dashboard`, `fullDiagnostic`, `pcMovil`,
  `pcKeyboard`, `pcMouse`, `pcCamera`, `pcAudio`, `pcTransfer`, `pcVpn`, `pcResources`, `pcScanner`).
- `data/`:
  - `DiagnosticModels.kt`: `DiagnosticStatus`, `DiagnosticMetric`, `DiagnosticSection`, `VoltageRail`, `ThermalZone`, `RawPropertyGroup`, `FullDiagnosticReport` (+ `toText()`/`toJson()`), `ProbePermissions`, `DiagnosticSectionIds`.
  - `DiagnosticAudit.kt`: **pure Kotlin** consistency engine. `IdentitySnapshot` → `List<AuditFinding>`. Also `sectionStatus()` and `overall()`. Unit-testable without Android.
  - `FullDiagnosticBuilder.kt`: the only reader. `build(context, permissions)` runs on `Dispatchers.IO`, reads everything and returns `FullDiagnosticReport`. Helpers: `requestedPermissions()` and `permissionsFrom(context)`.
  - `DeviceReaders.kt` / `PowerEstimator.kt`: reused low-level readers (`BatteryDiagnosticReader`, `UsbDiagnosticReader`, `StorageDiagnosticReader`, `DeviceReading`, `PowerEstimator`).
  - `DeviceModule.kt`: dashboard catalog (`ModuleIds.FULL_DIAGNOSTIC`, `ModuleIds.PC_MOVIL`) with a `cta` per module.
- `ui/screens/`: `DashboardScreen` (module cards + last report summary), `FullDiagnosticScreen` (state, sections, export, permissions), `DiagnosticDialogs.kt` (mic, speakers, touch grid, color test, 10 s charge measurement).
- `pcmovil/data/`: `PcModels` (link state, capabilities, metrics, scan/transfer/VPN models), `PcDeviceStore` (`SharedPreferences`: paired devices, tokens, mirror/metrics/VPN settings).
- `pcmovil/link/`: `PcProtocol` + `MiniJson` + `PcWire` (JSON lines and 6-byte-header binary frames on one socket), `PcConnection` (reader loop, correlated requests, conflated flows), `PcDiscovery` (UDP broadcast), `PcLinkManager` (singleton: connect/pair/heartbeat/input/mirror/files/VPN), `PcFileTransfers`, `PcAudioBridge`, `PcCameraStreamer`, `IpPacketCodec` (pure), `PcVpnEngine` + `PcVpnService`, `DataScanner`, `PcRemoteInputService` (AccessibilityService).
- `ui/PcMovilScreen.kt`, `ui/Pc*ToolScreen.kt`, `ui/PcUi.kt`, `ui/PcFormat.kt`, `ui/PcTools.kt`: module panel, 8 tool screens and shared widgets.
- `ui/components/`: `ScreenHeader`, `StatusPill` (uses `DiagnosticStatus`), `DiagnosticUi` (`SectionCard`, `MetricRow`, `FindingsBlock`, `NoteBlock`, `ActionButton`, `SectionIcon`).
- No ViewModel, repository or DI. Screen state lives in `remember`; the report is passed back to `DeviceScopeApp` for the dashboard summary.
- Interactive test results are merged into sections in `FullDiagnosticScreen` via `sessionResults: Map<String, List<DiagnosticMetric>>` and re-scored with `DiagnosticAudit.sectionStatus()`.
- Export uses `ActivityResultContracts.CreateDocument` (TXT and JSON). No `ACTION_SEND`. The diagnostic never opens a socket.
- PC-Móvil uses the same plain-Compose state style (no ViewModel/DI) plus the `PcLinkManager` singleton created with the application context, because the accessibility service must share the same link.

## Hard constraints

- **The full diagnostic must stay offline.** `INTERNET` exists only for PC-Móvil: never add analytics, crash reporting, ads, cloud APIs or third-party network SDKs. Never add a relay/rendezvous server: the phone talks straight to the agent.
- PC-Móvil only reaches the LAN: no DNS-based discovery, no external STUN/TURN, no accounts. Pairing = UDP discovery + 6-digit PIN + token stored in private prefs (and `0600` JSON on the PC). The control channel is **not** encrypted; document it and keep the warning in the UI.
- Privacy: never read ICCID, IMSI, phone number, contacts or SMS. Coordinates are never read. WiFi scan uses `NEARBY_WIFI_DEVICES` with `neverForLocation` on Android 13+; `ACCESS_FINE_LOCATION` is requested only for the scanner on Android ≤ 12 and only after explicit user action.
- Runtime permissions: `READ_PHONE_STATE`, `RECORD_AUDIO`, `NEARBY_WIFI_DEVICES`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `CAMERA`, `ACCESS_FINE_LOCATION` (scanner, ≤ 12). Denied permissions must produce explicit "Requiere permiso" metrics or an explanatory note, never fabricated values.
- Services: `PcRemoteInputService` is an `AccessibilityService` the user enables manually — never auto-enable, never read window content beyond the focused editable node, never log typed text. `PcVpnService` must keep `BIND_VPN_SERVICE`, never request `0.0.0.0/0` by default, and must show the user which CIDRs are routed. Do not add `QUERY_ALL_PACKAGES`, SMS, call-log or overlay permissions.
- PC-Móvil has documented limits that must stay honest in UI copy: text injection only works on apps exposing an editable node; screen mirror needs Pillow/mss on the agent; webcam-as-system-device needs `v4l2loopback`/OBS; audio needs `sounddevice`; the VPN is IPv4 TCP/UDP only and does not tunnel ICMP; the scanner estimates distance from RSSI and cannot see what the radio does not hear.
- `minSdk = 26`, `compileSdk`/`targetSdk = 35`. Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9, Compose BOM 2024.12.01. `kotlinx-coroutines-android:1.7.3` is declared explicitly.
- UI strings hardcoded in Kotlin; `res/values/strings.xml` only has `app_name` and the two accessibility-service strings.
- The Kotlin wire format is a contract with `pc-agent/devicescope_agent/protocol.py`: change both sides and both test suites (`PcProtocolTest`, `tests/test_protocol.py`) together. Keep channel ordinals stable.
- The PC agent must start with the standard library only and degrade per capability (`capabilities` in `hello`); never print stack traces of secrets, never log PINs or tokens. Optional deps: `psutil`, `pillow`+`mss`, `pynput`, `sounddevice`.
- `FullDiagnosticBuilder` reads sysfs paths that SELinux may block. Always wrap in `runCatching` and degrade to `BatteryManager` values. Never claim to read PMIC/SoC core voltage rails on a non-rooted device.
- Anti-spoof findings are **coherence checks**, not proof of authenticity. Say so in UI copy and docs.
- Do not reintroduce fake health scores, fixed status pills, static guides, APK-compatibility screens or paid-license code. Tool cards may be disabled only when the link/capability is really missing, and must say why.
