# 倒计时结束提醒四模式（2026-10-08）

本补丁基于 `93921727f31d0958148cc891df19506af28c78a1`，独立于等级图标修改。包名、版本号、正式签名身份和数据结构不变。此文档只记录提醒补丁，不代表 2.0.3 整版冻结或正式签名验收。

## 现有行为与本次变化

旧版已使用 `sound` / `vibration` 两个偏好以及四个通知渠道实现组合提醒，默认声音、震动均开启。此次没有另做媒体播放器、持续闹铃、直接震动服务或新的后台服务。

- 入口仍为「今日 → 更多 → 提醒设置」，以四选一替代原声音/振动两开关：静音（仅通知）、仅响铃、仅震动、响铃和震动。
- 保留「专注结束提醒」总开关。静音模式仍显示通知；总开关关闭则不发到时通知。关闭/重开不改变选好的模式。
- 原 `shiben-settings` 中 `reminders` / `sound` / `vibration` 原样读取；选择模式只在一次编辑中写入原两键。不新增模式存储键、不改默认值、不迁移或清空记录。
- 四个 `shiben_done_*` 渠道 ID 不变，不删除已存在的渠道、不重置用户的系统通知偏好。新安装不再创建未使用的旧 `focus_done` 通用渠道，已有旧渠道保留。
- 若所选方式的已有系统渠道被关闭，或音效/震动/重要程度与预期不同，设置页显示说明，不替用户修改系统设置。
- 完成通知副作用移到持久化回滚范围之外。系统通知/清理失败不会复活已经保存为结束的计时，也不会阻止尝试停止前台服务。

## 提醒边界

响铃为一次 Android 系统通知音，震动由该通知渠道请求。实际表现服从通知权限、渠道、通知音量、静音/勿扰、设备硬件和系统限制。不承诺通知音为持续闹铃，不绕过任何系统选择。

原 `AlarmManager` / 前台服务 / 单调时钟逻辑保留。暂停会取消原到时闹钟；继续重新安排；主动提前结束不发送到时提醒。延迟回调会按计划时长封顶，重复检查不重复记录。设备重启按原规则只保留最后保存的检查点，不假设关机期间继续投入。应用被强行停止、手机厂商限制后台或通知受限时，不能保证当时提醒。

没有新增权限、INTERNET、广告/分析 SDK、全屏通知或绕过勿扰的特权。用户的 realme GT6 / UI7 / RMX3800 不在云端；云端无法验证真实扬声器音效、马达触感及厂商省电策略。

## 自动验证

- `testDebugUnitTest`：163 项通过，0 失败/错误。新增四模式双布尔映射及四个稳定渠道 ID 检查；原 TimerEngine 随机状态机、暂停/继续、结束幂等、进程恢复和重启检查仍通过。
- `tests/store/run.sh`：25 项 Java 断言及最终 38 项 SQL/迁移/历史保留检查通过。
- `lintDebug` / `lintRelease`：均 0 错误、20 条警告，涉及原绘制分配、图标配置、构造器和本地化区域；未引入新的提醒相关警告。
- `assembleDebug` / `assembleRelease` / `assembleDebugAndroidTest`：最终源代码构建通过。release 仍未签名，debug 仅供一次性云端模拟器验证。
- `check-unsigned-apk.py`：13 项包装检查通过，包括稳定包名/code6、无 INTERNET、禁用系统云备份、无测试运行器/预填数据库/私钥进入 release。没有正式签名或安装声明。
- `git diff --check` 通过；AndroidManifest、版本号、SQLite 与序列化格式、图像资源未修改。
- 官方免费工具按现有清单恢复，归档校验通过；没有接受新的 SDK 条款。
- Android API35 定向 `reminders` 运行检查：**未运行，受测试环境阻塞**。Android 系统始终未完成稳定启动，未安装任何时本 APK，不能计为应用测试失败或通过。

### 动态验证阻塞及交接

已恢复并校验官方工具；API35 AOSP x86_64 镜像为 `Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`。云端无 KVM，仅软件执行。先使用 480×800 / 160dpi、1 个虚拟 CPU、2048 MB RAM、SwiftShader、无音频/快照配置。

系统在自身通知权限迁移与服务启动过程中发生 watchdog / System UI ANR。已确认单核阶段两次 system_server 重启，均发生在时本安装前。保留同一 AVD 和 userdata、正常停机并确认锁释放后，仅把虚拟 CPU 改为 2 核，其他配置不变；该受控尝试仍在系统启动阶段出现 watchdog（06:32:49 UTC，主线程阻塞约 79 秒）。没有清理模拟器数据、删除锁文件、关闭 watchdog/SELinux，或以修改系统限制来掩盖失败。

在确认原配置与单一 CPU 调整均不能稳定启动后停止此环境，交给整版集成后的单一 QA 运行统一验证。没有继续同配置反复重建，也没有切换到用户 Mac 或真实手机。

尚未完成：四种模式的 Android 实际通知/UI 操作、真实系统 AppOp 拒绝、后台屏幕关闭、已有渠道覆盖、原生界面截图，以及相关故障注入动态断言。API29 补测如安排也只是兼容性补充，不能替代 API35 或 realme 真机验证。

复现命令（只在全新、空账本的可用 debug 模拟器中执行；debug 安装时授予通知权限）：

```
adb install -g app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e suite reminders cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation
```

应用无正式签名/交付，未推送 GitHub。生产实现提交为 `e4355cc8b3c7853fa6137ab29d6dcf50bc8394d6`。此结论允许继续代码整合，不构成发布验收。

已编写但尚未运行的定向套件仅允许一次性 debug 模拟器且要求空账本；不会用于用户手机。它拟验证旧偏好 8 种组合、四选一控件、设置重建/返回、数据不变、实际通知渠道配置、到时持久化、总开关关闭、POST_NOTIFICATION AppOp 拒绝、已有系统渠道重要程度保留、暂停/继续/提前结束、重复广播/返回不重播，以及屏幕关闭后的后台完成。进程重启/设备重启项是持久状态重建和 boot ID 变化模拟，不是实际杀进程/重启验收。

## 用户真机确认

整版完成并使用原正式证书覆盖安装后，在 realme 上分别选择四种模式，运行一轮实际倒计时；至少覆盖前台、锁屏/后台及暂停后继续。确认系统通知权限开启、对应渠道未禁用、通知音量可听，并按用户原有习惯测试勿扰/静音。若想要求锁屏更及时，可由用户自行开启已有的精确提醒权限和合适的后台运行设置。不要卸载或清除旧版数据。

## 平台依据

- Android 官方通知渠道说明：https://developer.android.com/develop/ui/compose/notifications/channels
- Android 官方通知权限说明：https://developer.android.com/develop/ui/compose/notifications/notification-permission

渠道创建后音效等行为由系统和用户控制，应用不能通过重新创建同一渠道重置这些设置。因此此次继续使用原稳定 ID，并明确显示必要的受限说明。
