# LightNovelReaderPlugin-linovelib

面向 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) API 3 的 Linovelib 数据源插件，使用 `https://www.bilinovel.net` 简体入口。繁体显示由阅读器的“简繁转换”设置提供。

[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![LightNovelReader](https://img.shields.io/badge/LightNovelReader-plugin-blue)](https://github.com/dmzz-yyhyy/LightNovelReader)
[![Plugin API](https://img.shields.io/badge/Plugin%20API-3-orange)](#兼容性)
[![Android](https://img.shields.io/badge/Android-7%2B-3DDC84?logo=android&logoColor=white)](#兼容性)
[![Release](https://img.shields.io/github/v/release/Maker-Wen/LightNovelReaderPlugin-linovelib?include_prereleases&label=release)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)
[![Downloads](https://img.shields.io/github/downloads/Maker-Wen/LightNovelReaderPlugin-linovelib/total?label=downloads)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)

## 功能

- 推荐、文库、排行、完结四个栏目，以及实际书单的更多入口。
- 文库展开页提供地区、主题、排序、动画、字数和状态六项单选筛选；切换条件后从第一页重新加载。
- 支持关键词、作品 ID 和书籍链接搜索；关键词可用于书名、作者和标签。先显示列表，再补全详情，详情失败保留已找到的书。
- 完整章节、文内插图、离线缓存和 EPUB 导出。
- 作者和作品标签可进入关联书单；没有已知链接时回退到关键词搜索。
- 当前代码支持一次性本地迁入上游 TW 数据，详见 [迁入规则](docs/tw-data-migration.md)。

## 兼容性

| 项目 | 约定 |
| --- | --- |
| 宿主 | LightNovelReader API 3；编译契约为 `api:0.3`，API 4 适配另行处理 |
| 系统 | Android 7 / API 24 及以上 |
| 插件包名 | `io.nightfish.lightnovelreader.plugin.linovelib` |
| 数据源 ID | `"linovelib".hashCode()`，即 `-1488977864` |
| 升级身份 | 沿用本项目 `2.0.0` 的包名、源 ID、章节编号和图片目录；覆盖升级还要求签名一致 |

本项目与上游 Linovelib TW 使用相同包名，不能同时安装。TW 的源 ID 为 `282781674`，与本项目不同；其本地数据按 [TW 数据迁入](docs/tw-data-migration.md) 处理。

仓库代码的版本字段见 [构建配置](plugin/build.gradle.kts)，已发布版本以 [Releases](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases) 为准。分支中的改动不代表已包含在发行包中。

## 下载与安装

1. 从 [Releases](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases) 下载 `*.apk.lnrp`。
2. 使用支持 API 3 的 LightNovelReader 打开该文件。
3. 在“扩展插件”中启用 `Linovelib`。
4. 在数据源中选择 `Linovelib`。
5. 如需繁体显示，在阅读设置中开启“简繁转换”。

从本项目 `2.0.0` 升级时直接导入同签名的新包，不要先卸载。自行构建见 [开发与验证](docs/development.md)。搜索词和站点返回内容均保持原文，插件不做繁简转换。

## 已知限制

主题筛选、作者与标签导航、连续滚动问题来自 2026-09-25 的官方宿主 `1.2.2a` 设备记录；出版社入口来自同轮旧宿主源码检查。后续宿主与当前插件组合未逐项复测，以下内容作为待验证问题保留。

| 场景 | 已知表现与证据范围 |
| --- | --- |
| 主题筛选 | 61 个主题选项超出不可滚动弹窗，部分选项和确认操作不可达；地区等较短选项可用 |
| 作者与标签导航 | 宿主混淆后的回调描述符与插件不匹配，点击可能无法进入关联页 |
| 出版社关联入口 | 当前映射保留出版社字段，未额外生成出版社标签；旧 API 3 宿主 `30458c` 的原生出版社点击回调为空，因此可能只展示而不能进入书单。这是曾暂缓处理的问题，尚无最新组合的独立设备复测 |

## 致谢

- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
