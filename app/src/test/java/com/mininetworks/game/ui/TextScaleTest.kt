package com.mininetworks.game.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The canvas UI grows by smallest width like sw600dp/sw720dp dimens would, and stays at phone size below. */
class TextScaleTest {
    @Test
    fun uiGrowsOnTabletsOnly() {
        assertEquals(1f, TextScale.uiScale(0), 0f)
        assertEquals(1f, TextScale.uiScale(411), 0f)
        assertEquals(1f, TextScale.uiScale(599), 0f)
        assertEquals(1.2f, TextScale.uiScale(600), 0f)
        assertEquals(1.2f, TextScale.uiScale(719), 0f)
        assertEquals(1.4f, TextScale.uiScale(720), 0f)
        assertEquals(1.4f, TextScale.uiScale(1200), 0f)
    }
}
