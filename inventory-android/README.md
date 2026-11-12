# OZAK Inventory Android pilot

Version 0.7.0-reel-check-ready-pilot, Android 11+. The original standalone camera project remains at the repository root. This directory contains the Inventory app and its pinned scanner integration.

See [English pilot instructions](docs/PILOT_TEST_EN.md). Download the Inventory APK from the release tagged `inventory-v0.3.1-pilot`; do not confuse it with the standalone PCB reader or synthetic sandbox apps.

The operator app uses embedded WebView authentication and server-provided orders, routes, sides and stations. The operator explicitly chooses the stage and station; the camera then runs inside that screen and each valid PCB board is recorded automatically (inspection stages still require an explicit PASS/FAIL, FAIL requires a note). Reels are checked against the server PACKAGES list as a LOCAL VERIFICATION stored on the phone only (one phone per order); nothing is sent. The app is prepared for a server REEL_CHECK and switches to it only when the server advertises "reel-checks". PCB stages are locked in the app until every reference package is verified, unless the operator records a local override with a reason (app-side policy; not enforced by the server). Write acceptance checks request_id and order, and accepts ALREADY_RECORDED by another operator only when the server reports requesting_user_id equal to the current user. Unknown requests are retained privately for recovery. OBSERVATION does not block normal warehouse production. No consumption operation is exposed.

## Build

Use JDK 17, Android SDK 36 / build tools 36.0.0, Gradle 8.13 and Python 3.10. Run `build-scanner.ps1` with the `-Sdk`, `-Jdk`, `-Python` and `-Gradle` paths for your machine. The scanner uses the pinned Java decoder and Python/OpenCV fallback. Create your own local debug signing key at `.android/pilot-debug.keystore` (standard Android debug alias/password); signing keys are intentionally not included. A new key cannot update an existing installation signed with another key.

The `-OperatorSandbox` switch builds a separate localhost-only test package. Server sandbox delivery files are not included. Main builds must omit this switch. Private databases, test passwords, journals, cookies, captures, delivered server bundles and build caches are excluded from this repository.

## Verification and limits

The stage harness passed 36 API checks in an isolated single-user SQLite sandbox. A separate normal-UI camera test recorded one PCB across eight stages and received one reel independently, with unchanged inventory and zero consumption. These results do not establish PostgreSQL concurrency, multi-operator acceptance or general readability of tiny engraved marks. These results predate 0.4/0.5: the embedded automatic camera and the reel lock built and install cleanly but have not yet been tested against a live pilot order.
