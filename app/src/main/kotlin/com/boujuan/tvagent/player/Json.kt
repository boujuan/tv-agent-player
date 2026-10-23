package com.boujuan.tvagent.player

import org.json.JSONObject

/** A protocol message {"t": type, …}; fields that are null are left out. */
fun msg(type: String, vararg fields: Pair<String, Any?>): JSONObject {
    val o = JSONObject().put("t", type)
    for ((k, v) in fields) if (v != null) o.put(k, v)
    return o
}
