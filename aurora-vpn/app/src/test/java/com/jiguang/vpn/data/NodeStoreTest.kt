package com.jiguang.vpn.data

import com.jiguang.vpn.model.ProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStoreTest {

    private fun node(uri: String, name: String = "") = ProxyNode(uri, uri.substringBefore("://", "unknown"), name)

    private val vless = node("vless://u@1.2.3.4:443?security=tls#a", "香港A")
    private val vmess = node(
        "vmess://eyJ2IjoiMiIsImFkZCI6IjUuNi43LjgiLCJwb3J0Ijo0NDMsImlkIjoiMTExMTExMTEtMTExMS0xMTExLTExMTEtMTExMTExMTExMTExIn0=",
        "日本B"
    )
    private val trojan = node("trojan://p@9.9.9.9:443#c", "美国C")
    private val ss = node("ss://YWVzOnB3@2.2.2.2:443#s", "ss节点")

    @Test
    fun appendExcludesSsAndDedupes() {
        NodeStore.clear()
        val added = NodeStore.append(listOf(vless, ss, vmess, vless, trojan))
        assertEquals(3, added)
        assertEquals(3, NodeStore.links.size)
        assertFalse(NodeStore.links.any { it.uri.startsWith("ss://") })
        // name 保留
        assertTrue(NodeStore.links.any { it.name == "香港A" })
    }

    @Test
    fun appendReturnsZeroForDuplicateBatch() {
        NodeStore.clear()
        NodeStore.append(listOf(vless))
        val again = NodeStore.append(listOf(vless))
        assertEquals(0, again)
        assertEquals(1, NodeStore.links.size)
    }

    @Test
    fun tagsAlignWithLinks() {
        NodeStore.clear()
        NodeStore.append(listOf(vless, vmess, trojan))
        assertEquals(NodeStore.links.size, NodeStore.tags.size)
        assertEquals("node-1", NodeStore.tags[0])
        assertEquals("node-3", NodeStore.tags[2])
    }

    @Test
    fun cacheRoundTripKeepsUriAndName() {
        NodeStore.clear()
        NodeStore.append(listOf(vless, vmess))
        val text = NodeStore.toCacheText()
        val parsed = NodeStore.parseCacheText(text)
        assertEquals(NodeStore.links.size, parsed.size)
        assertEquals("香港A", parsed[0].name)
        assertEquals(NodeStore.links[0].uri, parsed[0].uri)
    }

    @Test
    fun parseCacheSupportsLegacyUriOnly() {
        val parsed = NodeStore.parseCacheText("vless://u@1.2.3.4:443#x\n\ntrojan://p@9.9.9.9:443#y")
        assertEquals(2, parsed.size)
        assertEquals("", parsed[0].name)
        assertTrue(parsed[0].uri.startsWith("vless://"))
    }
}