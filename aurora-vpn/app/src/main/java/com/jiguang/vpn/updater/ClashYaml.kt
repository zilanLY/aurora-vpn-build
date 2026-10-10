package com.jiguang.vpn.updater

import com.jiguang.vpn.model.ProxyNode
import java.net.URLEncoder
import java.util.Base64

/**
 * Clash/Mihomo YAML 订阅解析器（仅提取 proxies 列表 → v2ray 分享链接）。
 *
 * 无第三方 YAML 依赖：这些仓库的 proxies 均为单行 flow 风格（- {k: v, ...}）
 * 或短横线块风格，按行扫描即可覆盖；嵌套 ws-opts/grpc-opts/reality-opts
 * 用括号计数在同一段内提取。
 */
object ClashYaml {

    fun parse(text: String): List<ProxyNode> {
        if (!text.contains("proxies:")) return emptyList()
        val out = ArrayList<ProxyNode>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            val trimmed = line.trim()
            if (trimmed != "proxies:") continue
            // proxies: 下面的条目：`- {…}` 单行 flow 或 `- key: v` 多行块
            while (i < lines.size) {
                val cur = lines[i]
                val t = cur.trim()
                if (!t.startsWith("-")) break
                val isFlow = t.startsWith("- {")
                val parts = ArrayList<Pair<Int, String>>() // (indent, text)
                var depth = 0
                var first = true
                while (i < lines.size) {
                    val l = lines[i]
                    val lt = l.trim()
                    if (first && lt.startsWith("-")) {
                        parts.add(indentOf(l) to lt.removePrefix("-").trim())
                        depth += delta(lt.removePrefix("-"))
                        first = false
                        i++
                    } else if (isFlow && depth > 0) {
                        // flow 风格被折行：按括号计数拼接
                        val prev = parts.removeAt(parts.size - 1)
                        parts.add(prev.first to (prev.second + " " + lt))
                        depth += delta(lt)
                        i++
                    } else if (!isFlow && !lt.startsWith("-") && lt.isNotEmpty() && !isTopLevelKey(l)) {
                        parts.add(indentOf(l) to lt)
                        i++
                    } else {
                        break
                    }
                }
                val flat = if (isFlow) {
                    parts.joinToString(",") { it.second }
                } else {
                    foldSubMaps(parts).joinToString("\n") { it.second }
                }
                val map = parseFlow(flat.trim().trimEnd(','))
                map["type"]?.let { type ->
                    toLinks(type, map).forEach { raw ->
                        val name = map["name"]?.unquote() ?: raw.substringAfterLast('#', "")
                        out.add(ProxyNode(uri = raw, protocol = raw.substringBefore("://", "unknown"), name = name))
                    }
                }
            }
        }
        return out
    }

    /** 块风格子映射折叠：`key:` + 更深缩进的行 → `key: {k: v, ...}`（递归处理深层嵌套）。 */
    private fun foldSubMaps(parts: List<Pair<Int, String>>): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < parts.size) {
            val (indent, text) = parts[i]
            if (text.endsWith(":") && i + 1 < parts.size && parts[i + 1].first > indent) {
                val sub = ArrayList<Pair<Int, String>>()
                var j = i + 1
                while (j < parts.size && parts[j].first > indent) {
                    sub.add(parts[j])
                    j++
                }
                val folded = foldSubMaps(sub)
                out.add(indent to (text + " {" + folded.joinToString(", ") { it.second } + "}"))
                i = j
            } else {
                out.add(indent to text)
                i++
            }
        }
        return out
    }

    private fun indentOf(s: String): Int {
        var n = 0
        for (c in s) if (c == ' ') n++ else break
        return n
    }

    private fun delta(s: String): Int {
        var d = 0
        for (c in s) {
            when (c) {
                '{', '[' -> d++
                '}', ']' -> d--
            }
        }
        return d
    }

    /** 块风格里遇到无缩进的顶层键（如 proxy-groups:）表示 proxies 列表结束。 */
    private fun isTopLevelKey(line: String): Boolean =
        line.isNotEmpty() && line[0] != ' ' && line.contains(':')

    // ---------- 单条 proxy → 分享链接 ----------

    private fun toLinks(type: String, m: Map<String, String>): List<String> {
        val server = m["server"] ?: return emptyList()
        val port = m["port"]?.toIntOrNull() ?: return emptyList()
        val name = m["name"]?.unquote() ?: "$type-$port"
        val frag = "#" + URLEncoder.encode(name, "UTF-8")
        return when (type.lowercase()) {
            "vmess" -> listOf(vmessLink(m, server, port, frag))
            "vless" -> listOf(vlessLike("vless", m["uuid"] ?: return emptyList(), m, server, port, frag))
            "trojan" -> listOf(vlessLike("trojan", m["password"] ?: return emptyList(), m, server, port, frag))
            else -> emptyList() // ss/socks/http 等不产出
        }
    }

    private fun vmessLink(m: Map<String, String>, server: String, port: Int, frag: String): String {
        val j = """{"v":"2","ps":${jsonStr(m["name"]?.unquote() ?: "vmess")},"add":${jsonStr(server)},"port":$port,""" +
            """"id":${jsonStr(m["uuid"] ?: "")},"aid":${m["alterId"]?.toIntOrNull() ?: 0},"scy":${jsonStr(m["cipher"] ?: "auto")},""" +
            """"net":${jsonStr(m["network"] ?: "tcp")},"tls":${if ((m["tls"] ?: "") == "true") "tls" else ""},""" +
            """"sni":${jsonStr(m["servername"] ?: m["sni"] ?: "")},"fp":${jsonStr(m["client-fingerprint"] ?: "")},""" +
            transportJson(m) +
            """"skip-cert-verify":${if ((m["skip-cert-verify"] ?: "") == "true") "true" else "false"},"host":${jsonStr(wsHost(m))}}"""
        return "vmess://" + Base64.getEncoder().encodeToString(j.toByteArray()) + frag
    }

    private fun transportJson(m: Map<String, String>): String {
        val net = (m["network"] ?: "tcp").lowercase()
        return when (net) {
            "ws" -> """"path":${jsonStr(wsPath(m))},"type":"ws","""
            "grpc" -> """"path":${jsonStr(grpcName(m))},"type":"grpc","""
            else -> """"path":"","type":"$net","""
        }
    }

    private fun wsPath(m: Map<String, String>): String =
        m["path"] ?: nested(m, "ws-opts", "path") ?: "/"

    private fun wsHost(m: Map<String, String>): String {
        nested(m, "ws-opts", "headers", "Host")?.let { return it }
        nested(m, "ws-opts", "headers", "host")?.let { return it }
        return m["host"] ?: m["servername"] ?: m["sni"] ?: ""
    }

    private fun grpcName(m: Map<String, String>): String =
        nested(m, "grpc-opts", "grpc-service-name") ?: (m["path"] ?: "")

    private fun vlessLike(scheme: String, cred: String, m: Map<String, String>, server: String, port: Int, frag: String): String {
        val q = ArrayList<String>()
        if (scheme == "vless") q.add("encryption=none")
        val tlsOn = (m["tls"] ?: "") == "true" || m["reality-opts"] != null
        val net = (m["network"] ?: "tcp").lowercase()
        if (tlsOn) {
            q.add("security=" + if (m["reality-opts"] != null) "reality" else "tls")
            (m["servername"] ?: m["sni"])?.let { q.add("sni=" + it) }
            m["client-fingerprint"]?.let { q.add("fp=" + it) }
            if ((m["skip-cert-verify"] ?: "") == "true") q.add("insecure=1")
            m["reality-opts"]?.let { ro ->
                val pk = regexKey(ro, "public-key")
                val sid = regexKey(ro, "short-id")
                pk?.let { q.add("pbk=" + it) }
                sid?.let { q.add("sid=" + it) }
            }
        }
        when (net) {
            "ws" -> {
                q.add("type=ws")
                q.add("path=" + wsPath(m))
                wsHost(m).takeIf { it.isNotEmpty() }?.let { q.add("host=" + it) }
            }
            "grpc" -> {
                q.add("type=grpc")
                q.add("serviceName=" + grpcName(m))
            }
            else -> q.add("type=$net")
        }
        if (scheme == "vless" && m["flow"] != null) q.add("flow=" + m["flow"]!!)
        return "$scheme://$cred@$server:$port" + (if (q.isEmpty()) "" else "?" + q.joinToString("&")) + frag
    }

    private fun regexKey(src: String, key: String): String? {
        val m = Regex("""$key:\s*("?)([^,"}]+)\1""").find(src) ?: return null
        return m.groupValues[2].trim()
    }

    private fun nested(m: Map<String, String>, vararg path: String): String? {
        var cur = m
        for (i in path.indices) {
            val key = path[i]
            if (i == path.size - 1) return cur[key]
            val inner = cur[key] ?: return null
            // 子映射值形如 "{k: v, ...}"，去掉外层括号再解析
            cur = parseFlow(inner.trim().removePrefix("{").removeSuffix("}").trimEnd(','))
        }
        return null
    }

    // ---------- 迷你 flow-map 解析（含一层引号与嵌套 {}） ----------

    private fun parseFlow(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val n = s.length
        while (i < n) {
            while (i < n && (s[i].isWhitespace() || s[i] == ',')) i++
            if (i >= n) break
            if (s[i] == '{' || s[i] == '[') i++ // 剥掉 entry 外层/残余括号
            if (i >= n) break
            val keyStart = i
            while (i < n && s[i] != ':') i++
            if (i >= n) break
            val key = s.substring(keyStart, i).trim()
            i++ // skip ':'
            while (i < n && s[i] == ' ') i++
            val value: String
            if (i < n && (s[i] == '{' || s[i] == '[')) {
                val open = s[i]
                val close = if (open == '{') '}' else ']'
                var depth = 0
                val start = i
                while (i < n) {
                    if (s[i] == open) depth++
                    else if (s[i] == close) {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                    i++
                }
                value = s.substring(start, i).trim()
            } else if (i < n && (s[i] == '"' || s[i] == '\'')) {
                val quote = s[i]
                val start = i
                i++
                while (i < n && s[i] != quote) i++
                if (i < n) i++
                value = s.substring(start, i)
            } else {
                val start = i
                // 未加引号的裸值：flow 风格以逗号结尾，块风格以换行结尾
                while (i < n && s[i] != ',' && s[i] != '\n') i++
                value = s.substring(start, i).trim()
            }
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun String.unquote(): String {
        val t = trim()
        if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
            val inner = t.substring(1, t.length - 1)
            return try {
                if (t.first() == '"') decodeUnicodeEscapes(inner) else inner
            } catch (_: Exception) {
                inner
            }
        }
        return this
    }

    /** 处理 all.yaml 里 “\U0001F1E9\U0001F1EA” 形式的转义。 */
    private fun decodeUnicodeEscapes(s: String): String {
        if (!s.contains("\\U")) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s.startsWith("\\U000", i) && i + 10 <= s.length) {
                val hex = s.substring(i + 2, i + 10)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.appendCodePoint(code)
                    i += 10
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }
}
