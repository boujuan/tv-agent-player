package com.boujuan.tvagent.player

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView

/**
 * TV Agent Player on the home screen: its settings and About, the same lists as the pause screen's, with a line of
 * subtitles showing their look. It plays nothing: tv-agent opens the player itself.
 */
class SettingsActivity : Activity() {
    private lateinit var menu: Menu

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings)
        val settings = Settings(this)
        val subtitles = findViewById<SubtitleView>(R.id.settings_subtitles)
        settings.styleSubtitles(subtitles)
        subtitles.setCues(listOf(Cue.Builder().setText(getString(R.string.sample_subtitle)).build()))
        findViewById<TextView>(R.id.settings_version).text =
            getString(R.string.version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        menu = Menu(findViewById(R.id.settings_panel), findViewById(R.id.settings_title), findViewById(R.id.settings_scroll),
            findViewById(R.id.settings_list))
        SettingsMenu(this, settings, menu, preview = {}, applied = { settings.styleSubtitles(subtitles) }, agent = { null }).open()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {
            if (menu.depth > 1) menu.back() else finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // ← on the first list would close it, leaving an empty screen: Back leaves instead.
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && menu.depth <= 1) return true
        return menu.onKey(keyCode) || super.onKeyDown(keyCode, event)
    }
}
