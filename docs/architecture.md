# 当前架构

[文档索引](README.md) · [开发与验证](development.md) · [TW 数据迁入](tw-data-migration.md)

本说明以 `e1ad8de`（2026-10-13）的三项架构改造为基础，并包含同日的首页展示摘要调整，以及 2026-10-14 的统一列表页识别、详情获取与接纳收拢。分页会话隔离、HTTP 会话抽离、Parser 与临时元数据拆分均已实现。方案取舍保留在 [历史设计](archive/2026-09-25-linovelib-architecture-design.md)，前三项的验证范围见 [改造验证记录](archive/2026-10-13-architecture-validation.md)。

## 模块与入口

工程只有一个 `plugin` Gradle 模块，面向 API 3。宿主 API 使用 `compileOnly`，由实际阅读器提供；KSP 生成插件与数据源注册清单。

| 代码入口 | 负责什么 | 状态归属 |
| --- | --- | --- |
| [LinovelibPlugin](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/LinovelibPlugin.kt) | 插件生命周期，接入 TW 迁入控制器 | 插件实例 |
| [LinovelibWebDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibWebDataSource.kt) | 装配请求、解析、元数据、搜索、探索与图片组件，适配宿主接口 | 数据源实例的宿主 Cache、加载检查任务 |
| [LinovelibHttpClient](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHttpClient.kt) | HTML 请求、搜索验证、Cookie、重试和可用性检查 | 一个数据源的 HTTP 会话 |
| [LinovelibSearchProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibSearchProvider.kt) | 搜索识别、结果事件及宿主对象更新 | 单次搜索已展示的可变书籍对象 |
| [LinovelibBookDetails](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookDetails.kt) | 详情获取、解析与接纳，搜索首批及串行补全 | 复用一个源的 MetadataStore，不另存缓存 |
| [LinovelibExplorePageProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibExplorePageProvider.kt) | 四个栏目、展开页定义登记、创建页面实例 | 源内稳定页面定义，不持有页面会话 |
| [LinkedExpandedPageDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRelatedSearch.kt) | 筛选、分页、加载更多和取消 | 页面持有筛选与当前通道；一次收集持有页码、URL 快照及去重集合 |
| [LinovelibHtmlParser](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHtmlParser.kt) | HTML 转为 `Parsed*` 数据，包括原始关联链接 | 无跨调用可变状态 |
| [LinovelibMetadataStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibMetadataStore.kt) | 最近成功详情、列表摘要、关联目标 | 一个源实例共享的有界临时数据 |
| [LinovelibBookMapping](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookMapping.kt) | 摘要、详情、目录转换成 API 3 类型 | 无状态 |
| [LinovelibImageStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibStoredImage.kt) | 插图下载、校验和本地文件引用 | 既有图片下载与磁盘缓存 |
| [migration 包](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration) | TW 本地数据读取、合并、事务与重复保护 | 见 [迁入规则](tw-data-migration.md) |

调用由宿主入口或 Provider 发起：HTTP 获取网页，Parser 产出数据，详情由 BookDetails 接纳元数据，列表摘要仍由调用方显式记录，最后映射为宿主类型。Parser 不访问宿主导航或临时存储；HttpClient 不解释书籍和目录。图片下载沿用独立链路。

## 分页会话

API 3 宿主的每个展开页 ViewModel 从 `exploreExpandedPageDataSourceMap` 取得并保留一个数据源实例。Provider 每次读取该属性时复制定义表，创建一份普通 Map；同一份 Map 内对象稳定，不同读取之间的页面和 Filter 独立。创建对象不启动请求或协程。

- 固定文库、普通更多、排行、完结及关联入口都使用这条创建路径。
- 每个文库页面创建自己的 `LinovelibWenkuFilters`。新开页面使用默认筛选，返回旧页面保留旧筛选。
- 每次收集先保存筛选 URL，页码和去重集合只在本次收集中使用。修改筛选后从第一页重收集。
- 当前加载通道的发布、读取与身份比较清理共用短锁。发布前检查取消状态，旧任务的清理不会清空新通道；请求和结果发送在锁外执行。
- 页面定义不按临时元数据的容量上限淘汰。已打开页面保留 URL 快照；新登记目标影响后续实例。

Map getter 的成本是 O(已登记定义数)，但只创建轻量对象。同一页面实例不额外承诺多个同时有效的订阅者；隔离单位是宿主页面。动态定义仍在内存中，进程死亡后的完整动态路由恢复不在当前能力内。

## HTTP 会话与离线状态

`LinovelibHttpClient` 对外提供 `loadHtml`、`loadChapterPage`、`search`、`checkOffline` 和只读 `isOffLineFlow`。章节与搜索返回最终 URL 和 HTML；搜索直达书籍与重定向识别保留在 `LinovelibSearchResponse`，列表页识别由 Parser 统一处理。

[RequestPolicy](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestPolicy.kt) 定义重试和退避，[RequestCoordinator](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestCoordinator.kt) 负责串行请求、间隔、冷却和取消释放。Cookie 合并、连接执行与写回在同一个请求执行区间内完成。搜索维持 JS、CSS、ticket 兑换、结果请求四步验证及其显式 Cookie 边界。

| 请求结果 | 当前操作 | 全源离线状态 |
| --- | --- | --- |
| 成功 HTTP 响应 | 返回内容，交给业务层识别 | 更新为在线 |
| 普通请求的 HTTP 错误或传输失败 | 按既有策略重试，最终抛出错误 | 保持之前状态 |
| 主动首页检查最终失败 | 返回不可用 | 更新为离线 |
| 协程取消 | 原样传播 | 不修改状态 |

状态在请求释放协调器之前发布，避免旧检查的外层回调覆盖较新的成功结果。在线只代表最近传输结果，不保证任意页面都可解析。宿主入口继续使用 API 3 已有回退和结果类型，不增加后台轮询。

## 解析、元数据与映射

Parser 返回原始作者、作者 URL、标签和出版社链接，不生成作者展示标签，也不隐式写缓存。探索解析保留当前 HTML 中全部有效行与书籍，按原顺序过滤无效项并在每行内按书籍 ID 去重；不承担显示限额。正文选择器、段落还原、日期解析和目录算法保持独立。

列表页由 `parseListPage(html)` 一次解析同一份 DOM，返回 `ParsedListPage?`，包含完整书目摘要和末页。解析到有效书目时按书籍 ID 保留第一条有效摘要，顺序与网页一致；没有有效书目时，只有明确为空的列表才返回空书目，验证页、未知页面和损坏书目候选返回 `null`。书目、空列表及末页识别共用这一入口，不向调用方暴露独立的空页或末页判定方法。

搜索与展开页把有效空列表和未识别页面分别映射为已有的 Empty 与 Error 结果。展开页仍使用第一页的末页信息；分页 URL、筛选、跨页去重和加载更多属于页面会话，空的中间页可继续向后扫描。文库和完结栏目将有效空列表或未识别页面投影为空展示行，继续保留栏目标题与展开入口。

首页四个栏目由 ExplorePageProvider 生成展示结果：推荐、排行取前 8 个有效行，每行最多 12 本；文库、完结沿用单行展示，每行最多 12 本。只将这些实际返回给宿主的书目摘要写入 MetadataStore，一次展示转换中每个位置只写一次；未展示的书籍或行不新增、覆盖或刷新摘要，也不登记隐藏行的展开页。固定文库定义和可见行的既有展开页 ID 规则保持。首页 fallback 同样使用每行 12 本的展示限额。

MetadataStore 的三个索引各保留最多 256 项：成功详情按书籍 ID、列表摘要按书籍 ID、关联目标按展示文本。按最近写入顺序淘汰；重写旧键会更新顺序，读取不会延长保留。操作使用短同步方法，不在锁内执行 I/O；不设 TTL，不存宿主可变书籍对象，也不持久化。

| 调用路径 | 元数据行为 |
| --- | --- |
| 普通详情、带详情的 ID 直达与搜索重定向、搜索补全 | 标题非空才记录详情和有效关联目标；无效详情不覆盖旧值 |
| 首页四个栏目 | 先限制展示行和每行书籍数量，再记录实际返回给宿主的展示摘要 |
| 搜索列表、展开分页 | 沿用已解析有效摘要的记录规则；分页结果去重不影响摘要记录 |
| 仅检索 ID 的直达与重定向 | 返回 ID，保留轻量路径，不额外抓取详情 |
| 普通详情失败或解析无效 | 依次尝试成功详情、列表摘要、宿主空对象 |
| 关联索引缺失 | 已登记定义保留原 URL；首次登记且无已知目标时回退到关键词搜索 |

`LinovelibBookDetails` 替代原 SearchDetails，提供三个内部操作：`fetch(id)` 始终获取实时 HTML 并复用 `acceptHtml(id, html)`；后者只解析已有响应，标题非空才接纳详情与关联目标；`enrich(books)` 先发完全部首批数据，再按顺序逐本实时补全。搜索首批取成功详情或本次列表的书目摘要，不使用 Store 中的旧摘要替换本次摘要。命中成功详情也不跳过实时补全。

`fetch` 与 `acceptHtml` 对无效页面返回空结果，异常原样传播；普通详情、ID 直达和重定向的宿主转换仍在各自原有异常处理内，分别保留普通缓存回退或 SingleBook 退路。`enrich` 只处理逐本获取失败，继续后续书籍；取消和收集方异常向上传播。ID-only 路径不调用详情操作；搜索事件与同一可变对象更新继续由 SearchProvider 处理。

WebDataSource 装配同一个 BookDetails 和 MetadataStore 供普通详情与搜索使用，公开的 Context 构造保留。内部装配允许替换详情 HTML 加载函数，以固定页面验证实际宿主入口；生产仍使用原 HTTP 会话，不新增重试、并发、请求合并或缓存规则。

新的有效详情缺少某链接时，不抹掉先前已知目标。同名标签受 API 3 只传展示文本的限制，采用最近有效解析到的关联。

`LinovelibBookMapping.kt` 是摘要、详情和目录到宿主数据的统一转换入口：

- 搜索首批与详情摘要回退都生成 `作者：姓名` 标签，过滤空标签并去重。
- 未知日期转成 `LocalDateTime.MIN`，由宿主显示为 `-`；真实日期使用当天零点。
- 出版社保持 `publishingHouse` 字段，关联 URL 保存在元数据中；不额外增加出版社点击标签。
- 同一搜索结果只发出一次书籍事件，详情补全更新已经展示的同一个 `MutableBookInformation`。

宿主 Cache 与 MetadataStore 各自独立：前者配置为每类 256 项、6 小时，后者是可丢弃的辅助元数据，仍按每个索引最多 256 项、最近写入顺序淘汰。首页一次展示最多包含 96 个书目位置；后续搜索、分页或其他栏目写入仍可能淘汰旧摘要，不保证当前页面的摘要长期保留。命中辅助详情也不会跳过普通搜索的实时补全。

## 正文与兼容边界

正文分页由 `LinovelibWebDataSource.kt` 内的 `getChapterContentPages` 编排。它根据最终 URL 识别同章分页，随后经过段落与内容格式化、图片本地化，再构建宿主正文组件。分页会话重构不改变这条链路的章节算法。

包名、源 ID、书籍与卷 ID、`linovelib-v3:` 章节编号及 `linovelib/chapter-images` 目录保持既有规则。导航保留 API 3 的 Route 反射适配；构建检查阻止直接链接已知不兼容的宿主 API。

宿主下拉刷新、错误展示、主题弹窗和标签回调有各自限制，见 [README 已知限制](../README.md#已知限制) 和 [历史设计边界](archive/2026-09-25-linovelib-architecture-design.md)。编译和插件自身的 ABI 检查不替代实际宿主设备验收。
