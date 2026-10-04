package com.mininetworks.game.audio

import com.mininetworks.game.game.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every service plucks its own note of the pentatonic scale, within the range SoundPool can play; the alarm rises in
 * pitch and volume.
 */
class ServicePitchTest {

    @Test
    fun everyServiceHasItsOwnNoteInThePentatonicScale() {
        val notes = Service.entries.map(ServicePitch::semitones)
        assertEquals(notes.size, notes.toSet().size)
        val pentatonic = setOf(0, 2, 4, 7, 9)
        for (n in notes) assertTrue("$n is in C major pentatonic", Math.floorMod(n, 12) in pentatonic)
    }

    @Test
    fun ratesStayInSoundPoolRange() {
        for (s in Service.entries) assertTrue(ServicePitch.rate(s) in 0.5f..2.0f)
        assertEquals(1f, ServicePitch.rate(Service.MAIL), 0f)
        assertEquals(2f, ServicePitch.rate(Service.CAMERA_UPLOAD), 1e-6f)
    }

    @Test
    fun alarmRisesInPitchAndVolume() {
        assertTrue(1f < Alarm.HALF_RATE && Alarm.HALF_RATE < Alarm.CRITICAL_RATE && Alarm.CRITICAL_RATE <= 2f)
        assertTrue(1f < Alarm.HALF_VOLUME && Alarm.HALF_VOLUME < Alarm.CRITICAL_VOLUME)
        assertTrue("the loudest alarm still fits SoundPool's volume", Sound.WARNING.volume * Alarm.CRITICAL_VOLUME <= 1f)
    }
}
