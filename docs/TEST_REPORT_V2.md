# 时本 2.0.0 验证报告

日期：2026-10-04。仅记录本轮实际结果；不继承旧版通过状态。正式发布为 `cn.goff.shiben`、versionCode **3**、versionName **2.0.0**，最低 API26、目标 API35。

## 构建及主机验证

- 免费官方 OpenJDK 21.0.2、Gradle 8.11.1、SDK Platform35 / BuildTools35.0.0。
- 最终生产代码包括大字体修复、空历史修复、复制任务重建恢复、旧观察者释放及进程死亡后的不确定保存提示。Debug / AndroidTest / 未签名 Release 构建成功。
- 最终聚合 **117 项 Gradle 任务使用 --rerun-tasks 全部执行成功**。
- JUnit **90 项**，0 失败、0 错误、0 跳过：TimerEngine 38、Session 11、DailyTotals 16、PriorityProgress 4、V2Reports 21。
- 辅助测试 **25 项 Java 断言、23 项 SQL 结构/迁移契约检查**通过。SQL 契约检查不冒充 Android SQLite 实测。
- Debug / Release Lint 各 **0 错误、13 警告**：绘制分配、仅代码构建的 View 构造函数及中文文案拼接；没有隐藏失败检查。
- 未签名 Release **12 项打包检查**通过：品牌、Launcher、API范围、非 debuggable、无 Internet 权限、关闭系统云备份、无私钥/测试类/预置数据库、无原生 ABI 限制。
- 本报告验证的最终未签名 APK：90,844 字节；SHA256 `7b7082d4f78bfee8258e0fbc5c3dfbaa80f966ec2043d2eb35c541245af2de19`。签名会改变文件哈希；这不是正式签名后的哈希。

## Android 运行验证：区分基线整套与最终定向回归

云环境无 KVM，使用官方 AOSP 软件 CPU/GPU 模拟器。

### 两个系统的基线整套

- Android10 / API29：**214 项联合断言通过**，无跳过。
- Android15 / API35：**214 项联合断言通过**，无跳过；最终整套运行 crash 缓冲区为空。
- 覆盖：空安装无示例数据；任务创建、草稿与进度对话框重建；进度取消/保存；100% 的两秒庆祝生命周期；排序、删除取消/归档；65% 优先级实例；历史及设置导航；Markdown / PNG 预览、剪贴板；计时开始、暂停持久化、重建、继续、提前结束、幂等与到点封顶；完整导出与真实 SQLite 迁移。
- API29 验证后台未返回界面前到点；API35 验证通知权限拒绝时仍可计时并在返回后校正。二者不能代替真实 OEM 后台策略测试。
- 每套基线含旧 **34 项 SQLite 检查**。大字体、空历史和复制观察者补丁后，执行下面定向回归；没有声称在最后补丁后又重复整套 214 项。

### 最终补丁的定向回归

- 最终 Android15 SQLite 套件 **38 项通过**：增加空 header 不生成历史、归档唯一任务仍保留历史、零时长记录保留、删除最后会话仍保留历史证据。原有 V1→V2 迁移、未知历史值、原时区跨日、快照不可变、日期回退、墓碑与旧序列化检查仍通过。
- 最终新安装历史 UI **3 项通过**：实际展示“还没有历史记录”，不把空日 header 当作历史。
- 字体 **85% 与 150% 各 50 项布局断言通过**。150% 时原先的 `100%` 换行已修复为单行并验证实际宽度。正常字体的 320dp 宽专注页也已目视通过。
- 最终系统保存及复制生命周期 **11 项通过**：原生 Downloads 文件选择器写文件；成功反馈；Activity 重建保留 job / URI / 已交付状态；不重复反馈；反复导出与复制释放旧 Activity 观察者，重建后绑定新 Activity。
- 实际 Downloads 保存文件与生成源文件的 9,306 字节逐字节相同，SHA256 均为 `475edc13d65089643b268784ef8ab2d3e32367c4bf1d6d9f24a061f8e541aeaf`。

### 导出压力与实际图片

- API29 导出专项 128 项；API35 导出专项 129 项，均通过。包含 43 个压力任务和超长 Unicode 名称。
- 校验完整 Markdown、PNG 分页规划、每页不超过 1080×1600、ZIP 收录所有实际页面、跨日期范围、明确 SAF 失败、取消、私有日文件和删除后更新。
- 单独初次压力实例为 15 页，整套含额外归档记录时为 16 页；不是裁掉长列表。真实首/中/末页已目视检查，末尾长标题仍保留。

## 未来同签名更新的数据保留

在一次性 Android15 模拟器做了实际 `adb install -r` 覆盖更新，期间未卸载或清除应用数据：

1. 安装测试 code3，并留下任务、百分比、优先级、历史、每日文件及暂停中的计时。
2. 用同一个 Android Debug 证书构建并安装 **仅测试用 code4 / 2.0.0-qa-upgrade**。临时版本通过 Gradle init 脚本覆盖配置；没有改正式源码的 versionCode。
3. **7 项原生升级断言通过**：code4 确已安装；暂停状态、计时/任务 ID、计划/实际累计时长、任务数、历史数与加权进度全部保留。
4. 前后快照的 **7 张 SQLite 表逐行完全相同**：任务48行、日任务快照50行、会话3行（含墓碑）、日快照3行及其他表。计时 blob 的可变检查点不作字节比较，其逻辑状态由原生断言比较。
5. **3 份持久每日 Markdown 的 SHA256 完全相同**。
6. 正式构建输出已恢复 code3 / 2.0.0；测试 code4 APK 不用于交付或正式签名。

本次正式 V2 使用新签名身份，**不能覆盖旧 1.0.1**（旧私钥不可用）。上述结果验证未来同签名更新的保留机制，不代表旧 V1→新 V2 的覆盖安装。后续正式 V3 应沿用新 V2 密钥；正式签名公钥与最终 APK 仍由 Mac 签名流程单独核验。

## 复现与隔离

- 环境：`source scripts/cloud-env.sh`。工具、缓存、模拟器、Debug 密钥都在忽略目录 `tools/`，不随源码上传。
- 构建：`scripts/build-cloud.sh testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease --rerun-tasks`
- 辅助存储：`source scripts/cloud-env.sh && bash tests/store/run.sh .`
- 未签名打包：`source scripts/cloud-env.sh && python3 scripts/check-unsigned-apk.py`
- 模拟器：`scripts/emulator-console.sh`；API35 设 `SHIBEN_EMULATOR_API=35 SHIBEN_EMULATOR_PORT=5556`。此云沙箱要求 emulator / adb 在同一持久终端中运行。
- 安装 Debug app 与测试 APK后：`adb shell am instrument -w cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation`
- 专项参数：`-e suite store`、`export`、`layout`、`saf`、`empty_history`、`upgrade_seed`、`upgrade_verify`。空历史和整套需新数据；layout 使用当前系统字体/尺寸；saf 在模拟器 Downloads 创建测试文件。
- 临时升级 APK：`scripts/build-cloud.sh -I scripts/qa-upgrade-code4.init.gradle assembleDebug`。仅用于一次性测试，不得作为正式包交付。
- 仅在一次性模拟器上运行：这些测试会创建大量数据。禁止对用户真实记录运行。

## 方法及尚未验证的边界

- 到点使用独立测试 APK 注入的 14分55秒既往夹具，加真实最后几秒；早期测试会话移至前一天，保持开始时间排序合理。不是连续15分钟真机锁屏测试。
- API35 软件仿真初期有蓝牙崩溃、System UI ANR 和外部存储就绪延迟。关闭测试环境蓝牙、恢复界面焦点、改用私有测试截图目录并重跑；最终整套运行无干扰、无应用崩溃。测试断言自身的问题已分开修正，没有归为应用问题。
- 代表截图来自实际原生 Android，4项任务/65%是测试夹具；正式 APK 内没有这些数据。压力列表截图不能当作首次安装画面。
- realme GT6、OEM 后台限制、真实来电、长时锁屏、Doze、真实设备重启、第三方云端 SAF 提供方尚未做真机验证。
- 新正式密钥备份与实际正式 APK 签名验收不由云构建替代。未签名 APK 不能直接安装。
