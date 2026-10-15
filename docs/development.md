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

修改公开插件回调或其参数类型时，另核对实际发行宿主与插件 DEX 的方法描述符，并验证调用结果。宿主 R8 混淆可能改变参数描述符，仅存在同名类或源码方法不足以证明覆盖了宿主入口。当前插件没有站点设置页；ABI 验收应覆盖实际存在的入口，如作者与标签导航。

## 两层行为测试

| 测试 | 入口 | 覆盖范围 |
| --- | --- | --- |
| JVM 测试 | `:plugin:testDebugUnitTest` | 解析、分页取消和隔离、本地 HTTP 服务、元数据淘汰、迁入规则等 |
| Android 宿主测试 | [run-host-tests.py](../scripts/run-host-tests.py) | 实际 Android / API 3 类型下的书籍映射、搜索与元数据组合，以及迁入正文和日期兼容 |

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
- 迁入：`MigrationPlannerTest`、`MigrationSnapshotCodecTest`、`MigrationReceiptTest`、`MigrationMessagesTest`、`LinovelibMigrationSessionTest` 等覆盖规则、归属、凭据、用户提示与会话；`MigrationBodyHostTest` 覆盖实际宿主正文及日期。快照夹具位于 `plugin/src/test/resources/migration/`。

测试源码统一位于 [plugin/src/test](../plugin/src/test)。已核对的执行结果见 [最近验收基线](#最近验收基线)，不以测试声明数量代替执行结果。

### 设备迁入验收

上述宿主测试不执行实际迁入事务或界面流程。修改迁入逻辑后，另在明确指定的可丢弃模拟器中验收，使用合成输入或历史备份副本；保留原备份和日常设备状态。记录宿主版本、官方 APK 哈希、实际加载的插件哈希、Android API 和 schema，测试结果仅对应这些基线。

| 场景 | 核对内容 |
| --- | --- |
| TW 或当前源为活动源 | 只使用活动数据库和另一源的权威快照；成功后选中当前源，业务结果与完成凭据一起生效 |
| 部分损坏与快照归属 | 验证可恢复字段、当前个人记录保护、标准 TW 与 `-1` 的选择；输入不可安全使用时保持原数据 |
| 完成凭据与重复启动 | 已完成、不可信凭据、无可恢复数据及非活动目标已完成分别按规则处理；重开不重复累计 |
| 保存失败与进程中断 | 在合成数据库副本中注入 SQL 失败或延迟，比较事务前后业务表、选中源、凭据、快照和共享图片；不向原备份或日常数据库注入故障 |
| 前后台与页面重建 | 检查旧回调过滤、同一在途尝试及实际事务结果；按迁入规则验证确认未提交后的手动重试与结果不明时重启的区别 |
| 重开与离线阅读 | 核对书架、阅读位置、统计及正文可读性，不以出现成功弹窗代替数据核验 |

每轮使用独立证据目录，保存实际报告、必要日志及数据摘要。设备专用实验脚本和原始数据库不作为公开构建依赖；已有结果与未覆盖范围见 [最近验收基线](#最近验收基线) 和 [已知验证边界](#已知验证边界)。

## 最近验收基线

### 2.2.0 正式签名覆盖升级

2026-10-15，正式 Release `2.1.1 / 35` 覆盖升级到 `2.2.0 / 36` 的四个场景通过。候选包来自提交 `8458c38967c49502ab0e61a92b6cc6036bef9ea0` 的 GitHub Actions 构建 `38698841673`；新旧包均通过签名校验且不可调试，使用相同正式签名证书。

| 项目 | 实际基线 |
| --- | --- |
| 旧正式包 SHA-256 | `903e053759e04408d03f6344607e28d5d275c0d9bd67ca980bc786264b2313f6` |
| 2.2.0 候选包 SHA-256 | `1039c28ecc345dfafd3822cf949e79f57d2bbd96f912a392201687c5a404dd76` |
| 正式签名证书 SHA-256 | `3fed37373f36b973255b35efbdc0f655f84537811241fb96851041330a7c63ac` |
| 宿主 | 未修改的官方 API 3 `1.2.3c`，schema 17 |
| 宿主 APK SHA-256 | `10b49335ff81f066451b2ca6a329fe950f5443854cc9559bdc9185ddc4418854` |
| 运行环境 | Android API 37 隔离模拟器，仅使用合成数据 |

四案均从 Files 内部存储的 Download 目录实际打开 `.lnrp`，由宿主安装流程读取旧插件 metadata、版本与签名后覆盖。验证时没有安装可被宿主重新发现的 Android 插件 APK；实际宿主插件文件核对为上述正式候选包。

| 旧版状态与输入 | 实际结果 |
| --- | --- |
| 已启用，当前源无 TW 数据 | 覆盖接受正式旧签名；重新启用后不发生迁入，冷启动及再次启动的 14 张表严格不变 |
| 已禁用，当前源无 TW 数据 | 覆盖后仍禁用；冷启动没有意外迁入，14 张表和源快照保留 |
| 已启用，当前源有 TW 快照 | 覆盖前后旧业务数据保留；热启用不迁入，冷启动确认后精确合并一次，第二次冷启动的 14 张表严格不变 |
| 已启用，TW 为当前活动源 | 冷启动确认后精确合并到当前 Linovelib；完成凭据与业务数据一致，第二次冷启动不重复累计 |

已启用插件覆盖时，官方宿主会卸载旧实例并将其禁用；本轮通过 Settings → Plugins → Linovelib 的真实开关重新启用，再冷启动验证迁入。安装前后比较单独识别该预期的启用状态变化；重新启用时 `enabledPlugins` 可能出现前导逗号，按包名集合核对。迁入完成后的重复启动则对全部 14 张表逐字段严格比较。

四案的源快照均保持原字节，非空共享图片 canary 在旧版与升级后均存在且 SHA-256 相同；图片文件保留不等于已验证界面图片渲染。

正式候选包另通过两项补验：关闭 Wi-Fi 和移动数据后，真实阅读界面打开迁入的章节 `5016` 并显示缓存正文 `tw body 5016`，实际插件文件仍为上述候选 SHA-256；Android 包管理独立安装旧正式 APK 后，通过 `install -r` 覆盖到新正式 APK，应用 UID `10232` 不变，系统安装包 SHA-256 与候选一致。宿主 `.lnrp` 覆盖与 Android APK 包管理覆盖是两层流程，本轮分别验收。离线阅读发生在 14 张表的重复启动严格比较之后，阅读计时可产生正常写入，不将阅读后的数据声明为逐字段不变。

原始材料保留在本地 `release-2.2.0` 证据目录，不随仓库附带；包校验入口为 `package-verification.json`，SHA-256 为 `6f3010ebaec07ff50886b3b9f4905f4784f1dc0d1e5e2494c1059ce753784fca`；四案结果位于 `upgrade/cases/*/proof.json`，补验入口为 `upgrade/offline-reading/proof.json` 和 `upgrade/android-package-upgrade/proof.json`。用户发布说明见 [2.2.0 发布说明](releases/v2.2.0.md)。

### 此前 Debug 数据迁入验收

此前完整的可恢复数据迁入验收对应 `a86c0a1`，插件为 API 3、Debug `2.1.1 / 35`，运行于 Android API 37 隔离模拟器和未修改的官方宿主 `1.2.3c`。该轮用于覆盖更多恢复输入和故障分支，不能与正式签名升级四案合并统计。最终插件 APK SHA-256：

```text
f1edbf032b746d053e7788ac6d31f93ba638ffc3f4e311259032d31acd6a9c31
```

该轮整理时已核对原始报告及有效设备断言；冻结的 75 个源码与测试文件摘要全部与 `a86c0a1` 一致。当前文档整理引用该历史执行结果，没有重跑整套 Debug 设备矩阵。

| 层次 | 已有结果 |
| --- | --- |
| JVM | 242 项通过，0 failure / error / skipped |
| 实际官方宿主 | 19 项通过，包含迁入正文与损坏日期兼容回归 |
| Debug 构建与 Host ABI | 通过 |
| 隔离设备 | 17 个不同合成场景、原始备份副本及离线正文验证，合计 23 个计划检查点；加 3 个专项补验，共 26 个实际执行检查点，包含 5 次实际冷启动 |

26 个检查点包含冷启动重复检查和补验，不表示 26 个不同场景。设备覆盖快照优先与归属、局部损坏恢复、当前个人记录和 SQL 原始值保护、无有效内容、不可信凭据、原子提交、凭据失败回滚及冷启动幂等。原始备份只验证了一个样本的隔离副本，原文件保持未修改；逐表结果、10 份源归档和 17 条全局设置核对通过。离线验证通过真实界面打开已有缓存目录和正文，未验证原始图片。

更早的 `580cb39` 会话改造在 Android 30 / 37、部分官方 `1.2.1` 至 `1.2.3c` 组合上取得 73 项设备场景结果，覆盖后台撤权、Activity 重建、SQL 失败与进程中断恢复；这套矩阵没有在 `a86c0a1` 上完整重跑，不能代替当前版本的多宿主、生命周期和交互重测。

本地原始报告不随仓库附带。需要定位该轮材料时，核对 `final-build-evidence.json` 的 SHA-256 `fb9fb1c7d73a811b1c1be47ba829868cd3af7ff6d03604ce744485226f51b7d8` 和 `summary.json` 的 SHA-256 `95dd4e38b0670ac4cf893b5810cad239cc2a91fdac52569c73422c07c39c8cbf`。

### 迁入中的外部入口补验

2026-10-15，提交 `2ccd0b7290cc23c8ab7004c6e1783424f8145190` 的 Debug `2.1.1 / 35` 在 Android API 37 / 官方 `1.2.3c` 上完成三个真实外部入口场景。APK SHA-256 与此前 Debug 迁入基线相同；该结果仍属于 Debug 在途交互补验，正式签名升级四案没有重跑这些时序。

| 外部操作 | 实际结算与数据结果 |
| --- | --- |
| Files 打开有效同包插件后取消安装 | 安装窗口覆盖在途迁入；取消后原事务自然完成，14 张表与正常合并对照一致，冷启动无重复累计 |
| Chrome 打开离线商店错误窗口后关闭 | 外部窗口可操作；关闭后原事务自然完成，14 张表与正常合并对照一致，冷启动无重复累计 |
| Files 确认同包覆盖安装 | 旧实例卸载，原事务自然回滚；排除宿主预期的启用状态变化后，14 张表与输入一致；重新启用并冷启动后迁入一次，再次启动不重复累计 |

外部案例均留存同一进程的 SQLite 写入线程栈与独立已提交视图；等待事务自然结算后再冷启动，未通过杀进程制造通过结果。源快照保留；正常合并对照之间只存在每次提交的 `receipt.commitId` 差异。三个入口加无外部入口对照共四个有效场景，完成六个结果或恢复阶段的实际冷启动检查。本地证据入口为 `external-entry-validation/report.md`。

## 已知验证边界

- 最新设备结果限于上述 Android API 37 / 官方 `1.2.3c` 基线；真实设备、旧宿主矩阵重跑、低内存和 64 MiB 临界输入仍未覆盖。正式签名升级只覆盖上述四案，单一样本的备份副本通过也不代表全部历史备份都可恢复。
- 2026-10-15 外部入口补验覆盖 Files 取消、Files 同包安装及 Chrome 离线错误窗口关闭；未发现所测路径的数据损坏或重复累计，但外部窗口仍可覆盖迁入进度并接受操作。没有增加新 Intent 拦截或窗口焦点隔离策略，不宣称排他隔离、所有外部 Intent 或全部时序已覆盖；Chrome 商店联网下载与成功商店安装尚未验证。
- Activity 为 resumed、迁入弹窗存在，不能单独证明后续窗口受排他限制。事务最后一次准入检查与生命周期变化不是原子操作；检查后发生 pause / unload 不保证回滚，会话按权威存储结果结算。
- 未知事务退出、调度拒绝和弹窗显示失败的部分分支由可控 JVM 测试覆盖，不等同于真实设备故障注入。结果不明时按 [当前迁入规则](tw-data-migration.md) 要求重启，不将其记为未提交。
- 宿主整包 `.lnr` 的 Overwrite / Merge 导入是另一条流程，需要单独验收；插件迁入事务及备份副本的结果不能证明整包导入安全。正式候选包已验证一个迁入章节的离线正文界面和非空图片文件保留，尚未验证图片渲染、全部章节及真实站点联网行为。

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
- 流水线使用仓库 Secrets 签名，执行 JVM 测试、Release 构建及完整 `lintRelease`，上传工作流产物；不运行 Android 宿主测试或设备升级验收。
- 标签推送或手动启用 `publish` 时会进入 Release 步骤；手动选项默认开启。说明读取版本专用的 `docs/releases/<标签>.md`，文件缺失即失败，并附加最终包 SHA-256。
- 不存在的 Release 创建为草稿；已有草稿可更新说明并替换同名附件。已有公开 Release 拒绝覆盖附件或说明，避免重跑构建替换已经发布的包。
- 标签去掉 `v` 后必须与 `versionName` 一致。正式覆盖升级还需要递增 `versionCode`、保持包名和签名，并单独验证旧数据。

## AI 协作的验证选择与完成标准

先根据改动选择验证层次，涉及多个范围时合并对应检查。改动迁入归属、数据保护、事务或提示前，读取 [当前迁入规则](tw-data-migration.md)；修改接口、职责或依赖前，读取 [当前架构](architecture.md)。

| 改动范围 | 应执行的检查 |
| --- | --- |
| 纯文档 | [文档改动检查](#文档改动检查) |
| 解析、分页、HTTP、元数据或迁入计算 | 对应 JVM 用例与 Debug 构建；测试入口见 [定位测试入口](#定位测试入口) |
| 宿主类型、正文组件、日期或类加载适配 | JVM / Debug 检查，加 [实际宿主测试](#运行宿主测试) |
| 迁入事务、会话生命周期或交互 | 计算与宿主检查，加 [设备迁入验收](#设备迁入验收) 中受影响的场景 |
| 网络行为或真实站点兼容 | 对应自动测试，加目标环境的真实请求与界面验证 |
| 签名、构建配置或发行 | JVM、Debug / Release 构建、完整 Release lint；涉及覆盖升级时另验签名、版本和旧数据 |

完成报告应列出本轮实际执行的命令与结果；设备结果附宿主、Android API、插件 APK 摘要及数据核验范围。历史通过结果按 [最近验收基线](#最近验收基线) 引用，未执行的必要层次明确列为待验收，并说明原因。每项改动都应能对应到已通过的检查或具体待验收项，不能用构建成功代替运行结果。

## 文档改动检查

检查相对链接、标题锚点及引用的命令和规则是否与当前源码一致；调整目录后清理旧路径。纯文档改动执行 Markdown 规范检查和下列检查，不重跑 Android 构建或设备测试。

```bash
git diff --check
```
