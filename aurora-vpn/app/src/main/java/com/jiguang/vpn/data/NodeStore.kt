package com.jiguang.vpn.data

import com.jiguang.vpn.core.Link
import com.jiguang.vpn.core.SingBoxConfig
import com.jiguang.vpn.model.ProxyNode

/**
 * 跨页面共享的线路数据(进程内单例)。
 * MainActivity 流式更新订阅后写入,NodePickerActivity 直接读取,避免重复解析。
 */
object NodeStore {

    @Volatile
    var links: List<Link> = emptyList()
        private set

    @Volatile
    var tags: List<String> = emptyList()
        private set

    @Volatile
    var selectedIndex: Int = -1

    private val seen = HashSet<String>()

    val isEmpty: Boolean get() = links.isEmpty()

    /** 清空(开始新一轮订阅时调用)。 */
    @Synchronized
    fun clear() {
        links = emptyList()
        tags = emptyList()
        selectedIndex = -1
        seen.clear()
    }

    /**
     * 追加一批节点(自动排除 ss/ssr),按 URI 去重后重建 links/tags。
     * @return 新增条数
     */
    @Synchronized
    fun append(nodes: List<ProxyNode>): Int {
        val fresh = ArrayList<Link>(nodes.size)
        for (n in nodes) {
            val low = n.uri.lowercase()
            if (low.startsWith("ss://") || low.startsWith("ssr://")) continue
            if (!seen.add(n.uri)) continue
            fresh.add(Link(n.uri, n.name.ifBlank { n.uri }))
        }
        if (fresh.isEmpty()) return 0
        val (kept, keptTags) = SingBoxConfig.supportedLinks(fresh)
        if (kept.isEmpty()) return 0
        links = links + kept
        tags = tags + keptTags
        return kept.size
    }

    /** 整表替换(如加载本地缓存)。 */
    @Synchronized
    fun replace(nodes: List<ProxyNode>) {
        clear()
        append(nodes)
    }

    /** 缓存序列化:每行 `uri\tname`。 */
    fun toCacheText(): String = links.joinToString("\n") { it.uri.replace("\t", " ") + "\t" + it.name.replace("\t", " ") }

    /** 解析缓存文本(兼容旧版仅 uri 的格式)。 */
    fun parseCacheText(raw: String): List<ProxyNode> =
        raw.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val i = line.indexOf('\t')
                if (i < 0) ProxyNode(uri = line.trim(), protocol = protocolOf(line), name = "")
                else ProxyNode(
                    uri = line.substring(0, i).trim(),
                    protocol = protocolOf(line.substring(0, i)),
                    name = line.substring(i + 1).trim()
                )
            }
            .filter { it.uri.isNotEmpty() }
            .toList()

    private fun protocolOf(uri: String) = uri.substringBefore("://", "unknown").lowercase()
}