package com.jiguang.vpn.updater

import android.util.Base64
import com.jiguang.vpn.model.ProxyNode
import java.net.URLDecoder

object NodeParser {

    private val schemes = listOf(
        "ss://", "ssr://", "vmess://", "vless://",
        "trojan://", "hysteria2://", "hysteria://", "tuic://", "socks5://"
    )

    private val whitespace = Regex("\\s+")

    fun parseTxt(raw: String): List<ProxyNode> {
        if (raw.isBlank()) return emptyList()
        // 站点 txt 实测为「多行、每行一段 base64」，但部分订阅是整包 base64，
        // 因此先按行解码，整行都拿不到节点时再尝试整包解码。
        val perLine = parseLines(raw.lines())
        if (perLine.isNotEmpty()) return perLine
        val whole = tryB64(raw.replace(whitespace, ""))
        return if (whole != null) parseLines(whole.lines()) else emptyList()
    }

    private fun parseLines(lines: List<String>): List<ProxyNode> {
        val out = ArrayList<ProxyNode>(lines.size)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val uri = normalizeLine(line) ?: continue
            out.add(ProxyNode(uri = uri, protocol = protocolOf(uri), name = nameOf(uri, out.size)))
        }
        return out
    }

    private fun normalizeLine(line: String): String? {
        if (matchesScheme(line)) return line
        val decoded = tryB64(line)?.trim() ?: return null
        // 整包 base64：解码后是多行，交由 parseTxt 的整包分支处理。
        if (decoded.contains('\n') || decoded.contains('\r')) return null
        return if (matchesScheme(decoded)) decoded else null
    }

    private fun matchesScheme(s: String): Boolean =
        schemes.any { s.startsWith(it, ignoreCase = true) }

    private fun tryB64(s: String): String? {
        val clean = s.trim()
        if (clean.isEmpty()) return null
        // 同时兼容标准 base64 与 URL-safe 变体（- _ 字符）。
        val candidates = listOf(clean, clean.replace('-', '+').replace('_', '/'))
        for (c in candidates) {
            for (flag in intArrayOf(Base64.URL_SAFE or Base64.NO_WRAP, Base64.DEFAULT or Base64.NO_WRAP)) {
                try {
                    var b = c
                    val mod = b.length % 4
                    if (mod == 1) continue
                    if (mod > 0) b += "=".repeat(4 - mod)
                    val decoded = Base64.decode(b, flag)
                    if (decoded.isNotEmpty()) return String(decoded, Charsets.UTF_8)
                } catch (_: Exception) {
                    // 换下一种解码方式
                }
            }
        }
        return null
    }

    private fun protocolOf(uri: String): String =
        uri.substringBefore("://", missingDelimiterValue = "unknown").lowercase()

    private fun nameOf(uri: String, index: Int): String {
        val frag = uri.substringAfterLast('#', missingDelimiterValue = "")
        if (frag.isNotBlank()) {
            return try {
                URLDecoder.decode(frag, "UTF-8")
            } catch (_: Exception) {
                frag
            }
        }
        return "${protocolOf(uri)}-${index + 1}"
    }
}
