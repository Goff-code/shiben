# Reminder runtime-denial fixture proposal

Base: QA commit `519642fcd9ae5bab0e5a63ef532d22359999c2cd`. Production remains exact `b1838a8fb66b74b13c278991d097b9be48155688`. This worker prepared and statically reviewed only these files; it did not apply, compile, install, or run them. The parent independently reported applying the two reviewed test replacements and starting its build. Build/runtime outcomes must come from that executor's evidence.

## Files

- `java/cn/focusledger/ReminderInstrumentationChecks.java`: complete replacement for the existing cloud-native test overlay class.
- `java/cn/focusledger/LedgerInstrumentation.java`: complete replacement; adds only `reminders_denied` dispatch.
- `reminder-denial.patch`: unified patch against those two files under `tests/cloud-native/java/cn/focusledger/`.
- `proposal.json`: exact base and replacement SHA-256 values.
- `static-validation.json`: completed static checks and explicit unrun stages.

Keep the previous failed-attempt evidence. The first failure was emulator recognition; the later failure was Android 15 refusing direct runtime app-op mutation. Neither attempt qualifies as completed reminder coverage.

## Behavior and limits

On API33+, `reminders` retains its fresh empty disposable debug fixture, granted notifications and absent effective completion channels. Its preference/UI checks, four actual posted mode notifications, master switch, legacy-channel retention, customized importance, expiry/idempotence, pause/resume, recovery and screen-off/background assertions stay intact. Only in-process appops denial moves to a separate fresh-install stage. The log explicitly says denial is not covered in the granted phase. The exact existing emulator guard and original timeouts remain unchanged.

API<33 retains the old appops denial/restoration body. `reminders_denied` explicitly rejects API<33.

`reminders_denied` requires the same fresh/debug/emulator/channel guards, plus the actual POST_NOTIFICATIONS runtime permission being ungranted, Android reporting notifications disabled and the production permission check agreeing. It keeps the reminder master enabled and exercises all four modes through the existing valid accelerated 15-minute state and genuine production expiry path. Each mode must produce exactly one 900000ms expired record, no durable timer, no active completion or fallback notification under any ID, no duplicate after repeated refresh/EXPIRE, and manual progress still 40%. Permission must remain ungranted. Preferences are restored. No app-op, permission or system setting is changed by the API33+ denied stage.

Production may create `focus_live` before evaluating permission. The suite intentionally checks no active notifications, rather than incorrectly requiring no channel creation at all.

This proves behavior with an ungranted fresh-install runtime permission. It does not prove clicking Deny in a permission dialog, live revocation of a grant, physical sound/vibration, realme behavior, or formal release-certificate upgrade.

## Integration

Only the two test-overlay replacements change. Keep ReminderInstrumentationChecks in the overlay replacement list alongside Ledger/Graffiti, regenerate the overlay manifest and test APK hash, and verify the production APK remains byte-identical. Both reminder stages should use the same reviewed test APK and unchanged production APK. Previously completed graffiti evidence retains its original test APK hash.

## Execution order

Use the already verified disposable emulator and explicit `$ANDROID_SERIAL`. These are instructions for the parent executor; this worker ran none of these commands. `$APP_APK` is the verified unchanged current app and `$TEST_APK` is the reviewed replacement test APK. Preserve complete logs, exit codes, APK hashes and system-server identity throughout.

0. Preserve failed-attempt logs/logcat, package/notification state and `files/qa-reminders` / `files/qa-v2` before resetting. A failed run that already created tasks/channels cannot be reused for the fresh granted fixture. An independent gallery run may finish on the existing fixture; preserve its `files/qa-v203` before resetting.

1. Fresh granted phase:

```sh
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben.test
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben
adb -s "$ANDROID_SERIAL" install -g "$APP_APK"
adb -s "$ANDROID_SERIAL" install "$TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e suite reminders cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

Collect `files/qa-reminders` and `files/qa-v2` immediately. If gallery remains unrun, it may run here, followed by collection of `files/qa-v203` before the next reset. Preserve any failure evidence.

2. Fresh actual-runtime-denied phase:

```sh
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben.test
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben
adb -s "$ANDROID_SERIAL" install "$APP_APK"
adb -s "$ANDROID_SERIAL" install "$TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e suite reminders_denied cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

Do not add `-g`, launch a permission prompt or grant/revoke permissions between this install and the suite. Capture package/notification dumps before and after as independent state evidence. The fixture must itself assert actual permission state; omitting `-g` alone does not prove denial. Collect `files/qa-reminders-denied`, the log and dumps before another reset. On failure, additionally collect optional `files/qa-v2/99-failure.png` when available; do not mislabel it as permission evidence.

3. Fresh general UI phase:

```sh
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben.test
adb -s "$ANDROID_SERIAL" uninstall cn.goff.shiben
adb -s "$ANDROID_SERIAL" install "$APP_APK"
adb -s "$ANDROID_SERIAL" install "$TEST_APK"
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e suite all cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e suite gallery_ui cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -r -e suite gallery_view cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

This extra reset is essential: `reminders_denied` leaves one task and four records; `all` requires an empty ledger. Keep all -> gallery_ui -> gallery_view in one installation because the latter two require the current-day fixture. Collect `qa-v2` and `qa-v203`, then follow the existing separate old202 seed -> install-r current -> verify -> routing plan. This proposal changes no upgrade steps or protections.

## Completion gate

For each reminder stage, require adb/process exit 0, exactly one positive `OK (N checks)`, exactly one `INSTRUMENTATION_CODE: -1`, and no FAIL/crash/shortMsg/timeout, using the existing instrumentation result parser. Also require the relevant exact streamed marker:

- Granted: `REMINDERS_PHASE_PASS phase=granted denial=separate-required suite=reminders_denied`
- Denied: `REMINDERS_PHASE_PASS phase=runtime-denied modes=4`

Markers are emitted only after that stage's assertions and preference restoration succeed. A marker without successful instrumentation termination remains incomplete. Record both app/test APK hashes with both results. Only both passing stages establish combined API33+ reminder coverage. `REMINDERS_COVERAGE runtime-denial=not-covered-here ...` is a scope disclosure, never a passed denial assertion.

Original timeouts were deliberately not changed. An outer process-ceiling increase cannot repair an internal timeout or ANR. Retain normal system watchdogs and security controls and preserve failures faithfully.

## API35 active alarm dump heading

The first real granted run reached timer checks, then rejected the official image's active-list heading `17 pending alarms:`. The preserved dump includes the expected current `cn.focusledger.EXPIRE` alarm. The test parser additionally recognizes an anchored line containing a decimal count and `pending alarms:`. It still reads only active alarm blocks and stops before blocked/non-wakeup queues or historical statistics. No timer assertion, Android timeout, production alarm scheduling, or system property changes.
