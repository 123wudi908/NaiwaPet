# NaiwaPet · 奶蛙 Android 桌宠源码

原生 Kotlin / Android View / WindowManager 桌宠项目。当前代码版本为 `0.5.0-personal`，`versionCode = 5`，包名为 `com.naiwa.androidpet`。

**此公开仓库只包含代码和构建配置，不包含角色图片、动画帧、原始参考图、生成素材、预览或 APK。** 未配置素材时，界面会说明素材缺失，不能开启可见桌宠。这是预期状态。

## 已有代码

- 悬浮窗、拖动、长按面板、连点、轻扫与自动行为。
- 逐帧动画、动作优先级、素材来源与指纹校验。
- 本地心情、精力、亲密度、互动记录及成就。
- 打鼓、连击挑战、接奶泡三个小游戏。
- 锁屏暂停、平台服务恢复、有限窗口恢复与本地诊断。

角色显示及相关游戏画面需要另行配置有权使用的素材。公开副本使用中性的纯色应用图标；全部 24 项动作配置均标记为素材缺失，不沿用私人素材的批准状态、来源通知或帧指纹。应用逻辑和单元测试保留自原项目。

## 构建

需要 JDK 17、Android SDK Platform 37、Build Tools 36.0.0；Gradle 9.6.0 / Android Gradle Plugin 9.4.1 已固定在工程中。最低 Android 8.0（API 26）。

使用 Android Studio 打开仓库根目录，配置自己的 Android SDK，或执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。SDK 路径放在本机 `local.properties`，不要提交该文件。

GitHub Actions 检查公开文件范围，执行单元测试、Lint 和内部 Debug 构建，只保存诊断报告，不上传 APK，不发布 Release。素材相关原生验收探针保留为开发代码，需要本地配置完整资源后另行运行；它们不属于公开副本的持续集成通过声明。

## 配置自己的素材

见 [素材接入说明](docs/ASSETS.md)。请自行确认角色和动画的使用、修改及分发权。仓库不提供私人素材下载地址，也不把可公开查看的第三方图片视为已获授权。

## 版本与更新

目前尚未实现应用内检查更新。当前构建仍采用 Android Debug 签名，没有正式发布签名或 Release。

后续更新设计见 [更新准备说明](docs/UPDATES.md)。正式发布前需要完成更新功能、长期签名与版本管理，并重新验证素材分发范围。

## 许可与范围

本仓库尚未指定项目级开源许可证；公开可见不等于授予任意复制、修改或再分发许可。角色形象及未包含的私人素材不在任何授权范围内。第三方构建工具的许可独立适用，见 [第三方说明](THIRD_PARTY_NOTICES.md)。
