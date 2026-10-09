package io.nightfish.lightnovelreader.plugin.linovelib.source

import io.nightfish.lightnovelreader.api.util.local
import io.nightfish.lightnovelreader.api.web.explore.filter.Filter
import io.nightfish.lightnovelreader.api.web.explore.filter.SingleChoiceFilter

internal class LinovelibWenkuFilters(private val host: String) {
    // Values follow the public wenku page's data-filter-type / data-filter-value controls.
    private val region = WenkuChoice("地区", linkedMapOf(
        "不限" to "0", "日本轻小说" to "1", "华文轻小说" to "2", "Web轻小说" to "3",
        "轻改漫画" to "4", "韩国轻小说" to "5"
    ))
    private val theme = WenkuChoice("主题", linkedMapOf(
        "不限" to "0", "恋爱" to "64", "后宫" to "48", "校园" to "63", "百合" to "27",
        "转生" to "26", "异世界" to "47", "奇幻" to "15", "冒险" to "61", "欢乐向" to "222",
        "女性视角" to "231", "龙傲天" to "219", "魔法" to "96", "青春" to "67", "性转" to "31",
        "病娇" to "198", "妹妹" to "217", "青梅竹马" to "225", "战斗" to "18", "NTR" to "256",
        "人外" to "223", "大小姐" to "227", "黑暗" to "189", "悬疑" to "68", "科幻" to "56",
        "伪娘" to "201", "战争" to "55", "萝莉" to "185", "复仇" to "229", "斗智" to "199",
        "异能" to "131", "猎奇" to "241", "轻文学" to "191", "职场" to "60", "经营" to "226",
        "JK" to "246", "机战" to "135", "女儿" to "261", "末日" to "221", "犯罪" to "220",
        "旅行" to "239", "惊悚" to "124", "治愈" to "98", "推理" to "97", "日本文学" to "205",
        "游戏" to "248", "耽美" to "228", "美食" to "211", "群像" to "245", "大逃杀" to "249",
        "音乐" to "233", "格斗" to "132", "热血" to "28", "温馨" to "180", "脑洞" to "224",
        "恶役" to "328", "JC" to "304", "间谍" to "254", "竞技" to "146", "宅文化" to "263",
        "同人" to "333"
    ), description = "选择一个作品主题")
    private val order = WenkuChoice("排序", linkedMapOf(
        "最近更新" to "lastupdate", "最新入库" to "postdate", "周点击" to "weekvisit",
        "月点击" to "monthvisit", "周推荐" to "weekvote", "月推荐" to "monthvote",
        "周鲜花" to "weekflower", "月鲜花" to "monthflower", "字数" to "words", "收藏数" to "goodnum"
    ))
    private val animation = WenkuChoice("动画", linkedMapOf(
        "不限" to "0", "已动画化" to "1", "未动画化" to "2"
    ))
    private val words = WenkuChoice("字数", linkedMapOf(
        "不限" to "0", "30万以下" to "1", "30-50万" to "2", "50-100万" to "3",
        "100-200万" to "4", "200万以上" to "5"
    ))
    private val status = WenkuChoice("状态", linkedMapOf(
        "不限" to "0", "新书上传" to "1", "情节展开" to "2", "精彩纷呈" to "3",
        "接近尾声" to "4", "已经完本" to "5"
    ))

    val filters: List<Filter<*>> = listOf(region, theme, order, animation, words, status)

    fun url(page: Int = 1): String = LinovelibUrls.wenku(
        host, order.code, page, theme.code, status.code, animation.code, region.code, words.code
    )
}

private class WenkuChoice(
    title: String,
    private val codes: Map<String, String>,
    description: String = ""
) : SingleChoiceFilter(title.local(), title.local(), description.local(), codes.keys.toList(), codes.keys.first()) {
    val code: String get() = codes.getValue(value)
}
