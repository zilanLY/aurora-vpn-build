package com.jiguang.vpn.data

import android.content.Context
import com.jiguang.vpn.model.ProxyNode

/**
 * 测速结果的持久化缓存(跨进程/重启生效),用于 8 小时节流:
 * 未到间隔时进入线路页直接复用上次结果,不重新测速。
 */
object LatencyCache {

    private const val PREFS = "jiguang_latency"
    private const val KEY_AT = "at"
    private const val KEY_SOURCE = "source"
    private const val KEY_VALUES = "values" // 每行 `tag\tms`
    private const val KEY_FP = "fingerprint"

    data class Snapshot(val at: Long, val source: String, val values: Map<String, Int>, val fingerprint: Int)

    fun load(context: Context): Snapshot? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        val source = p.getString(KEY_SOURCE, "tcp") ?: "tcp"
        val raw = p.getString(KEY_VALUES, null)
        return Snapshot(at, source, LatencyCodec.decode(raw), p.getInt(KEY_FP, 0))
    }

    fun save(context: Context, source: String, values: Map<String, Int>, fingerprint: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, source)
            .putString(KEY_VALUES, LatencyCodec.encode(values))
            .putInt(KEY_FP, fingerprint)
            .apply()
    }

    /** 节点集合指纹(订阅更新后旧延迟失效)。 */
    fun fingerprint(nodes: List<ProxyNode>): Int = nodes.joinToString("\n") { it.uri }.hashCode()
}

/** 测速结果的编解码(纯函数,便于单测)。格式:每行 `tag\tms`。 */
object LatencyCodec {

    fun encode(values: Map<String, Int>): String {
        val sb = StringBuilder()
        for ((k, v) in values) sb.append(k).append('\t').append(v).append('\n')
        return sb.toString()
    }

    fun decode(raw: String?): Map<String, Int> {
        val values = HashMap<String, Int>()
        if (raw.isNullOrEmpty()) return values
        for (line in raw.lineSequence()) {
            if (line.isBlank()) continue
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val ms = line.substring(i + 1).toIntOrNull() ?: continue
            values[line.substring(0, i)] = ms
        }
        return values
    }
}