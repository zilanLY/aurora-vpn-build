package com.jiguang.vpn.data

import com.jiguang.vpn.core.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * sing-box Clash API 只读封装(127.0.0.1:2081,内核已连接时可用)。
 * 仅用于主页状态展示:流量统计、出口确认;失败一律返回 null,不抛异常。
 */
object ClashApi {

    data class Traffic(val up: Long, val down: Long, val totalUp: Long, val totalDown: Long)

    private fun get(path: String, timeoutMs: Int = 2500): String? = try {
        val conn = java.net.URL("http://127.0.0.1:${SingBoxConfig.CLASH_API_PORT}$path").openConnection()
            as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        if (conn.responseCode == 200) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
    } catch (_: Exception) {
        null
    }

    /** /traffic 单次采样:{"up":n,"down":n}。 */
    suspend fun traffic(): Traffic? = withContext(Dispatchers.IO) {
        val body = get("/traffic") ?: return@withContext null
        try {
            val o = JSONObject(body)
            Traffic(o.optLong("up"), o.optLong("down"), 0, 0)
        } catch (_: Exception) {
            null
        }
    }

    /** /connections 聚合:当前活动连接数 + 累计上下行(内存态,进程生命周期内)。 */
    suspend fun connections(): Pair<Int, Traffic>? = withContext(Dispatchers.IO) {
        val body = get("/connections") ?: return@withContext null
        try {
            val o = JSONObject(body)
            val downloadTotal = o.optLong("downloadTotal")
            val uploadTotal = o.optLong("uploadTotal")
            val arr = o.optJSONArray("connections")
            var count = 0
            if (arr != null) count = arr.length()
            count to Traffic(0, 0, uploadTotal, downloadTotal)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 经代理链路查出口 IP 与国家(/proxies/global 或经节点直连均可,直接走系统网络栈即可,
     * 因为 tun 已开启,请求天然经代理)。返回 "ip|country" 或 null。
     */
    suspend fun exitIp(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("http://ip-api.com/json/?fields=query,countryCode").openConnection()
                as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            if (conn.responseCode != 200) return@withContext null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val o = JSONObject(body)
            val ip = o.optString("query")
            val cc = o.optString("countryCode")
            if (ip.isEmpty()) null else "$ip|$cc"
        } catch (_: Exception) {
            null
        }
    }
}
