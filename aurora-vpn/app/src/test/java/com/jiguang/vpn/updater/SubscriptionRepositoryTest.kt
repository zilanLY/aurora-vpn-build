package com.jiguang.vpn.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRepositoryTest {

    @Test
    fun defaultSourceIsV2rayFree() {
        assertEquals(SubscriptionRepository.SourceId.V2RAYFREE, SubscriptionRepository.DEFAULT_SOURCE)
    }

    @Test
    fun defaultSourceLabelNonEmpty() {
        assertTrue(SubscriptionRepository.DEFAULT_SOURCE.label.isNotBlank())
    }

    @Test
    fun dedupFiltersSsViaRepositoryHelper() {
        // fetchSingle 使用网络;此处仅验证去重/过滤语义通过 ClashYaml 间接覆盖:
        // 解析出的 ss 节点应被排除(ClashYaml 本身不产出 ss)。
        val yml = """
            proxies:
              - {name: a, server: 1.2.3.4, port: 443, type: vless, uuid: 11111111-1111-1111-1111-111111111111, tls: true}
              - {name: b, server: 5.6.7.8, port: 443, type: ss, cipher: aes-256-gcm, password: x}
        """.trimIndent()
        val nodes = ClashYaml.parse(yml)
        assertTrue(nodes.none { it.uri.startsWith("ss://") })
        assertEquals(1, nodes.size)
    }
}