# Android 云端升级验收环境（2026-10-06）

本说明记录 2.0.2 正式签名覆盖验收实际使用的环境。生产 APK 不在云端重编或重签；用户私钥不进入云端。只运行一个无 KVM 模拟器。

## 已核实工具与恢复

`qa/android-tools-2026-10-06.json` 固定本次五个 Google 官方免费压缩包的 URL、大小、官方 XML SHA-1、版本及许可 ID：Build Tools 35.0.0、platform-tools 37.0.1、cmdline-tools 23.0、emulator 37.2.12（build 16428233）、API35 AOSP default x86_64 r02。不可把这些版本称为以前成功测试所用的版本；旧引擎版本未留证，无法确认。

本次包适用 `android-sdk-license`，正文日期 2019-01-16，正文 SHA-1 `efa68a6b3c661d18699d5c026771d5911cdc2f83`。安装前已取得该具体许可和五项工具的授权。以后恢复仍应核对实际许可与已有授权，不能执行批量 `yes | sdkmanager --licenses`。

恢复步骤：

1. 从清单官方 URL 下载五个归档到 `tools/downloads`，将清单复制为该目录的 `qa-packages.json`。同时保留当次官方 `repository2-1.xml` 和 `sys-img2-1.xml`；官方目录会变化，应使用与归档修订匹配的元数据。
2. 在核对许可后运行 `python3 scripts/restore-verified-cloud-qa-tools.py`。该脚本先验证长度和官方 SHA-1，只提取指定五包，拒绝覆盖已有目标；不下载其他组件，不接受新协议。脚本是本次实际恢复脚本的路径通用化副本。
3. 仅记录已批准的具体许可 hash 到 SDK 的对应 license 文件。不要调用本次 cmdline-tools 23 的 `sdkmanager` 包装器：它会尝试下载另一 Android CLI；本次尝试失败且未安装该附加组件。直接 `avdmanager` 可用。
4. 系统 Java 为 Debian OpenJDK 21.0.12.1；`tools/jdk-21.0.2` 只是已有脚本兼容路径的软链接，不代表实际版本21.0.2。本机无 `javac` 入口和 `ct.sym`，但有 `jdk.compiler` 模块。纯测试比较器通过 `java com.sun.tools.javac.Main -source 17 -target 17` 编译，随后用系统 Java 运行；没有重编生产 APK。

## 运行条件与会话

使用 `bash --noprofile --norc` 的一个持续 TTY 会话，启动和所有 adb 都在该会话完成。这里不同 exec 的进程/网络隔离，新的独立 shell 看不到原 adb/QEMU；独立 shell 仅用于读取共享日志和截图。所有 adb shell 使用 `-n`，避免吞掉后续脚本输入。工具会话被中断时，磁盘 AVD 数据可能仍在，但必须重新验证会话与数据，不能假定模拟器继续运行。

在持续会话内执行 `bash scripts/start-compact-qa-emulator.sh`：

- 独立 AVD `shiben-202-clean-low`，480×800、160dpi、单核、2GB RAM、3GB data。
- `-accel off -gpu swiftshader -feature -Vulkan`，GLES 软件渲染；无窗口、音频、相机或快照。
- 三项系统动画倍率全部为0。等待 boot 完成后再等待后台初始化，并确认 system_server PID 稳定、包管理服务可读。
- 该脚本不安装或清除时本，也不生成测试数据。它拒绝占用一个已有的 emulator-5554。

首次启动可耗时约10分钟。旧720×1280配置及后续恢复出现过 watchdog、NetworkStack ANR、System UI ANR；新配置也出现过 System UI ANR，所以本说明不是“无异常环境”的保证。只对准确识别的系统对话框选择 Wait；不要关闭 System UI 来绕过测试。若持续无进展，保存证据并停止，不循环重建或捏造通过。

## 本次正式覆盖方法

旧正式2.0.1/code4成功安装后，通过真实UI创建A/B任务、A进度35%、关闭声音/振动、结束并保存A计时，再暂停B计时。UiAutomator受系统遮挡/层级dump失败干扰，未整套通过；后续使用 `adb input` 和真实截图完成输入与核对。不会注入数据库。

在同一启动和同一日期内，用 `scripts/capture-upgrade-state.sh --root` 捕获隔离AOSP测试机的旧数据，执行正式2.0.2/code5的 `adb install -r --no-streaming`，核对实际界面后捕获新数据。两次捕获之间没有卸载、清数据或重启。只在隔离测试模拟器上使用 adb root。

`compare-upgrade-v202.py` 的30项检查与原 `ComparePausedTimer.java` 的9项检查通过；检查数随保存日期/片段数量变化。本次一日、两任务、一条完成会话、暂停计时两片段。数据库旧值/序列化数据、偏好、MD语义完整保留。最终升级后 crash 缓冲为空。详细限制和证据见 `TEST_REPORT_2.0.2.md`。

测试结束用同一持续会话执行 `adb -s emulator-5554 emu kill`，保留证据和AVD数据。所有APK以原始SHA校验，不因测试工具调整而改变。
