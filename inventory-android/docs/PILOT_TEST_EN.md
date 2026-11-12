# OZAK Inventory 0.5.1 — pilot test instructions

Install the APK from the Inventory pilot release and open **OZAK Inventory**. Android 11 or newer is required. This is an internally tested pilot APK, not a public production rollout. It updates the 0.3.1 pilot in place (same signing key); local login data is kept.

**What changed since 0.3.1**

- The camera now opens **inside** the reel and PCB stage screens. There is no separate scanner screen and no "Use scanned code" or "Submit" confirmation.
- **Each valid code is recorded automatically** as soon as it is read. Green means the server confirmed it; red means nothing was recorded, and the reason is shown.
- **PCB stages are locked until every reel of the order has been received.** This lock exists only in the app.

## Steps

1. Open **Production controls**, sign in inside the app and approve the connection. Do not share passwords, cookies or login codes.
2. Select the authorized pilot order manually. Confirm the control mode and the **Reels received: x / y** counter on the order overview. **PCB stages / line entry** shows *(locked)* until all reels are received.
3. Open **Receive reels and packages** and tap **Scan at** for the station. The camera opens on the same screen, above the reference list:
   - **Black (○)**: still to be received. Packages awaiting handover or a warehouse transfer are marked as such and cannot be received yet.
   - **Green (✓)**: the server confirmed the receipt.
   - **Red (✗)**: a wrong scan that was not recorded. The reason and time are shown. Examples: not an OZAK label, not a package of this order, already received, handover required, rejected by the server.
4. Scan each reel label individually. Confirm that receiving one reel leaves the others black. Scanning does not consume material.
5. When all reference reels are green, the PCB stages unlock. If the server returns no packages, or some reels cannot be received, the stages stay locked. **Override reel lock** unlocks them with a written reason. The override is stored **only on this phone**, is not sent to the server, and is cancelled when a reel is added later. Use it only with the pilot owner's approval.
6. Open **PCB stages / line entry** and tap **Scan at** for the correct stage, side and station. Every board read in the square is checked with the server and recorded automatically. Watch for the server's expected-stage note.
7. For inspection stages, the board is read and checked first. Then tap **PASS** or **FAIL** to record it. FAIL requires a note. Completing the route does not erase a FAIL. **Discard read** drops the board without recording.
8. The camera stays on for the next code. A code held in view is not recorded twice. A board already recorded at this stage on this phone is not resent. Tap **Stop camera** or go back to close the camera.

## Rules and limits

Use only the designated single pilot operator account for production recording. Multi-operator behavior has not been validated. Supplemental reservation and handover are separate explicit operations.

Only one request is in flight at a time. A camera read is not server acceptance: only green after the server response counts. On timeout, the request is retained and new scans are refused until **Check saved request status** resolves it; do not create a replacement request.

The reel lock is an app-side policy. The server does not yet enforce it, and in OBSERVATION mode normal warehouse production continues without scans or connectivity.

Report readability problems, the selected stage and the visible message (green or red). Do not send credentials. Small, low-contrast engraved PCB performance still needs on-line testing; use **Focus**, **Light** and **Square 1x/2x/3x** under the preview.
