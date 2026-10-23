package com.boujuan.tvagent.player

import android.content.Context

/** Where tv-agent listens for the player, the token it expects and its certificate's SHA-256 (the pin). */
data class Pairing(val host: String, val port: Int, val token: String, val certSha256: String) {

    companion object {
        private const val FILE = "pairing"

        /** A pairing that can be used, or null: a short token or a malformed pin is never saved. */
        fun of(host: String?, port: Int, token: String?, cert: String?): Pairing? {
            val pin = cert?.lowercase()?.replace(":", "") ?: return null
            if (host.isNullOrBlank() || port !in 1..65535 || token == null || token.length < 16 ||
                !Regex("[0-9a-f]{64}").matches(pin)) return null
            return Pairing(host.trim(), port, token, pin)
        }

        fun load(c: Context): Pairing? {
            val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return of(p.getString("host", null), p.getInt("port", 0), p.getString("token", null), p.getString("cert", null))
        }

        fun save(c: Context, pairing: Pairing): Boolean =
            c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString("host", pairing.host).putInt("port", pairing.port)
                .putString("token", pairing.token).putString("cert", pairing.certSha256).commit()
    }
}
