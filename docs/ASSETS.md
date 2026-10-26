# 素材来源、范围与接入

按维护者要求，仓库现提供原项目已经审查的 14 组、86 张 640×640 RGBA PNG 动画帧。图片字节与本地正式素材一致，没有新增或重绘角色。

| 动作 | 帧数 |
| --- | ---: |
| idle、blink、wave、happy、walk、think | 各 6 |
| laugh | 8 |
| dance、fake_fall、laugh_fall、loading_brain、freeze、wobble、belly_drum | 各 6 |

全部文件路径和 SHA-256 见 `asset-manifest.json`。正式资源位于 `app/src/main/assets/naiwa/<action>/frameNN.png`。

## 真实来源与使用范围

这些动作是本项目生成的动画帧，经过分格、透明度处理、等比缩放及锚点校准。

运行所需的说明位于 `naiwa/notices/materials.txt`，只说明项目动画帧及其个人使用范围，不包含私人来源通知或原始参考信息。本次应维护者要求随源码提供，不宣称额外的原角色版权或商业授权。

原始参考图、参考视频、生成源图、提示词档案、私人来源通知、第三方候选和历史备份均不包含在本次提交中。

`dance_subway`、`roll_laugh`、`sit`、`sleep`、`look_around`、`angry`、`tap_react`、`surprised`、`cry`、`drink` 共 10 项仍缺合格素材并保持禁用。

## 校验与替换

```sh
node scripts/verify-public-source.mjs
node scripts/verify-assets.mjs
node scripts/verify-behavior.mjs
```

这些检查分别核对提交清单和精确指纹、PNG CRC/尺寸/透明背景，以及行为配置、来源通知指纹和动作准入。代码中的来源校验只验证记录与文件是否一致，不替代实际授权判断。

替换为自己有权使用的素材时，需同时调整 `animations.json` 的画布、裁切与逐帧时长，`behavior.json` 的帧指纹与真实来源通知，以及 `docs/asset-manifest.json`。完成图像质量和实际 Android 解码验收后再启用对应动作。不要仅修改批准标志来绕过素材检查。
