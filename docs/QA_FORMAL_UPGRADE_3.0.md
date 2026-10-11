# Formal certificate 2.0.2 → 3.0 public-interface fixture

Status: old formal code5 public seed passed on 2026-10-11 at 08:02:45 UTC
(JUnit OK 1 test and explicit OFFICIAL_UI_QA PASS seed). Signed code7 verification
is pending user-local signing; this is not yet a formal upgrade pass. Production
b183 and the existing debug-only guard remain intact.

`tests/upgrade/OfficialUpgradeV30Ui.java` is a separate shell UIAutomator test,
built with `scripts/build-official-ui-v30-qa.sh` after setting the already verified
JAVA_HOME, ANDROID_HOME and GRADLE_USER_HOME. It requires the exact official AOSP35
emulator fingerprint, ro.kernel.qemu=1, actual phase-specific package version,
and a recognized nondebug pkgFlags line. It neither signs nor installs an APK.

Before execution, independently verify both APK hashes, nondebug metadata and the
original V2 certificate `af1c11b14290abcd0aa95dfa3284ce931a822751f2780293f0aab9c90cce59d5`.
The old official APK SHA is `24ed77569d73918ea2bb7d759d7788950c0cf35e2056f62b10c02aa9f6c9f885`.
Use only the selected disposable emulator and a fresh synthetic formal fixture.
Capture the old package identity and system channel inventory; after successful
seed, replace with the signed code7 APK using install-r. No uninstall, data clear,
clock change, emulator restart, or fixture mutation between seed and verify.

Seed uses public UI to create two tasks, adjust manual progress, save an actual
early-ended timer session, turn old sound/vibration options off, and pause a
second real timer. After pause, it captures exact visible task/progress/history/
attention/timer values. It saves an all-records Markdown through official
DocumentsUI to a unique public Downloads file, requires it to equal the complete
untruncated preview, and retains its bytes and SHA. Verify reads the same fields,
checks retained master and the new silent RadioButton mapping, and saves a second
public Markdown for exact comparison. Same-day checks also run after export.
Existing files/baselines are not overwritten. No app-private path, root/run-as,
hidden notification setter, direct preference edit, or target instrumentation is
used. Do not run capture-upgrade-state.sh or old schema2→3 comparators here.

The external controller additionally compares UID, first-install time, boot/day
and actual existing channel ID/control inventory before and after replacement.
An early-ended old timer creates no shiben_done_* completion channel: inventory
retention alone is not a formal four-mode override/routing test. Those stronger
software checks remain attributed to the separately passed 354-check debug chain.
Public UI/export comparison does not establish exact raw SQL/blob milliseconds,
tombstones or unrelated private preferences. No realme, physical sound/vibration,
long Doze or OEM power-policy claim is implied.

Run the jar with `/system/framework/android.test.base.jar` using the commands
printed by its compile script. Both explicit `OFFICIAL_UI_QA PASS seed|verify`
markers and successful JUnit terminal results are required, with no failure,
crash or timeout. Keep before/after exports, baseline properties, screenshots/XML,
package/channel dumps, certificate verification and all command output.

The first seed attempt stopped at evidence capture after two zero-progress tasks
and before saving the progress dialog. Its PNG and full log are retained. The
pinned shell runner sets ANDROID_DATA=/data/local/tmp, so legacy UiDevice's dump
path was /data/local/tmp/local/tmp, not the previously assumed /data/local/tmp.
The adapter creates that shell-owned directory via Environment.getDataDirectory()
and checks/copies the resulting XML. No app-private path or OS setting changes.
A fresh old formal installation is used for the corrected seed.


## Retained successful formal baseline

Same running AOSP35 emulator, bootCount1, local date2026-10-11. Formal old package
UID10171, firstInstallTime2026-10-11 00:58:43 America/Los_Angeles. Alpha progress35%,
Beta0%, combined17.5%; completed display14s, paused display8s with24:52 remaining,
attention detail/export23s. These public displays are rounded and are not raw
millisecond assertions. Reminder mastertrue, soundfalse, vibrationfalse.

The complete native-saved Markdown is187 bytes, SHA256
`149a9c0f2559358f58aac1e9e9ab0cc1fe98fb8ca980e038ff7ba041b4fb51bb`.
The executed shell UI JAR SHA256 is
`777256cd0cbc492c405550da4799ccf58f649e70d32157da0b2d3c0a3192e5c9`.
Baseline and screenshot/XML files are under the shell-owned evidence directory,
and the actual export is `/sdcard/Download/shiben-formal-v30-seed.md`.

The current actual channel inventory contains only focus_live and obsolete
focus_done; it contains no effective shiben_done_* channel because this public
fixture ends early. Do not describe inventory preservation as an effective-mode
restriction test. Compare public package/channel dumps with
`tests/upgrade/compare-public-v30.py` before app launch and after public verify.
The comparator requires exact UID/first-install identity and existing control
values, excluding app-owned labels and volatile notification state.
