# Linovelib 插件架构整理设计

日期：2026-09-25。状态：已完成独立审查及修订复核，可进入实施规划；本文件不代表已实施或已完成设备验收。审查发现与关闭情况见 [审查记录](2026-09-25-linovelib-architecture-review.md)。

评估基线为插件 `codex/linovelib-rewrite` 当前工作区，包含已有未提交的 `2.1.7` 修改。宿主契约按 API 3 和本体历史提交 `6ea57a9` 核对，不用当前 API 4 开发分支推断 API 3 行为。

## 1. 目标与边界

本轮目标是让分页状态、网络会话、网页解析、临时元数据和宿主适配各有明确所有者，并修正评估中已经发现的分页串扰、资源错误误报全源离线和摘要转换分叉。

用户要求先完成架构设计，再审查设计。本轮交付仅为设计与审查记录；实现代码、构建配置及现有未提交修改不在本轮编辑范围。

后续实现保持以下契约：

- 单个 `plugin` 模块，继续面向 LightNovelReader 1.2.2 / API 3。
- 包名、`"linovelib".hashCode()` 数据源 ID、书籍与卷 ID、`linovelib-v3:` 章节编号和 `linovelib/chapter-images` 目录保持现有规则。
- 统一使用当前 `bilinovel.net` 入口；保留现有 Cookie、搜索验证、请求节流和图片下载策略。
- 保留推荐、文库、排行、完结四个栏目，以及搜索先显示列表、随后补全详情的行为。
- 正文分页、段落还原、插图位置、图片失败处理和离线导出的既有规则作为回归基线。
- 不增加宿主页面、API 4 支持、WebView、多站切换、新依赖或通用网络／分页／缓存框架。

## 2. 方案比较

| 方案 | 收益与成本 | 决定 |
| --- | --- | --- |
| 局部明确所有权，抽离 HTTP，会聚元数据与映射 | 覆盖已发现的问题；保留现有业务流程；只增加少量具体文件 | 采用 |
| 只修分页和离线标志，其他结构保持 | 差异最小，但请求组合仍难独立验证，Parser 继续隐式持有业务状态 | 可作为前两项独立交付，不作为最终结构 |
| 重建多模块、Repository／UseCase、通用分页框架 | 迁移面大，当前单源约 2,300 行代码不足以支撑这些额外层次 | 不采用 |

最小架构变更不等于一次移动全部方法。先建立行为回归，再按职责移动；正文加载暂留原位置，不同时改算法。

## 3. 目标结构

| 组件 | 职责 | 持有的可变状态 | 主要依赖 |
| --- | --- | --- | --- |
| `LinovelibWebDataSource` | 宿主接口入口、组件装配、正文加载编排、API 3 失败适配 | 现有宿主 Cache、一次性加载检查任务 | HttpClient、Parser、MetadataStore、Provider、ImageStore、BookMapping |
| 新 `LinovelibHttpClient` | HTML 请求、四步搜索验证、Cookie、重试及主动可用性检查 | 会话 Cookie、请求协调器、离线 StateFlow | Jsoup、RequestPolicy、RequestCoordinator、Diagnostics |
| `LinovelibSearchProvider` / `LinovelibSearchDetails` | 搜索类型、摘要首批、详情补全、API 3 结果事件 | 单次搜索内已展示书籍映射 | 加载函数、Parser、MetadataStore、BookMapping |
| `LinovelibExplorePageProvider` | 首页栏目、展开页定义登记和实例创建 | 页面 ID 到不可变定义的登记表 | 加载函数、Parser、MetadataStore |
| `LinovelibLinkedExpandedPageDataSource` | 一个宿主页面的筛选和分页会话 | 本页面 Filter 对象；当前收集任务的加载通道 | 加载函数、Parser、MetadataStore |
| `LinovelibHtmlParser` | HTML 到普通 Kotlin 数据；书籍、列表、目录和正文解析 | 无跨调用状态 | Jsoup、Urls、段落规则 |
| 新 `LinovelibMetadataStore` | 最近成功详情、列表摘要、关联目标索引 | 有界原始元数据，不存宿主可变对象 | JDK 集合、现有容量常量 |
| 新 `LinovelibBookMapping.kt` | 摘要、详情、目录到宿主数据的转换 | 无 | API 3 类型、标签显示规则、章节 ID 规则 |
| 现有辅助组件 | 节流策略、图片存储、封面修正、日期和正文格式化 | 各自现有状态 | 保持现有依赖 |

依赖方向为：宿主入口和 Provider 调用请求／解析／临时存储，再通过映射返回 API 3 类型。Parser 不知道 Provider、缓存或宿主导航；HttpClient 不知道书籍、正文组件或宿主数据库。

图片下载继续独立使用已有实现，不为了统一名称而并入 HTML 请求队列。网络图片和 HTML 的并发、校验及失败处理目的不同。

## 4. 展开页实例与分页生命周期

### 4.1 已核实的宿主边界

API 3 的 `ExpandedPageViewModel.init()` 从 `exploreExpandedPageDataSourceMap[id]` 取得数据源，并保存在该 ViewModel 字段中。筛选变化调用 `loadBookResult()`，取消旧收集任务并重收集同一数据源；`loadMore()` 也调用该实例。不同返回栈条目有不同 ViewModel。

因此同一页面 ID 可以对应相同内容定义，但不能强制不同 ViewModel 共用一个有状态的数据源。现有失败正是把一个 collection 的 Channel 存在共用实例上。

本次已用实际 `api:0.3` JAR 完成最小 Kotlin 验证：父类 getter 非 final，接口／父类／子类的 JVM 签名均为 `()Ljava/util/Map;`；经接口连续读取可取得不同 Map 和不同数据源实例。验证还确认父类登记函数会写入临时 Map，必须替换该登记路径。证据位于 `/private/tmp/lnr-api3-map-abi-20260925/`。此验证只证明覆写和分发可行，不替代最终 APK 或页面行为验收。

### 4.2 采用普通 Map 快照，不改变 Map.get 的含义

Provider 保留 `AbstractDefaultExplorePageProvider` 对首页 Tab 的便利方法，自行维护 `页面 ID → ExpandedPageDefinition(title, targetUrl)` 的有序登记表。定义是私有的不可变数据：有 URL 时创建链接书单；无 URL 时按现有规则由 title 提取关键词搜索；固定文库 ID 在创建时附加新的文库筛选器。不保存闭包、数据源或筛选器实例。

覆写 `exploreExpandedPageDataSourceMap` 的 getter：读取登记表快照后逐项调用私有 `createExpandedPage(id, definition)`，返回普通 `MutableMap<String, ExploreExpandedPageDataSource>`。同一份 Map 内重复查询得到同一对象；下一次读取属性得到另一份实例快照。沿用基类属性的 Kotlin 类型和 JVM getter 签名。

禁止继续调用继承的 `registerExpandedPageDataSource()`：该方法会写 getter 返回的临时 Map。新增私有登记方法只更新定义表；查重、标题比对和标签目标更新也直接操作定义表，不调用宿主暴露的 getter。

创建函数本身不启动协程、不发请求、不读写数据库或图片文件。只在锁内复制登记表，锁外创建实例；登记表的读写使用同一短同步边界。

全部入口使用这个边界，包括固定文库页、首页更多、排行／完结更多、作者／标签／出版社关联页。不能只让标签导航生成唯一 ID 而遗漏其他入口。

### 4.3 筛选与收集任务

- 文库的 `LinovelibWenkuFilters` 必须在创建函数内部新建；Filter 对象及宿主附着的监听器不能跨页面共享。
- 返回旧页面时，原 ViewModel 仍持有原数据源、原筛选值及原分页收集任务；新页面关闭不影响它。
- 新开同一文库页从默认筛选开始；这是页面实例隔离后的明确行为。同一页面的筛选变化保留选项，并从第一页重新加载。
- 页码、去重集合和本次筛选 URL 继续属于一次 collection。每次开始收集时先取得筛选 URL 快照，后续分页不再读取实时 Filter 值。
- 同一 ViewModel 快速改筛选时，旧协程取消不一定已完成。通道发布与 `finally` 中的“比较身份并清空”使用同一短同步锁，不能只依靠 `@Volatile` 加非原子的 check-then-set。
- collection 取得自己的 CoroutineContext 后，在通道发布的同一个锁内先 `ensureActive()`，再写当前通道，防止已取消的旧任务晚发布覆盖新任务。通道创建到关闭由 try/finally 覆盖，包括尚未成功发布就取消的情况。
- `loadMore()` 在同一锁内只取当前通道引用，然后在锁外 `trySend`；网络、emit、receive、关闭及取消传播均不放进同步锁。恰好与刷新交错的旧加载信号允许丢弃；新 collection 自行加载第一页，不把旧信号转投新条件。
- API 3 实际调用边界是一个页面数据源对应一个当前有效 collection。同一实例任意多个有效订阅者不是新增支持目标；跨页面通过不同实例解决。

宿主接口注释称结果流应为热流，但现有宿主按 `loadBookResult()` 重新收集。此设计保留现有可重收集流程，不新增共享热流或后台常驻 scope；实现验收以 API 3 的实际调用契约和设备行为为准。

### 4.4 取舍与生命周期上限

每次读取 Map 属性会为全部已登记页面构造轻量实例，复杂度为 O(页面定义数)。这是采用普通 Map、避免非标准查找行为的明确取舍；只构造对象，不触发 I/O。未来只有测量证明构造成本明显时才考虑宿主提供显式页面工厂接口。实现处标注这一 `ponytail:` 上限。

定义表只按稳定内容 ID 登记，重复点击不增加定义；允许更新未来实例使用的目标，已打开实例保持其 URL 快照。定义表不持有页面实例或收集任务，不按 LRU 删除定义，以免同进程返回栈失效。

动态页面定义仍是源实例内存状态。进程死亡后，仅恢复动态路由而没有重新加载定义的场景，目前没有完整支持；本轮不承诺新增恢复能力。固定文库定义随 Provider 初始化恢复。若后续要完整支持动态路由恢复，需另行设计可恢复路由参数，不用生成更多随机 ID 代替。

API 3 的展开页下拉刷新只检查离线状态，不等于重新加载结果；此宿主限制保持，不能把分页会话修正描述为同时修好了下拉刷新。

## 5. HTTP 与离线状态

### 5.1 请求组件接口

`LinovelibHttpClient` 是一个具体类，不建立接口／实现类对。对外只暴露 HTML 加载、章节页面加载、关键词搜索、主动可用性检查和只读离线 StateFlow。

章节与搜索返回包含 `finalUrl`、`html` 的小型普通结果。可将现有 `LinovelibSearchResponse` 更名为 `LinovelibHtmlResponse` 复用；底层 `Connection.Response` 和 Cookie 不离开 HttpClient。页面识别辅助方法放在 Parser，不让通用响应对象负责搜索业务。

构造参数允许提供站点根 URL，默认仍为现有 HOST，本地服务测试据此覆盖真实 Jsoup 请求。继续使用函数参数给 Provider 注入加载能力，不增加 HTTP mock 框架或通用拦截器体系。

Cookie 合并、连接执行、响应 Cookie 写回都保持在同一个 RequestCoordinator 保护范围内。搜索 guard 的 JS／CSS／ticket／结果请求保留现有请求头与显式 Cookie 规则，不擅自复用搜索 ticket 或提高并发。

现有最大尝试次数、间隔、退避计算和冷却登记时点保持。不得因为抽离请求类而另加一层重试，或在协调器外重复 sleep。

### 5.2 区分请求失败和全源可用性

保留异常传播，增加一个携带 HTTP 状态码和 URL 的具体状态异常即可；网络传输错误继续使用 IOException。解析失败属于上层页面业务，不由 HTTP 层猜测所有 HTML 的有效性。不建立新的通用 Result 层级。

离线状态只有两个写入来源：成功 HTML 请求，以及现有入口触发的主动站点检查。

| 情况 | 对当前操作的处理 | 对全源离线状态的处理 |
| --- | --- | --- |
| 普通请求获得成功 HTTP 响应 | 返回页面，业务层继续验证内容 | 更新为在线 |
| 章节或书籍 404、搜索 403、429、5xx | 按现有规则重试或抛状态异常 | 保持之前状态，不据此宣布整个源离线 |
| 普通请求传输失败且耗尽重试 | 抛原传输错误 | 保持之前状态，当前操作仍明确失败 |
| 主动首页检查成功 | 返回可用 | 更新为在线 |
| 主动首页检查遇到 HTTP 失败或传输失败 | 返回不可用 | 更新为离线 |
| 协程取消 | 原样传播 | 不修改状态、不降级为空结果 |
| 编程错误或其他未预期异常 | 不当成网络故障吞掉 | 不修改状态 |

“在线”表示最近请求或检查获得了可用的传输结果，不保证任意页面可解析；这是 API 3 单一 Boolean 的表达边界。业务请求失败后，状态可能保留上次结果，直到加载时检查、手动刷新检查或后续成功请求更新。本轮不增加轮询、失败计数器或后台恢复状态机。

`WebDataSource.isOffLine()` 委托主动检查，不再用无区别的 `runCatching { ... }.getOrElse { true }` 吞掉取消。现有 onLoad 检查任务仍只有一个活动实例，不新增进程级全局 scope。

状态发布与既有请求顺序一致：成功响应的在线更新、主动检查最终失败的离线更新，都在该次请求的 RequestCoordinator slot 释放之前执行。中间重试失败不发布离线；取消不发布；Cookie 写回和原冷却登记边界保持。外层检查函数只返回结果，WebDataSource 直接暴露 HttpClient 的只读流，不再持有第二份流或在 onLoad 中重复赋值。由此防止旧探测的外层回调在较新请求成功后又把状态改回离线，无需新增序号或状态机。

API 3 对外适配继续保持：详情失败使用成功缓存／摘要回退，目录和正文按现有契约返回空对象；搜索和展开页使用已有 Error／Empty／End 类型。错误分类在内部保留到这个最外层边界，不承诺宿主尚不支持的错误界面。

## 6. 纯解析、元数据和映射

### 6.1 Parser 返回显式信息

从 Parser 移走三个 ConcurrentHashMap 及对应读缓存方法。`parseBookInformation()` 的结果显式包含作者原始链接和标签／出版社目标链接；这些链接不写入宿主 BookInformation，也不进入书籍身份或持久化格式。

作者显示标签仍由统一映射规则生成。Parser 返回原始作者及 URL，不调用 RelatedSearch 的展示函数。网页解析函数不依赖此前解析过哪一本书，同一输入得到同一结果。

不顺便重写正文选择器、段落还原、日期解析或目录算法。列表的内容、末页和空结果识别可在后续站点调整时合并，本轮不为减少重复 DOM 构造增加一套解析层。

### 6.2 元数据存储边界

一个源实例创建一个 `LinovelibMetadataStore`，注入实际需要它的 WebDataSource、搜索和探索组件。它只保存不可变的 Parsed 数据，三个用途分别为详情回退、列表摘要回退、显示标签到已知网页链接的关联索引。

所有写入在调用 Parser 后显式发生：

| 调用路径 | 写入时机 |
| --- | --- |
| 普通详情、数字 ID 直达、搜索重定向、搜索补全 | 成功解析且标题非空后记录详情及关联目标 |
| 搜索结果、推荐、文库、排行、完结、展开列表分页 | 解析出列表项后记录摘要 |
| 验证页、无效详情、请求失败 | 不覆盖既有成功详情和关联目标 |

每类最多保留 256 项，复用现有容量常量；使用 JDK LinkedHashMap 和短同步方法，按最近写入顺序淘汰，不创建通用缓存类。更新旧键时先移除再写入，确保其移到末尾；不是 LinkedHashMap 默认的首次插入顺序，也不是读访问 LRU。读取／更新／淘汰在同一同步边界内，不在锁内执行 I/O 或回调。

这里是辅助元数据上限，不是宿主 Cache 的第二套正文缓存。没有 TTL，不跳过现有普通搜索的实时详情补全，也不引入新设置。宿主 Cache 的 6 小时规则保持独立。

淘汰后的回退行为必须明确：详情缺失时尝试摘要，再无则沿用空对象；关联链接缺失时使用已有关键词搜索。已打开页面保持其目标 URL，不随索引淘汰改变。同名标签在 API 3 只携带显示文本的限制下仍按最近有效解析结果关联，不承诺区分所有同名语义。

存储是可丢弃的源实例状态，构造时为空；不序列化，不改数据库，不清理用户阅读记录。API 3 无完整卸载生命周期，本轮不虚构一个宿主不会调用的 dispose 接口。

### 6.3 映射只有一份

`LinovelibBookMapping.kt` 集中放置现有具体转换函数：列表摘要到 ParsedBookInformation、ParsedBookInformation 到宿主书籍类型、目录到宿主卷／章节。沿用原章节编号、未知日期和其他字段默认值。

WebDataSource 和 SearchProvider 都调用同一份摘要转换，再调用同一份宿主转换。作者标签、出版社、封面与缺省字段因此一致；不再从 SearchProvider 文件借用公共映射函数。正文格式化、图片本地化和 ContentBuilder 仍留在正文加载链路中，不搬进纯映射函数。

搜索维持一个书籍 ID 对应一个已展示的 MutableBookInformation，详情补全更新同一对象；不得改成重新发出多条书籍事件导致列表重复，也不得把这个可变对象存进 MetadataStore。

## 7. 兼容性与暂缓事项

- 保留现有 Route 反射适配、API compileOnly 和打包 ABI 检查；不直接链接旧发行宿主缺失的 Route 或 coroutine Mutex。
- 通过 API 3 的实际依赖验证 Map getter 可覆写及签名兼容，不能仅根据当前 API 4 源码得出结论。
- 保留图片失败时省略受保护图片块的既有规则；单图恢复、图片磁盘回收和空间配额另立需求，不混入本轮结构调整。
- 保留封面修正组件及其活动数据源检查，不扩展到阅读进度迁移或其他源数据。
- 动态页面进程恢复、宿主展开页错误显示和下拉刷新是明确的既有边界，不作为本轮成功条件。
- 快速筛选时，插件保证取消后不继续发出结果；宿主旧 emit 已进入同步 collector 后的追加不能由插件撤回。API 3 的 ViewModel 先清列表再取消旧任务，因此界面上的极端交错仍需设备观察，不能承诺本轮消除所有宿主列表追加竞态。
- 不批量移动 source 包，不按每个数据类拆文件，不重写已经有效的 RequestPolicy／RequestCoordinator。

## 8. 验证设计

测试应围绕真实调用边界。此前原有 95 项单测通过；额外临时探针已经复现共享分页失败。这是现状证据，不代表下面的新结构已通过验证。

为直接测试生产正文分页循环，将现有 `getChapterContentPages` 原样移为 `LinovelibWebDataSource.kt` 内的 internal 顶层函数，参数为 chapterId、bookId、parser、diagnostics、`loadPage: suspend (String) -> LinovelibHtmlResponse` 和 host。WebDataSource 正式入口与本地服务测试共同调用这一函数。只开放最小测试接入点，不新增 ChapterLoader 类，不改循环、ContentBuilder 或图片本地化算法。纯分页测试验证 Parsed 结果，最终宿主正文组件与插图显示另做设备回归。

| 验证项 | 最小有效检查 | 通过条件 |
| --- | --- | --- |
| 两个相同展开页 | 从两次 Provider Map 属性读取取得 A、B；分别收集，关闭 B 后继续 A | 实例及 Filter 不同，A 能继续加载，无相互取消 |
| 普通更多与固定文库 | 同样覆盖固定文库、动态书单和标签入口 | 全部走新实例边界，不只标签例外 |
| 同页面筛选重收集 | 覆盖旧 finally 比较／清理交错，及旧任务取消后晚发布通道的交错 | 新通道保持有效；新条件从第一页开始；插件取消后不再发出结果 |
| Map 与登记 | 同一 Map 重复取值、两份 Map 取值、重复登记同内容 | 同一 Map 内实例稳定，不同 Map 的实例独立；重复点击不增定义；构造零请求 |
| HTTP 状态与可用性 | 本地 HTTP 服务返回 404、429、5xx、成功；主动检查另测传输失败与取消 | 重试次数／冷却不变；资源错误不把全源置离线；取消原样传播 |
| 可用性发布顺序 | 主动检查最终失败与普通成功请求交错，分别交换执行顺序 | 状态跟随请求 slot 内最后发布的结果，不被旧外层回调覆盖 |
| 搜索完整请求链 | 本地服务依次校验 JS、CSS、redeem、结果请求头与 Cookie | 顺序与隔离正确，结果携带最终 URL，不重复重试 |
| 正文章节组合 | 本地服务模拟重定向、两页同章、下一章、重复 URL | 基于最终 URL 解析，相同章节拼接，停止条件和正文输出保持 |
| 元数据与映射 | 第 257 项写入、重写旧键后淘汰、无效详情、两条摘要回退路径 | 容量与最近写入顺序正确、成功数据不被坏页面覆盖、两条路径字段一致 |
| 原有回归 | 既有 Parser、图片、封面、章节 ID、搜索、筛选测试 | 已确认的正常行为保持；按状态边界调整测试，不删除有效覆盖 |
| 打包与设备 | Debug／Release ABI 检查；API 3 宿主叠页返回、筛选、阅读和插图 | 编译、ABI、设备结果分别记录；发行签名覆盖升级另验 |

已有直接在同一数据源上开两个 collector 的探针保留为旧问题证据。修复后的验收应模拟宿主每个 ViewModel 各取一次数据源，而不是强迫单实例提供宿主未要求的任意多订阅语义。

## 9. 交付顺序

先建立 Provider 实例隔离及其回归，再完成 HTTP 抽离和离线状态边界，最后收敛 Parser 状态、元数据和映射。每一部分可独立审查，不同时升级 API、修改正文算法或改变发布身份。

这只是依赖顺序，不是已授权执行的实施计划。设计审查完成后，实际实现仍需以用户后续指令为准。

## 10. 源码证据

- 插件 `LinovelibWebDataSource.kt:52`、`:196`、`:252`、`:408`：宿主入口、搜索会话、请求与正文编排的当前边界。
- 插件 `LinovelibRelatedSearch.kt:32`、`:39`、`:75`：分页通道归属及清理。
- 插件 `LinovelibExplorePageProvider.kt:19`、`:43`、`:54`：筛选对象、普通更多和关联页复用。
- 插件 `LinovelibHtmlParser.kt:66`、`:102`、`:297`：隐式缓存与关联链接索引。
- 插件 `LinovelibSearchDetails.kt:28`、`LinovelibSearchProvider.kt:145`、`LinovelibWebDataSource.kt:530`：重复摘要和宿主映射。
- 本体 `6ea57a9` 的 `api/.../explore/AbstractDefaultExplorePageProvider.kt:15` 与 `ExplorePageProvider.kt:26`：Map 属性和基类登记契约。
- 本体 `6ea57a9` 的 `app/.../explore/expanded/ExpandedPageViewModel.kt:32`、`:61`、`:77`：实例取得、重收集和 loadMore 调用。
- 临时探针 `/private/tmp/linovelib-structure-probe/LinovelibSharedPaginationProbeTest.kt:14` 与 `result.xml`：双 collector 共享状态失败证据，未写入生产源码。
