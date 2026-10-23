package com.boujuan.tvagent.player

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/** The player screen, opened by tv-agent. This first version only says whether it's paired. */
class PlayerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setText(if (Pairing.load(this@PlayerActivity) == null) R.string.not_paired else R.string.connecting)
        }
        setContentView(FrameLayout(this).apply { addView(status) })
    }
}
