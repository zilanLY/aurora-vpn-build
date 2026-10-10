package com.jiguang.vpn.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SingBoxConfigTest {

    private fun vmessUri(): String {
        val j = """{"v":"2","ps":"样本","add":"example.com","port":443,"id":"11111111-1111-1111-1111-111111111111","aid":0,"scy":"auto","net":"ws","type":"none","host":"example.com","path":"/ws","tls":"tls","sni":"example.com","alpn":"h2,http/1.1","fp":"chrome"}"""
        return "vmess://" + Base64.getEncoder().encodeToString(j.toByteArray())
    }

    private fun realityKey(): String {
        val b = ByteArray(32) { it.toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun samples(): List<Link> = listOf(
        Link(vmessUri(), "样本vmess"),
        Link("vless://11111111-1111-1111-1111-111111111111@example.com:443?encryption=none&security=reality&sni=example.com&fp=chrome&pbk=${realityKey()}&sid=0123#样本vless", "样本vless"),
        Link("trojan://password@example.com:443?security=tls&sni=example.com&type=ws&path=/ws&host=example.com#样本trojan", "样本trojan"),
        Link("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:password".toByteArray()) + "@example.com:8388#样本ss", "样本ss"),
        Link("hysteria2://password@example.com:443?sni=example.com&insecure=1#样本hysteria2", "样本hysteria2"),
        Link("tuic://11111111-1111-1111-1111-111111111111:password@example.com:443?congestion_control=bbr&alpn=h3&sni=example.com#样本tuic", "样本tuic"),
        Link("socks5://user:pass@127.0.0.1:1080#样本socks", "样本socks")
    )

    @Test
    fun allCommonProtocolsSupported() {
        for (l in samples()) {
            assertTrue("should support ${l.uri.take(12)}", SingBoxConfig.supported(l.uri))
        }
    }

    @Test
    fun unsupportedProtocolsFiltered() {
        assertNull(SingBoxConfig.buildOutbound("ssr://abc", "t"))
        assertNull(SingBoxConfig.buildOutbound("http://example.com", "t"))
        val (kept, _) = SingBoxConfig.supportedLinks(listOf(Link("ssr://abc", "a"), Link("socks5://1.2.3.4:1080", "b")))
        assertEquals(1, kept.size)
    }

    @Test
    fun outboundFieldsCorrect() {
        val vm = SingBoxConfig.buildOutbound(vmessUri(), "node-1")!!
        assertEquals("vmess", vm["type"])
        assertEquals("example.com", vm["server"])
        assertEquals(443, vm["server_port"])
        val tls = vm["tls"] as Map<*, *>
        assertEquals(true, tls["enabled"])
        val tr = vm["transport"] as Map<*, *>
        assertEquals("ws", tr["type"])

        val ss = SingBoxConfig.buildOutbound("ss://" + Base64.getEncoder().encodeToString("aes-256-gcm:password".toByteArray()) + "@example.com:8388#x", "n")!!
        assertEquals("shadowsocks", ss["type"])
        assertEquals("aes-256-gcm", ss["method"])
        assertEquals("password", ss["password"])

        val hy = SingBoxConfig.buildOutbound("hysteria2://p@example.com:443?sni=example.com&insecure=1#x", "n")!!
        assertEquals("hysteria2", hy["type"])
        assertEquals(true, (hy["tls"] as Map<*, *>)["insecure"])
    }

    @Test
    fun configHasRequiredSections() {
        val res = SingBoxConfig.buildConfig(samples(), "rule", null)
        assertNotNull(res)
        val json = res!!.json
        assertTrue(json.contains("\"outbounds\""))
        assertTrue(json.contains("\"selector\""))
        // 低内存设备防护:连接配置不再包含 urltest 健康检查组
        assertTrue(!json.contains("\"urltest\""))
        assertTrue(json.contains("\"tun\""))
        assertTrue(json.contains("\"auto_route\":true"))
        assertEquals(samples().size, res.tags.size)
        assertTrue(res.tags.none { it.isEmpty() })
    }

    @Test
    fun globalModeOmitsPrivateRule() {
        val rule = SingBoxConfig.buildConfig(samples(), "rule", null)!!.json
        val global = SingBoxConfig.buildConfig(samples(), "global", null)!!.json
        assertTrue(rule.contains("ip_is_private"))
        assertTrue(!global.contains("ip_is_private"))
        assertTrue(global.contains("\"final\":\"proxy\""))
    }

    @Test
    fun hostOfAndPortOfExtractServers() {
        assertEquals("example.com", SingBoxConfig.hostOf(vmessUri()))
        assertEquals(443, SingBoxConfig.portOf(vmessUri()))
        assertEquals("example.com", SingBoxConfig.hostOf("vless://uuid@example.com:8443?security=tls#x"))
        assertEquals(8443, SingBoxConfig.portOf("vless://uuid@example.com:8443?security=tls#x"))
        assertEquals("1.2.3.4", SingBoxConfig.hostOf("trojan://pw@1.2.3.4:443#x"))
        assertEquals(1080, SingBoxConfig.portOf("socks5://1.2.3.4:1080#x"))
        assertNull(SingBoxConfig.hostOf("ssr://junk"))
    }

    private fun realityUriWithoutFingerprint(): String {
        val pbk = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        return "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
            "?encryption=none&security=reality&sni=example.com&pbk=$pbk&sid=0123#reality无指纹"
    }

    @Test
    fun realityWithoutFingerprintForcesUtls() {
        // sing-box 要求 reality 客户端必须启用 uTLS,否则整份配置启动失败
        val ob = SingBoxConfig.buildOutbound(realityUriWithoutFingerprint(), "node-1")!!
        val tls = ob["tls"] as Map<*, *>
        val utls = tls["utls"] as? Map<*, *>
        assertNotNull("reality 节点必须带 utls", utls)
        assertEquals(true, utls!!["enabled"])
        assertEquals("chrome", utls["fingerprint"])
        assertNotNull(tls["reality"])
    }

    @Test
    fun realityWithoutPublicKeyIsFiltered() {
        val bad = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=reality&sni=example.com#noPbk"
        assertNull(SingBoxConfig.buildOutbound(bad, "t"))
        val (kept, _) = SingBoxConfig.supportedLinks(
            listOf(Link(bad, "bad"), Link(realityUriWithoutFingerprint(), "good"))
        )
        assertEquals(1, kept.size)
        assertEquals("good", kept[0].name)
    }

    @Test
    fun configUsesModernSniffActionNotLegacyInboundField() {
        // sing-box 1.13+ 移除了 inbound 的 legacy sniff 字段,必须改为 route action=sniff,
        // 否则 libbox startOrReloadService 解析失败导致无法连接。
        for (mode in listOf("rule", "global")) {
            val json = SingBoxConfig.buildConfig(samples(), mode, null)!!.json
            assertTrue("mode=$mode 缺少 sniff action", json.contains("\"action\":\"sniff\""))
            assertTrue("mode=$mode 仍含 legacy sniff 字段", !json.contains("\"sniff\":true"))
        }
    }

    @Test
    fun selectedTagOverridesDefault() {
        val res = SingBoxConfig.buildConfig(samples(), "rule", null, "node-3")!!
        assertTrue(res.json.contains("\"default\":\"node-3\""))
        // 非法 selectedTag 退化为第一个节点
        val fallback = SingBoxConfig.buildConfig(samples(), "rule", null, "node-99")!!.json
        assertTrue(fallback.contains("\"default\":\"node-1\""))
    }
}
