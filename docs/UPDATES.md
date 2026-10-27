# 版本与更新

当前源码：`applicationId = com.naiwa.androidpet`、`versionName = 0.6.0-personal`、`versionCode = 6`。源码同步不包含 APK 或正式 Release 的发布。

## 应用内检查

更新源为 [123wudi908/NaiwaPet 的 GitHub Releases](https://github.com/123wudi908/NaiwaPet/releases)，接口为 `GET https://api.github.com/repos/123wudi908/NaiwaPet/releases/latest`。草稿和预发布版本不作为正式更新；版本比较使用清单中的数字 `versionCode`，不使用最新 Commit 或版本名字符串。

首次启动会检查更新。自动检查和更新通知默认关闭，设置页支持手动检查、最近检查时间、真实结果及失败重试。开启自动检查后，启动检查受时间间隔限制，并通过 Android `JobScheduler` 安排约 24 小时一次、要求联网的周期任务。调度受系统、电量、待机和厂商策略影响，不保证精确时间，也没有常驻轮询服务。

发现更新时，前台显示首页卡片并允许查看详情、稍后提醒或忽略该版本。后台通知需要用户开启通知、获得系统权限且应用不在前台；同一版本去重。Android 13 及以上使用 `POST_NOTIFICATIONS` 运行时权限。通知点击只进入更新详情，不直接打开安装器。

检查和下载失败不会主动停止桌宠服务或清空设置、养成数据。更新状态保存在独立的偏好中。

## 发布资产与信任关系

一个正式 Release 必须提供：

- 可覆盖升级的 APK，文件名只使用英文字母、数字、点、下划线和连字符。
- `naiwa-update.json`：包含 Release 标签、版本名、versionCode、applicationId、APK 资产名、长度、SHA-256 和更新说明。
- `naiwa-update.sig`：对 JSON 原始 UTF-8 字节生成的签名，使用 RSA/SHA-256 或 ECDSA/SHA-256；文件内容是原始签名字节。

当前客户端从已安装 APK 的单一签名证书取得公钥，验证清单后才信任其中的版本、资产和摘要。签名工具可以选择密钥库及别名，但非对应私钥签署的清单会被客户端拒绝。这是当前实现的信任设计，并非 Android 要求所有更新清单必须复用 APK 密钥。单独上传哈希文件不能独立证明发布来源。

Release 标签、APK 名称、长度和摘要必须与签名清单一致。签名后不要修改 JSON 的空格、换行或编码。首次安装包本身也须通过可信渠道取得。

下载使用应用私有目录的临时文件，支持进度、取消和重试。下载完成及安装前检查清单签名、大小、SHA-256、APK 包名、目标 versionCode 和当前签名证书；校验失败禁止安装。大小上限为 200 MiB，清单上限为 128 KiB。

安装交由 Android 系统安装器确认。缺少“安装未知应用”授权时引导用户设置，返回后需再次点击安装。取消安装可稍后重试，不会卸载旧版或主动清除应用数据。

## 正式签名与版本管理

正式构建复用以下环境变量，四项必须同时可用，或者全部不配置：

| 环境变量 | 用途 |
| --- | --- |
| `NAIWA_RELEASE_KEYSTORE` | 仓库外的密钥库路径 |
| `NAIWA_RELEASE_STORE_PASSWORD` | 密钥库密码 |
| `NAIWA_RELEASE_KEY_ALIAS` | 私钥条目别名 |
| `NAIWA_RELEASE_KEY_PASSWORD` | 私钥密码 |

配置不完整时拒绝构建；全部未配置时可进行 Debug 源码验证，但 Release 打包门禁会拒绝执行，不会退回 Debug 签名。不要把密钥库、密码、Token 或本地配置提交到仓库、打印到日志或上传为构建产物。密钥应在仓库外安全保存并离线备份。

后续正式更新保持 `com.naiwa.androidpet` 不变，复用相同签名证书，且 versionCode 必须高于已安装和已发布版本；当前基线为 6。当前校验保守拒绝多签名和证书轮换，不支持签名谱系迁移。

不同证书的正式 APK 不能按当前流程直接覆盖 Debug 安装，更新清单也会因签名身份不匹配而被拒绝。不能通过卸载或清空数据绕过该问题。现有记忆导出不等于已经具备导入和无损迁移能力，不能承诺不同签名重装后自动恢复数据。

## 维护者工具

`scripts/create-update-metadata.py` 使用 Android SDK 的 `aapt dump badging` 从实际 APK 读取包名和版本，再计算文件长度与 SHA-256。用 `--apk`、`--aapt`、`--tag`、`--notes-file` 和 `--output` 指定已经准备好的实际文件与 Release 标签，输出文件名为 `naiwa-update.json`。

`scripts/SignUpdateManifest.java` 使用 JDK 17，接收 JSON 路径、签名输出路径、密钥库路径和别名四个参数，密码从上述环境变量取得。它在本地签名后使用所选证书验签，不导出私钥。签名输出文件名为 `naiwa-update.sig`。

确认包名、版本、证书和三个资产一致后，才由维护者另行授权发布。现有 Actions 仅执行公开源码保护、资源校验、测试、Lint 和内部 Debug 构建，保存诊断报告；不读取正式签名 Secrets、不上传 APK、不创建 Release。若以后引入签名 CI，应仅向受信任的发布流程提供受保护的 Secrets，并单独限制发布权限。

## 源码验证与限制

无需打包 APK 的源码检查：

```sh
node scripts/verify-public-source.mjs
node scripts/verify-assets.mjs
node scripts/verify-behavior.mjs
python3 -B -m unittest scripts.test_update_metadata -v
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:lintDebug
```

原生 `UpdateProbe` 支持替换更新数据源，需要在维护者另行授权的隔离设备验证中运行，不能把单元测试或编译通过等同于完整真机安装验收。

下载在应用进程中执行，进程被回收后可能需要重新下载。Android 实际替换 APK 会结束或重建进程，桌宠可能短暂中断；系统安全界面、强制停止和厂商后台限制也可能影响悬浮层，不能保证安装期间始终显示。

公开资源仍遵守 [素材来源与范围](ASSETS.md) 和 [资源指纹清单](asset-manifest.json)，私人来源通知和内部验收资料不随源码发布。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[Android 版本管理](https://developer.android.com/studio/publish/versioning)。
