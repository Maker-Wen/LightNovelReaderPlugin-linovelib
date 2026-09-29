# LightNovelReaderPlugin-linovelib

一个面向 [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader) 的 Linovelib 数据源插件，统一使用当前可用的简体站入口。

> 本项目继续使用 Apache License 2.0，详见 [LICENSE](LICENSE)。

[![License](https://img.shields.io/badge/license-Apache--2.0-green)](LICENSE)
[![LightNovelReader](https://img.shields.io/badge/LightNovelReader-plugin-blue)](https://github.com/dmzz-yyhyy/LightNovelReader)
[![Plugin API](https://img.shields.io/badge/Plugin%20API-3-orange)]()
[![Android](https://img.shields.io/badge/Android-7%2B-3DDC84?logo=android&logoColor=white)]()
[![Release](https://img.shields.io/github/v/release/Maker-Wen/LightNovelReaderPlugin-linovelib?include_prereleases&label=release)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)
[![Downloads](https://img.shields.io/github/downloads/Maker-Wen/LightNovelReaderPlugin-linovelib/total?label=downloads)](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases)

## 下载

当前已发布版本为 [2.1.0](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases/tag/v2.1.0)，面向 LightNovelReader 1.2.2 / API 3。以下功能、兼容性及已知问题均以该发布版本为准；自行构建请使用 `v2.1.0` 标签，步骤见[构建](#构建)。

## 功能

- 提供推荐、文库、排行、完结四个栏目，支持实际书单的更多入口
- 文库展开页提供地区、主题、排序、动画、字数和状态六项单选筛选，切换条件后从第一页重新加载；主题筛选存在[已知操作限制](#已知问题)
- 支持书名、作品 ID、作者和标签搜索；先显示列表结果，再补全详情，详情失败不丢弃已找到的书
- 支持完整章节加载
- 支持章节插图，图片保持文内显示
- 支持离线缓存和 EPUB 导出
- 提供作品标签和作者的相关搜索入口；无已知书单链接时只检索书籍 ID，由本体加载详情。入口可用性受宿主限制，出版社当前仅显示，详见[已知问题](#已知问题)
- 统一使用 `https://www.bilinovel.net` 简体入口；繁体显示使用本体的“简繁转换”设置

## 与上游插件的区别

| 项目 | 上游 Linovelib TW | 本项目 |
|---|---|---|
| 插件名 | Linovelib TW | Linovelib |
| applicationId | `io.nightfish.lightnovelreader.plugin.linovelib` | `io.nightfish.lightnovelreader.plugin.linovelib` |
| 数据源 id | `linovelib_tw.hashCode()` | `linovelib.hashCode()`（`-1488977864`） |
| 内容来源 | 简体正文转繁体输出 | 简体站原文 |
| 版本名 | 1.1.2 | 2.1.0 |

本项目与上游插件使用相同 applicationId，不能同时安装。

## 兼容性

- LightNovelReader 1.2.2
- Plugin API 3
- Android 7（API 24）及以上

`2.1.0` 保留本项目 `2.0.0` 的包名和数据源 ID，版本号递增，可由使用相同发行签名的新包覆盖升级。API 4 适配另行处理。

所有支持的 Android 版本均按搜索词原文请求站点，不做繁简转换。站点返回的书籍内容保持原文。

## 已知问题

已发布的 `2.1.0` 存在以下两项已知限制：

1. 主题筛选弹窗无法完整操作。API 3 宿主的单选弹窗不能滚动，61 个主题选项会挤占确认按钮空间，部分选项无法到达。LightNovelReader `1.2.2a` 模拟器已复现；当前不保证主题筛选可正常应用，地区等较短选项的筛选可用。后续需调整筛选交互或配套修复宿主弹窗，并重新验证。
2. 出版社只显示，无法点击进入关联书单。`2.1.0` 使用的宿主出版社字段不支持点击；恢复出版社入口的修复尚未发布。正式宿主 `1.2.2a` 的标签接口兼容问题也仍需单独解决。

## 安装

从本项目 `2.0.0` 升级时直接导入新包，不要先卸载。覆盖升级需要新旧包签名一致。

1. 从 [Releases](https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib/releases) 下载 `*.apk.lnrp`
2. 使用 LightNovelReader 打开该文件
3. 在「扩展插件」中启用 `Linovelib`
4. 在数据源中选择 `Linovelib`
5. 如需繁体显示，在本体阅读设置中开启“简繁转换”

沿用正式 `2.0.0` 的章节编号和图片缓存目录，同源覆盖升级不改写阅读进度或目录。

## 构建

环境要求：

- JDK 17
- Android SDK 36

当前 `main` 分支源码仍为 `2.0.0`。构建 `2.1.0` 请先获取对应发布标签的源码：

```bash
git clone --branch v2.1.0 --depth 1 https://github.com/Maker-Wen/LightNovelReaderPlugin-linovelib.git
cd LightNovelReaderPlugin-linovelib

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

Release 签名通过上面的四个环境变量注入。未提供签名变量时产物为 unsigned，不能直接安装；本仓库发布工作流在配置签名 Secrets 后，会通过 GitHub Actions 完成签名。

## 致谢

- [LightNovelReader](https://github.com/dmzz-yyhyy/LightNovelReader)
- [j955229/LightNovelReaderPlugin-linovelib](https://github.com/j955229/LightNovelReaderPlugin-linovelib)

## License

Apache License 2.0，详见 [LICENSE](LICENSE)。
