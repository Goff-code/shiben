# 时本 2.0.0 验证记录

日期：2026-10-04。此报告只记录第二版本轮实际运行结果，不继承旧版通过状态。

## 工具链与隔离

- 免费官方 OpenJDK 21.0.2、Gradle 8.11.1、Android SDK Platform 35、Build Tools 35.0.0、Android Emulator 与 AOSP API29/API35 系统镜像。
- 工具、构建缓存、模拟器和 Android 标准测试签名位于 Git 忽略的 `tools/`；不随源码和发布文件上传。
- `source scripts/cloud-env.sh` 配置隔离环境；`scripts/build-cloud.sh` 执行 Gradle。
- 云环境没有 `/dev/kvm`，使用软件 CPU/GPU 模拟器，不代表用户 realme 真机验证。每个执行环境需在同一持久终端内运行 emulator 与 adb（`scripts/emulator-console.sh`）。
- 发布私钥没有由这些构建脚本生成。Release 默认未签名；Debug 签名仅用于一次性测试环境。

## 已通过

1. Debug、独立测试 APK、未签名 Release 2.0.0（versionCode 3，API26–35）构建成功。
2. JUnit 90 项通过，0 失败、0 错误、0 跳过：TimerEngine 38、Session 11、DailyTotals 16、PriorityProgress 4、V2Reports 21。
3. 存储兼容性辅助测试：25 项 Java 断言、21 项 SQL 结构/迁移契约检查通过。SQL 检查与下面真实 Android SQLite 检查分开计数。
4. Debug / Release Lint：各 0 错误、13 警告（绘制分配、仅代码使用的 View 构造函数、中文文案拼接）。117 项 Gradle 任务已使用 `--rerun-tasks` 完整重跑通过。
5. 未签名 Release 打包检查 11 项通过：名称、启动 Activity、最低/目标 SDK、非 debuggable、无网络权限、禁用云备份、无密钥/测试类/原生 ABI 限制、旧品牌不存在。此项不等同于签名或可安装性验证。
6. API29 AOSP 模拟器真实 SQLite 专项 34 项通过：V1→V2 升级、未知历史百分比/优先级不伪造、旧序列化计时与记录不变、规则 B、原子排序、历史快照、归档、跨日与原时区、删除墓碑、日期回退保护。
7. API29 导出专项 128 项通过：43 个压力任务（含超长 Unicode 名称）、Markdown 完整性、15 页真实 PNG、尺寸上限、ZIP 完整收录、观察者替换、私有持久日文件、删除末条会话后更新、跨日期组合、明确 SAF 写入失败、拒绝未获授权 URI、取消导出。实际首/中/末页已目视检查，无文字截断。
8. API29 完整 UI/计时/导出/存储联合运行 **214 项断言全部通过**，无跳过；本轮包含 16 页压力 PNG。原生界面已验证：空安装无示例数据、四任务 65% 实例、编辑草稿重建、进度草稿重建/取消/保存、2 秒烟花、排序、删除取消/归档、更多菜单、历史、Markdown/PNG 预览、复制、计时开始/暂停/重建/继续/提前结束、后台到点、计划时长封顶和完成页。

## 可复现命令

- 主机：`scripts/build-cloud.sh testDebugUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest assembleRelease`
- 存储兼容性：`source scripts/cloud-env.sh && bash tests/store/run.sh .`
- 未签名打包：`source scripts/cloud-env.sh && python3 scripts/check-unsigned-apk.py`
- 在一次性模拟器上安装 Debug app 与测试 APK，然后运行：`adb shell am instrument -w cn.goff.shiben.test/cn.focusledger.LedgerInstrumentation`
- 专项：在上述 instrument 命令中加入 `-e suite store` 或 `-e suite export`。
- 联合测试需要全新的一次性 app 数据；它会创建大量测试记录。禁止对用户真实设备/数据运行。

## 测试方法与限制

- 到点测试使用仅位于独立测试 APK 中的 14 分 55 秒既往夹具，加真实最后几秒；早期测试记录移至前一天，避免夹具回拨开始时间使记录排序失真。这不是连续 15 分钟真实锁屏测试。
- 所有测试数据存在模拟器私有目录，不进入正式 APK；原生截图中的任务和数值为测试夹具。
- 用户 realme GT6、OEM 后台限制、真实来电打断、真实长时锁屏、真实系统重启尚未测试。
- 正式签名及原安装覆盖升级需取得用户批准的发布身份后单独核验；本报告不将未签名 APK 描述为可直接安装正式包。
- API29 的 320dp 宽屏幕与 150% 字体专注页已目视通过。150% 字体任务列表发现百分号换行，已交实现者修复并等待重测。API35 最终结果待补记。
