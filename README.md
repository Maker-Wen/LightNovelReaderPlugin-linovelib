# LightNovelReaderPlugin-linovelib

一个面向 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 的 Linovelib 数据源插件，可在插件设置中切换简体站与繁体站。

本项目 fork 自 [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)，增加简体站与繁体站切换。

> 原项目版权归原作者所有。本项目继续使用 Apache License 2.0，详见 [LICENSE](LICENSE)。

[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![LightNovelReader](https://img.shields.io/badge/LightNovelReader-plugin-blue)](https://github.com/dmzz-yyhyy/LightNovelReader)
[![Plugin API](https://img.shields.io/badge/Plugin%20API-3-orange)]()
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)]()
[![Release](https://img.shields.io/github/v/release/Maker-Wen/LightNovelReaderPlugin-linovelib?include_prereleases&label=release)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)
[![Downloads](https://img.shields.io/github/downloads/Maker-Wen/LightNovelReaderPlugin-linovelib/total?label=downloads)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)

## 下载

兼容 LightNovelReader 1.2.2 的 `2.0.0` 发布产物可从 Releases 下载，也可以按下方[构建](#构建)步骤生成。

## 功能

- 支持 Linovelib 的探索、排行榜和完本页面
- 支持书名、作品 ID、作者和标签搜索
- 支持完整章节加载
- 支持章节插图，图片保持文内显示
- 支持离线缓存和 EPUB 导出
- 支持点击作品标签、作者和出版社跳转相关搜索
- 支持在插件设置中切换简体站与繁体站（重启应用后生效）
- 默认使用简体站

## 与上游插件的区别

| 项目 | 上游 Linovelib TW | 本项目 |
|---|---|---|
| 插件名 | Linovelib TW | Linovelib |
| applicationId | `io.nightfish.lightnovelreader.plugin.linovelib` | `io.nightfish.lightnovelreader.plugin.linovelib` |
| 数据源 id | `linovelib_tw.hashCode()` | `linovelib.hashCode()`（`-1488977864`） |
| 内容来源 | 繁体输出 | 简体站 / 繁体站可切换 |
| 版本名 | 1.1.2 | 2.0.0 |

本项目与上游插件使用相同 applicationId，不能同时安装。

## 兼容性

- LightNovelReader 1.2.2
- Plugin API 3
- Android 10（API 29）及以上

本版本使用新的统一包名和数据源 id，不兼容旧版 `Linovelib 简体` 的升级与数据迁移。

搜索词会使用 Android ICU 转换为所选站点偏好的简体或繁体；站点返回的书籍内容保持原文。

## 安装

安装前请卸载旧版 `Linovelib 简体` 或原版 `Linovelib TW`。

1. 从 [Releases](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases) 下载 `*.apk.lnrp`
2. 使用 LightNovelReader 打开该文件
3. 在「扩展插件」中启用 `Linovelib`
4. 在数据源中选择 `Linovelib`
5. 如需切换站点，在插件详情的「设置」页选择简体站或繁体站并重启应用

旧数据源下已经添加的书不会自动迁移，需要在 `Linovelib` 下重新搜索和添加。

## 构建

环境要求：

- JDK 17
- Android SDK 36

构建 release：

```bash
export SIGNING_KEYSTORE_PATH=/path/to/release.keystore
export SIGNING_STORE_PASSWORD=...
export SIGNING_KEY_ALIAS=...
export SIGNING_KEY_PASSWORD=...
./gradlew :plugin:assembleRelease
```

产物：

```text
plugin/build/outputs/apk/release/plugin-release.apk.lnrp
```

Release 签名通过上面的四个环境变量注入。未提供签名变量时产物为 unsigned，不能直接安装；GitHub Actions 会自动使用仓库 Secrets 完成签名。

## 说明

- 本插件保留原插件的图片、搜索和章节处理逻辑，并使用 LightNovelReader 1.2.2 支持的 API 3。
- 切换站点会保留书架与阅读进度；已经离线缓存的章节不会自动转换，在线打开后会由所选站点内容刷新。
- 图片仍然按原插件的 `simpleText` / `image` 组件顺序输出，因此文内插图位置保持不变。
- 数据源来自第三方站点。本项目与任何内容提供方没有关联，请自行判断版权和来源风险。
- Release 产物使用 release keystore 签名。

## 致谢

- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
