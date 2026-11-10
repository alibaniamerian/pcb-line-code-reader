# OZAK Inventory Android pilot

Version 0.3.1-stages-camera-pilot, Android 11+. The original standalone camera project remains at the repository root. This directory contains the Inventory app and its pinned scanner integration.

See [English pilot instructions](docs/PILOT_TEST_EN.md). Download the Inventory APK from the release tagged `inventory-v0.3.1-pilot`; do not confuse it with the standalone PCB reader or synthetic sandbox apps.

The operator app uses embedded WebView authentication and server-provided orders, routes, sides and stations. Submissions require explicit operator action. Unknown requests are retained privately for recovery. OBSERVATION does not block normal warehouse production. No consumption operation is exposed.

## Build

Use JDK 17, Android SDK 36 / build tools 36.0.0, Gradle 8.13 and Python 3.10. Run `build-scanner.ps1` with the `-Sdk`, `-Jdk`, `-Python` and `-Gradle` paths for your machine. The scanner uses the pinned Java decoder and Python/OpenCV fallback. Create your own local debug signing key at `.android/pilot-debug.keystore` (standard Android debug alias/password); signing keys are intentionally not included. A new key cannot update an existing installation signed with another key.

The `-OperatorSandbox` switch builds a separate localhost-only test package. Server sandbox delivery files are not included. Main builds must omit this switch. Private databases, test passwords, journals, cookies, captures, delivered server bundles and build caches are excluded from this repository.

## Verification and limits

The stage harness passed 36 API checks in an isolated single-user SQLite sandbox. A separate normal-UI camera test recorded one PCB across eight stages and received one reel independently, with unchanged inventory and zero consumption. These results do not establish PostgreSQL concurrency, multi-operator acceptance or general readability of tiny engraved marks. Pilot UI has a known auxiliary scanner-status clipping limitation.
