package com.jiguang.vpn.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jiguang.vpn.core.Link
import com.jiguang.vpn.core.SingBoxConfig
import com.jiguang.vpn.data.LatencyEngine
import com.jiguang.vpn.data.LatencyTester
import com.jiguang.vpn.data.NodeStore
import com.jiguang.vpn.data.RegionResolver
import com.jiguang.vpn.service.JiguangVpnService
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 线路选择子页面:
 * - 顶部地区过滤(依据服务器 IP 判定的国家/地区)+ 搜索
 * - 每行显示地区徽章、节点名、协议、延迟(ms,颜色分级)
 * - 进入页面不自动测速,展示引擎已有结果(8h 节流,结果持久化)
 * - 点击节点即选中并返回;「自动优选」= 已测延迟最低的节点
 */
class NodePickerActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout
    private lateinit var filterBar: LinearLayout
    private lateinit var countView: TextView
    private lateinit var hintView: TextView
    private lateinit var searchInput: EditText

    private var latencies: MutableMap<String, Int> = HashMap() // key = tag
    private var activeFilter: String? = null
    private var query = ""
    private var density = 1f

    private val regionCache = HashMap<Int, String>()
    private var lastRenderedCount = 0

    /** 列表重建防抖:测速回调可能高频到来,全量重建列表会掉帧。 */
    private val renderDebounce = android.os.Handler(android.os.Looper.getMainLooper())
    private val renderDebounceRunnable = Runnable { renderList() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        RegionResolver.init(this)
        LatencyEngine.init(this)
        setContentView(buildLayout())
        // 只展示引擎已有结果,不在进入列表时自动测速(由 App 进入按 8h 触发)
        latencies = LatencyEngine.current().toMutableMap()
        renderFilter()
        renderList()
        if (latencies.isEmpty() && LatencyEngine.isRunning()) {
            showHint("正在测速…")
        } else if (latencies.isNotEmpty()) {
            showHint("上次测速结果(${LatencyEngine.sourceLabel()}) · 8 小时后自动刷新")
        } else {
            showHint("暂无测速结果,可点击「重新测速」")
        }
        NodeStoreBridge.addListener(storeListener)
        LatencyEngine.addListener(latencyListener)
        // 进页自动增量补测:只测没结果的节点,已有结果立即展示
        LatencyEngine.ensureIncremental()
        resolveRegions()
    }

    override fun onDestroy() {
        NodeStoreBridge.removeListener(storeListener)
        LatencyEngine.removeListener(latencyListener)
        super.onDestroy()
    }

    /** NodeStore 变更回调(流式加载时刷新列表)。 */
    private val storeListener: () -> Unit = {
        runOnUiThread {
            if (NodeStore.links.size != lastRenderedCount) {
                renderFilter()
                renderList()
            }
        }
    }

    /** 测速引擎更新回调(进程级,离开页面仍会回调到已销毁页面,需判空)。 */
    private val latencyListener: () -> Unit = {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            latencies = LatencyEngine.current().toMutableMap()
            if (LatencyEngine.isRunning()) {
                showHint("正在测速…(${latencies.size} 条已完成)")
            } else {
                showHint("测速完成(${LatencyEngine.sourceLabel()}) · ${latencies.size} 条已测")
            }
            // 300ms 防抖重建,避免测速回调高频全量重建列表导致掉帧
            renderDebounce.removeCallbacks(renderDebounceRunnable)
            renderDebounce.postDelayed(renderDebounceRunnable, 300)
        }
    }

    // ---------- 布局 ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(18), dp(14), dp(18), dp(12))
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val back = Button(this).apply {
            text = "←"
            textSize = 18f
            setTextColor(Theme.TEXT)
            background = Theme.ripple(Theme.rounded(Theme.CARD2, 14, density))
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(14), dp(4), dp(14), dp(6))
            setOnClickListener { finish() }
        }
        header.addView(back)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.apply {
            addView(TextView(this@NodePickerActivity).apply {
                text = "选择线路"
                textSize = 18f
                setTextColor(Theme.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(TextView(this@NodePickerActivity).apply {
                text = "实时测速 · 地区以服务器 IP 判定"
                textSize = 11f
                setTextColor(Theme.SUBTEXT)
            })
        })
        header.addView(Button(this).apply {
            text = "重新测速"
            isAllCaps = false
            textSize = 12f
            setTextColor(Theme.TEXT)
            background = Theme.ripple(Theme.rounded(Theme.CARD2, 14, density))
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { rerunLatency() }
        })
        val bestBtn = Button(this).apply {
            text = "自动优选"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = Theme.ripple(Theme.rounded(Theme.PRIMARY, 14, density))
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { pickBest() }
        }
        header.addView(
            bestBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(6) }
        )

        searchInput = EditText(this).apply {
            hint = "搜索线路名 / 地区"
            textSize = 13f
            setTextColor(Theme.TEXT)
            setHintTextColor(Theme.SUBTEXT)
            background = Theme.rounded(Theme.CARD2, 12, density)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim()?.lowercase(Locale.ROOT) ?: ""
                    renderList()
                }
            })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .hideSoftInputFromWindow(windowToken, 0)
                    true
                } else false
            }
        }

        countView = TextView(this).apply {
            text = "0 条线路"
            textSize = 11f
            setTextColor(Theme.SUBTEXT)
            setPadding(0, dp(10), 0, dp(4))
        }

        filterBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(filterBar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        hintView = TextView(this).apply {
            textSize = 11f
            setTextColor(Theme.SUBTEXT)
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(header)
        root.addView(searchInput, topMargin(dp(10)))
        root.addView(countView)
        root.addView(filterScroll, topMargin(dp(4)))
        root.addView(hintView)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) }
        )
        return root
    }

    private fun topMargin(margin: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = margin }

    // ---------- 地区 ----------

    private fun regionFor(index: Int, link: Link): String =
        regionCache.getOrPut(index) {
            RegionResolver.regionOf(this, SingBoxConfig.hostOf(link.uri) ?: "", link.name).region
        }

    private fun renderFilter() {
        filterBar.removeAllViews()
        val regions = linkedSetOf("全部")
        for ((i, l) in NodeStore.links.withIndex()) regions.add(regionFor(i, l))
        for (r in regions) {
            val active = (activeFilter == null && r == "全部") || activeFilter == r
            filterBar.addView(
                Button(this).apply {
                    text = r
                    isAllCaps = false
                    textSize = 12f
                    stateListAnimator = null
                    minHeight = 0; minimumHeight = 0
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    setTextColor(if (active) Color.WHITE else Theme.SUBTEXT)
                    typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    background = Theme.ripple(Theme.rounded(if (active) Theme.PRIMARY else Theme.CARD2, 16, density))
                    setOnClickListener {
                        activeFilter = if (r == "全部") null else r
                        renderFilter()
                        renderList()
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { rightMargin = dp(8) }
            )
        }
    }

    // ---------- 列表 ----------

    private fun visibleLinks(): List<Int> =
        NodeStore.links.indices
            .filter { activeFilter == null || regionFor(it, NodeStore.links[it]) == activeFilter }
            .filter { query.isEmpty() || matches(it) }
            .sortedBy { latencyOf(tagOf(it)) ?: Int.MAX_VALUE }

    private fun matches(index: Int): Boolean {
        val l = NodeStore.links[index]
        return l.name.lowercase(Locale.ROOT).contains(query) ||
            regionFor(index, l).lowercase(Locale.ROOT).contains(query) ||
            l.uri.lowercase(Locale.ROOT).contains(query)
    }

    private fun latencyOf(tag: String): Int? = latencies[tag]

    private fun tagOf(index: Int): String = NodeStore.tags.getOrNull(index) ?: ""

    private fun renderList() {
        lastRenderedCount = NodeStore.links.size
        val tested = latencies.size
        countView.text = "${NodeStore.links.size} 条线路 · $tested 条已测速"
        listContainer.removeAllViews()
        if (NodeStore.links.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "正在获取线路…"
                textSize = 13f
                setTextColor(Theme.SUBTEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, dp(36))
            })
            return
        }
        val visible = visibleLinks()
        if (visible.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "没有匹配的线路"
                textSize = 13f
                setTextColor(Theme.SUBTEXT)
                gravity = Gravity.CENTER
                setPadding(0, dp(36), 0, dp(36))
            })
            return
        }
        for (index in visible) {
            val row = nodeRow(index, NodeStore.links[index])
            row.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            row.setOnClickListener {
                NodeStore.selectedIndex = index
                toast("已选择:${NodeStore.links[index].name}")
                finish()
            }
            listContainer.addView(row)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun nodeRow(index: Int, link: Link): LinearLayout {
        val region = regionFor(index, link)
        val selected = index == NodeStore.selectedIndex
        val latency = latencyOf(tagOf(index))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.ripple(
                Theme.rounded(
                    if (selected) Theme.SELECTED_BG else Theme.CARD,
                    16, density
                ).apply {
                    if (selected) setStroke(dp(1), Theme.PRIMARY)
                }
            )
            setPadding(dp(13), dp(12), dp(13), dp(12))
        }
        row.addView(TextView(this).apply {
            text = region
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = Theme.rounded(regionBadgeColor(region), 10, density)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        mid.addView(TextView(this).apply {
            text = link.name
            textSize = 14f
            setTextColor(Theme.TEXT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        mid.addView(TextView(this).apply {
            text = protocolOf(link.uri).uppercase(Locale.ROOT)
            textSize = 11f
            setTextColor(Theme.SUBTEXT)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(mid)
        row.addView(TextView(this).apply {
            text = when {
                selected -> "使用中"
                latency != null && latency >= LatencyTester.TIMEOUT_MS -> "超时"
                latency != null && latency >= LatencyTester.UNTESTED_MS -> "待测"
                latency != null -> delayLabel(latency)
                else -> if (LatencyEngine.isRunning()) "测速中…" else "待测"
            }
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            when {
                selected -> {
                    setTextColor(Theme.PRIMARY)
                    background = Theme.rounded(Theme.CARD2, 10, density)
                }
                latency != null && latency >= LatencyTester.UNTESTED_MS -> {
                    setTextColor(Theme.SUBTEXT)
                }
                latency != null -> {
                    setTextColor(delayColor(latency))
                    background = Theme.rounded(Theme.CARD2, 10, density)
                }
                else -> setTextColor(Theme.SUBTEXT)
            }
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        return row
    }

    private fun regionBadgeColor(region: String): Int = when (region) {
        "香港" -> Color.rgb(0x24, 0x4C, 0x7A)
        "台湾" -> Color.rgb(0x2A, 0x5A, 0x4A)
        "日本" -> Color.rgb(0x4A, 0x2A, 0x5A)
        "新加坡" -> Color.rgb(0x5A, 0x3A, 0x24)
        "美国" -> Color.rgb(0x2A, 0x3A, 0x6A)
        "韩国" -> Color.rgb(0x2A, 0x5A, 0x6A)
        "英国", "德国", "法国", "荷兰" -> Color.rgb(0x3A, 0x2A, 0x4A)
        "未知", "其他" -> Theme.CARD2
        else -> Theme.BADGE
    }

    private fun delayColor(ms: Int): Int = when {
        ms < 150 -> Theme.GREEN
        ms < 400 -> Theme.YELLOW
        else -> Theme.RED
    }

    private fun delayLabel(ms: Int): String = if (ms >= 9999) "超时" else "$ms ms"

    private fun protocolOf(uri: String) = uri.substringBefore("://", "unknown").lowercase()

    // ---------- 测速 ----------

    /** 手动重新测速(进程级引擎执行,离开页面也会继续并保存)。 */
    private fun rerunLatency() {
        if (NodeStore.links.isEmpty()) {
            showHint("等待线路加载后再测速")
            return
        }
        showHint("正在测速…")
        LatencyEngine.rerun(viaClash = JiguangVpnService.running)
    }

    private fun resolveRegions() {
        if (NodeStore.isEmpty) return
        lifecycleScope.launch {
            // DNS 解析 + ip-api HTTP 请求必须离开主线程,否则列表页会明显卡顿
            val hosts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                NodeStore.links.map { SingBoxConfig.hostOf(it.uri) ?: "" }.filter { it.isNotEmpty() }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RegionResolver.refreshUnknownOnline(this@NodePickerActivity, hosts)
            }
            runOnUiThread {
                regionCache.clear()
                renderFilter()
                renderList()
            }
        }
    }

    private fun pickBest() {
        // 只在已确认可用(内核实测或本地探测通过)的节点中优选,超时/待测不参与
        val best = latencies.entries
            .filter { it.value < LatencyTester.UNTESTED_MS }
            .minByOrNull { it.value }
        if (best == null) {
            toast("暂无可用测速结果,请稍候")
            return
        }
        val idx = NodeStore.tags.indexOf(best.key)
        if (idx < 0) {
            toast("节点已失效,请刷新订阅")
            return
        }
        NodeStore.selectedIndex = idx
        toast("已优选:${NodeStore.links[idx].name}(${best.value} ms)")
        finish()
    }

    private fun showHint(s: String) {
        hintView.visibility = View.VISIBLE
        hintView.text = s
    }

    private fun dp(value: Int): Int = Theme.dp(value, density)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

/**
 * 极简的 NodeStore 变更监听(仅用于本页流式刷新),避免引入额外依赖。
 */
object NodeStoreBridge {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun notifyChanged() {
        for (l in listeners) l()
    }
}