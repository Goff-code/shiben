# 时本 3.0 云端运行验收

日期：2026-10-11。生产代码固定为 `b1838a8fb66b74b13c278991d097b9be48155688`，tree `a98e365ba03ed9622e17a43d9398853df2efa014`；版本 3.0/code7，包名 cn.goff.shiben。QA 分支仅包含测试适配和记录，生产文件及两版测试目标 APK 的字节校验始终不变。GitHub Actions 未运行，本次在助手自己的单台官方完整 AOSP API35 云端模拟器执行。

## 真实通过的运行阶段

| 阶段 | 断言检查数 | 范围 |
| --- | ---: | --- |
| 13 等级及公共边界，分 5 批 | 138 | 全部原图对应、原生 Canvas PNG、3:4 尺寸、长范围/长时长、未知值、隐私像素、完整任务附页 |
| gallery | 138 | 真实 MediaStore PNG 写入/读取、相册路径与发布、单日分页/多日汇总、取消/失败清理 |
| reminders granted | 471 | 四模式、系统限制、记录不重复、真实系统通知投递、一次模拟器后台熄屏完成 |
| reminders denied | 81 | 新装未授予通知权限，四模式均无通知但记录正常保存 |
| native all | 242 | 原生任务/手工进度/计时/编辑/排序/草稿/旋转/Markdown/长任务十页 PNG |
| gallery_ui | 78 | 隐私偏好、实际保存、重建防重复与任务状态 |
| gallery_view | 22 | 点击“查看图片”，真实外部 Gallery 显示导出图片 |
| 升级 seed | 119 | 真正 2.0.2/code5 源码建立任务、历史、墓碑、暂停计时及四个非默认系统渠道限制 |
| 升级 verify | 89 | install-r 后原始 SQL/类型/计时字节、偏好、身份、渠道完全保留；原生界面进度37/8、暂停和警告正确 |
| 升级 routing | 146 | 新原生选项实际投递；关闭渠道无通知，其余用原 ID，静音/关振/LOW限制不被绕过 |

合计 1524 项断言检查，包括重复与聚合检查，不能称为 1524 个独立案例。每个阶段有完整日志、正数 OK、唯一 INSTRUMENTATION_CODE:-1 和客户端 exit0。13张等级PNG均1080×1440，已逐张目检；任务长文本保留到最后一页。升级三个阶段的 baseline 文件 SHA256 完全一致且 token 相同，保持同一实例、同一启动、同一当地日期、UID、firstInstallTime 和临时证书。单独的前一次 seed/verify 成功及后续夹具失败不计入此最终链。

## 构建与待签名包

固定生产代码 177 项 JVM 测试此前已通过。最终 assembleRelease、lintRelease 成功，Release lint 0错误/21警告。13项未签名包装检查及13张用户原图 SHA 校验通过：nondebug、无 INTERNET、无测试类/预置数据库、无额外存储权限，minSDK26/target35。

新的待签名文件：`时本-3.0-code7-待本机签名.apk`，39,067,962 bytes，SHA256 `00d169217ab27dcf328ada41f9f1d7a166692f451a7cee64ef3acdcc65f54059`。它尚未签名，不能当作可安装成品；旧2.0.3候选不得混用。本次没有接触正式私钥。

## 运行身份和范围

目标 Debug APK SHA256：`53cf41db47fcfab7a9b9f9f3308092a6c6d7f88869319342cf9be4feb63278b9`。
旧2.0.2 Debug APK：`192e00e040381e3bc132b816dc077118750c953160d377ed717416b843bebd1d`。
最终升级旧/新 test APK：`88a47ab4dd12de1fd8443802294186629aae90867e36bf6e8e1806d830535b96` / `d930b50b6e0ee2039a081471a4a313da925f8add52241fc2b5413e1f4f62fc5c`。
临时 Debug 证书 SHA256：`87db02b1778672eaa4298eeb328a8711ed1a0cd5bf6eaabb8f02c5f57dca2e57`，与正式V2证书不同。

早期五批图像使用 a3b27b09… test APK，gallery 使用6592846b…，其余原生成功阶段使用e5c07c5b…；每阶段完整SHA在累计证据manifest中，不能倒写归属为后来改进的test APK。

- 证明软件模拟器中同测试证书的真实版本升级；正式V2证书 code5→7 覆盖尚未执行。
- 没有 realme GT6/UI7.0 真机、实体扬声器或震动验证，没有长时间Doze/OEM省电策略保证。
- 本次普通3.0后台熄屏场景实际通过，exactAlarmGranted=false；升级后的后台投递未单独重复。
- 权限拒绝阶段是新装未授予，不代表在运行中撤销权限的测试。
- 不同boot ID/引擎恢复是受控状态模拟，并非真实整机重启；Markdown可以重新生成，升级保留源数据和日期，不保证旧报告字节不变。
- 系统 Settings 某些截图捕获到过渡帧，系统最终值以每次实际 NotificationChannel 读回、用户锁定位及 dumpsys 联合验证；所有原始图像照实保留。

## 夹具修正与失败留存

保留以前启动前系统失败、403材料和所有本次失败。此次只修正测试对官方API35的适配：严格模拟器保护、权限分阶段、真实alarm dump标题、Settings任务复用与嵌套滚动、十六进制用户锁定位、ICU正则边界、RadioButton点击返回语义。没有修改系统watchdog/安全超时、绕过权限或使用隐藏渠道setter。RadioButton那次进程终止栈来自测试自身断言，不能说没有发生进程崩溃，也不能算生产功能崩溃。详细依据见 QA_ACTIONS_UPGRADE.md 与累计日志。

复现入口：tests/cloud-native/README.md、REMINDER_PERMISSION_STAGES.md、docs/QA_ACTIONS_UPGRADE.md。保持固定生产源及临时测试证书，按阶段正向终态和相同baseline token验收；失败单次路由夹具不得复用。最终证据包含完整命令结果、build/packaging记录、通知JSON、SQL/偏好快照、PNG/XML与原始logcat，历史条目逐字节保留。
