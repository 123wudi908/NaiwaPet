# 第三方库、构建与测试工具

以下依赖和工具的许可独立于应用代码及角色素材：

- Gson 2.11.0：用于解析 GitHub Release 和签名更新清单的 JSON，采用 Apache License 2.0，参见 [Gson 2.11.0 许可文件](https://github.com/google/gson/blob/gson-parent-2.11.0/LICENSE)。通过 Gradle 引用，未将其源码或构建缓存复制到仓库。
- Gradle Wrapper / Gradle：由 Gradle 项目提供，参见 [Gradle 的许可文件](https://github.com/gradle/gradle/blob/master/LICENSE)。Wrapper JAR 是构建启动器，不是角色素材。
- Android Gradle Plugin、Android SDK 和 Java：由相应供应方发布，使用前按其许可配置本地环境；不随本仓库再分发 SDK 或 JDK。
- JUnit 4.13.2：仅用于单元测试，参见 [JUnit 许可文件](https://github.com/junit-team/junit4/blob/main/LICENSE-junit.txt)。

未复制外部桌宠项目的图片或候选图包；不将第三方代码许可视为角色图像授权。本仓库尚未添加项目级开源许可证。
