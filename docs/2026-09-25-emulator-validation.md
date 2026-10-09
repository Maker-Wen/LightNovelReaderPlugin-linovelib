# Linovelib 模拟器验证记录

日期：2026-09-25。结论：基础抓取和阅读展示可用，存在两个已定位的问题和一个待定位的切章异常，不能判定完整验收通过。

本轮测试现有实现；此前架构设计尚未落地。本轮没有修改生产代码。

测试完成后，本轮源码的版本名统一为 `2.1.0`，内部 `versionCode` 保留 `34`。下文 `2.1.7` 及其包哈希是测试当时的实际记录；新版本名的构建产物不沿用旧包哈希。

## 测试基线

| 项目 | 实际值 |
| --- | --- |
| 设备 | 独立临时 AVD `Linovelib_QA_5580`，Android 17 / API 37 |
| 屏幕 | 1080 × 2424，density 420，默认字体大小 |
| 宿主 | 正式 APK 1.2.2a / 10202002，包名 `indi.dmzz_yyhyy.lightnovelreader` |
| 宿主源码参考 | `30458c2215691c0f588705ea87eba0ad081cb079` |
| 插件 | 当前工作区打包的 debug 2.1.7 / 34，API 3 |
| 构建 | `:plugin:assembleDebug --offline` 成功，包含 `verifyDebugHostAbi` |
| 站点 | 模拟器实际请求 `https://www.bilinovel.net` |

宿主 SHA-256：`a82ca522136b952a99f97ee06064313fb5d303ea398e33c400f5a7a1880f099f`。

插件 SHA-256：`a0321bcb5877deb8c41f8e1ef0c133759a3a7b139ba33678baaa6898a1e42ae6`。

这是新设备安装验证，未验证已有用户升级、正式插件签名或发布流水线。插件构建 ABI 检查通过，不代表覆盖了正式宿主所有经过混淆的接口。

## 用例结果

| 用例 | 结果 | 证据 |
| --- | --- | --- |
| 安装、启用、切换数据源 | 通过 | 插件列表显示 2.1.7，源首页 HTTP 200 |
| 推荐页、文库列表 | 通过 | 实际书名、封面与简介可见 |
| 地区筛选与分页 | 通过 | 日本轻小说的第 1、2 页均 HTTP 200，URL 保留地区条件 |
| 主题筛选 | 失败 | 61 个选项撑满弹窗，应用按钮文字不可见，后续选项无法滚动到达 |
| 关键词搜索 | 通过 | `Sword` 通过 search guard，列表最终显示 8 个结果 |
| 详情与目录 | 通过 | 《生命的吃法》书籍 4833 的详情、卷和章节列表可见 |
| 正文、插图、纵向滚动 | 通过 | 292876 章节标题、图片和正文实际显示，阅读进度变化 |
| 站点多页章节合并 | 通过 | 292876 合并 2 页、4466 字符；292877 合并 11 页、27730 字符，均在下一章边界停止 |
| 默认连续滚动下切章 | 失败，根因待定 | 点击下一章后仍停留在 #0 末尾；返回目录再选 #1 仍显示 #0 |
| 关闭连续滚动后切章 | 通过 | 重启设备后关闭开关，下一章正确显示 #1，首页阅读记录保存为 #1 |
| 详情标签导航 | 失败，已定位 | 奇幻标签点击无响应；宿主与插件方法描述符不匹配 |
| 同标签叠页后返回分页 | 未执行 | 标签导航入口被阻塞，无法建立 A → B → A 场景 |

地区分页实际请求为 `/wenku/lastupdate_0_0_0_1_0_0_0_1_0.html` 和 `/wenku/lastupdate_0_0_0_1_0_0_0_2_0.html`。搜索通过历史行正常提交后验收；ADB Enter 首次提交曾保留展开的历史面板，未将该自动化输入现象记为搜索缺陷。

## 发现 1：正式宿主的标签回调 ABI 不匹配

严重度：P1。复现：文库 → 书籍《生命的吃法》 → 点击“奇幻”。重复点击仍留在详情页，没有 `RELATED_NAV_START` 日志。

实际 APK 的方法描述符：

```text
宿主 WebBookDataSource:
progressBookTagClick(Ljava/lang/String;Lon2;)V

插件 LinovelibWebDataSource:
progressBookTagClick(Ljava/lang/String;Landroidx/navigation/NavController;)V
```

插件方法没有覆盖宿主接口方法。宿主默认实现只检查非空后返回，解释了无异常、无跳转、无插件日志的表现。BookRepository 和最后一层代理在实际 DEX 中仍转发该调用，可排除代理漏转发。

证据：[宿主接口](/private/tmp/linovelib-emulator-20260925/host-WebBookDataSource.smali:369)、[插件实现](/private/tmp/linovelib-emulator-20260925/plugin-LinovelibWebDataSource.smali:8026)、[详情截图](/private/tmp/linovelib-emulator-20260925/26-related-a-open.jpg)。没有 release mapping，不单独声称还原了 `on2` 原始类名；方法签名不一致已直接证实。

后续应先稳定宿主对插件暴露的 ABI，再用实际正式 APK 验证标签导航。仅调整插件分页实例所有权无法修复此入口问题。

## 发现 2：主题筛选弹窗无法完整操作

严重度：P2。复现：文库 → 全部轻小说 → 主题。选择“奇幻”只产生临时高亮，界面没有可见的应用按钮，垂直滑动无效，返回后主题仍为“不限”。

宿主 `FilterChipsDialog` 一次性展开全部选项，内容没有滚动；`BaseDialog` 先布局内容，再布局按钮。61 项主题中，现场 XML 只暴露 43 项，后 18 项不可达；可滚动节点为 0，按钮文字与语义尺寸被压没。仍有无标签点击区域，因此不表述为整个按钮节点都在屏幕外。

官方 1.2.2a 与当前宿主工作区相关布局逻辑相同。源码位置：[Filter.kt](/Users/happyelements/Documents/LightNovelReader/app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/components/Filter.kt:209)、[Dialog.kt](/Users/happyelements/Documents/LightNovelReader/app/src/main/kotlin/indi/dmzz_yyhyy/lightnovelreader/ui/components/Dialog.kt:90)。

证据：[弹窗截图](/private/tmp/linovelib-emulator-20260925/18-theme-selected.jpg)、[滑动后 XML](/private/tmp/linovelib-emulator-20260925/19-theme-bottom.xml)。修复应由宿主通用筛选弹窗提供可滚动内容区，并保证确认按钮始终可见。

## 发现 3：连续滚动下切章未稳定生效

严重度：P2，根因尚未确定。书籍 4833 的 #0（`linovelib-v3:292876`）正常加载，#1（292877）的网页抓取及 11 页正文解析已完成；点击下一章后仍显示 #0 尾部，进度达到 100%。返回目录直接选择 #1 后仍显示旧内容。`CHAPTER_DONE` 早于图片本地化和最终返回，不能单独证明宿主当时已收到完整预加载结果。

[切章后](/private/tmp/linovelib-emulator-20260925/35-reader-settled.xml)、[滚动后](/private/tmp/linovelib-emulator-20260925/36-reader-after-next.xml)、[目录重选后](/private/tmp/linovelib-emulator-20260925/38-reader-direct-chapter.xml) 的正文节点相同，标题均为 #0；日志继续保存 292876 的阅读进度。

设备重启并切换软件渲染后，关闭 Continuous Scrolling，再点下一章，正确显示 #1，阅读记录也保存为 #1。证据：[关闭后的正确章节](/private/tmp/linovelib-emulator-20260925/59-next-continuous-off.jpg)、[首页记录](/private/tmp/linovelib-emulator-20260925/61-back-home.xml)。由于同时存在设备重启和渲染模式变化，不能据此独立证明开关就是根因。

官方宿主连续滚动同时存在手动切章、按可见列表项自动切章、异步恢复滚动位置的路径，值得继续检查状态交错。日志中的 292878 请求符合下一章预加载；取消不会打印 HTTP 错误，不能把“只有 START”判定为网络死锁。当前未发现插件章节 ID 串写的源码依据。

## 提交前审查补充与处理决定

用户确认以下两项 P2 作为 `2.1.0` 已知问题暂缓修复，不再阻挡本轮代码提交。README 已同步公开说明：

- 主题筛选弹窗：沿用“发现 2”的设备证据，状态为已接受的已知限制。
- 出版社关联入口：提交前静态审查发现，公共书籍映射已从可点击 `tags` 中移除出版社，只填入 `publishingHouse`。API 3 正式宿主 `30458c` 的 `DetailScreen.kt:806–816` 对原生出版社设置空点击回调，仅标签执行导航；因此出版社可见但无法进入关联书单。此项为源码调用链结论，尚未进行独立设备复测；后续需恢复入口或配套修复宿主。

此决定仅覆盖主题筛选和出版社入口两项。上文设备用例继续保留实际的失败／未执行结果；宿主标签 ABI、连续滚动切章、遗留分页及发行签名验证仍按原记录跟踪。

## 证据与边界

主要现场证据目录：`/private/tmp/linovelib-emulator-20260925/`。这是本机临时目录，长期保存需另行归档。

- [第一阶段日志](/private/tmp/linovelib-emulator-20260925/linovelib-logcat.txt)
- [重启后日志](/private/tmp/linovelib-emulator-20260925/linovelib-logcat-restart.txt)
- [筛选结果](/private/tmp/linovelib-emulator-20260925/23-region-applied.jpg)
- [正文与插图](/private/tmp/linovelib-emulator-20260925/30-reader-content.jpg)
- [搜索最终结果](/private/tmp/linovelib-emulator-20260925/65-search-final.jpg)

第一阶段模拟器进程异常退出（退出码 134，日志含图形 color buffer 错误），改用软件渲染后完成补测。采集的 AndroidRuntime 日志没有应用崩溃；模拟器异常不计为应用崩溃。所有后续操作只针对独立的 5580 设备。

本轮没有覆盖弱网、离线缓存、全量主题、全部榜单、搜索中文转换、分页叠层、进程恢复动态路由和正式升级安装。之前 JVM 分页探针的失败结论仍属于逻辑层证据，本轮没有补齐其 UI 复现。
