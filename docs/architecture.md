# 当前架构

[文档索引](README.md) · [开发与验证](development.md) · [TW 数据迁入](tw-data-migration.md)

本文件说明当前模块边界、状态归属和必须保留的设计理由。修改前按任务查对应章节和源码入口；迁入的输入、合并、凭据及失败规则以 [TW 数据迁入](tw-data-migration.md) 为准，测试结果以 [最近验收基线](development.md#最近验收基线) 为准。

## 模块与入口

工程只有一个 `plugin` Gradle 模块，面向 API 3。宿主 API 使用 `compileOnly`，由实际阅读器提供，避免插件打包第二份宿主类型；KSP 生成插件与数据源注册清单。只注册一个可见的 Linovelib 数据源；TW 是本地迁入输入，不另注册兼容源，以免用户数据继续分散在两个源中。

| 代码入口 | 负责什么 | 状态归属 |
| --- | --- | --- |
| [LinovelibPlugin](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/LinovelibPlugin.kt) | 插件生命周期，接入 TW 迁入控制器 | 插件实例 |
| [LinovelibWebDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibWebDataSource.kt) | 装配组件，适配宿主接口，编排正文加载 | 数据源实例的宿主 Cache、加载检查任务 |
| [LinovelibHttpClient](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHttpClient.kt) | HTML 请求、搜索验证、Cookie、重试和可用性检查 | 一个数据源的 HTTP 会话 |
| [LinovelibSearchProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibSearchProvider.kt) | 搜索识别、结果事件及宿主对象更新 | 单次搜索已展示的可变书籍对象 |
| [LinovelibBookDetails](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookDetails.kt) | 详情获取、解析与接纳，搜索首批及串行补全 | 复用一个源的 MetadataStore，不另存缓存 |
| [LinovelibExplorePageProvider](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibExplorePageProvider.kt) | 四个栏目、展开页定义登记、创建页面实例 | 源内稳定页面定义，不持有页面会话 |
| [LinovelibLinkedExpandedPageDataSource](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRelatedSearch.kt) | 筛选、分页、加载更多和取消 | 页面持有筛选与当前通道；一次 Flow 收集持有页码、URL 快照及去重集合 |
| [LinovelibHtmlParser](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibHtmlParser.kt) | HTML 转为 `Parsed*` 数据，包括原始关联链接 | 无跨调用可变状态 |
| [LinovelibMetadataStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibMetadataStore.kt) | 最近成功详情、列表摘要、关联目标 | 一个源实例共享的有界临时数据 |
| [LinovelibBookMapping](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibBookMapping.kt) | 摘要、详情、目录转换成 API 3 类型 | 无状态 |
| [LinovelibImageStore](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibStoredImage.kt) | 插图下载、校验和本地文件引用 | 既有图片下载与磁盘缓存 |
| [migration 包](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/migration) | TW 本地数据读取、合并、事务与重复保护 | 见 [迁入规则](tw-data-migration.md) |

调用由宿主入口或 Provider 发起：HTTP 获取网页，Parser 产出 `Parsed*` 数据，BookDetails 接纳有效详情，列表调用方显式记录摘要，最后映射为宿主类型。Parser 不访问宿主导航或临时存储；HttpClient 不解释书籍和目录。图片下载使用独立链路，不并入 HTML 会话。

## 分页会话

API 3 宿主的每个展开页 ViewModel 从 `exploreExpandedPageDataSourceMap` 取得并保留一个数据源实例。Provider 每次读取该属性时复制定义表，创建一份普通 Map；同一份 Map 内对象稳定，不同读取之间的页面和 Filter 独立。共享页面单例会让多个页面互相修改筛选、页码或加载更多通道，因此共享的只能是页面定义。

- 固定文库、普通更多、排行、完结及关联入口都使用这条创建路径。
- 每个文库页面创建自己的 `LinovelibWenkuFilters`。新开页面使用默认筛选，返回旧页面保留旧筛选。
- 每次收集先保存筛选 URL，页码和去重集合只在本次收集中使用。修改筛选后从第一页重收集。
- 当前加载通道的发布、读取与身份比较清理共用短锁。发布前检查取消状态，旧任务的清理不会清空新通道；请求和结果发送在锁外执行。
- 页面定义不按临时元数据的容量上限淘汰。已打开页面保留 URL 快照；新登记目标影响后续实例。

Map getter 的成本是 O(已登记定义数)，只创建轻量对象，不启动请求或协程。不能调用父类注册方法向 getter 返回的临时 Map 写入定义，否则下次读取就会丢失。页面定义支撑已登记导航入口，与可淘汰的元数据分开保存；动态定义仍只在内存中，进程死亡后的完整动态路由恢复不在当前能力内。

同一页面实例只有一个当前加载更多通道，不承诺多个同时有效的订阅者；隔离单位是宿主页面，收集级状态隔离用于筛选重收集与取消交接。

## HTTP 会话与离线状态

`LinovelibHttpClient` 对外提供 `loadHtml`、`loadChapterPage`、`search`、`checkOffline` 和只读 `isOffLineFlow`。章节与搜索返回最终 URL 和 HTML；搜索直达书籍与重定向识别保留在 `LinovelibSearchResponse`，列表页识别由 Parser 统一处理。

[RequestPolicy](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestPolicy.kt) 定义重试和退避，[RequestCoordinator](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibRequestCoordinator.kt) 负责串行请求、间隔、冷却和取消释放。协调单位是一次 HTTP 请求尝试；普通请求的 Cookie 合并、连接执行与写回都在该执行区间内，避免并发请求丢失或覆盖会话 Cookie。

搜索保留 JS、CSS、ticket 兑换、结果请求四步验证，每一步只使用该验证流程明确提供的 Cookie，不读取或写回普通会话 Cookie。不能以统一 Cookie 管理为由移除这一边界，也不能把四步流程误写成一个整体串行事务。

| 请求结果 | 当前操作 | 全源离线状态 |
| --- | --- | --- |
| 成功 HTTP 响应 | 返回内容，交给业务层识别 | 更新为在线 |
| 普通请求的 HTTP 错误或传输失败 | 按既有策略重试，最终抛出错误 | 保持之前状态 |
| 主动首页检查最终失败 | 返回不可用 | 更新为离线 |
| 协程取消 | 原样传播 | 不修改状态 |

状态在请求释放协调器之前发布，避免旧检查的外层回调覆盖较新的成功结果。普通单页失败不把整个源判为离线；在线也不保证页面可解析。宿主入口继续使用 API 3 已有回退和结果类型，`onLoad` 只启动当前加载检查，不增加后台轮询。

## 解析、元数据与映射

Parser 返回原始作者、作者 URL、标签和出版社链接，不生成作者展示标签，也不隐式写缓存。相同输入的解析不应因先前请求或页面展示而改变；显示限额和元数据写入由调用方决定，不能放进 Parser。正文选择器、段落还原、日期解析和目录算法保持独立。

列表页由 `parseListPage(html)` 一次解析同一份 DOM，返回 `ParsedListPage?`，包含完整书目摘要和末页。按书籍 ID 保留第一条有效摘要，顺序与网页一致；没有有效书目时，只有明确为空的列表才返回空书目，验证页、未知页面和损坏书目候选返回 `null`。书目、空列表及末页共用这一识别入口，避免调用方分别判断而将验证页误报成空结果。

搜索与展开页把有效空列表和未识别页面分别映射为已有的 Empty 与 Error 结果。展开页仍使用第一页的末页信息；分页 URL、筛选、跨页去重和加载更多属于页面会话，空的中间页可继续向后扫描。文库和完结栏目将有效空列表或未识别页面投影为空展示行，继续保留栏目标题与展开入口。

探索解析保留 HTML 中全部有效行与书籍，按原顺序过滤无效项并在每行内按书籍 ID 去重。首页四个栏目由 ExplorePageProvider 限制展示：推荐、排行取前 8 个有效行，每行最多 12 本；文库、完结各展示一行，每行最多 12 本。只将实际返回给宿主的书目摘要写入 MetadataStore，一次展示转换中每个位置只写一次；隐藏书目不新增、覆盖或刷新摘要，隐藏行也不登记展开页。否则大量未展示书目会占用有界存储并淘汰用户刚看到的摘要。首页 fallback 使用同样的每行 12 本限额。

MetadataStore 的三个索引各保留最多 256 项：成功详情按书籍 ID、列表摘要按书籍 ID、关联目标按展示文本。按最近写入顺序淘汰；重写旧键会更新顺序，读取不会延长保留。操作使用短同步方法，不在锁内执行 I/O；不设 TTL，不存宿主可变书籍对象，也不持久化。它只帮助详情回退与关联导航，丢失或淘汰后必须仍能走正常请求或回退路径。

| 调用路径 | 元数据行为 |
| --- | --- |
| 普通详情、带详情的 ID 直达与搜索重定向、搜索补全 | 标题非空才记录详情和有效关联目标；无效详情不覆盖旧值 |
| 首页四个栏目 | 先限制展示行和每行书籍数量，再记录实际返回给宿主的展示摘要 |
| 搜索列表、展开分页 | 沿用已解析有效摘要的记录规则；分页结果去重不影响摘要记录 |
| 仅检索 ID 的直达与重定向 | 返回 ID，保留轻量路径，不额外抓取详情 |
| 普通详情失败或解析无效 | 依次尝试成功详情、列表摘要、宿主空对象 |
| 关联索引缺失 | 已登记定义保留原 URL；首次登记且无已知目标时回退到关键词搜索 |

`LinovelibBookDetails` 统一详情获取与接纳：`fetch(id)` 始终获取实时 HTML 并复用 `acceptHtml(id, html)`；后者只解析已有响应，标题非空才接纳详情与关联目标。`enrich(books)` 先发完全部首批数据，再按顺序逐本实时补全。搜索首批取成功详情或本次列表摘要，不使用 Store 中的旧摘要替换本次摘要；命中成功详情也不跳过实时补全，避免辅助存储变成新的详情缓存策略。

`fetch` 与 `acceptHtml` 对无效页面返回空结果，异常原样传播；普通详情、ID 直达和重定向的宿主转换仍在各自异常处理内，分别保留详情回退或 SingleBook 退路。`enrich` 只处理逐本获取失败，继续后续书籍；取消和收集方异常向上传播。ID-only 路径不调用详情操作；SearchProvider 对同一搜索结果只发出一次书籍事件，详情补全更新已经展示的同一个 `MutableBookInformation`，避免重复追加结果。

WebDataSource 装配同一个 BookDetails 和 MetadataStore 供普通详情与搜索使用。内部装配可替换详情 HTML 加载函数，以固定页面验证宿主入口；生产仍使用数据源自己的 HTTP 会话。不要为复用详情逻辑顺带增加重试、并发、请求合并或缓存规则。

新的有效详情缺少某链接时，不抹掉先前已知目标，避免一次不完整页面破坏已知关联。API 3 的标签回调只传展示文本，同名标签采用最近有效解析到的关联；不能声称能精确区分同名标签的来源书籍。

`LinovelibBookMapping.kt` 是摘要、详情和目录到宿主数据的统一转换入口：

- 搜索首批与详情摘要回退都生成 `作者：姓名` 标签，过滤空标签并去重。
- 未知日期转成 `LocalDateTime.MIN`，由宿主显示为 `-`；真实日期使用当天零点。
- 出版社保持 `publishingHouse` 字段，关联 URL 保存在元数据中；不额外增加出版社点击标签。

宿主 Cache 与 MetadataStore 各自独立：前者配置为每类 256 项、6 小时，后者按每个索引最多 256 项、最近写入顺序淘汰。首页单次推荐或排行展示最多 96 个书目位置；后续搜索、分页或其他栏目写入仍可淘汰旧摘要，不保证当前页面的摘要长期保留。配置入口是 [LinovelibDataSourceConfiguration](../plugin/src/main/kotlin/io/nightfish/lightnovelreader/plugin/linovelib/source/LinovelibDataSourceConfiguration.kt)，不能把两个存储的时效语义混为一谈。

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

导航保留 API 3 的 Route 反射适配，避免直接链接宿主中不可用的类；[构建配置](../plugin/build.gradle.kts) 检查已知不兼容的宿主 API。编译和插件自身的 ABI 检查不替代实际宿主设备验收，新增依赖尤其要检查最终 DEX 与实际宿主运行结果。

宿主下拉刷新、错误展示、主题弹窗和标签回调的限制见 [README 已知限制](../README.md#已知限制)。已验证和未覆盖的范围统一见 [开发与验证](development.md#最近验收基线)。
