# 本地素材接入

公开仓库不包含角色图片、生成图、视频、预览、历史素材或私人来源通知。只有不含图像的动画参数和行为配置模板。

默认 `behavior.json` 将 24 项动作标记为 `missing`，`qualityApproved` 为 `false`，帧指纹表为空，未声明任何素材授权。因此应用会提示缺少合格素材，不会用空白或错误图片启动桌宠。

使用自己有权使用的素材时：

1. 将透明 PNG 帧放在 `app/src/main/assets/naiwa/<action>/`，例如 `idle/frame01.png`。文件按自然顺序播放。`idle` 是开启桌宠的必要动作。
2. 在 `animations.json` 配置对应画布、裁切范围、帧率、逐帧时长、循环和返回动作。当前参数来自原项目的 640×640 动画布局；自己的素材尺寸或帧数不同时必须同步修改。
3. 为实际使用的每张 PNG 计算 SHA-256，并填写 `behavior.json` 对应动作的 `sha256` 表。不要沿用其他素材的指纹。
4. 在本地 `naiwa/notices/<name>.txt` 保存真实来源、权利人、许可或使用范围；在动作的 `source` 中填写 `usageScope`、`origin`、`evidencePath` 和通知文件的 SHA-256。加载器接受 `licensed` 或 `private-personal` 范围声明；声明必须真实，文件校验不替代授权判断。
5. 完成图像质量、透明度与 Android 实际解码检查后，才将对应动作改为 `materialStatus: formal`、`qualityApproved: true`。`allowAutomatic` 和 `allowManual` 仍分别控制使用方式。

默认忽略规则将图片与动作子目录保留在本机。`scripts/verify-public-source.mjs` 检查实际进入 Git 的文件；请勿将私人素材强制加入这个公开仓库。原生素材探针在无素材的公开副本上不适用。

来源配置结构如下，仅描述字段，不授予任何素材许可：

```json
{
  "usageScope": "licensed",
  "origin": "填写真实权利人、来源及许可范围",
  "evidencePath": "naiwa/notices/your-art.txt",
  "evidenceSha256": "填写通知文件的64位SHA-256"
}
```
