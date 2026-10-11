package com.jiguang.vpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jiguang.vpn.JiGuangApp
import com.jiguang.vpn.R
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as LibboxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/**
 * 基于 sing-box libbox 的 VpnService。tun 模式下由内核接管全部流量。
 *
 * 关键约定(对齐官方 Android 客户端):
 * - Libbox.setup() 只在 Application 调用一次(此处 ensureLibbox 兜底,幂等)
 * - 整个服务生命周期只创建一个 CommandServer,换节点/重连走 startOrReloadService
 * - 断开时先 closeService() 再 close()
 */
class JiguangVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "JiguangVpn"
        const val ACTION_START = "com.jiguang.vpn.action.START"
        const val ACTION_STOP = "com.jiguang.vpn.action.STOP"
        const val EXTRA_CONFIG = "config"
        private const val CHANNEL_ID = "jiguang_vpn"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        @Volatile
        var connectedAt: Long = 0
            private set

        /** 最近一次启动失败原因(供 UI 展示),成功后清空。 */
        @Volatile
        var lastError: String? = null
    }

    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var monitor: DefaultNetworkMonitor? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()

    /** 连接归属查询缓存:相同五元组 60s 内复用,避免 DNS 风暴下高频回调拖慢内核。 */
    private val ownerCache = object : LinkedHashMap<String, ConnectionOwner>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConnectionOwner>?): Boolean = size > 256
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startForeground(NOTIFICATION_ID, buildNotification("正在连接…"))
                } catch (t: Throwable) {
                    Log.e(TAG, "startForeground failed", t)
                    lastError = "前台服务启动失败:${t.message ?: t.javaClass.simpleName}"
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.launch { startOrReload(config) }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun startOrReload(config: String) = operationMutex.withLock {
        try {
            JiGuangApp.ensureLibbox(applicationContext)
            val options = OverrideOptions().apply { autoRedirect = false }
            val existing = commandServer
            if (existing == null) {
                monitor = DefaultNetworkMonitor(this)
                val server = Libbox.newCommandServer(this, this)
                server.start()
                server.startOrReloadService(config, options)
                commandServer = server
            } else {
                // 复用同一内核实例做热重载(换节点/重连)
                existing.startOrReloadService(config, options)
            }
            running = true
            connectedAt = System.currentTimeMillis()
            lastError = null
            updateNotification("已连接")
        } catch (t: Throwable) {
            Log.e(TAG, "start vpn failed", t)
            lastError = t.message ?: t.javaClass.simpleName
            updateNotification("连接失败：$lastError")
            // 注意:此时已持有 operationMutex,必须调用不加锁的释放函数,
            // 否则 Mutex 重入会导致协程永久挂起、后续连接全部异常
            releaseResources(failedStart = true, keepNotification = true)
        }
    }

    private fun stopVpn() {
        serviceScope.launch { operationMutex.withLock { releaseResources(failedStart = false, keepNotification = false) } }
    }

    /**
     * 释放内核与 tun 资源。调用方必须已持有 [operationMutex](本函数不再加锁)。
     *
     * @param failedStart 内核启动失败:此时只关闭自家 tun fd 并"分离"内核对象,
     *   不调用 closeService/close——对半初始化的内核做关闭可能触发原生崩溃,
     *   交由 Go 侧 GC 回收;用户可直接重试(下次会创建全新 CommandServer)。
     */
    private suspend fun releaseResources(failedStart: Boolean, keepNotification: Boolean) {
        val server = commandServer
        if (server != null && !failedStart) {
            try {
                server.closeService()
            } catch (t: Throwable) {
                Log.w(TAG, "close service", t)
            }
            try {
                server.close()
            } catch (t: Throwable) {
                Log.w(TAG, "close command server", t)
            }
        }
        commandServer = null
        try {
            tunFd?.close()
        } catch (_: Throwable) {
        }
        tunFd = null
        monitor?.setListener(null)
        monitor = null
        running = false
        connectedAt = 0
        withContext(Dispatchers.Main) {
            if (!keepNotification) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
        }
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------- PlatformInterface ----------

    override fun openTun(options: TunOptions): Int {
        // 绝不向 Go 侧抛异常:失败一律返回 -1(libbox 会将其转为启动错误),
        // 保证失败路径走「连接失败」提示而不是进程崩溃
        var pfd: ParcelFileDescriptor? = null
        return try {
            if (prepare(this) != null) return -1
            val builder = Builder()
                .setSession("极光VPN")
                .setMtu(options.mtu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            builder.addAddress("172.19.0.1", 30)
            builder.addAddress("fdfe:dcba:9876::1", 126)
            val dns = options.dnsServerAddress
            if (dns != null) {
                while (dns.hasNext()) {
                    builder.addDnsServer(dns.next())
                }
            }
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
            val fd = builder.establish() ?: return -1
            pfd = fd
            tunFd = fd
            fd.fd
        } catch (t: Throwable) {
            Log.e(TAG, "openTun failed", t)
            runCatching { pfd?.close() }
            tunFd = null
            -1
        }
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor?.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        monitor?.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = ArrayList<LibboxNetworkInterface>()
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val javaNifs = runCatching {
                java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            }.getOrDefault(emptyList())
            for (network in cm.allNetworks) {
                val lp = cm.getLinkProperties(network) ?: continue
                val caps = cm.getNetworkCapabilities(network) ?: continue
                val name = lp.interfaceName ?: continue
                val ni = javaNifs.find { it.name == name } ?: continue
                val box = LibboxNetworkInterface()
                box.name = name
                box.index = ni.index
                box.mtu = runCatching { ni.mtu }.getOrDefault(0)
                box.dnsServer = StringArray(lp.dnsServers.mapNotNull { sanitizeAddr(it.hostAddress) }.iterator())
                box.gateway = StringArray(
                    lp.routes.filter { it.destination.prefixLength == 0 }
                        .mapNotNull { sanitizeAddr(it.gateway?.hostAddress) }
                        .iterator()
                )
                box.type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                box.addresses = StringArray(
                    ni.interfaceAddresses.mapNotNull { ia ->
                        // 内核用 netip.MustParsePrefix 解析该字段:IPv6 链路本地地址
                        // (fe80::…%wlan0)带 zone 后缀会直接 panic(SIGABRT),必须剥离;
                        // 同时校验 CIDR 格式,单条坏数据只跳过不崩进程。
                        val host = sanitizeAddr(ia.address.hostAddress) ?: return@mapNotNull null
                        "$host/${ia.networkPrefixLength}"
                    }.iterator()
                )
                var flags = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flags = flags or OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (ni.isLoopback) flags = flags or OsConstants.IFF_LOOPBACK
                box.flags = flags
                box.metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                list.add(box)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getInterfaces failed", t)
        }
        return InterfaceArray(list.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    /**
     * 清洗传给内核的地址字符串:
     * - 剥离 IPv6 zone 后缀(fe80::1%wlan0 → fe80::1),sing-box 1.14 的
     *   netip.MustParsePrefix 不接受 zone,不剥会 Go panic 导致整个进程 SIGABRT;
     * - 返回 null 表示该条地址无法安全传递,调用方应跳过该条。
     */
    private fun sanitizeAddr(addr: String?): String? {
        if (addr.isNullOrEmpty()) return null
        val cleaned = addr.substringBefore('%').trim()
        if (cleaned.isEmpty()) return null
        // 校验为合法 IP 后返回规范形式;畸形数据不送进内核
        return try {
            java.net.InetAddress.getByName(cleaned).hostAddress
        } catch (_: Throwable) {
            null
        }
    }

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {}

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("android: not supported")
    }

    override fun usePlatformBridge(): Boolean = false

    override fun registerMyInterface(name: String?) {}

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String?,
        sourcePort: Int,
        destinationAddress: String?,
        destinationPort: Int,
    ): ConnectionOwner? {
        // 绝不能返回 null:sing-box v1.14 libbox wrapper(service.go:226)拿到 nil
        // 会直接解引用导致 Go panic(SIGSEGV→SIGABRT),即连接后首个 DNS 查询必崩。
        // 查不到时返回空对象而非 null。
        val key = "$ipProtocol|$sourceAddress|$sourcePort|$destinationAddress|$destinationPort"
        ownerCache[key]?.let { return it }
        val owner = ConnectionOwner()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                // getConnectionOwnerUid 需要应用当前持有 VPN 接口(本服务已建立 tun),
                // 返回发起连接的 uid;查不到(连接已关闭/竞态)返回 -1
                val uid = cm.getConnectionOwnerUid(
                    ipProtocol,
                    java.net.InetSocketAddress(java.net.InetAddress.getByName(sourceAddress), sourcePort),
                    java.net.InetSocketAddress(java.net.InetAddress.getByName(destinationAddress), destinationPort)
                )
                if (uid > 0) {
                    // gomobile 绑定:字段生成 JavaBean setUserId/getUserId,
                    // SetAndroidPackageNames 导出为 setAndroidPackageNames(已从真实 aar dex 验证)
                    owner.setUserId(uid)
                    val packages = packageManager.getPackagesForUid(uid)
                    if (!packages.isNullOrEmpty()) {
                        owner.setAndroidPackageNames(StringArray(packages.iterator()))
                    }
                }
            } catch (_: Throwable) {
            }
        }
        ownerCache.put(key, owner)
        return owner
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("android: not supported")

    override fun lookupUser(username: String?): PlatformUser? = null

    override fun lookupSFTPServer(): String = error("android: not supported")

    override fun readSystemSSHHostKey(): String = error("android: not supported")

    override fun tailscaleHostname(): String = ""

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("android: not supported")

    override fun sendNotification(notification: LibboxNotification) {}

    override fun cancelNotification(identifier: String?, typeID: Int) {}

    // ---------- CommandServerHandler ----------

    override fun serviceStop() {
        stopVpn()
    }

    override fun serviceReload() {}

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() = error("android: not supported")

    override fun writeDebugMessage(message: String?) {
        Log.d(TAG, message ?: "")
    }

    override fun connectSSHAgent(): Int = -1

    // ---------- 通知 ----------

    private fun buildNotification(text: String): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "极光VPN", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("极光VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
}