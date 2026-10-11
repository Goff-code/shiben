# Actions upgrade fixture: real 2.0.2 → 3.0

Status: **passed on the assistant cloud AOSP API35 emulator on 2026-10-11**.
The final fresh chain passed seed 119, retention verify 89, and routing 146
checks (354 total), with one baseline token and byte-identical baseline files.
This is a same-ephemeral-debug-certificate upgrade of the two genuine source
versions. Formal release-certificate installation remains pending user-local
signing. See [the cloud runtime report](QA_CLOUD_RUNTIME_3.0_20261011.md).

## Source and signing contract

- Old production source: `bdbfafa8c4ec4344ae171ba1761f6ffea7017555`, version
  `2.0.2`, versionCode `5`, applicationId `cn.goff.shiben`.
- Old `TimerService.java` blob:
  `2583a9b7114fce48c1971499de218875a0e4a608`.
- Target production source: `b1838a8fb66b74b13c278991d097b9be48155688`, version
  `3.0`, versionCode `7`, same applicationId.
- Build both real source trees with the same freshly generated, ephemeral
  **debug test key**. Retain each tree's real version and production bytes.
  Do not build current code under an artificially lowered versionCode.
- Apply the test overlay only to the Android test APK. Independently compare
  each production APK built without/with the test overlay; its bytes must match.
- Install code5 normally, seed it, then `adb install -r` code7. Never uninstall,
  clear data, reinstall code5, delete/recreate channels, change the app UID, or
  restart/wipe the emulator between seed and verification.
- A successful run would prove a same-test-certificate package update. It does **not** prove the
  formal release-certificate upgrade path and must never be described as one.
  No release signing keys, user computer, or physical device are needed here.

Both source versions already use database schema 3. The existing
`scripts/compare-upgrade-v202.py` expects schema 2 → 3 and is unsuitable for this
pair. `OfficialUpgradeV202Ui.java` also assumes code4 → code5, old individual
sound/vibration switches, and a different history transition. It remains
unchanged and is not an upgrade gate for code5 → code7.

## Integration

New files are isolated under `tests/actions-upgrade`:

- `fixtures.init.gradle`: replaces only the test Java source directory and test
  instrumentation runner. It does not alter the main source, app manifest,
  signing configuration, package name, or versions.
- `java/cn/focusledger/ActionsUpgradeInstrumentation.java`: phased runner,
  production timer/store execution, exact data snapshots, and runtime assertions.
- `java/cn/focusledger/ChannelSettingsUi.java`: actual Android Settings control
  interaction, screenshots, and accessibility hierarchy evidence.

For each pinned checkout, the build interface is:

```sh
./gradlew --no-daemon \
  -I /absolute/fixture-repository/tests/actions-upgrade/fixtures.init.gradle \
  -PactionsUpgradeFixtures=/absolute/fixture-repository/tests/actions-upgrade \
  :app:assembleDebugAndroidTest
```

The parent build script owns checkout verification, isolated Gradle/SDK homes,
the ephemeral debug key, APK inspection, byte comparison, and artifact hygiene.
The runner uses only platform Android APIs, so it needs no additional UIAutomator,
AndroidX, or JUnit download and does not require an `android.test` runtime JAR.

Runtime target: a fresh **AOSP API 35 emulator, English system Settings**, same
boot and same local calendar day throughout. The application still renders its
Chinese labels. The initial debug install must grant notification permission.
Keep exactly one explicitly selected disposable emulator; do not use an implicit
ADB device or a personal phone.

The runner interface, after installing the matching old/new test APK:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -e phase seed \
  cn.goff.shiben.test/cn.focusledger.ActionsUpgradeInstrumentation
# Install the genuine target app with install -r and the matching target test APK.
adb -s "$ANDROID_SERIAL" shell am instrument -w -e phase verify \
  cn.goff.shiben.test/cn.focusledger.ActionsUpgradeInstrumentation
adb -s "$ANDROID_SERIAL" shell am instrument -w -e phase routing \
  cn.goff.shiben.test/cn.focusledger.ActionsUpgradeInstrumentation
```

Do not treat ADB exit 0 alone as test success. Each phase must have its exact
`ACTIONS_UPGRADE_PASS phase=...` line, an independent `OK (N checks)` line with
positive N, and `INSTRUMENTATION_CODE: -1`, with no failure/crash/timeout result.
Missing, truncated, or unsupported results fail the gate. A completed seed alone
is not an upgrade pass.

## Channel matrix

All four effective completion channels have existed since V2. The old runner
sets the released `reminders`, `sound`, and `vibration` preference keys and drives
the genuine old timer-expiry/persistence/notification path to create them.
Neither fixture constructs a `NotificationChannel` nor calls a channel setter.

| Real old channel | Setting changed through Android Settings | Required changed system value |
| --- | --- | --- |
| `shiben_done_sound_vibrate` | Turn channel notification master switch off | importance `NONE` (0) |
| `shiben_done_sound_still` | Sound → None → Save/OK | sound URI `null` |
| `shiben_done_quiet_vibrate` | Vibration switch off | `shouldVibrate()` false |
| `shiben_done_quiet_still` | Silent delivery | importance `LOW` (2), originally HIGH |

Each change is nontrivial against the old channel default. Read-back must show
the changed system value and the associated AOSP user-locked bit (importance
4, sound 32, vibration 16). The bit is read from the platform's diagnostic
string; it is never written or changed by hidden API or shell command. Unknown
diagnostic/UI formats fail with evidence rather than silently relaxing checks.

`focus_done` is obsolete. There is no special presence/default assertion for it.
If old code created it, it is included in the ordinary existing-channel
inventory/control snapshot. Completion must use the four effective IDs above;
creating or checking only `focus_done` cannot pass this test.

The AOSP Settings helper opens the exact package/channel settings intent, checks
the current channel title, and operates the real UI. It uses no app-side
`setImportance` shortcut, notification-service mutation command, channel deletion,
shell identity adoption, or simulated preferences-only override. Unsupported
widgets or an ambiguous control are failures with screenshot/hierarchy evidence.
This first fixture is intentionally limited to AOSP35/en-US; another OS needs a
reviewed UI adapter and a real run before claiming coverage.

## Retention and routing assertions

1. Seed genuine old code with two tasks, nonzero manual progress, prior-day
   history, an actual deleted-session tombstone, a real early-ended timer
   session, and unrelated typed preference sentinels. The four notification
   creation expirations use valid test-accelerated 15-minute state, exclusively
   inside the test APK; production expiry and persistence code are unchanged.
2. Apply all four system controls. Start a real 25-minute timer, let monotonic
   time accrue, pause it, and stop only its live service to obtain a quiescent
   serialized timer snapshot. Do not clear or reset the timer.
3. Store the full typed SQL rows, schema SQL, exact session/timer blobs, all
   preference files/keys/types/values, system channel IDs and controls,
   user-locked fields, two report dates, and immutable timer projection. Identity
   records package, UID, first-install time, signing-certificate digest, boot ID,
   and emulator fingerprint.
4. After package replacement, verify exact stored data **before** launching the
   new Activity or service. Then verify native tasks/progress, paused display,
   unchanged accumulated/planned milliseconds and segments, the new selected
   reminder radio option, and its disabled-channel warning. Read all channels
   again after opening the app. No new/replacement IDs may appear.
5. Paused service checkpoints legitimately re-anchor their clock metadata. Raw
   timer bytes are compared before launch; after UI activity, the projection
   compares exact identity, original time/zone, actual/planned milliseconds,
   paused state, and every accounted segment. The app never gets a broad
   duration tolerance. Other SQL rows stay exact.
6. Only after a verified retention result, the separate `routing` phase may
   intentionally end the disposable paused timer. It selects each new native
   reminder option and executes the genuine expiration path. The disabled
   channel must post no completion. Other modes must post notification ID 2 on
   their exact retained channel. System controls/user-locked fields and the
   complete channel inventory must remain exact after every attempt.
7. Notifications must contain no direct sound, vibration/default overrides,
   full-screen intent, insistent flag, or fallback notification. Reconciliation
   cannot replay completion or add another session. Manual task progress stays
   37%. Do not confuse these software assertions with an audio/haptic sensor.

The database is the source of truth for record retention. Daily Markdown may
intentionally regenerate with the new report template; this fixture preserves
report dates and exact source data rather than asserting obsolete report bytes.
It does not cover every export-format requirement or replace those other tests.

## Evidence and failure handling

All runtime files stay under the synthetic app fixture's
`files/qa-actions-upgrade/`. Collect only this dedicated directory with `run-as`;
never upload signing keys, SDK/Gradle homes, whole app-private data, or unrelated
emulator files. Files include:

- `baseline.json`, `channels-old-defaults.json`, `retention-observed.json`
- `seed-passed.json`, `verify-passed.json`, `routing-passed.json`, phase logs
- `routing-started.json` (single-use mutation guard)
- `seed-posted-*.json`, `routing-posted-*.json`
- phase `*-channels.json`, `*-notification-dumpsys.txt`, `*-package-dumpsys.txt`
- `system-*`, `seed-paused-old`, `verify-*`, `routing-mode-*` PNG/XML pairs
- on failure, `*-failed.log`, `*-failure` PNG/XML, system dumps, and exact
  `*-mismatch-expected.json` / `*-mismatch-actual.json` when applicable

The baseline/evidence includes only the synthetic disposable fixture's records.
The seed refuses existing tasks/timer/channels and any previous evidence folder.
The routing phase refuses a missing verify success marker or a previous routing
attempt. Preserve failed evidence. Start a separately authorized fresh AVD for a
clean rerun; never hide failures by clearing an app midway through the upgrade.

Successful execution establishes software package-update preservation and
channel routing on the recorded emulator image. It does not establish audible
output, physical vibration, realme behavior, background/locked-screen delivery,
OEM battery policies, or release-certificate compatibility. Those need their
own explicitly scoped evidence.

### Official API35 diagnostic radix correction (2026-10-11)

The cloud run reached all four requested system Settings changes. Its test then
misread `mUserLockedFields=20` and `10` as decimal, rejecting the sound lock after
the actual sound URI was already null. AOSP Android 15 `NotificationChannel`
formats this field with `Integer.toHexString`, so these are decimal 32 and 16.
The test parser now matches one complete hexadecimal field and parses radix 16.
The original required bits `{4,32,16,4}`, actual values, real Settings UI changes,
and emulator/debug target protections remain unchanged. No production source or
channel setter changes are involved. A host check exercised the extracted Java
parser on five valid and three malformed diagnostic cases; both upgrade fixture
APKs were rebuilt offline and their target APKs remain byte-identical.

Source: https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/app/NotificationChannel.java
(`getFieldsString`, line 1513; lock constants at lines 179, 187 and 192).
The failed run and its screenshots are retained. A clean synthetic old-app seed
is required before the replacement upgrade run; no failed fixture is passed off
as a successful baseline.

The first hexadecimal adapter also accepted a closing brace as a field boundary.
Although the host JDK accepted that regex, Android's ICU parser rejected its
unescaped literal brace. The replacement uses only the comma that actually
follows this field in the pinned AOSP format. This runtime fixture failure is
retained separately; host parser success is not Android runtime success.

### RadioButton click result correction (2026-10-11)

A complete seed (119 checks) and in-place retention verification (89 checks)
passed before the first routing attempt. That attempt crashed in the test's own
main-thread assertion because `CompoundButton.performClick()` returns whether
an `OnClickListener` ran, not whether its checked-state action succeeded. The
production screen intentionally uses `RadioGroup.OnCheckedChangeListener`.
The test now requires the exact matched native view to exist, be shown, enabled
and clickable, performs the real click, and keeps the separate exact preference
pair and channel-delivery assertions as the result oracle. Its control-presence
assertion runs on the instrumentation thread so an unsupported control retains
a normal failure report rather than killing the app main thread. It does not
set checked state or preferences directly in routing.

API contract: https://developer.android.com/reference/android/widget/CompoundButton#performClick()

The original failed routing log and AndroidRuntime stack are retained; the
partial routing fixture is never reused. A fresh complete seed → install-r →
verify → routing sequence is required after this test-only correction.
