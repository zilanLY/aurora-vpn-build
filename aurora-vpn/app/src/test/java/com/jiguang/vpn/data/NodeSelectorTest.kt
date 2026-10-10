package com.jiguang.vpn.data

import com.jiguang.vpn.core.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSelectorTest {

    private val links = listOf(
        Link("vless://u@a.com:443#A", "A"),
        Link("vless://u@b.com:443#B", "B"),
        Link("vless://u@c.com:443#C", "C"),
        Link("vless://u@d.com:443#D", "D")
    )
    private val tags = listOf("node-1", "node-2", "node-3", "node-4")

    @Test
    fun manualSelectionUsesSingleNode() {
        val plan = NodeSelector.plan(links, tags, 2, mapOf("node-1" to 50), 120)
        assertEquals(listOf(2), plan)
    }

    @Test
    fun aliveNodesSortedByLatency() {
        val lat = mapOf("node-1" to 300, "node-2" to 80, "node-3" to LatencyTester.TIMEOUT_MS, "node-4" to 120)
        val plan = NodeSelector.plan(links, tags, -1, lat, 120)
        // node-3 确认超时被剔除,其余按延迟升序
        assertEquals(listOf(1, 3, 0), plan)
    }

    @Test
    fun untestedNodesRankedBelowAliveButAboveTimeout() {
        val lat = mapOf(
            "node-1" to LatencyTester.UNTESTED_MS,
            "node-2" to LatencyTester.TIMEOUT_MS,
            "node-3" to 200,
            "node-4" to LatencyTester.UNTESTED_MS
        )
        val plan = NodeSelector.plan(links, tags, -1, lat, 120)
        // 可用优先,待测次之,确认超时剔除
        assertEquals(listOf(2, 0, 3), plan)
    }

    @Test
    fun clashVerifiedExcludesTimeoutOnly() {
        val lat = mapOf(
            "node-1" to 500,
            "node-2" to LatencyTester.TIMEOUT_MS,
            "node-3" to 100,
            "node-4" to LatencyTester.UNTESTED_MS
        )
        val plan = NodeSelector.plan(links, tags, -1, lat, 120, clashVerified = true)
        // 内核实测:超时剔除,真实延迟排序(UNTESTED_MS 是探测哨兵值,内核实测不会产生)
        assertEquals(listOf(2, 0), plan)
    }

    @Test
    fun limitAppliesAfterSort() {
        val lat = mapOf("node-1" to 300, "node-2" to 80, "node-4" to 120)
        val plan = NodeSelector.plan(links, tags, -1, lat, 2)
        assertEquals(listOf(1, 3), plan)
    }

    @Test
    fun fallsBackToOrderWhenNoResults() {
        val plan = NodeSelector.plan(links, tags, -1, emptyMap(), 2)
        assertEquals(listOf(0, 1), plan)
    }

    @Test
    fun fallsBackWhenAllTimeout() {
        val lat = tags.associateWith { LatencyTester.TIMEOUT_MS }
        val plan = NodeSelector.plan(links, tags, -1, lat, 3)
        assertEquals(listOf(0, 1, 2), plan)
    }

    @Test
    fun emptyLinksPlanEmpty() {
        assertTrue(NodeSelector.plan(emptyList(), emptyList(), -1, emptyMap(), 10).isEmpty())
    }
}