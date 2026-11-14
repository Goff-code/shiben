# 时本 3.1 AI 接入

Android applicationId 保持 `cn.goff.shiben`，versionCode 8，versionName 3.1。基线是 3.0/code7 的 `39fc151ec42ac4d397d6c3cb6f8c4b4120122f65`，开发分支 `codex/shiben-ai-v1`。覆盖安装必须沿用原发布证书，不能使用 debug 签名覆盖。

## 使用

1. 在原手机覆盖安装同证书签名的 3.1，保留应用数据。
2. 更多 → AI 连接 → 连接 ChatGPT，在浏览器登录自己的 ChatGPT 账户并授权，点击返回时本。
3. 首次上传全部任务、每日快照和专注记录；完成后在 ChatGPT 选择“时本 · AI 连接”。
4. 可询问任务、历史专注统计，新增任务、修改名称/优先级、设置当天进度；完成任务即进度 100。

服务地址：https://shiben-ai-personal.gof2088596.chatgpt.site 。只对所有者开放。配对中断或旧手机无法操作时，可在服务首页撤销原设备再配对。本机断开会立即停止新操作；若无法联网撤销，需在服务首页完成撤销。云端历史保留。

## 数据与执行约定

手机 SQLite 是最终数据源，云端 D1 是查询副本和命令队列。`pending` 只代表排队；必须查询操作状态，收到手机 `applied` 回执后才算完成。进度仅限手机时区当日。旧日期命令返回 expired，内容/优先级/进度变化后的旧版本命令返回 conflict，不覆盖手机修改。任务创建放在末尾，初始进度为 0。不提供远程计时、删除、历史修改或 Notion 同步。

SQLite v3→v4 只新增 sync_meta / sync_receipts / sync_hashes / sync_changes 和触发器，不重建原业务表。业务变更与回执处于同一数据库事务；重试复用请求 UUID，不能重复执行。已存在任务、日快照、session ID 和计时序列化保持原样。统计使用已有 TimerCoordinator 日快照逻辑；活动计时只是读取时投影，不伪造完成 session，未知进度保持 null。

首次全量、之后按实体 SHA-256 发送差异；上传批次未完成时拒绝查询，完整上传后更新 syncedAt。断点失败不会提前确认本地哈希，下次安全重传。每次进入 app 请求同步；前台约 30 秒请求，后台 JobScheduler 最小 15 分钟周期且受省电策略影响。AI 操作在手机执行并上传后反映到查询中。

## 授权

配对有效期 10 分钟。手机临时 RSA 公钥与 nonce 发到登录后的授权页，凭据通过 RSA-OAEP SHA256 + AES-GCM 加密深链接返回，仅发起手机可解密。临时私钥和长效凭据均用 Android Keystore AES 密钥保护。APK 不含账号 token、服务 token 或签名私钥；不开放明文网络或系统备份。云端只保存设备令牌 SHA-256，业务工具必须有 Sites 平台用户身份，设备请求必须有独立令牌，所有数据按 owner 隔离。

## 验证

`./gradlew testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease assembleDebugAndroidTest`。
`tests/store/run.sh` 保留原统计、序列化、旧版 SQL 迁移检查，需 JAVA_HOME、ANDROID_JAR 和 debug R.jar。
`python3 tests/branding/check.py .`，`python3 scripts/check-unsigned-apk.py` 检查安装身份和发布包。
新增 SyncDataTest 使用 Robolectric SDK 35 / SQLite 测试升级保留、原子回滚、重复操作、冲突和跨日过期。Robolectric 不替代真机 Keystore、浏览器回跳、后台调度和同证书覆盖安装测试。
