# 时本 2.0

记录每一份注意力，投资你想成为的人。

原生 Java Android 本地专注账本。无账号、无网络权限、无广告、无分析 SDK。暖米白与深青色界面，三个页签：专注、任务、今日。

## 功能

- 专注页无纵向滚动。选择当前任务，至少15分钟、每次增加5分钟；支持暂停/继续/结束，计时完成只提醒，不自动改任务进度。
- 单层任务列表，跨天保留任务、每天进度从0%开始；历史进度不变。手动排序优先级、编辑名称/进度、保留记录式删除。进度从低于100%保存为100%时播放2秒庆祝；重访不重播。
- 今日总进度按优先级加权：前面的任务占剩余权重一半，最后任务占全部余数。三项为50/25/25，四项50/25/12.5/12.5；部分进度线性计入。
- 更多位于今日底部，进入历史、提醒/权限、备份、说明和导出。
- 每日任务快照冻结名称、顺序、权重及进度；专注实际时长按记录时区跨日分摊。历史不自动过期，旧版未知信息明确标记。
- 日/月/年/自定义（含起止）/全部范围 Markdown 与 PNG；Markdown 支持完整复制（大文件改为保存），长图片完整分页，多日 PNG 为 ZIP。
- 每日 Markdown 原子写入内部持久目录，SQLite 为源；可选择外部文件夹自动备份。卸载或清除数据会丢失内部记录，当前无自动导入。

## 构建与验证

JDK17+、Android SDK Platform35 / BuildTools35.0.0、Gradle8.11.1。只使用免费官方工具及 JUnit 测试依赖。

```
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
bash tests/store/run.sh .
python3 tests/branding/check.py .
python3 scripts/check-unsigned-apk.py app/build/outputs/apk/release/app-release-unsigned.apk
```

云端隔离工具可使用 `scripts/build-cloud.sh`。测试运行器与设备验证见 `docs/TEST_REPORT_V2.md`；旧版测试报告不代表V2结果。设计参考见 `docs/design/v2`，实际截图另行交付。

## 安装与签名

安装身份保持 `cn.goff.shiben`；2.0.0 / code3 / min26 / target35。release 默认未签名。

旧版签名已丢失。本次正式密钥已在用户 Mac 本机创建、保管并完成正式包签名；云端没有收到私钥或密码。公开证书保存在 `docs/SHIBEN_V2_RELEASE_CERTIFICATE.pem`，正式包核验见 `docs/RELEASE_V2_VERIFICATION.md`。云端 debug 仅测试。V3 必须相同包名+V2相同正式证书+更大versionCode覆盖升级。详情见 `docs/INSTALL_AND_BACKUP.md`、`docs/SIGNING_KEY_RECOVERY.md`。不要上传私钥或密码。

计时使用单调时钟，暂停不计入；重启只保留已保存检查点。精确提醒权限、通知渠道与手机后台限制影响提醒。realme GT6 / UI7.0 须真机验证。
