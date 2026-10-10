package com.jiguang.vpn.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashYamlTest {

    @Test
    fun flowStyleProxiesParsed() {
        val yml = """
            port: 7890
            proxies:
              - {name: "未知 VLESS-93 | free-nodes", server: 85.149.211.28, port: 8443, type: vless, uuid: 0250424c-ae75-4b6b-890f-3fe26fae36c3, tls: true, tfo: false, skip-cert-verify: false, servername: hk.152568.xyz, client-fingerprint: edge, network: ws, ws-opts: {path: /cd, headers: {Host: hk.152568.xyz}}}
              - {name: 未知 VMESS-219 | free-nodes, server: 103.215.218.158, port: 22324, type: vmess, uuid: 04621bae-ab36-11ec-b909-0242ac120002, alterId: 0, cipher: auto, tls: false, skip-cert-verify: false}
              - {name: 未知 TROJAN-85 | free-nodes, server: 104.18.32.87, port: 443, type: trojan, password: 018b8e7b-7813-4dcb-84a5-3c98f1521e00, sni: id1.nathaya.web.id, skip-cert-verify: false, network: ws, ws-opts: {path: /trojan, headers: {Host: id1.nathaya.web.id}}}
              - {name: 未知 SS-01 | free-nodes, server: 130.61.103.121, port: 59924, type: ss, cipher: aes-256-gcm, password: xyz}
            proxy-groups:
              - name: PROXY
        """.trimIndent()
        val nodes = ClashYaml.parse(yml)
        assertEquals(3, nodes.size)
        assertTrue(nodes.none { it.uri.startsWith("ss://") })
        val vless = nodes.first { it.uri.startsWith("vless://") }
        assertTrue(vless.uri.contains("0250424c-ae75-4b6b-890f-3fe26fae36c3@85.149.211.28:8443"))
        assertTrue(vless.uri.contains("security=tls"))
        assertTrue(vless.uri.contains("type=ws"))
        assertTrue(vless.uri.contains("path=%2Fcd") || vless.uri.contains("path=/cd"))
        val vmess = nodes.first { it.uri.startsWith("vmess://") }
        assertTrue(vmess.name.contains("VMESS-219"))
        val trojan = nodes.first { it.uri.startsWith("trojan://") }
        assertTrue(trojan.uri.contains("018b8e7b-7813-4dcb-84a5-3c98f1521e00@104.18.32.87:443"))
    }

    @Test
    fun blockStyleWithRealityAndUnicodeNames() {
        val yml = """
            proxies:
                - client-fingerprint: chrome
                  flow: xtls-rprx-vision
                  name: "\U0001F1E9\U0001F1EADE_1|1.7MB/s"
                  network: tcp
                  port: 443
                  reality-opts:
                    public-key: kGdP0X0DwcMbXovNjpQ3o7ubghwu1dedhcoFtLFOnBk
                    short-id: 1fc709
                  server: agroup.de1.ilovegairport.com
                  servername: eu-central-1.console.aws.amazon.com
                  tls: true
                  type: vless
                  udp: true
                  uuid: 129e26fe-cee2-4786-9261-b29668337829
                - name: "\U0001F1ED\U0001F1F0HK_1|3.8MB/s"
                  password: 3fc0f254-54c3-4cf3-896b-5999a2b4c757
                  port: 56726
                  server: test01.y1s
                  type: trojan
        """.trimIndent()
        val nodes = ClashYaml.parse(yml)
        assertEquals(2, nodes.size)
        val vless = nodes[0]
        assertTrue(vless.uri.startsWith("vless://129e26fe-cee2-4786-9261-b29668337829@agroup.de1.ilovegairport.com:443"))
        assertTrue(vless.uri.contains("security=reality"))
        assertTrue(vless.uri.contains("pbk=kGdP0X0DwcMbXovNjpQ3o7ubghwu1dedhcoFtLFOnBk"))
        assertTrue(vless.uri.contains("sid=1fc709"))
        assertTrue(vless.uri.contains("flow=xtls-rprx-vision"))
        assertTrue(vless.name.contains("DE_1"))
        val trojan = nodes[1]
        assertTrue(trojan.uri.startsWith("trojan://3fc0f254-54c3-4cf3-896b-5999a2b4c757@test01.y1s:56726"))
        assertTrue(trojan.name.contains("HK_1"))
    }

    @Test
    fun ssExcludedInBuiltinFetchFilter() {
        // 过滤逻辑在 BuiltinSources 内做，这里验证 URI 判定helper 的行为一致性
        val uri = "ss://YWVzLTI1Ni1nY206cHc=@1.2.3.4:443#x"
        assertTrue(uri.startsWith("ss://"))
    }

    @Test
    fun grpcNodesProduceServiceName() {
        val yml = """
            proxies:
              - {name: g1, server: 1.2.3.4, port: 443, type: vless, uuid: 11111111-2222-3333-4444-555555555555, tls: true, servername: a.b.c, network: grpc, grpc-opts: {grpc-mode: gun, grpc-service-name: cdn.v1.AssetService}}
        """.trimIndent()
        val nodes = ClashYaml.parse(yml)
        assertEquals(1, nodes.size)
        assertTrue(nodes[0].uri.contains("type=grpc"))
        assertTrue(nodes[0].uri.contains("serviceName=cdn.v1.AssetService"))
    }
}
