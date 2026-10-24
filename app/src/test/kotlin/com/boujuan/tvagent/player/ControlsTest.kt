package com.boujuan.tvagent.player

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who gets a key on the player screen: the controls under the bar, or the film (Controls.takes). */
class ControlsTest {
    private val arrows = listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)

    @Test
    fun withAControlPickedTheArrowsMoveThePickAndNeverSeek() {
        for (key in arrows) assertTrue(key.toString(), Controls.takes(key, barShown = true, picked = true, open = false))
    }

    @Test
    fun withNothingPickedLeftAndRightJumpAndOkPlays() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_DPAD_UP)) {
            assertFalse(key.toString(), Controls.takes(key, barShown = true, picked = false, open = false))
        }
    }

    @Test
    fun downPicksPlayOnlyWhileTheBarIsUp() {
        assertTrue(Controls.takes(KeyEvent.KEYCODE_DPAD_DOWN, barShown = true, picked = false, open = false))
        assertFalse(Controls.takes(KeyEvent.KEYCODE_DPAD_DOWN, barShown = false, picked = false, open = false))   // it shows the bar
    }

    @Test
    fun anOpenListOrPanelTakesTheArrowsAndMediaKeysStayTheFilms() {
        for (key in arrows) assertTrue(key.toString(), Controls.takes(key, barShown = true, picked = false, open = true))
        for (picked in listOf(true, false)) {
            assertFalse(Controls.takes(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, barShown = true, picked = picked, open = true))
            assertFalse(Controls.takes(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, barShown = true, picked = picked, open = false))
        }
    }
}
