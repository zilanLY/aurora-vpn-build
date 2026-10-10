package com.jiguang.vpn.core

import java.io.File
import java.util.Base64

/** 一条分享链接。 */
data class Link(val uri: String, val name: String)

/** 配置生成结果；tags 与输入 links 一一对应，空串表示该节点协议不受支持。 */
data class ConfigResult(val json: String, val tags: List<String>)

/**
 * 把 v2ray 分享链接转换为 sing-box 配置。
 * 与 Windows 端 aurora-windows/link.go、config.go 保持一致。
 */
object SingBoxConfig {

    const val MIXED_PORT = 2080
    const val CLASH_API_PORT = 2081

    private val schemes = listOf(
        "ss://", "ssr://", "vmess://", "vless://",
        "trojan://", "hysteria2://", "hysteria://", "tuic://", "socks5://"
    )

    fun supported(uri: String): Boolean = buildOutbound(uri, "probe") != null

    /** 过滤出内核支持的链接，并返回与其对齐的 tag。 */
    fun supportedLinks(links: List<Link>): Pair<List<Link>, List<String>> {
        val out = ArrayList<Link>()
        val tags = ArrayList<String>()
        var next = 0
        for (l in links) {
            if (!supported(l.uri)) continue
            out.add(l)
            tags.add("node-${next + 1}")
            next++
        }
        return out to tags
    }

    /**
     * @param mode "global" 全局 | 其它值（"rule"）智能分流
     * @param workDir 运行目录（放 core.log / cache.db / rule-set）；null 时用相对路径
     */
    fun buildConfig(links: List<Link>, mode: String, workDir: String?, selectedTag: String? = null): ConfigResult? {
        val tags = arrayOfNulls<String>(links.size)
        val nodeOutbounds = ArrayList<Map<String, Any?>>()
        val allTags = ArrayList<String>()
        var next = 0
        for (i in links.indices) {
            val tag = "node-${next + 1}"
            val ob = buildOutbound(links[i].uri, tag) ?: continue
            nodeOutbounds.add(ob)
            allTags.add(tag)
            tags[i] = tag
            next++
        }
        if (allTags.isEmpty()) return null

        // urltest 健康检查对每个节点并发发起请求:节点过多会同时拉起大量 goroutine/定时器,
        // 在低内存手机上触发 Go "fatal: out of memory" 直接杀死进程(表现为无日志闪退)。
        // 因此:①连接配置里不放 urltest 组,selector 直接默认指向节点;
        //      ②候选节点数由调用方通过 NodeSelector 压到很小(默认 30)。
        val selector = linkedMapOf<String, Any?>(
            "type" to "selector", "tag" to "proxy",
            "outbounds" to allTags,
            "default" to if (!selectedTag.isNullOrEmpty() && allTags.contains(selectedTag)) selectedTag else allTags.first()
        )
        val outbounds = ArrayList<Map<String, Any?>>()
        outbounds.add(selector)
        outbounds.addAll(nodeOutbounds)
        outbounds.add(linkedMapOf("type" to "direct", "tag" to "direct"))

        val rules = ArrayList<Map<String, Any?>>()
        val route = linkedMapOf<String, Any?>(
            "rules" to rules, "final" to "proxy",
            "default_domain_resolver" to linkedMapOf<String, Any?>("server" to "dns-direct")
        )
        // sing-box 1.13+ 起 inbound 的 legacy sniff 字段被移除,改为 route 规则 action=sniff
        rules.add(linkedMapOf("action" to "sniff"))
        if (mode != "global") {
            rules.add(linkedMapOf("ip_is_private" to true, "outbound" to "direct"))
            val sets = localRuleSets(workDir)
            if (sets.isNotEmpty()) {
                route["rule_set"] = sets
                rules.add(linkedMapOf("rule_set" to sets.map { it["tag"] }, "outbound" to "direct"))
            }
        }

        val cfg = linkedMapOf<String, Any?>(
            "log" to linkedMapOf<String, Any?>(
                "level" to "info", "timestamp" to true,
                "output" to (workDir?.let { "$it/core.log" } ?: "core.log")
            ),
            "inbounds" to listOf(
                linkedMapOf<String, Any?>(
                    "type" to "tun", "tag" to "tun-in",
                    "address" to listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                    "mtu" to 8500,
                    "auto_route" to true, "strict_route" to false,
                    "stack" to "gvisor"
                )
            ),
            "outbounds" to outbounds,
            "route" to route,
            "dns" to linkedMapOf<String, Any?>(
                "servers" to listOf(
                    linkedMapOf<String, Any?>("type" to "udp", "tag" to "dns-direct", "server" to "223.5.5.5"),
                    linkedMapOf<String, Any?>("type" to "https", "tag" to "dns-proxy", "server" to "1.1.1.1", "detour" to "proxy")
                ),
                "final" to "dns-proxy"
            ),
            "experimental" to linkedMapOf<String, Any?>(
                "cache_file" to linkedMapOf<String, Any?>(
                    "enabled" to true,
                    "path" to (workDir?.let { "$it/cache.db" } ?: "cache.db")
                ),
                "clash_api" to linkedMapOf<String, Any?>(
                    "external_controller" to "127.0.0.1:$CLASH_API_PORT"
                )
            )
        )
        return ConfigResult(json(cfg), tags.map { it ?: "" })
    }

    private fun localRuleSets(workDir: String?): List<Map<String, Any?>> {
        if (workDir == null) return emptyList()
        val candidates = listOf("geosite-cn" to "rule-set/geosite-cn.srs", "geoip-cn" to "rule-set/geoip-cn.srs")
        val out = ArrayList<Map<String, Any?>>()
        for ((tag, rel) in candidates) {
            val f = File(workDir, rel)
            if (f.isFile) {
                out.add(linkedMapOf("type" to "local", "tag" to tag, "format" to "binary", "path" to f.absolutePath))
            }
        }
        return out
    }

    // ---- 协议转换 ----

    fun buildOutbound(uri: String, tag: String): Map<String, Any?>? {
        val low = uri.lowercase()
        return when {
            low.startsWith("vmess://") -> vmess(uri, tag)
            low.startsWith("vless://") -> vless(uri, tag)
            low.startsWith("trojan://") -> trojan(uri, tag)
            low.startsWith("ss://") -> shadowsocks(uri, tag)
            low.startsWith("hysteria2://") || low.startsWith("hy2://") -> hysteria2(uri, tag)
            low.startsWith("tuic://") -> tuic(uri, tag)
            low.startsWith("socks5://") -> socks(uri, tag)
            else -> null
        }
    }

    private fun vmess(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val body = uri.substring("vmess://".length).substringBefore('#')
        val raw = tryB64(body) ?: return null
        val m = parseFlatJson(raw)
        val server = m["add"]?.takeIf { it.isNotEmpty() } ?: return null
        val port = m["port"]?.toIntOrNull() ?: return null
        val uuid = m["id"]?.takeIf { it.isNotEmpty() } ?: return null
        var security = m["scy"] ?: m["security"] ?: "auto"
        if (security.isBlank()) security = "auto"
        val ob = linkedMapOf<String, Any?>(
            "type" to "vmess", "tag" to tag, "server" to server, "server_port" to port,
            "uuid" to uuid, "security" to security, "alter_id" to (m["aid"]?.toIntOrNull() ?: 0)
        )
        buildTLS(m["tls"] == "tls", m["sni"] ?: "", isTruthy(m["skip-cert-verify"] ?: ""), splitCsv(m["alpn"]), m["fp"] ?: "")
            ?.let { ob["tls"] = it }
        buildTransport(m["net"] ?: "", m["path"] ?: "", m["host"] ?: "")?.let { ob["transport"] = it }
        return ob
    }

    private fun vless(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val u = parseUri(uri) ?: return null
        if (u.host.isEmpty() || u.userInfo.isNullOrEmpty() || u.port <= 0) return null
        val q = u.query
        // Reality 参数非法时无法建立连接:提前过滤,避免单个坏节点导致整份配置启动失败
        if ((q["security"] ?: "").lowercase() == "reality" && !validReality(q)) return null
        val ob = linkedMapOf<String, Any?>(
            "type" to "vless", "tag" to tag, "server" to u.host, "server_port" to u.port,
            "uuid" to u.userInfo.substringBefore(':')
        )
        q["flow"]?.takeIf { it.isNotEmpty() }?.let { ob["flow"] = it }
        buildTLSFromQuery(q)?.let { ob["tls"] = it }
        buildTransport(q["type"] ?: "", q["path"] ?: "", q["host"] ?: "")?.let { ob["transport"] = it }
        return ob
    }

    private fun trojan(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val u = parseUri(uri) ?: return null
        if (u.host.isEmpty() || u.userInfo.isNullOrEmpty() || u.port <= 0) return null
        val q = u.query
        val ob = linkedMapOf<String, Any?>(
            "type" to "trojan", "tag" to tag, "server" to u.host, "server_port" to u.port,
            "password" to u.userInfo.substringBefore(':')
        )
        buildTLSFromQuery(q)?.let { ob["tls"] = it }
        buildTransport(q["type"] ?: "", q["path"] ?: "", q["host"] ?: "")?.let { ob["transport"] = it }
        return ob
    }

    private fun shadowsocks(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        var body = uri.substring("ss://".length).substringBefore('#').substringBefore('?')
        var method: String
        var password: String
        var server: String
        var port: Int
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            var cred = body.substring(0, at)
            tryB64(cred)?.let { cred = it }
            val hostport = body.substring(at + 1)
            val hp = hostport.lastIndexOf(':')
            if (hp < 0) return null
            server = hostport.substring(0, hp)
            port = hostport.substring(hp + 1).toIntOrNull() ?: return null
            val parts = cred.split(":", limit = 2)
            if (parts.size != 2) return null
            method = parts[0]; password = parts[1]
        } else {
            val decoded = tryB64(body) ?: return null
            val a = decoded.lastIndexOf('@')
            if (a < 0) return null
            val cred = decoded.substring(0, a)
            val hostport = decoded.substring(a + 1)
            val hp = hostport.lastIndexOf(':')
            if (hp < 0) return null
            server = hostport.substring(0, hp)
            port = hostport.substring(hp + 1).toIntOrNull() ?: return null
            val parts = cred.split(":", limit = 2)
            if (parts.size != 2) return null
            method = parts[0]; password = parts[1]
        }
        if (server.isEmpty() || port <= 0 || method.isEmpty()) return null
        return linkedMapOf(
            "type" to "shadowsocks", "tag" to tag, "server" to server, "server_port" to port,
            "method" to method, "password" to password
        )
    }

    private fun hysteria2(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val u = parseUri(uri) ?: return null
        if (u.host.isEmpty() || u.port <= 0) return null
        val q = u.query
        val ob = linkedMapOf<String, Any?>(
            "type" to "hysteria2", "tag" to tag, "server" to u.host, "server_port" to u.port,
            "password" to (u.userInfo?.substringBefore(':') ?: "")
        )
        val tls = linkedMapOf<String, Any?>("enabled" to true)
        (q["sni"] ?: q["peer"])?.takeIf { it.isNotEmpty() }?.let { tls["server_name"] = it }
        if (isTruthy(q["insecure"] ?: "") || q["allowInsecure"] == "1") tls["insecure"] = true
        splitCsv(q["alpn"])?.takeIf { it.isNotEmpty() }?.let { tls["alpn"] = it }
        ob["tls"] = tls
        q["obfs"]?.takeIf { it.isNotEmpty() }?.let {
            ob["obfs"] = linkedMapOf<String, Any?>("type" to it, "password" to (q["obfs-password"] ?: ""))
        }
        return ob
    }

    private fun tuic(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val u = parseUri(uri) ?: return null
        if (u.host.isEmpty() || u.port <= 0 || u.userInfo.isNullOrEmpty()) return null
        val q = u.query
        val user = u.userInfo.substringBefore(':')
        val pass = u.userInfo.substringAfter(':', "")
        val ob = linkedMapOf<String, Any?>(
            "type" to "tuic", "tag" to tag, "server" to u.host, "server_port" to u.port,
            "uuid" to user, "password" to pass
        )
        q["congestion_control"]?.takeIf { it.isNotEmpty() }?.let { ob["congestion_control"] = it }
        q["udp_relay_mode"]?.takeIf { it.isNotEmpty() }?.let { ob["udp_relay_mode"] = it }
        val tls = linkedMapOf<String, Any?>("enabled" to true)
        q["sni"]?.takeIf { it.isNotEmpty() }?.let { tls["server_name"] = it }
        if (isTruthy(q["allow_insecure"] ?: "") || isTruthy(q["insecure"] ?: "")) tls["insecure"] = true
        splitCsv(q["alpn"])?.takeIf { it.isNotEmpty() }?.let { tls["alpn"] = it }
        ob["tls"] = tls
        return ob
    }

    private fun socks(uri: String, tag: String): LinkedHashMap<String, Any?>? {
        val u = parseUri(uri) ?: return null
        if (u.host.isEmpty() || u.port <= 0) return null
        val ob = linkedMapOf<String, Any?>(
            "type" to "socks", "tag" to tag, "server" to u.host, "server_port" to u.port, "version" to "5"
        )
        u.userInfo?.takeIf { it.isNotEmpty() }?.let {
            ob["username"] = it.substringBefore(':')
            if (it.contains(':')) ob["password"] = it.substringAfter(':')
        }
        return ob
    }

    // ---- 共用构造 ----

    private fun buildTLSFromQuery(q: Map<String, String>): Map<String, Any?>? {
        val sec = (q["security"] ?: "").lowercase()
        if (sec != "tls" && sec != "reality" && sec != "xtls") return null
        val tls = linkedMapOf<String, Any?>("enabled" to true)
        (q["sni"]?.takeIf { it.isNotEmpty() } ?: q["host"]?.takeIf { it.isNotEmpty() })?.let { tls["server_name"] = it }
        if (isTruthy(q["insecure"] ?: "") || q["allowInsecure"] == "1") tls["insecure"] = true
        splitCsv(q["alpn"])?.takeIf { it.isNotEmpty() }?.let { tls["alpn"] = it }
        normalizeFingerprint(q["fp"] ?: "")?.let { tls["utls"] = linkedMapOf<String, Any?>("enabled" to true, "fingerprint" to it) }
        if (sec == "reality") {
            // sing-box 要求 reality 客户端必须启用 uTLS;节点未提供合法指纹时用默认 chrome
            if (tls["utls"] == null) {
                tls["utls"] = linkedMapOf<String, Any?>("enabled" to true, "fingerprint" to "chrome")
            }
            val reality = linkedMapOf<String, Any?>("enabled" to true)
            q["pbk"]?.takeIf { it.isNotEmpty() }?.let { reality["public_key"] = it }
            q["sid"]?.takeIf { it.isNotEmpty() }?.let { reality["short_id"] = it }
            tls["reality"] = reality
        }
        return tls
    }

    private fun buildTLS(enable: Boolean, sni: String, insecure: Boolean, alpn: List<String>?, fp: String): Map<String, Any?>? {
        if (!enable) return null
        val tls = linkedMapOf<String, Any?>("enabled" to true)
        if (sni.isNotEmpty()) tls["server_name"] = sni
        if (insecure) tls["insecure"] = true
        if (!alpn.isNullOrEmpty()) tls["alpn"] = alpn
        normalizeFingerprint(fp)?.let { tls["utls"] = linkedMapOf<String, Any?>("enabled" to true, "fingerprint" to it) }
        return tls
    }

    private fun buildTransport(net: String, path: String, host: String): Map<String, Any?>? {
        return when (net.lowercase()) {
            "ws" -> linkedMapOf<String, Any?>("type" to "ws").apply {
                if (path.isNotEmpty()) put("path", path)
                if (host.isNotEmpty()) put("headers", linkedMapOf<String, Any?>("Host" to host))
            }
            "grpc" -> linkedMapOf<String, Any?>("type" to "grpc").apply {
                if (path.isNotEmpty()) put("service_name", path)
            }
            "h2", "http" -> linkedMapOf<String, Any?>("type" to "http").apply {
                if (host.isNotEmpty()) put("host", listOf(host))
                if (path.isNotEmpty()) put("path", path)
            }
            else -> null
        }
    }

    /** Reality 参数校验:pbk 必须是 32 字节 base64url;sid 若存在必须是偶数长度十六进制(≤16)。 */
    private fun validReality(q: Map<String, String>): Boolean {
        val pbk = q["pbk"]?.trim().orEmpty()
        if (pbk.isEmpty()) return false
        val decoded = try {
            val mod = pbk.length % 4
            val padded = if (mod == 0) pbk else pbk + "=".repeat(4 - mod)
            Base64.getUrlDecoder().decode(padded)
        } catch (_: Exception) {
            null
        }
        if (decoded == null || decoded.size != 32) return false
        val sid = q["sid"]?.trim().orEmpty()
        if (sid.isNotEmpty()) {
            if (sid.length > 16 || sid.length % 2 != 0) return false
            if (!sid.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        }
        return true
    }

    private val allowedFingerprints = setOf("chrome", "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private fun normalizeFingerprint(fp: String): String? {
        val f = fp.trim().lowercase()
        return if (allowedFingerprints.contains(f)) f else null
    }

    // ---- 基础工具 ----

    private class UriParts(val userInfo: String?, val host: String, val port: Int, val query: Map<String, String>)

    /** 从分享链接中提取服务器主机（vmess 走 JSON，其余走 URI 解析；不支持的协议返回 null）。 */
    fun hostOf(uri: String): String? = when {
        !supported(uri) -> null
        uri.lowercase().startsWith("vmess://") -> {
            val body = uri.substring("vmess://".length).substringBefore('#')
            tryB64(body)?.let { parseFlatJson(it)["add"]?.takeIf { h -> h.isNotEmpty() } }
        }
        else -> parseUri(uri)?.host?.takeIf { it.isNotEmpty() }
    }

    /** 从分享链接中提取服务器端口。 */
    fun portOf(uri: String): Int? = when {
        !supported(uri) -> null
        uri.lowercase().startsWith("vmess://") -> {
            val body = uri.substring("vmess://".length).substringBefore('#')
            tryB64(body)?.let { parseFlatJson(it)["port"]?.toIntOrNull() }
        }
        else -> parseUri(uri)?.port?.takeIf { it > 0 }
    }

    private fun parseUri(uri: String): UriParts? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return null
        var rest = uri.substring(schemeEnd + 3)
        rest = rest.substringBefore('#')
        var query = ""
        val qm = rest.indexOf('?')
        if (qm >= 0) {
            query = rest.substring(qm + 1)
            rest = rest.substring(0, qm)
        }
        val at = rest.lastIndexOf('@')
        var userInfo: String? = null
        var hostport = rest
        if (at >= 0) {
            userInfo = rest.substring(0, at)
            hostport = rest.substring(at + 1)
        }
        var host: String
        var port = -1
        if (hostport.startsWith("[")) {
            val end = hostport.indexOf(']')
            if (end < 0) return null
            host = hostport.substring(1, end)
            val c = hostport.indexOf(':', end)
            if (c >= 0) port = hostport.substring(c + 1).toIntOrNull() ?: -1
        } else {
            val c = hostport.lastIndexOf(':')
            if (c >= 0) {
                host = hostport.substring(0, c)
                port = hostport.substring(c + 1).toIntOrNull() ?: -1
            } else {
                host = hostport
            }
        }
        return UriParts(userInfo, host, port, parseQuery(query))
    }

    private fun parseQuery(q: String): Map<String, String> {
        val out = HashMap<String, String>()
        if (q.isEmpty()) return out
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            if (i < 0) out[decode(pair)] = "" else out[decode(pair.substring(0, i))] = decode(pair.substring(i + 1))
        }
        return out
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun splitCsv(s: String?): List<String>? {
        if (s.isNullOrEmpty()) return null
        return s.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun isTruthy(s: String): Boolean = when (s.trim().lowercase()) {
        "1", "true", "yes" -> true
        else -> false
    }

    private fun tryB64(s: String): String? {
        val clean = s.trim()
        if (clean.isEmpty()) return null
        val candidates = listOf(clean, clean.replace('-', '+').replace('_', '/'))
        for (c in candidates) {
            for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
                try {
                    val mod = c.length % 4
                    if (mod == 1) continue
                    val padded = if (mod == 0) c else c + "=".repeat(4 - mod)
                    val bytes = decoder.decode(padded)
                    if (bytes.isNotEmpty()) return String(bytes, Charsets.UTF_8)
                } catch (_: Exception) {
                    // 下一种
                }
            }
        }
        return null
    }

    // ---- 极简 JSON ----

    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> buildString {
            append('"')
            for (c in v) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> json(v.toString())
    }

    private fun parseFlatJson(input: String): Map<String, String> {
        val out = HashMap<String, String>()
        val s = input.trim()
        var i = s.indexOf('{')
        if (i < 0) return out
        i++
        while (i < s.length) {
            while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= s.length || s[i] == '}') break
            if (s[i] != '"') break
            val keyRes = parseJsonString(s, i) ?: break
            val key = keyRes.first
            i = keyRes.second
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length || s[i] != ':') break
            i++
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            if (s[i] == '"') {
                val vRes = parseJsonString(s, i) ?: break
                out[key] = vRes.first
                i = vRes.second
            } else {
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                out[key] = s.substring(start, i).trim().trim('"')
            }
        }
        return out
    }

    private fun parseJsonString(s: String, start: Int): Pair<String, Int>? {
        if (start >= s.length || s[start] != '"') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                i++
                if (i >= s.length) break
                when (s[i]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 4 < s.length) {
                            sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                            i += 4
                        }
                    }
                    else -> sb.append(s[i])
                }
            } else if (c == '"') {
                return sb.toString() to (i + 1)
            } else {
                sb.append(c)
            }
            i++
        }
        return null
    }

    @Suppress("unused")
    private fun schemeSupported(uri: String): Boolean {
        val low = uri.lowercase()
        return schemes.any { low.startsWith(it) }
    }
}
