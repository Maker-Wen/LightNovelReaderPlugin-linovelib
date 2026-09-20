# LightNovelReaderPlugin-linovelib-simplified

一个面向 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 的 **Linovelib 简体输出** 数据源插件。

本项目 fork 自 [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)，在原插件基础上只调整了繁简转换方向和数据源标识，使 Linovelib 内容输出为简体中文。

> 原项目版权归原作者所有。本项目继续使用 Apache License 2.0，详见 [LICENSE](LICENSE)。

[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![LightNovelReader](https://img.shields.io/badge/LightNovelReader-plugin-blue)](https://github.com/dmzz-yyhyy/LightNovelReader)
[![Plugin API](https://img.shields.io/badge/Plugin%20API-4-orange)]()
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)]()
[![Release](https://img.shields.io/github/v/release/Maker-Wen/LightNovelReaderPlugin-linovelib?include_prereleases&label=release)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)
[![Downloads](https://img.shields.io/github/downloads/Maker-Wen/LightNovelReaderPlugin-linovelib/total?label=downloads)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)

## 下载

API 4 适配版为 `2.0.0`，发布产物需要按下方[构建](#构建)步骤生成。

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
| 版本名 | 1.1.2 | 2.0.0 |

因为 applicationId 和数据源 id 都不同，本项目可以和上游的 **Linovelib TW** 插件同时安装、同时使用。

## 兼容性

- LightNovelReader 1.3.x
- Plugin API 4
- Android 10（API 29）及以上

API 4 版本保留 API 2 的 `linovelib_simplified.hashCode()` 标识载荷，用于迁移旧版本的数据源选择记录。

从旧版升级时请使用相同签名的插件包，并在安装后确认插件已启用、数据源仍选择“Linovelib 简体”。升级前建议先导出应用数据；如果升级后书架为空，请先重启应用，再切换到该数据源，或使用应用的数据导入功能恢复备份。旧 API 2 的整数分区文件需要宿主侧配套兼容读取。

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

- 本插件保留原插件的图片、搜索和章节处理逻辑，并适配 API 4 的返回类型与数据源标识；繁简转换方向保持为简体输出。
- 图片仍然按原插件的 `simpleText` / `image` 组件顺序输出，因此文内插图位置保持不变。
- 数据源来自第三方站点。本项目与任何内容提供方没有关联，请自行判断版权和来源风险。
- Release 产物使用独立的 release keystore 签名。

## 致谢

- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
