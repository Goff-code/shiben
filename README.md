# 时本 2.0.2

记录每一份注意力，投资你想成为的人。

原生 Java Android 本地专注账本。无账号、无网络权限、无广告、无分析 SDK。暖米白与深青色界面，三个页签：专注、任务、今日。

## 功能

- 专注页无纵向滚动。选择当前任务，至少15分钟、每次增加5分钟；支持暂停/继续/结束，计时完成只提醒，不自动改任务进度。
- 单层任务列表，跨天保留任务、每天进度从0%开始；历史进度不变。手动排序优先级、编辑名称/进度、保留记录式删除。进度从低于100%保存为100%时播放2秒庆祝；重访不重播。
- 今日总进度按优先级加权：前面的任务占剩余权重一半，最后任务占全部余数。三项为50/25/25，四项50/25/12.5/12.5；部分进度线性计入。
- 更多位于今日底部，进入补记注意力时长、历史、提醒/权限、备份、说明和导出。补记支持实际小时/分钟及历史日期，不改变任务进度。
- 今日显示累计投资注意力时长；结算等级仅依加权完成度原值计算。真正100%默认S，才可按日选择S/SS/SSS/SSS+，退回未完成会清除选择。
- 每日任务快照冻结名称、顺序、权重及进度；专注实际时长按记录时区跨日分摊。历史不自动过期，旧版未知信息明确标记。
- 日/月/年/自定义（含起止）/全部范围 Markdown 与 PNG；Markdown仅含日期与品牌、注意力总时长、总完成度、任务及各完成度、结算等级；PNG使用模糊绿叶底与近白面板。Markdown 支持完整复制（大文件改为保存），长图片完整分页，多日 PNG 为 ZIP。
- 每日 Markdown 原子写入内部持久目录，SQLite 为源；可选择外部文件夹自动备份。卸载或清除数据会丢失内部记录，当前无自动导入。

## 构建与验证

JDK17+、Android SDK Platform35 / BuildTools35.0.0、Gradle8.11.1。只使用免费官方工具及 JUnit 测试依赖。

```
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
bash tests/store/run.sh .
python3 tests/branding/check.py .
python3 scripts/check-unsigned-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

云端隔离工具可使用 `scripts/build-cloud.sh`。本次工具精确版本、环境恢复与正式升级操作见 [Android云端QA说明](docs/ANDROID_CLOUD_QA.md)。当前验收见 `docs/TEST_REPORT_2.0.2.md`，运行器历史说明见 `docs/TEST_REPORT_V2.md`；较早版本报告不替代本次结果。设计参考见 `docs/design/v2`，实际截图另行交付。

2.0.2已完成双系统业务／布局验证、最终正式签名核验及API35正式code4至5无清数据覆盖升级。正式升级的UiAutomator整套驱动曾中断，最终通过真实界面输入、截图和严格数据比较验收：旧列／偏好／Markdown语义30项、暂停计时9项保留检查通过。各候选差异、环境异常及实机限制均记录在本次报告中。

## 安装与签名

安装身份保持 `cn.goff.shiben`；2.0.2 / code5 / min26 / target35。release 默认未签名。

既有 V2 正式密钥由用户 Mac 本机保管；云端没有收到私钥或密码。公开证书保存在 `docs/SHIBEN_V2_RELEASE_CERTIFICATE.pem`。2.0.2 的正式签名和同证书覆盖升级状态见 `docs/TEST_REPORT_2.0.2.md`，既有签名记录见 `docs/RELEASE_V2_VERIFICATION.md`。云端 debug 仅测试；2.0.2 及未来版本必须保持相同包名、V2 正式证书和更大的 versionCode。2.0.2 的非破坏迁移与每日文件格式变更见 `docs/UPGRADE_2.0.2.md`。详情见 `docs/INSTALL_AND_BACKUP.md`、`docs/SIGNING_KEY_RECOVERY.md`。不要上传私钥或密码。

已验收的正式2.0.2 APK为4,470,615字节，SHA256为 `24ed77569d73918ea2bb7d759d7788950c0cf35e2056f62b10c02aa9f6c9f885`。v2/v3签名有效，证书与原正式2.0.1一致；最终未签名原件的28个载荷项目字节不变，仅新增3个签名项目。升级时使用正式包直接覆盖安装，不要卸载旧版或清除数据；用户真机仍需设备侧确认。

计时使用单调时钟，暂停不计入；重启只保留已保存检查点。精确提醒权限、通知渠道与手机后台限制影响提醒。realme GT6 / UI7.0 须真机验证。
