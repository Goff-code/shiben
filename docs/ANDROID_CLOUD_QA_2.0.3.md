# Android云端QA恢复与2.0.3签名后续

本文件用于继续本项目的云端开发/测试；用户Mac只进行本地签名，不在Mac开发或运行模拟器。

## 工具

八项已验证的固定官方归档在 `docs/qa/android-tools-v203.json`。包括前次五个QA组件、Platform35 r02、完整Adoptium Temurin21.0.12.1+1及Gradle8.11.1。每次恢复先校验长度与官方hash。不要把同名系统JRE当作完整JDK，须有javac和ct.sym。

本次Google许可仍为2019-01-16版，SHA1 `efa68a6b3c661d18699d5c026771d5911cdc2f83`，已取得该具体许可同意。JDK使用已批准Adoptium同类GPLv2+CE/Assembly条款；没有付费安装。恢复不得批量接受未知SDK许可。

证据包的 `toolchain/` 保存固定清单、官方元数据和安装/配置核验记录。实际构建统一使用 `scripts/build-cloud.sh`。`tools/jdk-21.0.2`只是兼容软链，实际目标为完整 `tools/jdk-21.0.12.1+1`。

## 模拟器

沿用 `scripts/start-compact-qa-emulator.sh` 的AOSP API35配置：480×800、160dpi、单核、SwiftShader，关闭Vulkan、动画、相机及音频，无KVM。第一次boot约433秒，并等待system_server PID稳定才安装应用。

同一持续shell拥有adb/QEMU；不同工具执行环境不能凭session编号直接接管，必须由原环境操作或正常停止后在新环境重新启动。所有adb明确指定隔离 `emulator-5554`，shell命令加 `-n`。不要向用户手机运行清数据、root或测试夹具操作。

## 已完成的覆盖证据

`api35/upgrade-before` 与 `upgrade-after` 是2.0.2/code5 Debug到2.0.3/code6 Debug的真实无清数据升级快照。包含数据库、偏好与每日Markdown，另有10项原生、完整表/文件比对与8项暂停计时检查。它们不是正式证书升级的替代证明。

正式旧版2.0.2 APK已从已有交付文件恢复并验证：4,470,615字节，SHA256 `24ed77569d73918ea2bb7d759d7788950c0cf35e2056f62b10c02aa9f6c9f885`，v2/v3签名有效，V2证书SHA256 `af1c11b14290abcd0aa95dfa3284ce931a822751f2780293f0aab9c90cce59d5`。

## 用户签名后

1. 只接受本次未签名2.0.3的签名产物，先核实包名、code6、版本2.0.3、原V2证书和APK签名有效。
2. 对照未签名原件逐个验证ZIP载荷条目，除预期签名条目外内容字节不得变化。签名不是重建，Mac不重新编译。
3. 确认原正式2.0.2基线任务/历史/设置/暂停计时及before证据已落盘，随后 `adb install -r --no-streaming` 安装正式2.0.3。不能卸载、清数据或注入新版数据库。
4. 检查真实UI、图片导出/相册路径、已暂停计时、数据/偏好/Markdown保留，然后抓取after并严格比对。
5. 只在全部实际验收通过后更新2.0.3正式结果。若模拟器环境中断，先核对恢复数据与设备状态，不把重新安装误称覆盖成功。

## 持久化

证据、工具元数据、实际AVD配置和正式旧版基线状态应先完成独立持久保存。打包AVD磁盘前正常停止模拟器，记录各文件hash；不要把正在写入的qcow2直接当成一致备份。只打包本隔离测试profile，不打包host的adbkey、Debug keystore、用户Mac文件或任何签名私钥。恢复到不同路径时须更新AVD配置及qcow2 backing路径，并验证文件可读后再启动。

最终归档名、hash与恢复状态见2.0.3交付校验说明和测试报告，不凭口头交接推断仍有活动模拟器。
