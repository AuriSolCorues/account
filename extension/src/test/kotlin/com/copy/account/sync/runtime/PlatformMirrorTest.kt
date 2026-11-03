package com.copy.account.sync.runtime

import com.copy.account.sync.data.PlatformState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformMirrorTest {

    private fun defaultTheme(themeMode: String = "system", accentTheme: String = "green", customThemeJson: String = "") =
        PlatformState(locked = true, themeMode = themeMode, accentTheme = accentTheme, customThemeJson = customThemeJson)

    @Test
    fun initial_defaultState_locked() {
        val mirror = PlatformMirror { defaultTheme() }
        assertTrue(mirror.locked)
    }

    @Test
    fun refresh_bridgeUnreachable_stillLocked() {
        val mirror = PlatformMirror {
            PlatformState(locked = false, themeMode = "system", accentTheme = "green", customThemeJson = "", unreachable = true)
        }
        mirror.refresh()
        assertTrue(mirror.locked)
    }

    @Test
    fun markLockedLocally_lockedImmediately() {
        val mirror = PlatformMirror { PlatformState(locked = false, themeMode = "system", accentTheme = "green", customThemeJson = "") }
        mirror.refresh()
        assertFalse(mirror.locked)
        mirror.markLockedLocally()
        assertTrue(mirror.locked)
    }

    @Test
    fun refresh_themeTriple_passesThrough() {
        val mirror = PlatformMirror {
            PlatformState(locked = true, themeMode = "dark", accentTheme = "blue", customThemeJson = "{\"x\":1}")
        }
        mirror.refresh()
        assertEquals("dark", mirror.state.themeMode)
        assertEquals("blue", mirror.state.accentTheme)
        assertEquals("{\"x\":1}", mirror.state.customThemeJson)
    }

    @Test
    fun refresh_unlockedAfterLocalMark_clearsLocalFlag() {
        var reply = defaultTheme()
        val mirror = PlatformMirror({ reply })
        mirror.markLockedLocally()
        assertTrue(mirror.locked)
        reply = PlatformState(locked = false, themeMode = "system", accentTheme = "green", customThemeJson = "")
        mirror.refresh()
        assertFalse(mirror.locked)
    }
}
