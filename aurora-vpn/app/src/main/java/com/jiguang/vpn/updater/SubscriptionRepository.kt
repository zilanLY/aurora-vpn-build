package com.jiguang.vpn.updater

import com.jiguang.vpn.model.ProxyNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 订阅仓库:负责拉取与解析订阅源,并以**流式**方式逐批产出节点,
 * 让 UI 尽早拿到可用线路,不必等待全部来源加载完成。
 *
 * 来源优先级(默认订阅 = 非 ss 节点最多且体积最小的 [V2RAYFREE]):
 * 1. v2rayfree    — 每日 v2ray base64,约 320 条,~160KB(默认)
 * 2. clashfree    — 每日 Clash YAML,约 327 条,~640KB
 * 3. mibei        — 米贝分享,约 290 条
 * 4. pawdroid     — 约 10 条
 * 5. shaoyou      — base64 + YAML,约 7 条
 *
 * 全部来源自动排除 ss/ssr 协议。
 */
object SubscriptionRepository {

    val DEFAULT_SOURCE = SourceId.V2RAYFREE

    enum class SourceId(val label: String) {
        V2RAYFREE("V2RayFree"),
        CLASHFREE("ClashFree"),
        MIBEI("米贝分享"),
        PAWDROID("Pawdroid"),
        SHAOYOU("ShaoyouFree")
    }

    /** 单批产出:[source] 为来源标识,null 表示这是收尾批(全部来源结束)。 */
    data class Batch(val source: SourceId?, val nodes: List<ProxyNode>)

    /**
     * 流式抓取:按来源优先级顺序,每解析完一个来源立即 emit 一批;
     * 流结束时 emit 一个 [Batch.source] == null 的收尾批。
     */
    fun stream(): Flow<Batch> = flow {
        for (source in orderedSources()) {
            val nodes = try {
                fetchSource(source)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (nodes.isNotEmpty()) emit(Batch(source, dedupNonSs(nodes)))
        }
        emit(Batch(null, emptyList()))
    }.flowOn(Dispatchers.IO)

    private fun orderedSources(): List<SourceId> {
        val all = listOf(
            SourceId.V2RAYFREE, SourceId.CLASHFREE, SourceId.MIBEI,
            SourceId.PAWDROID, SourceId.SHAOYOU
        )
        // 默认订阅置顶
        return listOf(DEFAULT_SOURCE) + all.filter { it != DEFAULT_SOURCE }
    }

    /** 供单一来源刷新(手动「刷新订阅」仍走多来源流式)。 */
    suspend fun fetchSingle(source: SourceId): List<ProxyNode> = withContext(Dispatchers.IO) {
        try {
            dedupNonSs(fetchSource(source))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchSource(source: SourceId): List<ProxyNode> = when (source) {
        SourceId.V2RAYFREE -> fetchBase64(Endpoints.V2RAYFREE)
        SourceId.PAWDROID -> fetchBase64(Endpoints.PAWDROID)
        SourceId.SHAOYOU -> fetchBase64(Endpoints.SHAOYOU_TXT) + fetchYaml(Endpoints.SHAOYOU_YAML)
        SourceId.CLASHFREE -> fetchClashFree()
        SourceId.MIBEI -> fetchMiBei()
    }

    // ---------- 各来源实现 ----------

    private fun fetchBase64(url: String): List<ProxyNode> =
        NodeParser.parseTxt(Fetcher.get(url))

    private fun fetchYaml(url: String): List<ProxyNode> =
        ClashYaml.parse(Fetcher.get(url))

    private fun fetchClashFree(): List<ProxyNode> {
        val zone = ZoneId.of("Asia/Shanghai")
        var last: Exception? = null
        for (back in 0..3) {
            val d = LocalDate.now(zone).minusDays(back.toLong())
            val u = Endpoints.CLASH_DAILY.format(d.year, d.monthValue, d.dayOfMonth)
            try {
                val body = Fetcher.get(u)
                if (body.contains("proxies:")) return ClashYaml.parse(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IllegalStateException("clashfree 无可用日期")
    }

    private fun fetchMiBei(): List<ProxyNode> {
        // 1) HTML 主链
        try {
            val listHtml = Fetcher.get(Endpoints.MIBEI_BASE + "/free-nodes/")
            val detailPath = MIBEI_DETAIL.find(listHtml)?.value
            if (detailPath != null) {
                val detailHtml = Fetcher.get(Endpoints.MIBEI_BASE + detailPath)
                val urls = MIBEI_SUB.findAll(detailHtml).map { it.value }.distinct().toList()
                val ordered = urls.sortedBy { if (it.endsWith(".txt")) 0 else 1 }
                for (u in ordered) {
                    try {
                        val nodes = NodeParser.parseTxt(Fetcher.get(u))
                        if (nodes.isNotEmpty()) return nodes
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        // 2) 日期直猜兜底
        val zone = ZoneId.of("Asia/Shanghai")
        for (back in 0..3) {
            val d = LocalDate.now(zone).minusDays(back.toLong())
            val u = "https://node.mibeifenxiang.com/uploads/%d/%02d/%d%02d%02d.txt"
                .format(d.year, d.monthValue, d.year, d.monthValue, d.dayOfMonth)
            try {
                val nodes = NodeParser.parseTxt(Fetcher.get(u))
                if (nodes.isNotEmpty()) return nodes
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return emptyList()
    }

    // ---------- 工具 ----------

    private fun dedupNonSs(nodes: List<ProxyNode>): List<ProxyNode> {
        val seen = HashSet<String>()
        return nodes.filter {
            val low = it.uri.lowercase()
            !low.startsWith("ss://") && !low.startsWith("ssr://") && seen.add(it.uri)
        }
    }

    private val MIBEI_DETAIL = Regex("""/free-nodes/[^"']+\.htm""")
    private val MIBEI_SUB =
        Regex("""https://node\.mibeifenxiang\.com/uploads/\d{4}/\d{2}/\d{8}\.(?:txt|yaml|json)""")

    private object Endpoints {
        const val V2RAYFREE = "https://raw.githubusercontent.com/free-nodes/v2rayfree/main/sub"
        const val PAWDROID = "https://raw.githubusercontent.com/Pawdroid/Free-servers/main/sub"
        const val SHAOYOU_TXT = "https://raw.githubusercontent.com/shaoyouvip/free/main/base64.txt"
        const val SHAOYOU_YAML = "https://raw.githubusercontent.com/shaoyouvip/free/main/all.yaml"
        const val CLASH_DAILY = "https://raw.githubusercontent.com/free-nodes/clashfree/main/clash%04d%02d%02d.yml"
        const val MIBEI_BASE = "https://mibeifenxiang.com"
    }
}

/** 各来源共用的 HTTP 抓取工具。 */
internal object Fetcher {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36"

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()

    fun get(url: String): String {
        val req = okhttp3.Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code} $url")
            return resp.body?.string() ?: throw IllegalStateException("空响应 $url")
        }
    }
}