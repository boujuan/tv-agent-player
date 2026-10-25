package com.boujuan.tvagent.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What tv-agent's backup reads and restores (the `settings` event and command): the ten options and their values. */
class SettingsTest {
    @Test
    fun aRestoreTakesOnlyValuesTheOptionOffers() {
        assertTrue(Settings.accepts("sub_size", 125))
        assertFalse(Settings.accepts("sub_size", 130))                 // not one of its sizes
        assertTrue(Settings.accepts("jump_back", 15))
        assertFalse(Settings.accepts("volume", 5))                     // not an option
    }

    @Test
    fun theKeysAreTheTenTheBackupKeeps() {
        assertEquals(listOf("sub_size", "sub_place", "sub_color", "sub_background", "sub_edge", "sub_font", "jump_back",
            "jump_forward", "held_speed", "badges"), Settings.ALL.map { it.key })
    }
}
