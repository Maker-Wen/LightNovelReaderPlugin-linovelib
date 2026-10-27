package io.nightfish.lightnovelreader.plugin.linovelib.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinovelibHomeWenkuTest {
    @Test
    fun capturedHomepageKeepsEveryWenkuGroupAndItsBookOrder() {
        val html = javaClass.getResource("/html/home-modules-20261027.html")!!.readText()
        val rows = LinovelibHtmlParser().parseExploreRows(html)
        val expectedGroups = listOf(
            "电击文库" to listOf("2059", "5497", "3868", "4451", "1410", "4629", "28", "4124", "5494", "4354"),
            "富士见文库" to listOf("4649", "3805", "2734", "5429", "1305", "1", "5134", "53", "2399", "5251"),
            "角川文库" to listOf("75", "2013", "2044", "6", "2829", "2342", "5496", "2782", "3109", "2499"),
            "MF文库J" to listOf("5370", "2139", "3768", "8", "9", "5392", "2890", "4648", "3091", "3870"),
            "Fami通文库" to listOf("2014", "5457", "88", "90", "3676", "93", "2418", "4333", "87", "91"),
            "GA文库" to listOf("2906", "3", "4097", "3149", "3006", "2547", "3586", "4878", "3603", "2356"),
            "HJ文库" to listOf("2353", "2831", "2322", "2845", "2361", "2960", "5109", "629", "119", "634"),
            "一迅社" to listOf("131", "129", "130", "132", "133", "134", "135", "136", "137", "138"),
            "集英社" to listOf("2727", "1534", "5478", "4315", "7", "144", "145", "146", "147", "148"),
            "小学馆" to listOf("3095", "2", "3914", "3584", "4385", "3124", "2923", "159", "168", "3008"),
            "讲谈社" to listOf("4613", "1804", "177", "3581", "19", "172", "173", "174", "175", "176"),
            "少女文库" to listOf("3042", "638", "187", "188", "189", "190", "191", "192", "193", "194"),
            "其他文库" to listOf("5491", "5493", "3285", "2848", "5492", "73", "2841", "2600", "71", "77"),
            "华文轻小说" to listOf("5209", "4971", "4678", "4916", "4222", "5045", "5378", "5131", "4880", "2679"),
            "Web轻小说" to listOf("4386", "5440", "5428", "5470", "3193", "5336", "4138", "4672", "3645", "3961"),
            "韩国轻小说" to listOf("4519", "4279", "4915", "5340", "5445", "3410", "5485", "4748", "2985", "3090")
        )

        assertEquals(
            listOf("勇者新推", "新书抢鲜", "点击榜", "推荐榜", "新书榜", "鲜花榜") +
                expectedGroups.map { it.first } + "人气完本",
            rows.map { it.title }
        )
        val groups = rows.subList(6, 22)
        assertEquals(expectedGroups, groups.map { row -> row.title to row.books.map { it.id } })
        // The shared /sort/ destination is a category index, not a group book list.
        groups.forEach { assertNull(it.expandedUrl) }
    }
}
