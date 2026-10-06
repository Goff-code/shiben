# Mac 本地签名最小准备

2.0.0 正式包已在用户 Mac 完成签名。后续版本必须复用已经建立的 V2 密钥，不能重新创建替代身份。以下保留首次建立与后续本地签名的参考步骤；本页不是新建或替换私钥的授权。

## 文件和工具

- 对应本次发布的 `时本-2.0.3-unsigned.apk`，签名前必须核对2.0.3校验说明中的SHA256。
- Mac 上 JDK17+（keytool 和 apksigner 的 Java 运行环境）。
- 官方 Android SDK Build-Tools35.0.0，只需 zipalign、apksigner 及其 lib/apksigner.jar；安装时仅接受实际所需组件对应的已批准许可，禁止批量接受全部 SDK 许可。
- 不需要 Android Studio、Gradle、模拟器或重新下载源码来完成纯签名。

## 新身份建立（首次、仅用户批准后）

1. 在 Mac 用户控制的私有目录创建长期 V2 密钥；目录和文件仅用户可读。
2. 密码由用户在本地终端或安全输入界面输入，不在聊天中索要、不打到日志。
3. 用户亲自将加密 keystore 做第二份受控加密备份，密码单独保存。
4. 用该私钥签名 APK；仅公开证书和证书 SHA256 可保存到仓库。私钥和密码不离开 Mac。

## 已有 V2 密钥时签名

使用仓库 `scripts/sign-release.sh <unsigned-apk> <output-apk>`，本地环境指定 ANDROID_HOME、KEYSTORE、KEY_ALIAS。脚本兼容 macOS mktemp，使用16KiB原生库页对齐并禁用无需分发的v4 sidecar；不会创建密钥，默认由 apksigner 在本地交互询问密码；也可使用用户本地受保护密码文件。

签名前核对交付的 unsigned APK SHA256；签名后执行 apksigner verify --verbose --print-certs、zipalign检查，并核对包名 cn.goff.shiben、versionCode6、versionName2.0.3、非debuggable、无网络权限。记录正式证书 SHA256，后续 V3 不得更换。

私钥丢失将失去与当前安装同签名更新的能力。旧V1签名已失效，用户自行安排旧版数据导出及卸载，本任务不会操作用户手机卸载。

2.0.3签名只使用本次交付的未签名APK，不在Mac重新开发、编译或测试。签名完成后由云端核验载荷、证书及正式code5至6覆盖升级；此前Debug升级不替代该验收。
