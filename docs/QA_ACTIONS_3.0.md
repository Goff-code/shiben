# 时本 3.0：隔离 GitHub Actions 原生测试候选

状态：2026-10-10 离线准备，尚未上传或运行。生产源码、main、发布 APK、正式签名私钥均未改变。当前仍没有 3.0 动态通过证据。此文件与测试分支不构成发布验收。

本地已验证：8 个固定归档全部下载/哈希通过；SDK 包元数据可被 Gradle 与 avdmanager 读取。固定 3.0 源码重跑 177 项 JVM 全过、Debug lint 0 错误/21 警告，Debug 与原生测试 APK 构建成功。真实 2.0.2 和 3.0 分别加入升级测试夹具后均编译成功；两版 app APK 在加入夹具前后逐字节相同，修改仅在 test APK。实际 test APK 清单与 DEX 中的 runner 已核对。脚本语法和通过/失败/缺终态的结果解析测试通过。本地只编译，没有启动 AVD 或运行任何原生测试；GitHub Actions 也未执行。

## 执行前的零费用条件

用户同意 GitHub Actions，前提是严格零费用。执行者必须先核实 Goff-code 账户当前剩余免费计算分钟、Actions/Packages 共用存储余量，以及对超额费用有效的强制阻止措施。只有预算提醒不够；未知余量、未知阻止范围、付费 runner 或需要新收费时都不能推送运行。

本候选只用标准 `ubuntu-24.04`，一个 job、一个 API35 AOSP/KVM AVD、无 matrix、无 cache。job 的硬上限是 35 分钟；阶段上限为工具 7 分钟、构建 7 分钟、原生测试 18 分钟、证据整理 1 分钟、上传 2 分钟，整个 job 上限优先。最坏时可能来不及整理全部证据，绝不据此把未完成测试写成通过。预留至少 35 标准 Linux 分钟，以及最多 96 MiB 测试证据存储 1 天，并核对账户其他并发使用。超时限制本身不能保证免费。

Artifact 只含合成测试数据、原生 PNG、日志、公开证书摘要和版本/哈希；不含 APK、AVD、SDK/JDK、缓存、私钥、凭证或用户账本。生成后按字节去重，超过 96 MiB 就拒绝上传。收到后及时下载并经正常授权清理云端证据；一天保留不是零费用证明。

## 精确触发范围和审查

GitHub 对新 `workflow_dispatch` 要求默认分支存在相应文件，所以仅在测试分支新增该事件不能可靠首次启动。经明确授权，本候选采用一次精确测试分支 push。没有修改 main、默认分支、仓库权限或创建持久访问。

- 唯一远端分支：`qa/shiben-3.0-kvm-20261010-8fc0e7`
- 唯一触发消息：`qa: validate Shiben 3.0 once [20261010-8fc0e7]`
- 候选必须只有一个 commit，父提交为 `b1838a8fb66b74b13c278991d097b9be48155688`
- 审查者核对最终完整 SHA、父节点和全部差异后，才可由获准执行者将那个准确 SHA 推到该分支；不得 force push、re-run 或再推同样触发消息
- job-level guard 检查事件、仓库、分支、消息和 `run_attempt == 1`；第一步检查 checkout SHA、父节点及全部变更白名单
- 固定并发组且 `cancel-in-progress: false`，同组最多一项运行，无代码内重试或第二次开机

这些约束限制执行范围，但不是任意未来 push 都只能发生一次的密码学锁。最终绑定来自对准确 SHA 的审阅和按 SHA 推送。运行后必须核对 run 的 `head_sha`，不能只看分支名或绿色图标。若失败，保留本次证据，诊断后再单独决定下一次测试，不自动重新触发。

## 工具和 KVM

仅两个 GitHub 官方 actions，均固定完整 commit：checkout v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1`，upload-artifact v7.0.2 `cf430e030ddbb5b0abf93d22962f4752f3646cd9`。仓库 token 仅 contents:read，checkout 不保存凭据，不用 PAT、SSH、子模块、LFS 或第三方 Android action。

`tests/actions/toolchain-lock.json` 沿用先前已验证和批准的 8 个官方固定归档。Google 包的 URL、大小、SHA-1、包版本与原始官方 XML 核对；emulator 另固定官方 SHA-256。JDK 与 Gradle 固定 SHA-256。传输后再校验，解压拒绝路径逃逸和 ZIP 链接。没有 `latest` 动态解析、包管理器安装或 `yes | sdkmanager --licenses`。所有 Android 包只用已批准的 2019-01-16 SDK 许可，完整文本 SHA-1 为 `efa68a6b3c661d18699d5c026771d5911cdc2f83`；不相符立即停止。

原始 XML 来自既有 Library v6 测试证据中的公开工具元数据；不是此刻重新抓取最新版。SHA-256：repository2-1.xml `2fe5ec9185d840be617b2d336f12f6683c4c365e213599a8be28a2d7fb026afe`；sys-img2-1.xml `ebf2d810d9e0c0b511ae49ee6e8c671a8fa67d210d06f5e3e068f83244ece435`。

KVM 必须实际存在且当前 runner 用户可读写，随后 `emulator -accel-check` 成功；使用 `-accel on`，没有无加速回退。当前候选不执行 sudo、chmod、ACL 或 group 变更。如标准 runner 权限不足，下载前失败并交由审查者确认是否另授权最小临时设备访问；不悄悄扩大权限。

固定 API35 AOSP default x86_64 r02、Emulator 37.2.12/build16428233、2560 MiB RAM、2 vCPU、480×800/160dpi、SwiftShader、关闭 Vulkan。正常 watchdog、SELinux、系统权限、安全检查全部保留。全新 AVD 仅启动一次，180 秒开机期限，20 秒稳定期，再核对 system_server PID。每套测试前后检查 PID；系统重启则停止。此配置有 KVM，不能预先声称必能修复旧云端软件解释执行时的启动故障。

## 固定源码与覆盖

目标源提交 `b1838a8fb66b74b13c278991d097b9be48155688`，tree `a98e365ba03ed9622e17a43d9398853df2efa014`，3.0/code7。真实旧版源提交 `bdbfafa8c4ec4344ae171ba1761f6ffea7017555`，tree `063e7b57ad73b3c6f1cb8aef8f1d23023c2c414b`，2.0.2/code5。两个独立 detached worktree 构建，生产文件不在测试分支里改写。JUnit 177 项、Debug lint 和原图字节校验随此次运行重新执行；既有 Release 结果仍属之前的构建，不冒称本次重跑。

所有 APK 使用一次性 runner 的同一个默认 debug 证书。校验 APK 版本、包名、相同证书及证书不是正式 V2 证书；没有读取或传输正式私钥。升级测试的额外源码只进入 test APK，通过 Gradle init 文件加入；增加 test runner 前后 app APK 必须逐字节相同，否则停止。

1. 新装 code7 并授予合成测试所需通知权限：`graffiti` 输出全部 13 等级的真实 1080×1440 PNG，验证原图、alpha、长日期/长时长、未知与范围等级；立即收集原图输出。
2. `reminders` 在空账本和全新渠道起点，覆盖四模式选择/记忆、实际通知渠道、用户拒绝通知、倒计时到期、暂停/恢复和熄屏后台场景。它不测声音是否真的被听见或手机马达震动。
3. `gallery` 覆盖单日多张、日期范围单张、PNG 真图片、隐藏详情、MediaStore 字节读回、取消/失败清理和权限保持；包括 RangeSummary 原生计算与排版。
4. 仅在该一次性 AVD 的不同独立阶段之间重装，清除前一阶段合成数据；code7 不授予通知权限的起点运行 `all`、`gallery_ui`、`gallery_view`，覆盖通用任务/计时/导出/数据库、相册按钮重入/重建及系统 Gallery 真正显示 PNG。
5. 新装真实 code5 和专用测试 APK，执行 `seed`；通过真实 AOSP Settings 设置四个实际生效 channel 的关闭、静音、关闭震动与低重要性，保存真实旧版账本/暂停计时器/偏好/渠道状态。
6. `adb install -r` 原位升级为 code7，再换测试 APK；这段期间不卸载、不清数据、不恢复备份、不改时间、不重新授权通知。`verify` 比较持久数据、计时器和真实系统渠道，最后 `routing` 验证到期仍使用原 ID 且不绕过限制。细节见 `QA_ACTIONS_UPGRADE.md`。

`focus_done` 仅是 V1 残留对象。V2 2.0.0 开始真正使用四个 `shiben_done_*`，2.0.2→3.0 没有替换它们；不增加废弃渠道的全局门控。残留对象存在不能代替本次有效渠道升级测试。

每个 Instrumentation suite 必须有唯一 `OK (正数 checks)`、唯一 `INSTRUMENTATION_CODE: -1`，adb/timeout 返回 0，且无 FAIL/shortMsg/crash；缺项一律 failed_or_incomplete。缺少日志写 not_run，不计入通过。原生测试结果和 PNG 像素需要执行后实际审查。

## 明确未覆盖

本次 AOSP API35 测试通过也不等于用户 realme GT6/UI7.0 真机、物理响铃/震动、API29 或正式发布证书的覆盖升级通过。正式 code5→7 覆盖必须用户持有的原证书签名后再验收，不能把 debug-key 结果改写为正式升级结果。用户要求不用 Mac 测试，本候选没有创建任何 Mac 任务。

## 审查依据

- https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch
- https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#concurrency
- https://docs.github.com/en/actions/reference/runners/github-hosted-runners
- https://docs.github.com/en/billing/concepts/product-billing/github-actions
- https://developer.android.com/studio/run/emulator-acceleration
- https://developer.android.com/studio/emulator_archive
