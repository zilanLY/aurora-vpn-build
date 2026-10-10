package com.jiguang.vpn.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * 通过服务器 IP 判定节点所在国家/地区。
 *
 * 判定优先级：
 * 1. 节点名称关键词（RegionDetect，完全离线）
 * 2. 在线 IP 地理 API（ip-api.com 批量查询，100 条/批，免费无 Key）
 *
 * 结果按「服务器主机」持久缓存，命中缓存不产生任何网络请求；
 * 域名节点先做 DNS 解析取 IP（失败退化为域名本身）。
 */
object RegionResolver {

    data class Entry(val region: String, val source: String)

    private val cache = ConcurrentHashMap<String, Entry>()

    private const val PREFS = "jiguang_region"
    private const val KEY_MAP = "region_map"

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MAP, null) ?: return
        for (line in raw.split('\n')) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            val host = line.substring(0, idx)
            val value = line.substring(idx + 1)
            val sep = value.indexOf('|')
            if (sep < 0) continue
            cache[host] = Entry(value.substring(0, sep), value.substring(sep + 1))
        }
    }

    fun regionOf(context: Context, serverHost: String, nodeName: String): Entry {
        cache[serverHost]?.let { return it }

        val nameHit = com.jiguang.vpn.util.RegionDetect.of(nodeName)
        if (nameHit != "其他") {
            val e = Entry(nameHit, "name")
            cache[serverHost] = e
            return e
        }

        val e = Entry("其他", "none")
        cache[serverHost] = e
        return e
    }

    /** 对仍为「其他」的节点做一次在线批量查询（在 IO 协程中调用）。 */
    fun refreshUnknownOnline(context: Context, hosts: List<String>) {
        val targets = hosts.filter { cache[it]?.source != "api" }
            .map { it to (resolveIp(it) ?: it) }
            .toMap()
        if (targets.isEmpty()) return
        for (batch in targets.entries.chunked(100)) {
            // country 供参考,countryCode 经 countryName() 映射中文,避免徽章显示英文国家名
            val url = "http://ip-api.com/batch?fields=status,country,countryCode,query"
            try {
                val body = postJson(url, "[${batch.joinToString(",") { "\"" + jsonEscape(it.value) + "\"" }}]") ?: break
                val rows = com.jiguang.vpn.data.RegionApiParser.parse(body)
                var i = 0
                for ((orig, _) in batch) {
                    if (i >= rows.size) break
                    val row = rows[i++]
                    val country = row.countryCode?.takeIf { it.isNotBlank() }
                        ?.let { countryName(it) }
                        ?: row.country
                        ?: continue
                    cache[orig] = Entry(country, "api")
                }
            } catch (_: Exception) {
                break
            }
        }
        persist(context)
    }

    private fun postJson(url: String, body: String): String? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: Exception) {
        null
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            else -> append(c)
        }
    }

    private fun persist(context: Context) {
        val sb = StringBuilder()
        for ((k, v) in cache.entries) sb.append(k).append('=').append(v.region).append('|').append(v.source).append('\n')
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAP, sb.toString()).apply()
    }

    private fun resolveIp(host: String): String? {
        return try {
            val addr = java.net.InetAddress.getByName(host)
            // 已是 IP 字面量时 getByName 直接返回原地址；域名则做一次 DNS 解析
            addr.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    fun countryName(code: String): String = when (code.uppercase()) {
        "HK" -> "香港"; "TW" -> "台湾"; "JP" -> "日本"; "SG" -> "新加坡"
        "US" -> "美国"; "KR" -> "韩国"; "GB", "UK" -> "英国"; "DE" -> "德国"
        "FR" -> "法国"; "CA" -> "加拿大"; "AU" -> "澳大利亚"; "RU" -> "俄罗斯"
        "IN" -> "印度"; "NL" -> "荷兰"; "MY" -> "马来西亚"; "TH" -> "泰国"
        "VN" -> "越南"; "PH" -> "菲律宾"; "BR" -> "巴西"; "TR" -> "土耳其"
        "IT" -> "意大利"; "ES" -> "西班牙"; "AR" -> "阿根廷"
        "CN" -> "中国"; "MO" -> "澳门"; "ID" -> "印尼"; "MX" -> "墨西哥"
        "AE" -> "阿联酋"; "SA" -> "沙特"; "ZA" -> "南非"; "UA" -> "乌克兰"
        "PL" -> "波兰"; "SE" -> "瑞典"; "CH" -> "瑞士"; "AT" -> "奥地利"
        "IE" -> "爱尔兰"; "NO" -> "挪威"; "DK" -> "丹麦"; "FI" -> "芬兰"
        else -> if (code.length == 2) code.uppercase() else "其他"
    }
}
