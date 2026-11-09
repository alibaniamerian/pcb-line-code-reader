# Architecture

## Scope

PCB Line Reader is a standalone Android scanner. It reads one code inside a
centered square and displays verified text locally. It contains no panel
registration, pick-and-place data, browser bridge or server transport.

The package is `org.datamatrix.linereader`; the app label is PCB Line Reader.
Keeping the package preserves compatibility with the initial DataMatrix Line
installation. DataMatrix RAW uses a different package and can coexist.

## Camera and coordinates

`MainActivity.java` owns Camera2, permission handling, autofocus and torch.
Preview and YUV analysis use a common output size nearest 1920x1080 and a shared
centered sensor crop, up to 1.5x. TextureView provides hardware preview without
CPU conversion of the whole camera image. Sensor/display rotation is applied
explicitly; the inverse image mapping determines the square's native crop.

Only luminance samples inside that crop reach the decoder. Square 1x has a side
of 13.5% of the shorter preview dimension. 2x and 3x multiply both sides. Format
and square size persist in app preferences. A setting epoch rejects results
from earlier settings, including jobs already running when the user switches.

Camera acquisition, UI and serial decoding run separately. At most one decode
job is active. Frames are skipped rather than queued behind a slow decoder.
The tested Samsung A21s delivered approximately 30 camera frames per second.
That is not a decoded-code throughput benchmark.

## QR

`LineDecoder.java` restricts ZXing to QR_CODE in QR mode. It tries local/global
binarization, both polarities and adaptive illumination thresholds. The custom
DataMatrix analysis is never invoked in this mode.

## DataMatrix ECC200

1. The same fast reader is restricted to DATA_MATRIX.
2. On failure, `MarkedDecoder.java` invokes `python/line_grid.py` on the crop only.
3. Threshold and edge contours propose approximately square code boundaries.
   Candidates are ordered by proximity to the crop center; geometry is inferred
   from the current frame, not from known panel positions.
4. A quadratic illumination surface is fit using brighter pixels outside each
   candidate. Its deviation image exposes faint module interiors independently
   of their darker edges.
5. Several small boundary insets, legal square symbol sizes 10..26, cell-sampling
   statistics and deviation thresholds produce observed grid candidates. This
   specialized fallback currently targets small square marks; the fast reader
   also handles the broader symbol set supported by ZXing.
6. Candidates require finder conformance of at least 95%.
7. `GridDecoder.java` tries four rotations and both handednesses, then ZXing's
   ECC200 decoder. The first valid result is accepted. No bit guessing, expected
   text matching or synthetic finder repair is performed.

`grid_sampling.py` contains the self-contained sampling helpers extracted from
the earlier panel project. It imports only NumPy and OpenCV. Android packages
pin ZXing 3.5.3, Chaquopy 17.0.0, Python 3.10, NumPy 1.26.2 and OpenCV 4.5.1.48.

## Results and limits

A successful decode updates the displayed payload/time and briefly turns the
square green. The last result remains visible while scanning continues. Format
or size changes clear the displayed result. Green currently means decoded,
never accepted by a production server.

USB diagnostic images are opt-in through the private `capture_scan` marker.
Ordinary scanning saves no diagnostic frames. No network permission or image
upload is present. Remaining production work includes the transport, event
identity, duplicate policy, acknowledgements, timeout behavior and recovery.
