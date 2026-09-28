package com.mininetworks.game.monetization

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.mininetworks.game.R
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlertDialog

/**
 * The release first start (docs/TOP100.md B1): the neutral age screen picks the date on three wheels (no free-text
 * format), starts the online services with the right band, and remembers an under-13 answer across relaunches.
 */
@RunWith(RobolectricTestRunner::class)
class AgeGateDialogTest {

    private val today = LocalDate.of(2026, 9, 28)

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun gate(activity: Activity, answers: MutableList<AgeGate.Band>) =
        AgeGateDialog(activity, today = { today }) { answers += it }.also { it.show(); shadowOf(Looper.getMainLooper()).idle() }

    private fun pick(gate: AgeGateDialog, year: Int, month: Int, day: Int) {
        gate.year.value = year
        gate.month.value = month
        gate.day.value = day
    }

    @Test
    fun neutralWheelsStartOnTheCurrentYearAndPickAnAdult() {
        val activity = activity()
        val answers = mutableListOf<AgeGate.Band>()
        val gate = gate(activity, answers)
        assertTrue(gate.dialog!!.isShowing)
        assertEquals("no age is preselected", today.year, gate.year.value)
        assertEquals(1, gate.month.value)
        assertEquals(1, gate.day.value)
        assertEquals("months have names, not a format to learn", 12, gate.month.displayedValues.size)
        pick(gate, 1990, 5, 17)
        gate.dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(listOf(AgeGate.Band.ADULT), answers)
        assertFalse(gate.dialog!!.isShowing)
        assertEquals(LocalDate.of(1990, 5, 17), AgeGate.savedBirthDate(activity))
    }

    @Test
    fun aTeenStartsTheServicesAsTeen() {
        val answers = mutableListOf<AgeGate.Band>()
        val gate = gate(activity(), answers)
        pick(gate, 2011, 1, 1)
        gate.confirm()
        assertEquals(listOf(AgeGate.Band.TEEN), answers)
    }

    @Test
    fun aFutureDateIsRejectedInPlace() {
        val activity = activity()
        val answers = mutableListOf<AgeGate.Band>()
        val gate = gate(activity, answers)
        pick(gate, 2026, 12, 1)
        gate.confirm()
        assertTrue(answers.isEmpty())
        assertTrue(gate.dialog!!.isShowing)
        assertEquals(activity.getString(R.string.age_gate_invalid), gate.error.text.toString())
    }

    @Test
    fun theDayWheelFollowsTheMonthLength() {
        val gate = gate(activity(), mutableListOf())
        gate.year.value = 2023
        gate.month.value = 2
        gate.fitDays()
        assertEquals(28, gate.day.maxValue)
        gate.year.value = 2024
        gate.fitDays()
        assertEquals(29, gate.day.maxValue)
    }

    @Test
    fun underThirteenIsRememberedAcrossRelaunches() {
        val activity = activity()
        val answers = mutableListOf<AgeGate.Band>()
        val gate = gate(activity, answers)
        pick(gate, 2016, 3, 3)
        gate.confirm()
        assertTrue(answers.isEmpty())
        assertNull("the date of a child is not stored", AgeGate.savedBirthDate(activity))
        assertTrue(AgeGate.isUnderAge(activity))

        // Relaunch: straight to the minimum-age notice, no date wheels to try again.
        val again = gate(activity(), answers)
        assertTrue(again.dialog!!.isShowing)
        assertEquals(activity.getString(R.string.age_gate_minimum), shadowOf(ShadowAlertDialog.getLatestAlertDialog()).message.toString())
        assertTrue(answers.isEmpty())
    }
}
