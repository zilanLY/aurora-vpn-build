package com.jiguang.vpn.data

import android.content.Context
import com.jiguang.vpn.model.ProxyNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程级测速引擎:
 * - 结果持久化([LatencyCache]),进程/页面切换不丢失;
 * - 8 小时节流:未过期且节点集合未变时不重复本地探测;
 * - 在进程级协程中运行,离开页面不会中断,保证结果能保存;
 * - **失败/不支持节点也写入结果**(超时/待测),UI 不会卡在「测速中…」;
 * - 连接内核后 [recheckViaClash] 走真实代理链路复测并覆盖缓存(不受节流限制):
 *   本地探测只能判端口/TLS 可达,内核实测才是真实可用性,以后者为准。
 */
object LatencyEngine {

    private const val INTERVAL_MS = 8L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val values = ConcurrentHashMap<String, Int>()

    @Volatile
    private var running = false

    @Volatile
    private var rechecking = false

    @Volatile
    var source: String = "tcp"
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastFingerprint = 0

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        LatencyCache.load(appContext!!)?.let { snap ->
            source = snap.source
            lastFingerprint = snap.fingerprint
            values.putAll(snap.values)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 当前测速结果(tag -> ms)。 */
    fun current(): Map<String, Int> = HashMap(values)

    fun isRunning(): Boolean = running || rechecking

    fun sourceLabel(): String = if (source == "clash") "经内核代理链路" else "TCP/TLS 探测"

    /** 若结果过期或节点集合变化则探测一次(8h 节流),否则复用。 */
    fun ensureFresh(viaClash: Boolean) {
        val ctx = appContext ?: return
        if (running || isFresh(ctx)) return
        startInternal(ctx, viaClash)
    }

    /** 手动重新测速。 */
    fun rerun(viaClash: Boolean) {
        val ctx = appContext ?: return
        if (running) return
        startInternal(ctx, viaClash)
    }

    /**
     * 内核已连接后的真实链路复测:覆盖本地探测结果并立即持久化。
     * 这是可用性的最终依据(NodeSelector 优先采用)。
     */
    fun recheckViaClash() {
        val ctx = appContext ?: return
        if (rechecking || running) return
        if (NodeStore.tags.isEmpty()) return
        rechecking = true
        notifyChanged()
        scope.launch {
            try {
                source = "clash"
                val onResult: (String, Int) -> Unit = { tag, ms ->
                    values[tag] = ms
                }
                val res = LatencyTester.clashApi(NodeStore.tags, onResult)
                if (res == null) {
                    // Clash API 不可用(内核未就绪等):保留原结果,不覆盖
                    source = if (values.isEmpty()) "tcp" else source
                } else {
                    persist(ctx)
                    notifyChanged()
                }
            } catch (_: Throwable) {
            } finally {
                rechecking = false
                persist(ctx)
                notifyChanged()
            }
        }
    }

    /**
     * 增量测速:只补齐没有结果的节点,已有结果直接复用(进线路页时调用,秒出可用列表)。
     * 与 8h 全量节流(ensureFresh)互不干扰。
     */
    fun ensureIncremental() {
        val ctx = appContext ?: return
        if (running || rechecking) return
        if (NodeStore.links.isEmpty()) return
        val missing = NodeStore.tags.filter { !values.containsKey(it) }
        if (missing.isEmpty()) return
        running = true
        notifyChanged()
        scope.launch {
            try {
                val uriByTag = HashMap<String, String>(missing.size)
                NodeStore.tags.forEachIndexed { i, tag ->
                    if (tag in missing) NodeStore.links.getOrNull(i)?.let { uriByTag[tag] = it.uri }
                }
                var done = 0
                val onResult: (String, Int) -> Unit = { tag, ms ->
                    values[tag] = ms
                    done++
                    if (done % 5 == 0) {
                        persist(ctx)
                        notifyChanged()
                    }
                }
                source = "tcp"
                LatencyTester.tcpPing(uriByTag, onResult)
            } catch (_: Throwable) {
            } finally {
                persist(ctx)
                running = false
                notifyChanged()
            }
        }
    }

    private fun isFresh(ctx: Context): Boolean {
        val snap = LatencyCache.load(ctx) ?: return false
        if (snap.values.isEmpty()) return false
        if (System.currentTimeMillis() - snap.at >= INTERVAL_MS) return false
        val fp = fingerprint()
        if (snap.fingerprint != 0 && snap.fingerprint != fp) return false
        return true
    }

    private fun startInternal(ctx: Context, viaClash: Boolean) {
        val links = NodeStore.links
        if (links.isEmpty()) return
        val fp = fingerprint()
        if (fp != lastFingerprint) {
            values.clear()
            lastFingerprint = fp
        }
        running = true
        notifyChanged()
        scope.launch {
            try {
                val uriByTag = HashMap<String, String>(links.size)
                links.forEachIndexed { i, link ->
                    NodeStore.tags.getOrNull(i)?.let { uriByTag[it] = link.uri }
                }
                var done = 0
                val onResult: (String, Int) -> Unit = { tag, ms ->
                    values[tag] = ms
                    done++
                    if (done % 5 == 0) {
                        persist(ctx)
                        notifyChanged()
                    }
                }
                if (viaClash) {
                    source = "clash"
                    val res = LatencyTester.clashApi(NodeStore.tags, onResult)
                    if (res == null) {
                        source = "tcp"
                        LatencyTester.tcpPing(uriByTag, onResult)
                    }
                } else {
                    source = "tcp"
                    LatencyTester.tcpPing(uriByTag, onResult)
                }
            } catch (_: Throwable) {
            } finally {
                persist(ctx)
                running = false
                notifyChanged()
            }
        }
    }

    private fun fingerprint(): Int =
        LatencyCache.fingerprint(NodeStore.links.map { ProxyNode(it.uri, "", it.name) })

    private fun persist(ctx: Context) {
        LatencyCache.save(ctx, source, HashMap(values), lastFingerprint)
    }

    private fun notifyChanged() {
        for (l in listeners) {
            try {
                l()
            } catch (_: Throwable) {
            }
        }
    }
}
