# 开发与验证

[文档索引](README.md) · [当前架构](architecture.md)

以下命令均在插件仓库根目录执行。依赖版本以 [版本目录](../gradle/libs.versions.toml) 为准，Android 与签名配置见 [plugin/build.gradle.kts](../plugin/build.gradle.kts)。

## 环境

- JDK 17，设置 `JAVA_HOME` 指向可用 JDK；项目的 Java / Kotlin 编译目标为 17。
- Android SDK Platform 36、Build Tools 36.0.0 和 Platform Tools；设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。
- 使用仓库自带 `./gradlew`，无需另装全局 Gradle。
- Python 3 和 ADB 用于宿主测试；设备上需已安装官方包名的 API 3 LightNovelReader。

首次构建需要解析并下载依赖。依赖缓存完整后可追加 `--offline`；宿主测试脚本内部默认使用离线构建。

## 构建与本地检查

构建可安装的 Debug 插件：

```bash
./gradlew :plugin:assembleDebug
```

运行 JVM 测试、两种构建和完整 Release lint：

```bash
./gradlew :plugin:testDebugUnitTest :plugin:assembleDebug :plugin:assembleRelease :plugin:lintRelease
```

| 内容 | 位置 |
| --- | --- |
| Debug 插件 | `plugin/build/outputs/apk/debug/plugin-debug.apk.lnrp` |
| 已签名 Release 插件 | `plugin/build/outputs/apk/release/plugin-release.apk.lnrp` |
| 未签名 Release 插件 | `plugin/build/outputs/apk/release/plugin-release-unsigned.apk.lnrp` |
| JVM 测试报告 | `plugin/build/reports/tests/testDebugUnitTest/index.html` |
| JVM 测试 XML | `plugin/build/test-results/testDebugUnitTest/` |
| Release lint 报告 | `plugin/build/reports/lint-results-release.html` |

`assembleDebug` 和 `assembleRelease` 自动执行对应的 `verifyDebugHostAbi` / `verifyReleaseHostAbi`。这些任务检查插件类和 DEX 中的已知不兼容引用，包括 Route、协程同步 API 与部分 Base64 API；它们不读取实际宿主 APK，也不证明全部运行时接口兼容。

## 两层行为测试

| 测试 | 入口 | 覆盖范围 |
| --- | --- | --- |
| JVM 测试 | `:plugin:testDebugUnitTest` | 解析、分页取消和隔离、本地 HTTP 服务、元数据淘汰、迁入规则等 |
| Android 宿主测试 | [run-host-tests.py](../scripts/run-host-tests.py) | 实际 Android / API 3 类型下的书籍映射，以及搜索、探索与元数据组合行为 |

`*HostTest` 与普通单测一起编译，但从 Gradle 的 JVM 测试执行中排除，因为它们需要真实 Android `Uri` 和宿主类型。只运行 JVM 测试不会执行这部分用例。

### 运行宿主测试

先启动测试设备或模拟器，安装官方包名 `indi.dmzz_yyhyy.lightnovelreader` 的 API 3 宿主。用实际设备序列号替换示例：

```bash
adb devices
python3 scripts/run-host-tests.py --serial emulator-5556
```

默认会离线构建 Debug 插件和测试类。确认已有产物与当前源码一致时，可以跳过构建：

```bash
python3 scripts/run-host-tests.py --serial emulator-5556 --no-build
```

脚本把 `*HostTest` 和 JUnit 打包为临时 DEX，通过 `app_process` 从设备上实际的宿主 APK 和本次插件 APK 加载运行时类型。脚本不安装插件、不启动宿主、不修改宿主业务数据，结束时清理自己的设备临时目录。

测试使用固定网页输入，验证实际类加载及组件组合；真实站点、插件界面与签名覆盖升级需要另外验收。某一宿主版本通过也不能推导全部 API 3 发行包都通过。

### 定位测试入口

- 分页：`LinovelibExplorePageProviderTest`、`LinovelibPaginationSessionTest`、`LinovelibRelatedSearchTest`。
- 列表页识别：`LinovelibListPageTest` 覆盖统一解析结果、分页信息，以及搜索和展开页的共同识别约定；栏目投影由 `LinovelibMetadataFlowHostTest` 覆盖。
- HTTP：`LinovelibHttpClientTest`、`LinovelibHttpClientRequestTest`、`LinovelibChapterHttpTest`、`LinovelibChapterRedirectTest`。
- 元数据与映射：`LinovelibMetadataStoreTest`、`LinovelibMetadataFlowTest`、`LinovelibBookDetailsTest`、`LinovelibBookDetailsHostTest`、`LinovelibBookMappingHostTest`、`LinovelibMetadataFlowHostTest`。
- 迁入：`migration` 测试包及 `plugin/src/test/resources/migration/` 中的快照夹具。

测试源码统一位于 [plugin/src/test](../plugin/src/test)。有日期的执行结果见 [文档索引中的历史记录](README.md#历史记录)，不以测试声明数量代替执行结果。

## 签名与发行

本地 Release 签名读取四个环境变量：

```bash
export SIGNING_KEYSTORE_PATH=/path/to/release.keystore
export SIGNING_STORE_PASSWORD='your-store-password'
export SIGNING_KEY_ALIAS='your-key-alias'
export SIGNING_KEY_PASSWORD='your-key-password'
./gradlew :plugin:assembleRelease
```

四项全部提供才启用 Release 签名；未配齐时生成 unsigned 产物，不能直接安装。Debug 签名也不能替代正式发行签名做覆盖升级验证。

[GitHub Actions](../.github/workflows/build.yml) 的现有行为：

- 推送 `v*` 标签会触发构建；手动运行需要指定已经存在的标签。
- 流水线使用仓库 Secrets 签名，执行 JVM 测试与 Release 构建，上传工作流产物；不会运行 Android 宿主测试或显式的完整 `lintRelease` 任务。
- 标签推送或手动启用 `publish` 时会进入 Release 步骤；手动选项默认开启。不存在的 Release 创建为草稿，已有 Release 则更新说明并替换同名附件。
- 标签去掉 `v` 后必须与 `versionName` 一致。正式覆盖升级还需要递增 `versionCode`、保持包名和签名，并单独验证旧数据。

## 历史设置页 ABI 工具

[check-settings-abi.py](../scripts/check-settings-abi.py) 仅比较实际 APK 的 `PageContent` 描述符，用于 [历史设置页排查](archive/2026-09-24-release-settings-abi.md)。当前插件已不提供该设置页，不把这项比较作为当前功能的必过检查，也不把它当成标签导航或整体 ABI 检查。

## 文档改动检查

调整目录后检查相对链接与标题锚点，避免留下旧的 `superpowers/specs` 路径。现行说明与历史结果分别维护；无需为纯文档整理重跑 Android 构建。

```bash
git diff --check
```
