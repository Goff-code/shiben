# Native graffiti batches

Baseline: QA `6e22dd90c79138960f974b195cc30ea17bba9563`; target app remains production `b1838a8fb66b74b13c278991d097b9be48155688`. The two original test files are identical in those commits. Only their proposed androidTest replacements are changed.

## Batch set and parity

Run the existing `suite=graffiti` with optional instrumentation argument `grade_batch`:

- Omitted or `0`: original full suite, same assertion order, all 13 native grade renders, then common/extreme/privacy coverage.
- `1`: SSS+, SSS, SS, S (4 grades).
- `2`: A+, A, A- (3 grades).
- `3`: B+, B, B- (3 grades).
- `4`: C, D, E (3 grades).
- `5`: preview/arc/background checks, all-13 cross-batch PNG uniqueness, unknown values, visible task appendix, extreme date/duration range, and unchanged `runHiddenPreview` privacy checks. Run after successful batches 1–4 on the same fixture.

Each grade's asset hash, alpha/memory, safe bounds, settlement rule, output dimensions/count and PNG uniqueness checks are unchanged. Batches 1–4 cover the 13 grades exactly once, retaining `qa-graffiti/grades/<grade>/...`. Batch 5 reads those completed PNGs, requires exactly one valid 1080×1440 PNG per expected directory, and rechecks all 13 hashes together. This preserves the cross-group uniqueness check that separate in-memory sets would otherwise lose. Preview/shared paths stay under `qa-graffiti`; hidden preview still uses the existing `qa-v203/hidden-preview.png`.

Full-run execution still calls the same reflective `ExportWorker.generatePng`, production `RangeSummaryRenderer`, and original privacy check. No renderer/settlement logic, assertions, thresholds, timeout, or production source is replaced. The preview prelude is moved verbatim into one helper; the grade loop and shared tail remain common. Invalid batch numbers or `grade_batch` on another suite fail through the runner's normal failure path.

Keep every successful batch log and persist that batch's PNGs immediately, before starting the next. All five successful runs from the same app/test APK and disposable fixture are needed to qualify the split suite; old PNGs alone are not proof of passed grade checks. Batch 5 intentionally fails if any grade output is missing. Do not clear fixture data between batches. After an executor/emulator loss, restore and verify the evidence/state together or rerun batches on a fresh fixture; do not combine unexplained stale directories from different runs. A batch's `OK` is only a subset pass. Batch 5 performs six existing shared native render calls (the visible appendix can produce multiple pages), so allow its normal render time.

Expected check counts against this pin: full/0 = 95; batch 1 = 25; batches 2/3/4 = 19 each; batch 5 = 56 (includes 39 retained-file/hash checks). The batched total exceeds the full run because it adds four group-completion assertions and 39 aggregation checks.

## Test-source-only overlay

Files provided: complete replacement Java files under `java/`, reviewable `native-graffiti-batches.patch`, `prepare-overlay.py`, and `graffiti-batches.init.gradle`. Keep `scripts/actions/build.sh` and `tests/actions-upgrade/fixtures.init.gradle` unchanged; use a separate Gradle invocation after the existing pinned build. Never add this init script to `COMMON` or either upgrade build.

Reproducible build commands (native execution results are recorded separately):

```bash
PROPOSAL="$QA_ROOT/tests/cloud-native"
BATCH_STAGE="$QA_WORK/graffiti-batch-overlay"
python3 "$PROPOSAL/prepare-overlay.py" "$QA_WORK/current" "$BATCH_STAGE"

# current.apk is the unmodified target APK already produced by scripts/actions/build.sh.
# The same script has already compared it with current-upgrade.apk.
"$QA_TOOLS/gradle/bin/gradle" -p "$QA_WORK/current" \
  --no-daemon --max-workers=2 -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs=-Xmx2048m \
  -I "$PROPOSAL/graffiti-batches.init.gradle" \
  -PgraffitiBatchOverlay="$BATCH_STAGE" \
  assembleDebug assembleDebugAndroidTest > "$QA_OUT/build-graffiti-batches.log" 2>&1
cmp "$QA_OUT/apks/current.apk" "$QA_WORK/current/app/build/outputs/apk/debug/app-debug.apk"
cp "$QA_WORK/current/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" \
  "$QA_OUT/apks/current-graffiti-batch-tests.apk"
git -C "$QA_WORK/current" diff --exit-code HEAD
```

The generator only reads the pinned worktree and writes a new external staging directory. It stages all ten original test Java sources, replacing exactly two, and saves source SHA-256 values in `overlay.json`; original test assets stay in place. It rejects another HEAD, tracked changes, an output inside the worktree, and an existing output. The init script only replaces `androidTest.java` and rejects the upgrade fixture property or a different app/runner. The explicit APK `cmp` is the required byte-identity gate before using the test APK; source isolation alone is not proof of identical produced APK bytes.

Run one batch, then persist its log/output before issuing the next (substitute your verified emulator selector):

```bash
adb -s "$QA_SERIAL" shell am instrument -w -r \
  -e suite graffiti -e grade_batch 1 \
  cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

Repeat with `2`, `3`, `4`, and `5`. A shell/ADB exit code alone does not establish instrumentation success: inspect the runner's terminal result (`INSTRUMENTATION_CODE: -1`, `OK (...)`, no failure) and the expected checks. Preserve device/app/test APK identities with each batch log.

## Validation scope

Proposal-only static checks verify disjoint 4/3/3/3 coverage, preserved assertion-bearing blocks and production renderer body, unchanged runner body outside argument dispatch, and an untouched source checkout. No Android build, Java compilation, emulator, ADB, or Library operation was run. The parent must compile the overlay, check APK byte identity, and run native batches before claiming runtime parity.

## Official API35 emulator guard correction (2026-10-11)

The first reminders run stopped before any fixture writes: its original fingerprint/model substring heuristic did not recognize the pinned official API35 image. The installed app was DEBUGGABLE; the actual fingerprint was `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`, hardware `ranchu`, and `ro.kernel.qemu=1`. Model `Android SDK built for x86_64` contains uppercase SDK, so the old lowercase match failed.

A third androidTest-only replacement preserves every reminders assertion and timing bound. It adds only that exact fingerprint together with ranchu hardware to the accepted emulator alternatives, and additionally requires `ro.kernel.qemu=1` for every alternative. The DEBUGGABLE conjunction, empty-ledger requirement and zero-existing-mode-channel requirements remain. It changes no production source, system property, permission, watchdog or security timeout. Save the original failed log and rebuilt test APK identity; compare target APK bytes again before running. Five graffiti batches completed with the original batch-test APK; their original receipt remains authoritative and must not be attributed to this later test APK.
