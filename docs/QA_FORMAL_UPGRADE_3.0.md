# 时本 3.0 正式签名覆盖验收

## 正式签名覆盖验收已通过（2026-10-11）

原正式 2.0.2/code5 与用户本机原 V2 密钥签署的 3.0/code7，在官方完整 AOSP API35 云端模拟器同一次启动、同一当地日期完成覆盖。10:20:52 UTC 的旧版 seed 和 10:27:24 UTC 的新版 verify 均有明确 PASS、OK (1 test)、正常 STATUS_CODE 终态与客户端 exit0。仅执行 install -r；成功 seed 之后没有卸载、清数据、调整时钟、重启或改写夹具。

正式成品 `时本-3.0.apk`：39,078,965 bytes，SHA256 `542a81644573f0473545636f81af9ecf19fb87bf5c5ec83a9ab19c02ab6102cf`。原 V2 证书 SHA256 `af1c11b14290abcd0aa95dfa3284ce931a822751f2780293f0aab9c90cce59d5`，v2/v3 签名、zipalign、3.0/code7、nondebug、无 INTERNET 均通过；全部 45 个未签名原始 ZIP 载荷逐字节相同。私钥与密码始终只在用户 Mac，本轮云端只接收正式 APK 和公开校验资料。

保留结果：UID10173、首次安装时间2026-10-11 03:11:14（模拟器当地时间）、两个任务35%/0%、当日17.5%、完成记录15秒、注意力合计23秒、暂停已计8秒/剩余24:52均一致。旧声音false/震动false正确映射为新界面唯一选中的“静音（仅通知）”，提醒总开关true。两次原生 DocumentsUI 实际保存的完整 Markdown 都为187 bytes，SHA256 `149a9c0f2559358f58aac1e9e9ab0cc1fe98fb8ca980e038ff7ba041b4fb51bb`，内容逐字节一致；baseline 文件也未变。保存、任务、暂停和设置截图已经实际目检。

升级前、安装后未启动、公开验证后，实际存在的 focus_live 与历史 focus_done 两渠道的 ID/控制值完全一致。本夹具通过提前结束计时建立记录，没有创建四个 shiben_done_* 完成渠道，所以这项正式结果不能替代有效模式限制与真实到时路由测试；后者属于此前354项同临时测试证书升级检查。公开 UI/导出验证也不等于正式包私有 SQL/blob 的毫秒级逐字节检查。realme GT6/UI7.0实体声音、震动及OEM省电仍未实测。

本次 bootCount2 的成功基线独立于上午 bootCount1 的旧基线；前一窗口09:44已结束，旧结果只作历史保留。本轮首次 DocumentsUI 保存遇到系统 Downloads provider ANR死亡/null Bundle失败，第二次被System UI ANR挡在无任务的初始页。保留全部失败日志与0字节文件；使用系统弹框“Close app”恢复System UI和Files后，以同一原APK、同一已审JAR、新证据目录建立成功基线。未更改生产代码、测试成功条件、系统watchdog、安全设置或权限保护。

复现证据：native-run-2 的命令030(seed)、034(install-r)、037(verify)，以及032/033、035/036、039/040的包/渠道读回；正式JAR SHA256 `777256cd0cbc492c405550da4799ccf58f649e70d32157da0b2d3c0a3192e5c9`。累计证据同时保留之前1524项debug运行断言与所有历史环境故障，不能将它们改称1524个正式包独立案例。

## 历史检查点及复现方法（下列 pending 状态已被上述最终结果取代）

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
