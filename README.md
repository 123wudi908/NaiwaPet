# NaiwaPet · 奶蛙 Android 桌宠

原生 Kotlin / Android View / WindowManager 桌宠项目。当前代码版本为 `0.5.0-personal`，`versionCode = 5`，包名为 `com.naiwa.androidpet`。

仓库包含原生 Android 源码、构建配置及 **14 组、86 帧透明 PNG 动作**。原始参考图、视频、生成过程稿、历史备份、签名私钥和 APK 不随仓库提供。

## 已有代码

- 悬浮窗、拖动、长按面板、连点、轻扫与自动行为。
- 逐帧动画、动作优先级、素材来源与指纹校验。
- 本地心情、精力、亲密度、互动记录及成就。
- 打鼓、连击挑战、接奶泡三个小游戏。
- 锁屏暂停、平台服务恢复、有限窗口恢复与本地诊断。

已恢复正式动作的动画参数、已审查指纹和应用图标；其余 10 项没有合格素材的动作继续禁用。应用逻辑和单元测试保留自原项目。

素材为本项目生成的动画帧，附有不含个人记录的公开说明。私人来源通知、参考媒体和过程档案不随仓库提供；原有个人使用范围不被改写为商业许可，具体见 [素材来源与范围](docs/ASSETS.md)。

## 构建

需要 JDK 17、Android SDK Platform 37、Build Tools 36.0.0；Gradle 9.6.0 / Android Gradle Plugin 9.4.1 已固定在工程中。最低 Android 8.0（API 26）。

使用 Android Studio 打开仓库根目录，配置自己的 Android SDK，或执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。SDK 路径放在本机 `local.properties`，不要提交该文件。

GitHub Actions 检查提交范围、86 帧指纹、PNG 完整性与透明背景，并执行单元测试、Lint 和内部 Debug 构建；只保存诊断报告，不上传 APK，不发布 Release。原生验收探针需连接设备或模拟器另行运行，不能把构建通过等同于所有真机运行验证。

## 素材

正式动作位于 `app/src/main/assets/naiwa/`，帧清单与 SHA-256 位于 [asset-manifest.json](docs/asset-manifest.json)。请按 [素材接入说明](docs/ASSETS.md) 核对使用范围或更换为自己有权使用的资源。

## 版本与更新

目前尚未实现应用内检查更新。当前构建仍采用 Android Debug 签名，没有正式发布签名或 Release。

后续更新设计见 [更新准备说明](docs/UPDATES.md)。正式发布前需要完成更新功能、长期签名与版本管理，并重新验证素材分发范围。

## 许可与范围

本仓库尚未指定项目级开源许可证；公开可见不等于授予任意复制、修改或再分发许可。图片来源通知与代码许可分别适用，不将素材标注为 MIT 或商业图包。第三方构建工具的许可独立适用，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
