package com.jiguang.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * 跟踪系统默认网络,把变化告知 sing-box(用于绑定出站网卡,避免回环)。
 *
 * 对齐官方实现:
 * - Android 9(28)~10(30) 用 requestNetwork(REQUEST):registerDefaultNetworkCallback
 *   会把 VPN 自身的 tun 网卡当作默认网络,导致出站绑到 tun 形成回环。
 * - Android 12(31+) 用 registerBestMatchingNetworkCallback。
 * - 26~27 用 registerDefaultNetworkCallback。
 * 回调注册在独立 HandlerThread 上(Go 线程无 Looper)。
 */
class DefaultNetworkMonitor(context: Context) {

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var listener: InterfaceUpdateListener? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var defaultNetwork: Network? = null

    private val callbackHandler by lazy {
        Handler(HandlerThread("jiguang-network").apply { start() }.looper)
    }

    private val request: NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        if (listener == null) unregister() else register()
    }

    private fun register() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                defaultNetwork = network
                report(network)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                report(network)
            }

            override fun onLost(network: Network) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    listener?.updateDefaultInterface("", -1, false, false)
                }
            }
        }
        callback = cb
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    cm.registerBestMatchingNetworkCallback(request, cb, callbackHandler)

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                    cm.requestNetwork(request, cb, callbackHandler)

                else ->
                    cm.registerDefaultNetworkCallback(cb, callbackHandler)
            }
        } catch (_: Throwable) {
            try {
                cm.registerDefaultNetworkCallback(cb, callbackHandler)
            } catch (_: Throwable) {
            }
        }
        val active = cm.activeNetwork
        defaultNetwork = active
        report(active)
    }

    private fun unregister() {
        val cb = callback ?: return
        callback = null
        defaultNetwork = null
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Throwable) {
        }
        callbackHandler.looper.quitSafely()
    }

    private fun report(network: Network?) {
        val l = listener ?: return
        if (network == null) {
            l.updateDefaultInterface("", -1, false, false)
            return
        }
        // 网络刚切换时 LinkProperties 可能短暂为空,重试几次
        for (attempt in 0 until 10) {
            val props = cm.getLinkProperties(network)
            val name = props?.interfaceName
            if (name != null) {
                val index = try {
                    NetworkInterface.getByName(name).index
                } catch (_: Throwable) {
                    -1
                }
                if (index >= 0) {
                    l.updateDefaultInterface(name, index, false, false)
                    return
                }
            }
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}