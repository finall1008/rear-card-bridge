# 模块商店上架与更新

[浏览商店](https://finall1008.github.io/rear-card-modules/) · [商店仓库](https://github.com/finall1008/rear-card-modules) · [模块开发指南](<module-development.md>)

App「商店」与网页使用同一份[模块目录](https://finall1008.github.io/rear-card-modules/modules.json)。模块作者在自己的公开仓库保存代码和预览图，通过 GitHub Releases 发布模块 ZIP，再向商店仓库提交目录条目；不需要额外服务器或后台账号。

## 1. 准备模块版本

1. 按[模块开发指南](<module-development.md#6-发布与安装>)构建模块 ZIP。ZIP 根目录必须直接包含 `module.json`，不能多套一层目录；不要用 GitHub 自动生成的 Source code ZIP 代替构建产物。
2. 在模块的 `module.json` 中填写稳定、唯一的 `id`，`apiVersion` 使用 `2`。发布更新时保留 `id`，增加 `versionCode` 并更新 `version`；商店按 `versionCode` 判断是否显示「更新」。
3. 若提供预览图，将反映当前卡片界面的图片提交到模块仓库，再创建版本 tag。使用示例数据，不要展示令牌、Cookie 或其他敏感信息，不要把过时原型图当成当前版本预览。
4. 为该 tag 创建 GitHub Release，将构建好的模块 ZIP 上传为附件。记录具体版本发布页和 ZIP 下载直链，不使用 `latest` 或分支归档链接。

## 2. 添加商店条目

Fork [商店仓库](https://github.com/finall1008/rear-card-modules)，编辑根目录的 `modules.json`，在现有 `modules` 数组中新增条目。保留顶层 `schemaVersion: 1` 和其他模块，不重复添加同一 `id`。

```json
{
  "id": "dev.example.weather",
  "name": "天气卡片",
  "version": "1.0.0",
  "versionCode": 1,
  "apiVersion": 2,
  "description": "显示指定城市的天气",
  "author": "your-name",
  "capabilities": ["network", "storage"],
  "repository": "https://github.com/OWNER/REPO",
  "releaseUrl": "https://github.com/OWNER/REPO/releases/tag/v1.0.0",
  "downloadUrl": "https://github.com/OWNER/REPO/releases/download/v1.0.0/weather-1.0.0.zip",
  "tags": ["天气", "工具"],
  "previews": [
    "https://raw.githubusercontent.com/OWNER/REPO/v1.0.0/previews/card.webp",
    "https://raw.githubusercontent.com/OWNER/REPO/v1.0.0/previews/detail.webp"
  ]
}
```

将示例中的仓库、版本、文件名和元数据替换为自己的实际内容。

| 字段 | 填写要求 |
| --- | --- |
| `id`、`version`、`versionCode`、`apiVersion` | 必须与 ZIP 根目录的 `module.json` 完全一致；App 下载后会核对这四项 |
| `name`、`description`、`author`、`capabilities` | 按实际模块信息填写，能力声明与模块包保持一致 |
| `repository` | 模块公开源码仓库的 HTTPS 地址 |
| `releaseUrl` | 本次具体版本的 GitHub Release 页面 |
| `downloadUrl` | 本次已构建 ZIP 附件的 HTTPS 下载直链 |
| `tags` | 可选，分类和搜索用的字符串数组 |
| `previews` | 可选，有序的 HTTPS 图片直链数组；第一张为封面，可放多张；省略或空数组时不显示预览区域 |

预览图留在模块仓库，不复制到商店仓库，也不需要塞进模块 ZIP。引用对应发布 tag 下的图片直链，不使用 GitHub 文件浏览页地址；图片应在创建该 tag 前提交。App 和网页共用 `previews`，支持左右切图、页码和点击放大，完整等比显示图片。

## 3. 提交并上架

1. 向商店仓库提交 Pull Request，说明模块用途、版本、发布页和下载地址。
2. 确认目录是有效 JSON，所有地址均可公开访问，下载结果确实是该版本模块 ZIP，预览图对应当前模块。
3. 维护者审核条目与包格式后合并到 `main`，GitHub Pages 部署目录。
4. 部署完成后，刷新网页或在 App「商店」下拉刷新，即可看到新条目。已安装的同一模块若版本号更低，App 会显示「更新」。

商店安装后仍通过 App 实际扫描出的本地卡片选择替换目标；需要重新应用时，按 App 提示到系统背屏设置操作。

## 4. 后续更新

- 在模块仓库构建新版 ZIP、发布新 tag 和 Release，递增 `versionCode`。
- 修改商店中**同一个 `id` 的现有条目**，同步更新 `version`、`versionCode`、`releaseUrl`、`downloadUrl` 和相应元数据；不要新增另一个条目。
- 界面变化时提供新版预览图，将 `previews` 指向新 tag 中的图片。只有元数据或预览图调整时，也可单独提交目录修改；不要移动已有发布 tag 或替换其 ZIP 来冒充新版本。
- 提交 PR 并合并部署后，用户刷新商店即可读取新目录，无需发布新版卡片桥 App。
