# 依赖与原创性说明

## APK 运行时

- 仅使用 Android 系统公开框架 API（Activity、SQLite、通知、AlarmManager、前台服务等）。
- 没有打包第三方应用运行库、广告库、分析 SDK、网络客户端或第三方字体。
- 应用图标是本项目绘制的 XML 向量图形；界面、中文文案、配色、布局和代码为本项目实现。
- 不使用 OffScreen 的图标、截图、商标、代码或素材。名称“时本”由用户确认，未进行商标注册检索；如需公开上架，应另作名称合规检查。
- 完成提醒使用 Android 系统通知音和震动，声音由系统提供，未复制商业音频。

## 开发与测试工具（不打包进 APK）

- Android SDK Command-line Tools、SDK Platform 35、Build Tools 35.0.0、Platform Tools、官方 Android Emulator、官方 API35/API29 AOSP 系统镜像：Google SDK 许可和各组件的 NOTICE；经用户确认官方工具安装及 SDK 许可后下载。
  - https://developer.android.com/studio#command-tools
  - https://developer.android.com/studio/terms
- Android Gradle Plugin 8.9.2：Android 官方 Maven 仓库；Apache-2.0。
  - https://developer.android.com/build/releases/agp-8-9-0-release-notes
- Gradle 8.11.1 与 Wrapper：官方发行包；Apache-2.0（及发行包所列第三方工具许可）。Wrapper 随源码保留，发行包不随源码打包。
  - https://gradle.org/releases/
  - https://github.com/gradle/gradle/blob/v8.11.1/LICENSE
- OpenJDK 21.0.2：OpenJDK 官方 GPLv2 + Classpath Exception 发行包。仅作构建工具，未打包或分发 JDK。
  - https://openjdk.org/legal/gplv2+ce.html
- JUnit 4.13.2：EPL-1.0；Hamcrest Core 1.3：BSD-3-Clause。仅测试依赖，由 Maven Central 获取；没有进入应用运行时 APK。
  - https://junit.org/junit4/license.html
  - https://hamcrest.org/JavaHamcrest/distributables

工具安装均免费，本项目没有购买、订阅、付费 API 或发布上架操作。源码的 MIT 许可见根目录 LICENSE；系统 API 与开发工具的自身许可不因该许可而改变。
