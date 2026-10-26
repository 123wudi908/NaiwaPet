# 更新准备

当前公开仓库用于源码管理。应用内检查更新、下载和安装流程尚未实现；本次没有发布 Release 或上传 APK。

当前基线：`applicationId = com.naiwa.androidpet`、`versionName = 0.5.0-personal`、`versionCode = 5`。签名仍使用各开发环境的 Android Debug 身份，不是稳定的正式发布身份。

后续工作：

1. 确定可分发素材及长期正式签名，独立保管私钥，避免更换签名后破坏原安装的升级路径。
2. 每次发布递增 `versionCode`，保持应用 ID 和兼容的签名身份。
3. 实现手动检查更新，并处理没有 Release、断网、下载中断和校验失败等情况。
4. 为正式安装包提供版本、最小系统版本、下载地址、文件大小、SHA-256 和更新说明，并核对实际包名、版本及签名。
5. 完成测试后再发布正式 Release，通过系统安装界面由用户确认安装。

正式发布后可使用 `GET https://api.github.com/repos/123wudi908/NaiwaPet/releases/latest` 查询更新。当前没有正式 Release 时，该地址不能提供可用更新。不要将个人 GitHub Token 内置到应用。

参考：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[Android 版本管理](https://developer.android.com/studio/publish/versioning)。
