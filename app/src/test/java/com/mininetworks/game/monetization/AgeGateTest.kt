package com.mininetworks.game.monetization

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgeGateTest {
    private val today = LocalDate.of(2026, 9, 27)

    @Test fun ageBoundaries() {
        assertEquals(AgeGate.Band.UNDER_13, AgeGate.band(LocalDate.of(2013, 9, 28), today))
        assertEquals(AgeGate.Band.TEEN, AgeGate.band(LocalDate.of(2013, 9, 27), today))
        assertEquals(AgeGate.Band.TEEN, AgeGate.band(LocalDate.of(2008, 9, 28), today))
        assertEquals(AgeGate.Band.ADULT, AgeGate.band(LocalDate.of(2008, 9, 27), today))
    }

    @Test fun invalidDatesAreNotAccepted() {
        assertNull(AgeGate.parse("2013-02-30", today))
        assertNull(AgeGate.parse("2027-01-01", today))
        assertNull(AgeGate.parse("", today))
    }
}
