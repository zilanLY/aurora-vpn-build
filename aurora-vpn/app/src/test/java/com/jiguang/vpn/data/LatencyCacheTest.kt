package com.jiguang.vpn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyCacheTest {

    @Test
    fun encodeDecodeRoundTrip() {
        val values = mapOf("node-1" to 123, "node-2" to 456, "node-9" to 78)
        val decoded = LatencyCodec.decode(LatencyCodec.encode(values))
        assertEquals(values, decoded)
    }

    @Test
    fun decodeSkipsMalformedLines() {
        val raw = "node-1\t100\nbroken-line\nnode-2\t200\n\t300\nnode-3\tabc\n"
        val decoded = LatencyCodec.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals(100, decoded["node-1"])
        assertEquals(200, decoded["node-2"])
    }

    @Test
    fun decodeNullAndEmpty() {
        assertTrue(LatencyCodec.decode(null).isEmpty())
        assertTrue(LatencyCodec.decode("").isEmpty())
    }
}