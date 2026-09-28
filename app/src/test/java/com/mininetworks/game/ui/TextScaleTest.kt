package com.mininetworks.game.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The canvas UI grows with the smallest width from sw600dp on, and stays at phone size below. */
class TextScaleTest {
    @Test
    fun uiGrowsOnTabletsOnly() {
        assertEquals(1f, TextScale.uiScale(0), 0f)
        assertEquals(1f, TextScale.uiScale(411), 0f)
        assertEquals(1f, TextScale.uiScale(599), 0f)
        assertEquals(1.3f, TextScale.uiScale(600), 0.01f)
        // Grows with the shortest side from there: a 7" tablet (sw 800 at hdpi) gets more than a small 600 dp one.
        assertEquals(720f / 460f, TextScale.uiScale(720), 0.001f)
        assertEquals(800f / 460f, TextScale.uiScale(800), 0.001f)
        assertTrue(TextScale.uiScale(719) < TextScale.uiScale(720))
        assertEquals(1.9f, TextScale.uiScale(1200), 0f)
    }
}
