# 正式宿主插件设置页空白

## 已确认原因

设备为 `emulator-5554`，安装的正式宿主为 LightNovelReader `1.2.2a`，versionCode `10202002`。日志已出现 `Linovelib plugin loaded`，并开始数据源请求；设置页控件树只有宿主标题和页签，没有插件设置内容。

从该设备实际 APK 提取的接口与插件实现如下：

```text
宿主接口及调用：PageContent(Lhz2;Lhe0;I)V
插件实现：      PageContent(Landroidx/compose/foundation/layout/PaddingValues;Landroidx/compose/runtime/Composer;I)V
```

宿主 R8 混淆了参数类型，完整方法描述符不一致。插件实现未覆盖宿主接口，执行的是接口默认的空实现，因此没有异常，也没有设置开关。独立审查复核了同一份字节码。

这是正式宿主的动态插件接口兼容问题，不是 Debug 签名造成，也不是插件没有实现开关。更改插件 UI、改用 Release 插件包不能恢复宿主已经混淆的接口类型。

## 可重复检查

使用 Android SDK 的 `apkanalyzer`，并设置可用的 `JAVA_HOME`：

```bash
python3 scripts/check-settings-abi.py /path/to/host.apk /path/to/plugin.apk.lnrp
```

该检查比较实际 DEX 中的 `PageContent` 描述符。当前正式宿主与插件组合返回 `FAIL`、退出码 1，与设备空白页一致。描述符匹配仅证明入口一致，不替代设备渲染检查。

另以本地未混淆的 `1.3.0` Debug APK 验证检查器的匹配分支，返回 `PASS`、退出码 0；这仅是检查器的正例，不代表该不同 API 版本已完成插件兼容验证。

## 为什么 1.3 能显示

设备另一个宿主包为 `indi.dmzz_yyhyy.lightnovelreader.debug`，版本 `1.3.0-10300008`，包标志含 `DEBUGGABLE`。对应 Debug 构建未开启 R8 混淆，设置入口保留原始类型。

此外，1.3 当前已提交源码的 `app/proguard-rules.pro` 已包含对 API 的 `includedescriptorclasses` 保护，以及对 `androidx.**` 类型与成员的保留规则；这一层也覆盖 Compose。不能只把差异归因于 Debug：1.3 源码确实还增加了旧正式版缺失的保护。

因此下文建议仅针对需要继续支持的 API 3 旧宿主分支；当前 1.3 无需重复添加同样规则。1.3 Release 的设备运行结果仍需实际构建验证，Debug 设置页可显示不等于全部 API 4 功能已验收。

此前 88 项单元测试和 `verifyHostAbi` 构建检查通过，但后者仅检查已知的类引用禁用项，并未读取实际正式宿主的 UI 接口，因此没能发现这项发布兼容问题。

## 本体修复方案

用户在根因与方案确认后，已授权实施本体修复并生成测试包，设备安装和手测由用户完成。

已查到准确发布基线：[上游 1.2.2a 标签](https://github.com/dmzz-yyhyy/LightNovelReader/tree/1.2.2a)，提交 `30458c2215691c0f588705ea87eba0ad081cb079`；其 `versionName=1.2.2a`、`versionCode=10202002`、`API_VERSION=3` 与设备一致。此前 `6ea57a9` 只是 API 3 接口参考，不能当作本次发布源码；准确发布版本的混淆规则与该参考相同。

建议从准确发布基线创建隔离工作树，仅为 `app/proguard-rules.pro` 增加以下规则，再构建 Release 验证：

```proguard
# Shared ABI used by dynamically loaded plugins.
-keep,includedescriptorclasses class io.nightfish.lightnovelreader.api.** { *; }
-keep,includedescriptorclasses class androidx.** { *; }
```

这里沿用 1.3 的 AndroidX 共享范围：除了 Compose 设置页，现有插件还通过 `NavController` 实现标签导航，只保留 Compose 会留下同一类 Navigation 兼容风险。旧版已有的 Kotlin、协程及宿主实现保留规则继续使用，不搬入 1.3 的 API 4 或 ClassLoader 重构。修复不改变 API 契约，不需要提高 API_VERSION；正式发布时递增本体版本。

动态插件的界面调用不在宿主的静态调用图里；只保留 `PageContent` 方法名不够，参数类型和插件调用的类及成员也需要保留。规则依据见 [Android 官方保留规则说明](https://developer.android.com/topic/performance/app-optimization/add-keep-rules)。该范围会减少 AndroidX 的裁剪，包体变化需在构建时对比。

已生成[回补补丁](patches/1.2.2a-plugin-ui-abi.patch)，对准确 1.2.2a 文件执行 `git apply --check` 通过，并应用到独立修复分支。构建与包检查结果见下文，设备界面验收待用户执行。

验收必须使用重新构建的 Release 宿主：检查 DEX 描述符恢复原名，并在模拟器验证设置页显示、开关保存、底部重启提示及重启后的站点。新宿主与现有正式包的签名可能不同，不能直接假定可覆盖安装；验收包的安装方式需要保留原数据。

复用现有 `scripts/check-settings-abi.py` 比较宿主与插件的实际 APK；Release 验收再覆盖标签跳转与页面返回。后续可将这个检查接到发布构建之后，避免仅凭 Debug 和源码编译通过再次漏检。首轮不拆出 API consumer rules 或新增检查框架。

## 已完成的构建与包检查

- 修复分支：`codex/api3-plugin-settings-r8`；工作树：`/private/tmp/lnr-1.2.2a-plugin-ui-fix`，基于准确发布提交 `30458c22`。生产源码仅修改 `app/proguard-rules.pro`，尚未提交。
- 构建：`:app:assembleRelease` 成功，执行 `minifyReleaseWithR8`、资源压缩及 Release lintVital；`debuggable=false`。最终重复构建成功。
- 测试打包：临时 init 脚本仅调整 `:app` 的 Release 包名后缀、版本后缀及本地测试签名；未写进生产源码。包名 `indi.dmzz_yyhyy.lightnovelreader.pluginuitest`，版本 `1.2.2a-plugin-ui-fix`，versionCode `10202002`。
- 对照检查：原正式包返回 `FAIL`；修复包的 `PageContent(PaddingValues, Composer, int)` 与插件一致，返回 `PASS`。R8 mapping 同时确认 `PaddingValues`、`Composer`、`NavController` 保留原名。
- 签名：`apksigner verify --verbose` 通过 V2 签名验证。使用本地测试签名，与正式版独立安装，不用于正式版覆盖升级。
- 体积：原设备正式包约 5.5 MiB，测试包约 12 MiB。构建环境和保留规则都有差异，该对比不单独归因于某条规则。
- 独立审查通过；测试打包脚本已限定到 `:app`。主工作区的 1.3 源码及未提交修改未改动。

测试 APK：`/Users/happyelements/Documents/LightNovelReader/app/build/outputs/apk/plugin-ui-fix/LightNovelReader-1.2.2a-plugin-ui-fix-release.apk`。

测试应用的数据与插件目录独立。安装后在该测试版内重新导入 Linovelib 插件，验证设置开关、保存与重启，再检查标签跳转。应用图标与原版相同，可通过版本 `1.2.2a-plugin-ui-fix` 区分。此轮未替用户安装或操作模拟器。
