package com.jiguang.vpn.util

/**
 * 依据节点名称/标签保守识别地区，不依赖网络查询，也不伪造结果。
 */
object RegionDetect {

    private val regions = listOf(
        "香港" to listOf("香港", "hongkong", "hong kong", "hk"),
        "台湾" to listOf("台湾", "臺灣", "taiwan", "tw"),
        "日本" to listOf("日本", "japan", "tokyo", "osaka", "jp"),
        "新加坡" to listOf("新加坡", "狮城", "singapore", "sg"),
        "美国" to listOf("美国", "美國", "united states", "usa", "los angeles", "san jose", "seattle", "us"),
        "韩国" to listOf("韩国", "韓國", "korea", "seoul", "kr"),
        "英国" to listOf("英国", "英國", "united kingdom", "london", "uk"),
        "德国" to listOf("德国", "德國", "germany", "frankfurt", "de"),
        "法国" to listOf("法国", "法國", "france", "paris", "fr"),
        "加拿大" to listOf("加拿大", "canada", "ca"),
        "澳大利亚" to listOf("澳大利亚", "澳洲", "australia", "sydney", "au"),
        "俄罗斯" to listOf("俄罗斯", "俄羅斯", "russia", "moscow", "ru"),
        "印度" to listOf("印度", "india", "mumbai", "in"),
        "荷兰" to listOf("荷兰", "荷蘭", "netherlands", "amsterdam", "nl"),
        "马来西亚" to listOf("马来西亚", "馬來西亞", "malaysia", "my"),
        "泰国" to listOf("泰国", "泰國", "thailand", "th"),
        "越南" to listOf("越南", "vietnam", "vn"),
        "菲律宾" to listOf("菲律宾", "菲律賓", "philippines", "ph"),
        "巴西" to listOf("巴西", "brazil", "br"),
        "土耳其" to listOf("土耳其", "turkey", "tr"),
        "意大利" to listOf("意大利", "義大利", "italy", "it"),
        "西班牙" to listOf("西班牙", "spain", "es"),
        "阿根廷" to listOf("阿根廷", "argentina", "ar")
    )

    fun of(name: String): String {
        val lower = name.lowercase()
        val tokens = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()
        for ((label, keys) in regions) {
            for (key in keys) {
                if (key.length <= 2) {
                    if (key in tokens) return label
                } else if (lower.contains(key)) {
                    return label
                }
            }
        }
        return "其他"
    }
}
