# OZAK Inventory 0.3.1 — pilot test instructions

Install the APK from the Inventory pilot release and open **OZAK Inventory**. Android 11 or newer is required. This is an internally tested pilot APK, not a public production rollout.

1. Open **Open PCB / QR Scanner** and scan the engraved DataMatrix on a real PCB. Adjust focus, light and square size as needed. Tap **Use scanned code** and compare the decoded serial with the board. This screen does not send production records.
2. Open **Production controls**, sign in inside the app and approve the connection. Do not share passwords, cookies or login codes.
3. Select the authorized pilot order manually. Confirm **OBSERVATION** and **Scans required for production: No**. Start with a read-only board-context check; do not submit until the pilot owner confirms the actual production operation.
4. Select the correct stage, side and station. Scan the PCB and compare the selected stage with the server's expected stage.
5. Tap **Submit stage** only to record an intentional production operation. Select PASS or FAIL where required. FAIL requires a note; completing the route does not erase a FAIL.
6. Receive reels individually by scanning their exact reserved package label. Confirm that receiving one reel leaves the others pending. Scanning does not consume material.

Use only the designated single pilot operator account for production recording. Multi-operator behavior has not been validated. Supplemental reservation and handover are separate explicit operations; do not use them during a scanner-only test.

A camera read is not server acceptance. On timeout, retain the unknown request and use status recovery; do not create a replacement request. OBSERVATION allows normal warehouse production to continue without scans or connectivity.

Report readability problems, the selected stage and the visible error message. Do not send credentials. Small, low-contrast engraved PCB performance still needs on-line testing. The camera's auxiliary status text can be clipped on some screens; the focus, lighting and local-result buttons were moved above the preview.
