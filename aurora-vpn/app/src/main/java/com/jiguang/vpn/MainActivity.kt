package com.jiguang.vpn

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.jiguang.vpn.JiGuangApp
import com.jiguang.vpn.core.SingBoxConfig
import com.jiguang.vpn.data.ClashApi
import com.jiguang.vpn.data.LatencyEngine
import com.jiguang.vpn.data.NodeSelector
import com.jiguang.vpn.data.NodeStore
import com.jiguang.vpn.data.RegionResolver
import com.jiguang.vpn.service.JiguangVpnService
import com.jiguang.vpn.ui.NodePickerActivity
import com.jiguang.vpn.ui.NodeStoreBridge
import com.jiguang.vpn.ui.Theme
import com.jiguang.vpn.updater.SubscriptionRepository
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var durationView: TextView
    private lateinit var exitView: TextView
    private lateinit var nodeCard: LinearLayout
    private lateinit var nodeRegion: TextView
    private lateinit var nodeName: TextView
    private lateinit var nodeExtra: TextView
    private lateinit var nodeLatency: TextView
    private lateinit var trafficView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var connectBtn: Button
    private lateinit var retryView: TextView
    private lateinit var refreshBtn: Button
    private lateinit var menuBtn: Button
    private lateinit var menuPanel: LinearLayout
    private lateinit var crashView: TextView
    private lateinit var modeRuleBtn: Button
    private lateinit var modeGlobalBtn: Button

    private var mode = "rule"
    private var isLoading = false
    private var density = 1f
    private var statusNote: String? = null
    private var connectRequestedAt = 0L

    /** 本次连接会话是否已做过内核可用性检测(断开时重置)。 */
    private var availabilityChecked = false

    /** 连接成功后经代理确认的出口信息("ip|cc"),失败/断开置空。 */
    private var exitInfo: String? = null

    /** 主页流量/连接数刷新 ticker。 */
    private val handler = Handler(Looper.getMainLooper())
    private var exitJob: kotlinx.coroutines.Job? = null
    private val ticker = object : Runnable {
        override fun run() {
            refreshConnectState()
            refreshTraffic()
            handler.postDelayed(this, 1000)
        }
    }

    /** 已连接时每秒拉一次内核流量/连接数(本地 127.0.0.1,开销可忽略);未连接时跳过。 */
    private fun refreshTraffic() {
        if (!JiguangVpnService.running) return
        lifecycleScope.launch {
            val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClashApi.connections() }
            if (isFinishing || isDestroyed || !JiguangVpnService.running) return@launch
            if (t != null) {
                val (count, traffic) = t
                trafficView.text = "活动连接 $count · 累计 ↓${formatBytes(traffic.totalDown)} ↑${formatBytes(traffic.totalUp)}"
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startVpn() else toast("已取消 VPN 授权")
        }

    private val nodePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            renderCurrentNode()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        RegionResolver.init(this)
        LatencyEngine.init(this)
        setContentView(buildLayout())
        prepareRuleSets()

        prefs().getString(KEY_CACHE, null)?.takeIf { it.isNotBlank() }?.let { cached ->
            NodeStore.replace(NodeStore.parseCacheText(cached))
        }
        renderCurrentNode()

        connectBtn.setOnClickListener { toggleConnect() }
        refreshBtn.setOnClickListener { refresh(showToastOnFail = true) }
        nodeCard.setOnClickListener { openNodePicker() }
        retryView.setOnClickListener { retryWithNextNode() }
        menuBtn.setOnClickListener { toggleMenu() }
        setMode(prefs().getString(KEY_MODE, "rule") ?: "rule")
        handler.post(ticker)

        autoUpdateIfNeeded()
        if (!isLoading) LatencyEngine.ensureFresh(viaClash = JiguangVpnService.running)
        showLastCrash()
    }

    /** 上次异常退出时,把崩溃摘要展示在页脚,便于用户反馈定位。 */
    private fun showLastCrash() {
        val crash = JiGuangApp.lastCrash(this) ?: return
        val firstLine = crash.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }
            ?: crash.lineSequence().firstOrNull() ?: return
        crashView.text = "上次异常退出：${firstLine.take(120)}"
        crashView.visibility = View.VISIBLE
        // 只展示一次,避免长期占用页面
        JiGuangApp.clearCrash(this)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        exitJob?.cancel()
        super.onDestroy()
    }

    // ---------- 自动更新订阅(8 小时一次,进入应用时触发) ----------

    private fun autoUpdateIfNeeded() {
        val last = prefs().getLong(KEY_LAST_UPDATE, 0L)
        val elapsed = System.currentTimeMillis() - last
        if (elapsed >= AUTO_UPDATE_INTERVAL_MS) {
            refresh(showToastOnFail = false)
        } else {
            val nextIn = ((AUTO_UPDATE_INTERVAL_MS - elapsed) / 3600000L).coerceAtLeast(0)
            if (NodeStore.selectedIndex < 0) {
                nodeExtra.text = "订阅 $nextIn 小时后自动更新"
            }
        }
    }

    // ---------- 模式 ----------

    private fun setMode(m: String) {
        mode = m
        prefs().edit().putString(KEY_MODE, m).apply()
        styleModeChip(modeRuleBtn, m == "rule")
        styleModeChip(modeGlobalBtn, m == "global")
    }

    private fun styleModeChip(btn: Button, active: Boolean) {
        btn.background = Theme.ripple(Theme.rounded(if (active) Theme.PRIMARY else Theme.CARD2, 14, density))
        btn.setTextColor(if (active) Color.WHITE else Theme.SUBTEXT)
        btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ---------- 布局 ----------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.BG)
            setPadding(dp(18), dp(16), dp(18), dp(14))
        }

        // 顶部:品牌 + 菜单
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "极"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Theme.gradientOval(intArrayOf(Theme.PRIMARY, Theme.ACCENT))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        header.addView(logo)
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }.apply {
            addView(TextView(this@MainActivity).apply {
                text = "极光 VPN"
                textSize = 19f
                setTextColor(Theme.TEXT)
                typeface = Typeface.DEFAULT_BOLD
            })
        })
        menuBtn = Button(this).apply {
            text = "⚙"
            textSize = 16f
            setTextColor(Theme.TEXT)
            background = Theme.ripple(Theme.rounded(Theme.CARD2, 14, density))
            stateListAnimator = null
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            setPadding(dp(14), dp(6), dp(14), dp(8))
        }
        header.addView(menuBtn)

        // 下拉菜单面板(默认隐藏):模式切换 + 刷新订阅
        menuPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.rounded(Theme.CARD, 16, density)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            visibility = View.GONE
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRuleBtn = modeChip("智能分流")
        modeGlobalBtn = modeChip("全局模式")
        modeRuleBtn.setOnClickListener { setMode("rule") }
        modeGlobalBtn.setOnClickListener { setMode("global") }
        chips.addView(modeRuleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chips.addView(modeGlobalBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        menuPanel.addView(chips)
        refreshBtn = pillButton("刷新订阅(每 8 小时自动)")
        refreshBtn.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        menuPanel.addView(refreshBtn)

        // Hero 卡片(渐变)
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = Theme.gradient(
                intArrayOf(Color.rgb(0x1B, 0x2A, 0x5E), Color.rgb(0x2A, 0x1F, 0x52)),
                28, density
            )
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        hero.addView(TextView(this).apply {
            text = "连接状态"
            textSize = 11f
            letterSpacing = 0.2f
            setTextColor(Color.argb(0xCC, 0xC8, 0xD2, 0xEA))
            typeface = Typeface.DEFAULT_BOLD
        })
        statusView = TextView(this).apply {
            text = "未连接"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        }
        hero.addView(statusView)
        durationView = TextView(this).apply {
            text = " "
            textSize = 12f
            setTextColor(Color.argb(0xCC, 0xC8, 0xD2, 0xEA))
            gravity = Gravity.CENTER
        }
        hero.addView(durationView)
        exitView = TextView(this).apply {
            text = " "
            textSize = 11f
            setTextColor(Color.argb(0xAA, 0xC8, 0xD2, 0xEA))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(10))
        }
        hero.addView(exitView)

        val connectFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(172), dp(172)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        connectFrame.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            background = Theme.ring(2, Color.argb(0x55, 0xFF, 0xFF, 0xFF), density)
        })
        connectFrame.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(150), dp(150)).apply { gravity = Gravity.CENTER }
            background = Theme.ring(1, Color.argb(0x33, 0xFF, 0xFF, 0xFF), density)
        })
        connectBtn = Button(this).apply {
            text = "连接"
            isAllCaps = false
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = Theme.gradientOval(intArrayOf(Theme.PRIMARY, Theme.ACCENT))
            stateListAnimator = null
            layoutParams = FrameLayout.LayoutParams(dp(118), dp(118)).apply { gravity = Gravity.CENTER }
        }
        connectFrame.addView(connectBtn)
        hero.addView(connectFrame)
        hero.addView(TextView(this).apply {
            text = "点击开始安全加速"
            textSize = 11f
            setTextColor(Color.argb(0xAA, 0xC8, 0xD2, 0xEA))
            setPadding(0, dp(12), 0, 0)
        })

        // 当前线路卡片
        nodeCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.ripple(Theme.rounded(Theme.CARD, 18, density))
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        nodeRegion = TextView(this).apply {
            text = "无"
            textSize = 12f
            setTextColor(Theme.SUBTEXT)
            typeface = Typeface.DEFAULT_BOLD
            background = Theme.rounded(Theme.CARD2, 10, density)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        nodeCard.addView(nodeRegion)
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        nodeName = TextView(this).apply {
            text = "自动匹配最快节点"
            textSize = 15f
            setTextColor(Theme.TEXT)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        mid.addView(nodeName)
        nodeExtra = TextView(this).apply {
            textSize = 11f
            setTextColor(Theme.SUBTEXT)
            setPadding(0, dp(3), 0, 0)
        }
        mid.addView(nodeExtra)
        nodeCard.addView(mid)
        nodeLatency = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.SUBTEXT)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, dp(8), 0)
        }
        nodeCard.addView(nodeLatency)
        nodeCard.addView(TextView(this).apply {
            text = "换线 ›"
            textSize = 12f
            setTextColor(Theme.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        })

        // 实时状态行:↓速度 ↑速度 · 连接数(仅连接时可见)
        trafficView = TextView(this).apply {
            textSize = 12f
            setTextColor(Theme.SUBTEXT)
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        // 失败重试提示(默认隐藏):连接失败/超时时出现,一键换节点重连
        retryView = TextView(this).apply {
            text = "↻ 换一个节点重试"
            textSize = 13f
            setTextColor(Theme.PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Theme.ripple(Theme.rounded(Theme.CARD2, 14, density))
            setPadding(0, dp(12), 0, dp(12))
            visibility = View.GONE
        }

        root.addView(header)
        root.addView(menuPanel, topMargin(dp(10)))
        root.addView(hero, topMargin(dp(14)))
        root.addView(nodeCard, topMargin(dp(12)))
        root.addView(trafficView, topMargin(dp(8)))
        root.addView(retryView, topMargin(dp(10)))
        root.addView(progress, topMargin(dp(6)))
        crashView = TextView(this).apply {
            textSize = 10f
            setTextColor(Theme.SUBTEXT)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        root.addView(crashView)
        return root
    }

    private fun topMargin(margin: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = margin }

    private fun modeChip(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), dp(10), dp(10), dp(10))
    }

    // ---------- 当前节点展示 ----------

    private fun renderCurrentNode() {
        val link = NodeStore.links.getOrNull(NodeStore.selectedIndex)
        if (link == null) {
            nodeRegion.text = "无"
            nodeRegion.setTextColor(Theme.SUBTEXT)
            nodeRegion.background = Theme.rounded(Theme.CARD2, 10, density)
            nodeName.text = "自动匹配最快节点"
            nodeExtra.text = if (NodeStore.isEmpty) "尚未获取线路" else "点击进入线路选择"
            nodeLatency.text = ""
            return
        }
        val region = RegionResolver.regionOf(this, SingBoxConfig.hostOf(link.uri) ?: "", link.name).region
        nodeRegion.text = region
        nodeRegion.setTextColor(Color.WHITE)
        nodeRegion.background = Theme.rounded(Theme.BADGE, 10, density)
        nodeName.text = link.name
        nodeExtra.text = protocolOf(link.uri).uppercase(Locale.ROOT)
        val tag = NodeStore.tags.getOrNull(NodeStore.selectedIndex)
        val ms = tag?.let { LatencyEngine.current()[it] }
        nodeLatency.text = ms?.let { "$it ms" } ?: ""
        nodeLatency.setTextColor(
            when {
                ms == null -> Theme.SUBTEXT
                ms < 150 -> Theme.GREEN
                ms < 400 -> Theme.YELLOW
                else -> Theme.RED
            }
        )
    }

    private fun openNodePicker() {
        if (NodeStore.isEmpty && !isLoading) {
            toast("正在获取线路,请稍候…")
            return
        }
        nodePicker.launch(Intent(this, NodePickerActivity::class.java))
    }

    private fun protocolOf(uri: String) = uri.substringBefore("://", "unknown").lowercase()

    // ---------- 连接 ----------

    private fun toggleConnect() {
        if (JiguangVpnService.running) {
            startService(Intent(this, JiguangVpnService::class.java).setAction(JiguangVpnService.ACTION_STOP))
            availabilityChecked = false
            exitInfo = null
            retryView.visibility = View.GONE
            note("正在断开…")
            return
        }
        if (NodeStore.isEmpty) {
            toast("正在获取线路,请稍候")
            if (!isLoading) refresh(showToastOnFail = true)
            return
        }
        JiguangVpnService.lastError = null
        retryView.visibility = View.GONE
        connectRequestedAt = 0L
        val prep = VpnService.prepare(this)
        if (prep != null) vpnPermission.launch(prep) else startVpn()
    }

    /** 连接失败/超时后的换节点重试:清除手动选择,由测速结果选下一个可用候选重连。 */
    private fun retryWithNextNode() {
        retryView.visibility = View.GONE
        JiguangVpnService.lastError = null
        if (NodeStore.isEmpty) {
            toast("正在获取线路,请稍候")
            return
        }
        connectRequestedAt = 0L
        note("正在换线重连…")
        startVpn()
    }

    private fun startVpn() {
        // 只用可用候选构建配置:手动选择=单节点;自动=按测速可用节点排序取前 N,
        // 既缩小配置体积,也避免把测速确认不可用的节点塞进内核
        val indices = NodeSelector.plan(
            NodeStore.links, NodeStore.tags, NodeStore.selectedIndex,
            LatencyEngine.current(), CONNECT_NODE_LIMIT
        )
        if (indices.isEmpty()) {
            toast("没有可用线路")
            return
        }
        val links = indices.map { NodeStore.links[it] }
        val config = SingBoxConfig.buildConfig(
            links, mode, filesDir.absolutePath, null
        )?.json ?: run {
            toast("没有可用线路")
            return
        }
        connectRequestedAt = System.currentTimeMillis()
        note("正在连接…")
        val intent = Intent(this, JiguangVpnService::class.java)
            .setAction(JiguangVpnService.ACTION_START)
            .putExtra(JiguangVpnService.EXTRA_CONFIG, config)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun note(text: String?) {
        statusNote = text
        if (!JiguangVpnService.running) {
            statusView.text = text ?: "未连接"
            statusView.setTextColor(if (text == null) Color.WHITE else Color.argb(0xFF, 0xDC, 0xE6, 0xFF))
        }
    }

    private fun refreshConnectState() {
        if (isLoading) return
        val running = JiguangVpnService.running
        connectBtn.text = if (running) "断开" else "连接"
        connectBtn.background = if (running) {
            Theme.gradientOval(intArrayOf(Theme.RED, Color.rgb(0xB0, 0x2A, 0x27)))
        } else {
            Theme.gradientOval(intArrayOf(Theme.PRIMARY, Theme.ACCENT))
        }
        if (running) {
            statusView.text = "已连接"
            statusView.setTextColor(Theme.GREEN)
            durationView.text = "已连接 ${formatDuration(System.currentTimeMillis() - JiguangVpnService.connectedAt)}"
            trafficView.visibility = View.VISIBLE
            retryView.visibility = View.GONE
            // 连接成功后用内核 Clash API 做一轮真实可用性复测(每次会话一次,
            // 不受节流限制,结果覆盖本地探测值——内核实测才是真实可用性)
            if (!availabilityChecked && !LatencyEngine.isRunning()) {
                availabilityChecked = true
                exitInfo = null
                exitView.text = "正在确认出口…"
                exitJob?.cancel()
                exitJob = lifecycleScope.launch {
                    LatencyEngine.recheckViaClash()
                    val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ClashApi.exitIp() }
                    if (isFinishing || isDestroyed) return@launch
                    exitInfo = ip
                    if (ip != null) {
                        val parts = ip.split("|")
                        exitView.text = "出口 ${parts[0]} · ${RegionResolver.countryName(parts.getOrElse(1) { "" })}"
                    } else {
                        exitView.text = "出口确认失败(网络可能未走代理)"
                    }
                }
            }
            return
        }
        trafficView.visibility = View.GONE
        exitView.text = " "
        val err = JiguangVpnService.lastError
        if (err != null) {
            statusNote = "连接失败"
            statusView.text = "连接失败"
            statusView.setTextColor(Theme.RED)
            durationView.text = err
            retryView.visibility = View.VISIBLE
            return
        }
        if (statusNote?.startsWith("正在连接") == true &&
            connectRequestedAt > 0 &&
            System.currentTimeMillis() - connectRequestedAt > CONNECT_TIMEOUT_MS
        ) {
            statusNote = "连接超时"
            statusView.text = "连接超时"
            statusView.setTextColor(Theme.RED)
            durationView.text = "请检查线路或网络后重试"
            retryView.visibility = View.VISIBLE
            return
        }
        statusView.text = statusNote ?: "未连接"
        statusView.setTextColor(Color.WHITE)
        durationView.text = " "
    }

    private fun toggleMenu() {
        menuPanel.visibility = if (menuPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    // ---------- 订阅(流式:先出列表,后台继续补) ----------

    private fun refresh(showToastOnFail: Boolean) {
        if (isLoading) return
        isLoading = true
        refreshBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        note("正在获取线路…")
        prefs().edit().putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()

        lifecycleScope.launch {
            var failed = true
            var firstBatch = true
            SubscriptionRepository.stream().collect { batch ->
                if (batch.source == null) return@collect
                if (firstBatch) {
                    NodeStore.clear()
                    firstBatch = false
                }
                val added = NodeStore.append(batch.nodes)
                if (added > 0) {
                    failed = false
                    note("已加载 ${NodeStore.links.size} 条 · ${batch.source.label}")
                    NodeStoreBridge.notifyChanged()
                }
            }
            if (failed && NodeStore.isEmpty) {
                note(if (showToastOnFail) "更新失败" else null)
                if (showToastOnFail) toast("更新失败,已用缓存")
            } else {
                prefs().edit()
                    .putString(KEY_CACHE, NodeStore.toCacheText())
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply()
                note("已更新 · ${NodeStore.links.size} 条线路")
            }
            LatencyEngine.ensureFresh(viaClash = JiguangVpnService.running)
            isLoading = false
            refreshBtn.isEnabled = true
            progress.visibility = View.GONE
            refreshConnectState()
            renderCurrentNode()
            NodeStoreBridge.notifyChanged()
        }
    }

    private fun prepareRuleSets() {
        val dir = java.io.File(filesDir, "rule-set")
        if (!dir.exists()) dir.mkdirs()
        for (name in listOf("geosite-cn.srs", "geoip-cn.srs")) {
            val out = java.io.File(dir, name)
            if (out.exists()) continue
            try {
                assets.open("rule-set/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            } catch (_: Exception) {
            }
        }
    }

    private fun isOnline(): Boolean = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = if (net != null) cm.getNetworkCapabilities(net) else null
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        true
    }

    // ---------- 样式 ----------

    private fun pillButton(label: String): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Theme.TEXT)
        background = Theme.ripple(Theme.rounded(Theme.CARD2, 14, density))
        stateListAnimator = null
        minHeight = 0
        minimumHeight = 0
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(6), dp(13), dp(6), dp(13))
    }

    private fun dp(value: Int): Int = Theme.dp(value, density)

    private fun prefs() = getSharedPreferences("jiguang", Context.MODE_PRIVATE)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_CACHE = "nodes_cache"
        private const val KEY_LAST_UPDATE = "last_update_at"
        private const val KEY_MODE = "mode"
        private const val AUTO_UPDATE_INTERVAL_MS = 8L * 60 * 60 * 1000
        private const val CONNECT_TIMEOUT_MS = 15_000L

        /** 连接配置最多包含的节点数(缩小配置体积、加快内核启动)。 */
        private const val CONNECT_NODE_LIMIT = 30
    }
}