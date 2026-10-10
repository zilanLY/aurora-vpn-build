package com.jiguang.vpn.data

import com.jiguang.vpn.core.SingBoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 节点可用性/延迟测试。
 *
 * 设计要点(可用性判定必须贴近真实连通性,不能只看端口是否 open):
 * - **TLS 类协议**(trojan / vless+tls / vless+reality / vmess+tls / ss+tls):
 *   TCP 建连后继续完成 TLS ClientHello 握手(不校验证书链),握手成功才判为可用。
 *   这样能识别「端口开着但服务是假的 / 被中间设备重置」的节点。
 * - **明文 TCP 协议**(ss / vmess 无 tls / vless 无 tls / socks5):TCP 建连计时。
 * - **QUIC/UDP 协议**(hysteria2 / hysteria / tuic):TCP 探测无意义(端口根本不监听 TCP),
 *   未连接内核时不做探测,直接标记 [UNTESTED_MS](「待测」),避免把可用节点误判为超时;
 *   连接内核后由 Clash API 走真实链路给出结果。
 *
 * 并发受信号量限制,避免低配手机线程爆炸;支持逐条回调以「边测边出」。
 */
object LatencyTester {

    private const val PROBE_CONCURRENCY = 32
    private const val TCP_TIMEOUT_MS = 2500
    private const val TLS_TIMEOUT_MS = 3000
    private const val CLASH_CONCURRENCY = 8
    private const val TEST_URL = "http://www.gstatic.com/generate_204"

    /** 探测失败(不可用),UI 显示「超时」。 */
    const val TIMEOUT_MS = 9999

    /** 未探测(UDP 类协议在未连接内核时无法探测),UI 显示「待测」。 */
    const val UNTESTED_MS = 9998

    /** 判定节点可用的上限:小于该值即视为可用(含待测节点,交由内核实测兜底)。 */
    const val ALIVE_MAX_MS = UNTESTED_MS

    data class Result(val values: Map<String, Int>, val source: String)

    /** 探测方式。 */
    private enum class ProbeKind { TLS, TCP, SKIP }

    /**
     * 逐节点探测:key 为 tag;可用回毫秒(建连/握手耗时),
     * 失败回 [TIMEOUT_MS],UDP 类协议回 [UNTESTED_MS]。
     */
    suspend fun tcpPing(
        hosts: Map<String, String>,
        onResult: ((String, Int) -> Unit)? = null
    ): Result = withContext(Dispatchers.IO) {
        val out = java.util.concurrent.ConcurrentHashMap<String, Int>()
        coroutineScope {
            val sem = java.util.concurrent.Semaphore(PROBE_CONCURRENCY)
            hosts.map { (key, uri) ->
                async {
                    sem.acquire()
                    try {
                        val kind = probeKindOf(uri)
                        val ms = when {
                            kind == ProbeKind.SKIP -> UNTESTED_MS
                            else -> {
                                val host = SingBoxConfig.hostOf(uri)
                                val port = SingBoxConfig.portOf(uri)
                                if (host == null || port == null) TIMEOUT_MS
                                else if (kind == ProbeKind.TLS) tlsHandshake(host, port, sniOf(uri))
                                else tcpHandshake(host, port)
                            }
                        }
                        out[key] = ms
                        onResult?.invoke(key, ms)
                    } catch (_: Exception) {
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
        Result(out, "tcp")
    }

    /** Clash API 测速(需内核已运行),走真实代理链路;失败记 [TIMEOUT_MS]。null 表示 API 不可用。 */
    suspend fun clashApi(
        tags: List<String>,
        onResult: ((String, Int) -> Unit)? = null
    ): Result? = withContext(Dispatchers.IO) {
        val out = java.util.concurrent.ConcurrentHashMap<String, Int>()
        coroutineScope {
            val sem = java.util.concurrent.Semaphore(CLASH_CONCURRENCY)
            tags.map { tag ->
                async {
                    sem.acquire()
                    try {
                        val ms = clashDelay(tag)
                        out[tag] = ms
                        onResult?.invoke(tag, ms)
                    } catch (_: Exception) {
                    } finally {
                        sem.release()
                    }
                }
            }.awaitAll()
        }
        if (out.isEmpty()) null else Result(out, "clash")
    }

    /**
     * 由分享链接判定探测方式。
     * - hysteria2/hy2/hysteria/tuic 走 QUIC(UDP),TCP 探测必然失败 → SKIP;
     * - trojan 强制 TLS;
     * - vless/vmess 依 security/tls 参数;
     * - 其余按明文 TCP 处理。
     */
    private fun probeKindOf(uri: String): ProbeKind {
        val low = uri.lowercase()
        if (low.startsWith("hysteria2://") || low.startsWith("hy2://") ||
            low.startsWith("hysteria://") || low.startsWith("tuic://")
        ) {
            return ProbeKind.SKIP
        }
        if (low.startsWith("trojan://")) return ProbeKind.TLS
        if (low.startsWith("vless://") || low.startsWith("trojan://")) {
            val sec = queryValue(low, "security")
            return if (sec == "tls" || sec == "reality" || sec == "xtls") ProbeKind.TLS else ProbeKind.TCP
        }
        if (low.startsWith("ss://")) {
            // SS 本身无 TLS 层;若链接声明插件/TLS 则按 TLS 探测
            return if (low.contains("security%3Dtls") || low.contains("security=tls")) ProbeKind.TLS else ProbeKind.TCP
        }
        return ProbeKind.TCP
    }

    private fun sniOf(uri: String): String? {
        val low = uri.lowercase()
        val sni = queryValue(low, "sni") ?: queryValue(low, "host")
        if (!sni.isNullOrEmpty()) return sni
        // 未提供 SNI 时用服务器名兜底,保证 TLS 握手能带上 server_name
        return SingBoxConfig.hostOf(uri)?.takeIf { it.isNotEmpty() }
    }

    /** 极简 query 取值(仅用于探测判定,不做完整 URL 解析)。 */
    private fun queryValue(uri: String, key: String): String? {
        val i = uri.indexOf("?")
        if (i < 0) return null
        val query = uri.substring(i + 1).substringBefore('#')
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == key) {
                return try {
                    java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                } catch (_: Exception) {
                    pair.substring(eq + 1)
                }
            }
        }
        return null
    }

    private fun tcpHandshake(host: String, port: Int): Int {
        val socket = Socket()
        return try {
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (ms in 1..TCP_TIMEOUT_MS) ms else TIMEOUT_MS
        } catch (_: Exception) {
            TIMEOUT_MS
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * TCP 建连 + TLS 握手。握手成功(拿到会话,不校验证书)才判可用,
     * 耗时含建连与握手全过程,更贴近实际首次请求的表现。
     */
    private fun tlsHandshake(host: String, port: Int, sni: String?): Int {
        var socket: Socket? = null
        var ssl: SSLSocket? = null
        return try {
            val start = System.nanoTime()
            val plain = Socket()
            socket = plain
            plain.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
            val factory = trustAllContext().socketFactory
            ssl = factory.createSocket(plain, host, port, true) as SSLSocket
            ssl.soTimeout = TLS_TIMEOUT_MS
            if (!sni.isNullOrEmpty() && !isIpLiteral(sni)) {
                try {
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNICompat(sni))
                    ssl.sslParameters = params
                } catch (_: Throwable) {
                }
            }
            ssl.startHandshake()
            val session = ssl.session ?: return TIMEOUT_MS
            if (session.cipherSuite == null) return TIMEOUT_MS
            val ms = ((System.nanoTime() - start) / 1_000_000).toInt()
            if (ms in 1..(TCP_TIMEOUT_MS + TLS_TIMEOUT_MS)) ms else TIMEOUT_MS
        } catch (_: Exception) {
            TIMEOUT_MS
        } finally {
            try {
                ssl?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun SNICompat(host: String) = javax.net.ssl.SNIHostName(host)

    private fun isIpLiteral(host: String): Boolean =
        try {
            java.net.InetAddress.getByName(host).hostAddress == host ||
                host.all { it.isDigit() || it == '.' || it == ':' || it in "abcdefABCDEF" }
        } catch (_: Exception) {
            false
        }

    /** 探测专用 TLS 上下文:不校验证书链(仅用于「服务是否在真实响应 TLS」判定)。 */
    private fun trustAllContext(): SSLContext {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        }
    }

    private fun clashDelay(tag: String): Int {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val enc = java.net.URLEncoder.encode(tag, "UTF-8")
            val url = java.net.URL(
                "http://127.0.0.1:${SingBoxConfig.CLASH_API_PORT}/proxies/$enc/delay" +
                    "?timeout=3000&url=" + java.net.URLEncoder.encode(TEST_URL, "UTF-8")
            )
            conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            val code = conn.responseCode
            val body = if (code == 200) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                ""
            }
            parseDelay(body) ?: TIMEOUT_MS
        } catch (_: Exception) {
            TIMEOUT_MS
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseDelay(body: String): Int? {
        // 响应形如 {"delay":123}
        val idx = body.indexOf("\"delay\"")
        if (idx < 0) return null
        val colon = body.indexOf(':', idx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < body.length && !body[i].isDigit()) i++
        val start = i
        while (i < body.length && body[i].isDigit()) i++
        if (start == i) return null
        return body.substring(start, i).toIntOrNull()
    }
}
