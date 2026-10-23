package com.boujuan.tvagent.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Pairing from the computer: `tv-agent tv pair` sends the agent's address (`host`), `port`, `token` and certificate
 * pin (`cert`) here over ADB. Only shell can send it (the manifest requires DUMP). Answers RESULT_OK (-1) when saved,
 * so the computer can check.
 */
class PairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pairing = Pairing.of(intent.getStringExtra("host"), intent.getIntExtra("port", 0),
            intent.getStringExtra("token"), intent.getStringExtra("cert"))
        resultCode = if (pairing != null && Pairing.save(context, pairing)) Activity.RESULT_OK else Activity.RESULT_CANCELED
    }
}
