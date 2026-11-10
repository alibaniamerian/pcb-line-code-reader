# PCB Line Reader

Mobile code reader for PCB production lines. This repository is independent of
the panel-registration application: no panel file, four-point alignment, web
server, private captures or external source checkout is required.

## Install and use

Download `PCBLineReader-debug.apk` from [Releases](https://github.com/alibaniamerian/pcb-line-code-reader/releases).
Install it on Android 11 or later and allow Camera access. Select DataMatrix or
QR, choose Square 1x/2x/3x, and position the complete code inside the square with
a small clear margin. Focus and Light are manual controls. Green means a local
decode succeeded; it is not a server acknowledgement.

The release APK is a debug-signed test build for evaluation, not a production
deployment package. Application ID `org.datamatrix.linereader` is retained so it
can update the earlier DataMatrix Line installation. The displayed name is now
PCB Line Reader. It installs separately from the panel reader.

## Team handoff

- [Architecture and decoding method](docs/ARCHITECTURE.md)
- [Server integration proposal and extension points](docs/SERVER_INTEGRATION.md)
- [Persian delivery note](docs/HANDOFF_FA.md)

## Current behavior

Reads one production-line PCB DataMatrix or QR inside a centered square.

The Square 1x/2x/3x selector scales both sides of the reading region relative to
the current base size and persists on the phone. The base side is 13.5% of the
shorter preview dimension (2x = 27%, 3x = 40.5%). Changing size clears the previous
result and rejects old in-flight results; the overlay and analysis crop use the
same updated mapping. Preview and analysis use
matching stream sizes and the same sensor crop, with explicit sensor/display
mapping. Capture selects a supported common preview/YUV size nearest 1920x1080.
A centered 1.5x sensor crop (capped by device support) preserves code detail.
TextureView displays the camera stream directly; no full-frame RGB conversion or
bitmap upload runs on the CPU. Only square luminance samples enter the decoder.

On the tested A21s, capture was reduced from 2576x1932 to 1920x1080 and the decoder
crop initially from 150x150 to 96x96, about 60% fewer samples. The visible square
was then enlarged by 1.5x per side (approximately 144x144 samples at that layout)
for easier placement. Approximately 30 capture frames
per second and a successful live read were observed. This is a preview/capture
rate, not a claim of 30 decoded codes per second. Decode work runs independently.

The top DataMatrix/QR selector enables exactly one format; selection persists on
the phone. Switching clears the displayed result and discards in-flight results
from the old mode. Both modes read only the displayed square.

The app crops native Y luminance samples to the displayed square before decoding.
ZXing attempts the selected format using local/global thresholds, local illumination
correction and both polarities. QR never runs the specialized DataMatrix pipeline.
In DataMatrix mode, if the fast reader fails, OpenCV finds code-boundary candidates inside the square,
fits a quadratic illumination surface outside each candidate and samples observed
ECC200 grids. At least 95% finder conformance and a successful ZXing ECC200 decode
are required. Neither the geometry nor the text is hardcoded. The observed-grid
decoder and sampling utilities are included in this repository. No panel project
source dependency remains.
Continuous autofocus and automatic exposure are enabled; Focus and Light buttons
provide manual control. A successful read turns the square green briefly and
shows the exact payload and reading time. The latest result remains visible while
the camera continues scanning. No payload whitelist or bit guessing is used.

This first version reads locally. Server submission, acknowledgements, duplicate
handling and production tracking are not implemented yet. There is no network
permission. The panel alignment and RAW localization pipelines are not included.

Build with JDK 17, Android SDK 36, Gradle 8.11.1 and Python 3.10 (PYTHON310):

```powershell
$env:PYTHON310 = 'C:\path\to\python310\python.exe'
.\build.ps1 -Sdk C:\path\to\sdk -Jdk C:\path\to\jdk17
adb install -r build/outputs/apk/debug/PCBLineReader-debug.apk
```

Install Gradle on PATH or pass `-Gradle C:\path\to\gradle.bat`.
The script supports `-GradleUserHome` for an existing dependency cache. Local SDK
and JDK overrides may be stored in ignored `.local/build-config.json` with `Sdk`
and `Jdk` properties. SDK paths, signing keys, captures and build caches are not
committed. Network access is needed for the first build's dependency downloads.
The Android app itself has no Internet permission and sends no data.

After building, run `test.ps1` to test exclusive DataMatrix/QR modes, rotations,
polarities and blank rejection. It accepts `-Jdk`, `-ZxingJar` and `-GradleUserHome`.
Project license has not yet been selected by the owner.

The minimum Android version is 11. Camera permission is requested on first launch.

For USB diagnosis, create the private `files/capture_scan` marker using `run-as`.
One full luminance frame, the exact decoder crop and geometry metadata are saved
as `scan_frame.pgm`, `scan_roi.pgm` and `scan_info.txt` in app-private storage.
No diagnostic images are saved during normal use.

## OZAK Inventory Android pilot

The separate [Inventory app](inventory-android/README.md) includes production-stage controls and embedded sign-in. See its [English test guide](inventory-android/docs/PILOT_TEST_EN.md) and the `inventory-v0.3.1-pilot` release for the APK. The standalone reader above remains unchanged.
