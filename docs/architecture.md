# 当前架构

[文档索引](README.md) · [开发与验证](development.md) · [TW 数据迁入](tw-data-migration.md)

本文件说明当前模块边界、状态归属和必须保留的设计理由。修改前按任务查对应章节和源码入口；迁入的输入、合并、凭据及失败规则以 [TW 数据迁入](tw-data-migration.md) 为准，测试结果以 [最近验收基线](development.md#最近验收基线) 为准。

## 模块与入口

工程只有一个 `plugin` Gradle 模块，面向 API 3。宿主 API 使用 `compileOnly`，由实际阅读器提供，避免插件打包第二份宿主类型；KSP 生成插件入口清单；插件 `onLoad()` 通过公开 API 注册适配后的数据源，`onUnload()` 显式注销。只注册一个可见的 Linovelib 数据源；TW 是本地迁入输入，不另注册兼容源，以免用户数据继续分散在两个源中。

当前单源工程按请求、解析、页面会话和宿主适配等具体职责拆分，足以明确状态归属并验证调用边界。增加多模块、Repository / UseCase 或通用网络、分页、缓存框架，应先说明现有结构无法处理的实际复杂度或复用需求；仅为了增加层次而重建结构，会扩大迁移和回归范围。

| 代码入口 | 负责什么 | 状态归属 |
| --- | --- | --- |
| [LinovelibPlugin](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/LinovelibPlugin.kt) | 插件生命周期、数据源注册与注销，接入 TW 迁入控制器 | 插件实例 |
| [LinovelibHostDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHostDataSource.kt) | 按真实宿主接口分发标签回调，其余调用原样委托 | 同一数据源的动态代理 |
| [LinovelibWebDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibWebDataSource.kt) | 装配组件，适配宿主接口，编排正文加载 | 数据源实例的宿主 Cache、加载检查任务 |
| [LinovelibHttpClient](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHttpClient.kt) | HTML 请求、搜索验证、Cookie、重试和可用性检查 | 一个数据源的 HTTP 会话 |
| [LinovelibSearchProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibSearchProvider.kt) | 识别搜索输入，逐页输出列表摘要 | 单次搜索的分页状态 |
| [LinovelibBookDetails](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookDetails.kt) | 详情获取、解析与接纳 | 复用一个源的 MetadataStore |
| [LinovelibExplorePageProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibExplorePageProvider.kt) | 三个栏目、展开页定义登记、创建页面实例 | 源内稳定页面定义，不持有页面会话 |
| [LinovelibLinkedExpandedPageDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRelatedSearch.kt) | 筛选、分页、加载更多和取消 | 页面持有筛选与当前通道；一次 Flow 收集持有页码、URL 快照及去重集合 |
| [LinovelibHtmlParser](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHtmlParser.kt) | HTML 转为 `Parsed*` 数据，包括原始关联链接 | 无跨调用可变状态 |
| [LinovelibMetadataStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibMetadataStore.kt) | 最近成功详情、列表摘要、关联目标 | 一个源实例共享的有界临时数据 |
| [LinovelibBookMapping](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookMapping.kt) | 摘要、详情、目录转换成 API 3 类型 | 无状态 |
| [LinovelibImageStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibStoredImage.kt) | 插图下载、校验和本地文件引用 | 既有图片下载与磁盘缓存 |
| [migration 包](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration) | TW 本地数据读取、合并、事务与重复保护 | 见 [迁入规则](tw-data-migration.md) |

调用由宿主入口或 Provider 发起：HTTP 获取网页，Parser 产出 `Parsed*` 数据，BookDetails 接纳有效详情，列表调用方显式记录摘要，最后映射为宿主类型。Parser 不访问宿主导航或临时存储；HttpClient 不解释书籍和目录。图片下载使用独立链路，不并入 HTML 会话。

同一数据源内，相同图片 URL 的并发调用共享一次文件读取或下载。下载方负责验证图片和原子落盘，等待方复用已保存的文件。失败后允许再次加载，下载方取消时仍在等待的调用可以接管。磁盘缓存继续使用 URL 的 SHA-256 作为文件名。

图片下载收到 HTTP 429 时，按响应域名共享 `Retry-After` 冷却。响应头支持秒数或 HTTP 日期，无法读取时沿用 429 的退避值。每次新请求、重试和同协议重定向都在发送前检查目标域名。已发出的请求正常结束，冷却按仍有效的最长等待时间计算，最后一次尝试收到 429 也会更新冷却。

## 分页会话

已核对的 API 3 宿主调用契约（历史提交 `6ea57a9`）中，每个展开页 ViewModel 从 `exploreExpandedPageDataSourceMap` 取得并保留一个数据源实例。Provider 每次读取该属性时复制定义表，创建一份普通 Map；同一份 Map 内对象稳定，不同读取之间的页面和 Filter 独立。共享页面单例会让多个页面互相修改筛选、页码或加载更多通道，因此共享的只能是页面定义。

- 固定文库、普通更多、排行及关联入口都使用这条创建路径。
- 每个文库页面创建自己的 `LinovelibWenkuFilters`。新开页面使用默认筛选，返回旧页面保留旧筛选。
- 每次收集先保存筛选 URL，页码和去重集合只在本次收集中使用。修改筛选后从第一页重收集。
- 当前加载通道的发布、读取与身份比较清理共用短锁。发布前检查取消状态，旧任务的清理不会清空新通道；请求和结果发送在锁外执行。
- 页面定义不按临时元数据的容量上限淘汰。已打开页面保留 URL 快照；新登记目标影响后续实例。

Map getter 的成本是 O(已登记定义数)，只创建轻量对象，不启动请求或协程。不能调用父类注册方法向 getter 返回的临时 Map 写入定义，否则下次读取就会丢失。页面定义支撑已登记导航入口，与可淘汰的元数据分开保存；动态定义仍只在内存中，进程死亡后的完整动态路由恢复不在当前能力内。

同一页面实例只有一个当前加载更多通道，不承诺多个同时有效的订阅者；隔离单位是宿主页面，收集级状态隔离用于筛选重收集与取消交接。

上述历史宿主的展开页下拉刷新调用 `ExploreViewModel.refresh()`，只重新检查离线状态，没有调用结果重收集；筛选回调才调用 `ExpandedPageViewModel.loadBookResult()`。当前插件的分页重构没有扩展宿主刷新行为，不能以页面隔离或分页测试通过推定下拉刷新已修复。其他宿主发行版需按实际调用链和设备行为核对。

## HTTP 会话与离线状态

`LinovelibHttpClient` 提供普通页面、章节、关键词搜索和首页探活入口。章节与搜索返回最终 URL 和 HTML。搜索直达书籍与重定向识别由 `LinovelibSearchProvider` 负责，列表页识别由 Parser 统一处理。

[RequestPolicy](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestPolicy.kt) 定义重试和退避，[RequestCoordinator](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestCoordinator.kt) 负责串行请求、间隔、冷却和取消释放。协调单位是一次 HTTP 请求尝试；普通请求的 Cookie 合并、连接执行与写回都在该执行区间内，避免并发请求丢失或覆盖会话 Cookie。

启动探活和推荐共享首页请求，文库预览和默认展开首屏共享同一文库 URL。每个数据源实例只为这两个 URL 合并在途请求，并从有效 HTML 取回时缓存 15 秒。显式离线重试跳过已有缓存，可以等待同 URL 的在途请求。其他筛选、后续页、搜索和正文继续各走原有请求路径。

关键词搜索在每次 Flow 收集时创建独立会话。首个列表页依次获取 JS、CSS 验证值，兑换一次性票据，再请求结果。后续页的首次尝试复用同一组验证值，每页单独兑换新票据。复用时未获得票据，或带票据请求得到空白响应，会重新获取验证值。HTTP 或传输错误的重试也使用新验证值与新票据。搜索只使用验证流程明确提供的 Cookie，与普通会话 Cookie 分开。

| 请求结果 | 当前操作 | 全源离线状态 |
| --- | --- | --- |
| 成功 HTTP 响应 | 返回内容，交给业务层识别 | 更新为在线 |
| 普通请求的 HTTP 错误或传输失败 | 按既有策略重试，最终抛出错误 | 保持之前状态 |
| 主动首页检查最终失败 | 返回不可用 | 更新为离线 |
| 协程取消 | 原样传播 | 不修改状态 |

状态在请求释放协调器之前发布，避免旧检查的外层回调覆盖较新的成功结果。普通单页失败不把整个源判为离线；在线也不保证页面可解析。宿主入口继续使用 API 3 已有回退和结果类型，`onLoad` 只启动当前加载检查，不增加后台轮询。

## 解析、元数据与映射

Parser 返回原始作者、作者 URL、标签和出版社链接，不生成作者展示标签，也不隐式写缓存。相同输入的解析不应因先前请求或页面展示而改变；显示限额和元数据写入由调用方决定，不能放进 Parser。正文选择器、段落还原、日期解析和目录算法保持独立。

列表页由 `parseListPage(html)` 一次解析同一份 DOM，返回 `ParsedListPage?`，包含完整书目摘要、末页和下一页链接。下一页取 `#pagelink a.next` 的实际地址，`href="#"` 或没有该链接表示结束。按书籍 ID 保留第一条有效摘要，顺序与网页一致；没有有效书目时，只有明确为空的列表才返回空书目，验证页、未知页面和损坏书目候选返回 `null`。书目、空列表及末页共用这一识别入口，避免调用方分别判断而将验证页误报成空结果。

搜索与展开页把有效空列表和未识别页面分别映射为已有的 Empty 与 Error 结果。搜索在单次 Flow 收集中顺序请求下一页，逐页按网页顺序追加摘要，保留跨页重复条目。没有下一页时结束列表抓取。HTTP 200 空响应和未识别页面作为失败报告，保留已经发出的书籍，随后发出 Error 与 End。取消直接传播。

搜索以响应的最终 URL 为基准解析相对分页链接，并在本次收集中记录请求地址和最终地址。遇到已访问页时停止分页、记录回环并跳过缓存。已有书目时发出 End，让官方 API 3 宿主继续显示已找到的书；无书目时报告分页错误。

同一数据源实例按去除首尾空白后的搜索 URL 缓存完整关键词列表，最多保留 8 条，写入后有效 30 秒。只有成功拉到末页的列表会写入缓存，合法空列表也可缓存。网页仍标有未加载的页码时，即使缺少下一页链接也不写入缓存。缓存保留网页摘要和顺序，每次命中重新构造宿主卡片对象。书号直达和重定向到书籍详情走各自的处理路径。

展开页仍使用第一页的末页信息；分页 URL、筛选、跨页去重和加载更多属于页面会话，空的中间页可继续向后扫描。文库栏目将有效空列表或未识别页面投影为空展示行，继续保留栏目标题与展开入口。

错误分类保留到宿主入口再适配：详情失败依次使用成功详情、列表摘要和空对象；目录与正文失败返回各自空对象；搜索和展开页发出已有的 `SearchResult.Error`、`Empty`、`End`。历史官方宿主 `6ea57a9` 的 `ExpandedPageViewModel` 只处理书籍结果，忽略这些状态事件。2026-10-28 核对的官方 `1.2.4 / 10204004` 也保留这一处理方式，并直接用书目为空作为进度条显示条件，因此有效空结果结束后仍会显示加载条。当前重构沿用这些接口边界，保留该宿主展示行为；其他宿主版本的展示能力需单独核对。

探索解析保留 HTML 中全部有效行与书籍，按原顺序过滤无效项并在每行内按书籍 ID 去重。ExplorePageProvider 沿用宿主默认布局，按推荐、排行、文库注册三个栏目：推荐只选勇者新推、新书抢鲜、人气完本三组，保留各组完整书目和来源顺序；排行保留全部有效行及完整书目，不重排榜单；文库展示一行，最多 12 本预览。独立完结栏目已取消，推荐的人气完本和排行的完本榜继续保留。只将实际返回给宿主的书目摘要写入 MetadataStore，一次展示转换中每个位置只写一次；未展示的行和书目不记录摘要，未展示的行也不登记展开页。

文库展开页依次提供地区、出版社、主题、排序、动画、字数和状态七项单选筛选，出版社位于地区之后。地区只提供“不限”、日本轻小说、华文轻小说、Web 轻小说、韩国轻小说，保留站点原有编码 `0 / 1 / 2 / 3 / 5`；不提供轻改漫画选项。出版社使用站点真实 `sortid` 编码，提供 13 项及“不限”，与其他条件共同生成筛选 URL，不自动修改地区；切换条件仍复用既有的第一页重载与分页会话。主题保留 61 项，弹窗显示范围由宿主处理，已知限制见 [README](../README.md#已知限制)。

MetadataStore 的三个索引各保留最多 256 项：成功详情按书籍 ID、列表摘要按书籍 ID、关联目标按展示文本。按最近写入顺序淘汰；重写旧键会更新顺序，读取不会延长保留。操作使用短同步方法，不在锁内执行 I/O；不设 TTL，不存宿主可变书籍对象，也不持久化。它只帮助详情回退与关联导航，丢失或淘汰后必须仍能走正常请求或回退路径。

| 调用路径 | 元数据行为 |
| --- | --- |
| 普通详情、搜索重定向带回的详情 | 标题非空时记录详情和有效关联目标 |
| 探索三个栏目 | 推荐选择三组、排行保留全量、文库截取预览后，只记录实际返回给宿主的展示摘要 |
| 搜索列表、展开分页 | 沿用已解析有效摘要的记录规则；展开页结果去重不影响摘要记录 |
| 作品 ID 和书籍链接直达 | 返回 ID，由宿主进入书籍后请求详情 |
| 普通详情失败或解析无效 | 依次尝试成功详情、列表摘要、宿主空对象 |
| 关联索引缺失 | 已登记定义保留原 URL；首次登记且无已知目标时回退到关键词搜索 |

`LinovelibBookDetails.fetch(id)` 获取详情 HTML 并调用 `acceptHtml(id, html)` 解析。普通关键词搜索和展开书单以 `MultipleBook` 返回当前网页的摘要，宿主可直接显示列表。打开书籍后，宿主通过 `getBookInformation` 获取完整详情。

关键词搜索重定向到详情时，复用响应中已有的详情 HTML。关联书单的关键词回退仍遍历结果分页，并按书籍 ID 去重。若重定向的详情无法解析，普通搜索发出 `SingleBook` 让宿主直达书籍，关联书单报告错误。

WebDataSource 装配同一个 BookDetails 和 MetadataStore 供普通详情与搜索使用。内部装配可替换详情 HTML 加载函数，以固定页面验证宿主入口；生产仍使用数据源自己的 HTTP 会话。不要为复用详情逻辑顺带增加重试、并发、请求合并或缓存规则。

新的有效详情缺少某链接时，不抹掉先前已知目标，避免一次不完整页面破坏已知关联。API 3 的标签回调只传展示文本，同名标签采用最近有效解析到的关联；不能声称能精确区分同名标签的来源书籍。

`LinovelibBookMapping.kt` 是摘要、详情和目录到宿主数据的统一转换入口：

- 搜索首批与详情摘要回退都生成 `作者：姓名` 标签，过滤空标签并去重。
- 未知日期转成 `LocalDateTime.MIN`，具体显示由宿主决定。真实日期使用当天零点。
- 当前出版社只写入 `publishingHouse` 字段，关联 URL 保存在元数据中，没有加入可点击的 `tags`。官方 `1.2.3c` 的 `DetailScreen` 对原生出版社使用空点击回调；2026-10-18 模拟器确认点击不跳转，本轮保持现状，见 [README 已知限制](../README.md#已知限制)。

宿主 Cache 与 MetadataStore 各自独立：前者配置为每类 256 项、6 小时，后者按每个索引最多 256 项、最近写入顺序淘汰。后续搜索、分页或其他栏目写入仍可淘汰旧摘要，不保证当前页面的摘要长期保留。配置入口是 [LinovelibDataSourceConfiguration](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibDataSourceConfiguration.kt)，不能把两个存储的时效语义混为一谈。

## 迁入会话

迁入只处理本地数据，不参与网站请求和正文解析。API 3 没有公开满足本次完整迁入要求的事务接口，因此宿主私有存储访问收口在 Adapter 中；只能在实际宿主与存储契约通过检查时启用写入，识别失败时停用迁入并保留正常阅读。

| 层 | 唯一职责 | 不应承担的职责 |
| --- | --- | --- |
| [LinovelibMigrationController](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration/LinovelibMigrationController.kt) | Android 生命周期、Activity / Dialog 绑定、同步渲染 Session 输出 | 决定合并结果或断言事务结果 |
| [LinovelibMigrationSession](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration/LinovelibMigrationSession.kt) | 冷启动资格、一个在途尝试、30 秒准入等待、结果与迟到回调 | 持有 Activity、Dialog、快照或数据库 |
| [Api3StartupGate](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration/Api3StartupGate.kt) | 核对普通冷启动、初始化和冲突任务条件 | 暂停或取消宿主任务 |
| [Api3HostAdapter](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration/Api3HostAdapter.kt) | 宿主识别、输入重查、共享连接上的原子写入、提交事实核对 | 依赖 UI 是否成功显示来推断数据库结果 |
| Codec / Normalizer / Planner / Receipt | 快照归属与读取、数据归一、合并计划、凭据分类 | 控制生命周期或自行提交数据库 |

`MigrationHost` 只暴露 `inspectOnLoad` 与 `executeAttempt`。Session 接收已完成、无工作、确认未提交或需要重启的权威结果，不自行补查凭据。30 秒期限只限制启动准入等待，不是事务超时。`MigrationRuntime` 收口单一工作线程、主线程投递和单调时钟；测试可以控制调度，无需通用工作流框架。

准入绑定 Activity、弹窗身份与尝试 ID；只有 resumed 页面中确实显示的本次进度弹窗授予准入。检查前后核对同一份绑定，并验证同一个实际页面的 StartupGate，防止旧页面、旧弹窗或旧尝试借用新绑定。初始 Gate 证据在插件早期构造时捕获，不能在重试或页面重建时重新采样，否则可能把宿主已取消但尚未停止的任务误判为安全。

前后台切换撤销页面准入；前台渲染失败还会撤销本次冷启动资格。在途事务都要自然结算，页面重建不创建新会话、不自动启动第二次尝试。手动重试只接受已确认未开始写入或正常回滚、原因可恢复且冷启动会话仍有效的结果，每次重新准备输入；在途工作不能仅凭 UI 错误、卸载或结果不明判为“未提交”。这一边界用于防止重复累加阅读统计。

完整业务数据、选中源和完成凭据必须由 Adapter 在同一事务内写入；不能先写数据再异步补凭据。事务结果不明时，需要权威存储证据或重启，不能凭页面状态、一次未读到凭据或合并计划推断失败后重放。快照选择、坏数据保护、合并与三态凭据的完整规则只维护在 [TW 数据迁入](tw-data-migration.md)。

当前准入不观察同一 Activity 实例的新 Intent，也不保证对后来窗口的输入独占；外部安装入口尚无额外监听或拦截。此缺口不能通过已有生命周期测试推定已解决，具体实测范围和后续验收要求见 [已知验证边界](development.md#已知验证边界)。

## 正文与兼容边界

正文分页由 `LinovelibWebDataSource.kt` 内的 `getChapterContentPages` 编排：根据最终 URL 识别同章分页，再经过段落与内容格式化、图片本地化，构建宿主正文组件。修改展开页分页不应连带改变章节编号、同章分页识别或段落还原算法。

公开的 `LinovelibPlugin` 注入构造、`LinovelibWebDataSource(Context)` 构造和官方快照格式保持兼容。包名、源 ID、书籍与卷 ID、`linovelib-v3:` 章节编号及 `linovelib/chapter-images` 目录保持既有规则；这些标识关联插件发现和已有用户数据，内部重构不能顺带改名。

官方 API 3 会混淆导航参数类型，直接实现 Maven API 的 `progressBookTagClick(String, NavController)` 无法覆盖发行包回调。`LinovelibHostDataSource` 以宿主实际接口创建动态代理，标签调用转交同一 `LinovelibWebDataSource` 的关联页逻辑，其余方法委托原实例并解包反射异常；数据源不再使用 `@WebDataSource` 自动注册，避免绕过代理。

导航保留 API 3 的 Route 反射适配，并从真实回调的第二参数类型中唯一匹配 `static void(Controller, Object)`，这是官方内置 Wenku8 标签入口实际使用的导航方法。`1.2.2a`、`1.2.3`、`1.2.3c` 发行包均已核对该描述符；不依赖混淆后的类名或方法名，也不猜测其他签名。[构建配置](../plugin/build.gradle.kts) 检查已知不兼容的宿主 API。编译和插件自身的 ABI 检查不替代实际宿主设备验收，新增依赖尤其要检查最终 DEX 与实际宿主运行结果。

宿主刷新与结果展示的历史调用边界见本文的 [分页会话](#分页会话) 和 [解析、元数据与映射](#解析元数据与映射)。主题弹窗与出版社入口的已知问题及作者／标签导航验证范围见 [README 已知限制](../README.md#已知限制)。已验证和未覆盖的范围统一见 [开发与验证](development.md#最近验收基线)。
