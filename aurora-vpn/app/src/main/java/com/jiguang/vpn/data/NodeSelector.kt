package com.jiguang.vpn.data

import com.jiguang.vpn.core.Link

/**
 * 连接候选规划:
 * - 手动选中的节点 → 只用该节点(配置最小,连接最快);
 * - 自动模式 → 优先选用可用性确认的节点,按延迟升序截取前 [limit] 个:
 *   ① 内核实测(Clash API,source=clash,sourceTag="clash")结果最可信;
 *   ② 本地 TLS/TCP 探测结果次之;
 *   ③ 待测(UDP 类协议,UNTESTED_MS)节点排在可用节点之后但仍可入选,由内核兜底;
 *   ④ 确认超时(TIMEOUT_MS)的节点不入选;
 *   若无任何结果,退化为原顺序前 [limit] 个。
 *
 * 返回选中的原始下标列表(保持截取后的顺序)。
 */
object NodeSelector {

    fun plan(
        links: List<Link>,
        tags: List<String>,
        selectedIndex: Int,
        latencies: Map<String, Int>,
        limit: Int,
        clashVerified: Boolean = false
    ): List<Int> {
        if (links.isEmpty()) return emptyList()
        val manual = selectedIndex in links.indices
        if (manual) return listOf(selectedIndex)

        // 排序权重:数值越小越优先
        fun weight(i: Int): Int {
            val ms = tags.getOrNull(i)?.let { latencies[it] } ?: return Int.MAX_VALUE - 1
            return when {
                // 内核实测过:按真实延迟排,最可信
                clashVerified -> ms
                ms < LatencyTester.UNTESTED_MS -> ms          // 本地探测可用
                ms == LatencyTester.UNTESTED_MS -> Int.MAX_VALUE - 2 // 待测:排在超时前
                else -> Int.MAX_VALUE                          // 确认超时:不优先
            }
        }

        val candidates = if (clashVerified) {
            // 内核实测:超时与「待测」哨兵值都排除——后者意味着该节点没有内核实测结果,不可信任
            links.indices.filter { i ->
                val ms = tags.getOrNull(i)?.let { latencies[it] }
                ms != null && ms < LatencyTester.UNTESTED_MS
            }
        } else {
            // 本地探测:确认超时的排除;可用 + 待测都允许入选
            links.indices.filter { i ->
                val ms = tags.getOrNull(i)?.let { latencies[it] }
                ms == null || ms != LatencyTester.TIMEOUT_MS
            }
        }
        if (candidates.isEmpty()) return links.indices.toList().take(limit)
        return candidates.sortedBy { weight(it) }.take(limit)
    }
}
