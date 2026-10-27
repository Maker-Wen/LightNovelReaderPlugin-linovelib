真实详情页提取样本

用于离线回归，不是人为构造的备用页面结构。
保留原页面的 meta[property^=og:]、#bookDetailWrapper、#bookSummary 和 .book-status；
节点由 Jsoup 1.22.1 直接克隆并序列化，换行统一为 LF 并去除行尾空白；不改解析字段值或节点层级。
已用删除前解析器确认：提取样本与对应完整 HTML 的全部 ParsedBookInformation 字段相同。
完整原始响应另存本次真实页面回归证据；以下 SHA-256 对应完整响应，不是提取文件。

book-1.html
URL: https://www.bilinovel.net/novel/1.html
Fetched UTC: 2026-10-20T06:13:48.177958+00:00
Original response SHA-256: 660adce30334fd69d2ffd69fca23954490326c465239de9ef9d20db949ae93dc
Fixture SHA-256: 5ac8e109708c6779c749c20cfb8df247fb45c119992c633d4d48ba13c2301e0e

book-3095.html
URL: https://www.bilinovel.net/novel/3095.html
Fetched UTC: 2026-10-20T06:13:52.229825+00:00
Original response SHA-256: dea19a0a0173139dbd348b0515ddb42b0e2edd52e32d4ad1b474d7c881e9e5c4
Fixture SHA-256: 2f1d36f1ca1d880c7c1ce83deaa092df4d3b0a3c11a11dd72f8f29f4044bfe10

list-title-3343.html
真实榜单卡片片段，用于校验完整书名末尾本来包含省略号；不假设它是服务器截短。
URL: https://www.bilinovel.net/top/monthvisit/1.html
Fetched UTC: 2026-10-16T08:19:00.867582+00:00
Original response SHA-256: 616d6d98bb30e89951f82762695ba763d76d1611c54cc8561cca078a425122f5
提取方式：原始响应中字符 9154..9882 的完整 a.book-layout 原样子串，仅添加文件末尾 LF；未改属性、标题或嵌套结构。
详情 https://www.bilinovel.net/novel/3343.html 的 h1 与 og:title 同为“妹妹是不能当女友的，可是……”。
Fixture SHA-256: eaae2e5b5fa82ffa3964702a9b566c384ac45bcf0cf342ed9d6ba45b260600a8

home-modules-20261027.html
真实首页模块样本，用于首页四榜、文库推荐分组、更多入口的原始解析及 API 3 推荐栏目投影回归。
URL: https://www.bilinovel.net/
Fetched UTC: 2026-10-27T06:26:27Z
Original response SHA-256: 8e392eb0a02c43d6ba0d616d7f8a986a2545139bafa1d4b890d226da7cb2d1b9
提取方式：Jsoup 1.22.1 选择所有 .module，按文档顺序以 outerHtml 序列化；统一 LF 并去除行尾空白。
保留模块内全部节点、属性、书目和顺序，不人为添加面板或书籍。
已用修复前解析器确认：提取样本与完整首页的全部 ParsedExploreRow 字段相同。
排行榜四个标签依次为点击榜、推荐榜、新书榜、鲜花榜，每个面板各 10 本；跨榜存在相同书籍。
同日浏览器实点确认：切换榜单仅切换书目，模块唯一“更多”始终指向 /top/monthvisit/1.html。
Parser 将该入口保留在默认点击榜行，其他三榜不推导独立展开地址；这些首页榜单行不投影到宿主推荐栏目。
文库推荐由 sort、sort1、sort2、sort3 四套标签栏与相邻面板组成，共 16 组，每组 10 本。
同日浏览器实点确认：四套标签栏分别切换自己的书目，“更多”统一进入 /sort/ 文库分类索引。
文库各组不推导展开地址；Parser 按网页模块顺序解析全部分组，本样本合计 23 行。
Provider 仅选择勇者新推、新书抢鲜、人气完本三组投影到宿主推荐栏目，保留来源完整书目与书序；本样本依次为 10、6、6 本，不将原始 23 行全部展示。
Fixture SHA-256: 8adfd62703f88e56cbf619ca2925bba8daf0a9cf56af55e97ff57b70285bb200

catalog-5209.html
真实四卷目录样本，用于明确卷封面与章节关联回归。
URL: https://www.bilinovel.net/novel/5209/catalog
Fetched: 2026-10-27
提取方式：Jsoup 选择 #volumes .catalog-volume，保留每卷全部节点，以 outerHtml 序列化并包入 #volumes。
未修改卷名、封面地址、章节及占位图；广告块位于卷外，未保留。
Original response SHA-256: 2e220686a5b5f84d062c391e3395bbfb5e9a406b5894448d407e3b664c292e6d
Fixture SHA-256: 64ba4c4f373d7467408087441535a0b87acdd54f18892f14fac159c6ffe381fb
