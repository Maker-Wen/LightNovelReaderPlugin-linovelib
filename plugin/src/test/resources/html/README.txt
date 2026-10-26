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
