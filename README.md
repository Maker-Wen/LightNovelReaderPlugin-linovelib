# LightNovelReaderPlugin-linovelib-simplified

一个面向 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 的 **Linovelib 简体输出**数据源插件。

本项目 fork 自 [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)，在原插件基础上只调整了繁简转换方向和数据源标识，使 Linovelib 内容输出为简体中文。

> 原项目版权归原作者所有。本项目继续使用 Apache License 2.0，详见 [LICENSE](LICENSE)。

[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![LightNovelReader](https://img.shields.io/badge/LightNovelReader-plugin-blue)](https://github.com/dmzz-yyhyy/LightNovelReader)
[![Plugin API](https://img.shields.io/badge/Plugin%20API-2-orange)]()
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)]()
[![Release](https://img.shields.io/github/v/release/Maker-Wen/LightNovelReaderPlugin-linovelib?include_prereleases&label=release)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)
[![Downloads](https://img.shields.io/github/downloads/Maker-Wen/LightNovelReaderPlugin-linovelib/total?label=downloads)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)

## 下载

最新版本：[v1.1.2-simplified](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases/tag/v1.1.2-simplified)

直接下载：[Linovelib-Simplified-Standalone-1.1.2.apk.lnrp](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases/download/v1.1.2-simplified/Linovelib-Simplified-Standalone-1.1.2.apk.lnrp)

SHA-256：

```text
024593837c268d1bcff97a84cec4765d55e783bdbfbbe029aacf08d126f71df5
```

也可以按下方[构建](#构建)步骤自行生成。

## 功能

- 支持 Linovelib 的探索、排行榜和完本页面
- 支持书名、作品 ID、作者和标签搜索
- 支持完整章节加载
- 支持章节插图，图片保持文内显示
- 支持离线缓存和 EPUB 导出
- 支持点击作品标签、作者和出版社跳转相关搜索
- **正文默认输出简体中文**

## 与上游插件的区别

| 项目 | 上游 Linovelib TW | 本项目 |
|---|---|---|
| 插件名 | Linovelib TW | Linovelib 简体 |
| applicationId | `io.nightfish.lightnovelreader.plugin.linovelib` | `io.nightfish.lightnovelreader.plugin.linovelib_simplified` |
| 数据源 id | `linovelib_tw` | `linovelib_simplified` |
| 文字转换 | `toTraditional` | `toSimplified` |
| 版本名 | 1.1.2 | 1.1.2-simplified |

因为 applicationId 和数据源 id 都不同，本项目可以和上游的 **Linovelib TW** 插件同时安装、同时使用。

## 兼容性

- LightNovelReader 1.2.x
- Plugin API 2
- Android 10（API 29）及以上

繁简转换使用 Android ICU 的 `Traditional-Simplified`。Android 10 以下系统可能无法转换，文字会保持原文。

## 安装

1. 从 [Releases](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases) 下载 `*.apk.lnrp`
2. 使用 LightNovelReader 打开该文件
3. 在「扩展插件」中启用 `Linovelib 简体`
4. 在数据源中选择 `Linovelib 简体`

原版 `Linovelib TW` 可以继续保留。由于这是独立数据源，原版 TW 数据源下已经添加的书不会自动切换，需要在 `Linovelib 简体` 下重新搜索和添加。

## 构建

环境要求：

- JDK 17
- Android SDK 36

构建：

```bash
./gradlew :plugin:assembleDebug
```

产物：

```text
plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp
```

## 说明

- 本插件只修改繁简转换方向和插件标识，不修改原插件的图片、缓存、搜索和章节处理逻辑。
- 图片仍然按原插件的 `simpleText` / `image` 组件顺序输出，因此文内插图位置保持不变。
- 数据源来自第三方站点。本项目与任何内容提供方没有关联，请自行判断版权和来源风险。

## 致谢

- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
