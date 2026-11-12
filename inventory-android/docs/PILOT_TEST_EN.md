# OZAK Inventory 0.8.0 — pilot test instructions

Install the APK from the Inventory pilot release and open **OZAK Inventory**. Android 11 or newer is required. This is an internally tested pilot APK, not a public production rollout. It updates earlier pilots in place (same signing key); local login data is kept.

**Summary**

- New interface: home cards, order dashboard tiles (Reels, PCB stages, Supplement, Progress), app bar with Online/Offline status and a ⋮ menu (sign in, refresh, leave).

- The camera opens **inside** the reel and PCB stage screens. There is no separate scanner screen and no extra confirmation.
- **Reels are checked against the order's reference list.** Currently this is a **LOCAL VERIFICATION**: the result is stored on this phone only and nothing is sent to the server. **Use one phone per order** for the reel check.
- **PCB stages are locked in the app** until every reel of the reference list is green.
- **PCB boards are recorded on the server automatically.** Green means the server confirmed the record; red means nothing was recorded, and the reason is shown.

## Steps

1. Open **Production controls**, sign in inside the app with **your own** operator account and approve the connection. Do not share passwords, cookies or login codes.
2. Select the authorized pilot order manually. The order overview shows the verification mode and **Reels verified: x / y**. **PCB stages / line entry** shows *(locked)* until all reels are verified.
3. Open **Receive reels and packages** and tap **Scan reels of this order**. The camera opens above the reference list:
   - **Black (○)**: not yet scanned.
   - **Green (✓)**: verified. In local mode this is *Local verification on this phone* with a time.
   - **Red (✗)**: a wrong scan, with reason and time. Examples: not an OZAK label, not a package of this order, already verified, wrong code type.
   - Packages needing a warehouse transfer show a ⚠ warning; they can still be verified.
4. Scan each package label individually. Handover is not required for this check. Nothing is consumed or moved.
5. When all reference reels are green, the PCB stages unlock. If the server returns no packages, the stages stay locked: the order data is incomplete and must be reported. **Override reel lock** unlocks the stages with a written reason. The override is stored **only on this phone** and is cancelled when a package is added later. Use it only with the pilot owner's approval.
6. **Reset reel verification on this phone** clears the local greens of the order, for example after a simulation reset.
7. Open **PCB stages / line entry** and tap **Scan at** for the correct stage, side and station. Every board read in the square is checked with the server and recorded automatically. Watch for the server's expected-stage note.
8. For inspection stages, the board is read and checked first. Then tap **PASS** or **FAIL**. FAIL requires a note. **Discard read** drops the board without recording.
9. The camera stays on for the next code. A code held in view is not recorded twice. A board already recorded at this stage on this phone is not resent. Tap **Stop camera** or go back to close the camera.

## Server reel check (prepared, not active)

The app is prepared for a future server-side reel check (`REEL_CHECK`). It switches to it **only** when the server advertises `reel-checks` for the signed-in session. In that mode:

- Green means *checked on the server*, shared with all operators. The list refreshes every 10 seconds while the order or reel page is open.
- Phone-only greens from local mode are ignored and must be re-scanned.

The server has not implemented this yet. It will be enabled only after a joint sandbox test. Until then no `REEL_CHECK` request is sent.

## Rules and limits

- **Accounts:** Use separate operator accounts for different people. Multi-operator behaviour is not yet validated.
- **Requests:** Only one request is in flight at a time. A camera read is not server acceptance; only green after the server response counts for boards.
- **Timeouts:** On timeout, the request is retained and new board scans are refused until **Check saved request status** resolves it. Do not create a replacement request.
- **App-side policies:** The reel lock and local verification exist only in the app. The server does not enforce them. In OBSERVATION mode, normal warehouse production continues without scans or connectivity.
- **Reporting:** Report readability problems, the selected stage and the visible message (green or red). Do not send credentials.
- **Readability:** Small, low-contrast engraved PCB performance still needs on-line testing. Use **Focus**, **Light** and **Square 1x/2x/3x** under the preview.
