package com.jiguang.vpn.data

import org.json.JSONArray

/** 解析 ip-api.com /batch 响应（JSON 数组，每项含 status/country/countryCode/query）。 */
object RegionApiParser {

    data class Row(val query: String?, val country: String?, val countryCode: String?)

    fun parse(body: String): List<Row> = try {
        val arr = JSONArray(body)
        (0 until arr.length()).map { i ->
            val o = arr.optJSONObject(i) ?: return@map Row(null, null, null)
            val status = o.optString("status")
            Row(
                o.optString("query", ""),
                if (status == "success") o.optString("country", "") else null,
                if (status == "success") o.optString("countryCode", "") else null
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
