package com.mininetworks.game.monetization

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.mininetworks.game.R
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle

/**
 * The neutral age screen of release builds (docs/TOP100.md B1): day, month and year as three wheels (scroll, or tap one
 * to type digits), no free-text date format to learn. Nothing is preselected that would hint at an age: the wheels
 * start on 1 January of the current year. An answer under 13 is remembered ([AgeGate.saveUnderAge]), so a relaunch
 * shows the minimum-age notice again instead of a fresh date screen.
 */
class AgeGateDialog(
    private val activity: Activity,
    private val today: () -> LocalDate = LocalDate::now,
    private val onAnswer: (AgeGate.Band) -> Unit,
) {
    /** The dialog on screen, for tests. */
    internal var dialog: AlertDialog? = null
        private set
    internal lateinit var day: NumberPicker
        private set
    internal lateinit var month: NumberPicker
        private set
    internal lateinit var year: NumberPicker
        private set
    internal lateinit var error: TextView
        private set

    fun show() {
        if (AgeGate.isUnderAge(activity)) {
            showMinimum()
            return
        }
        val now = today()
        val locale = activity.resources.configuration.locales[0]
        day = NumberPicker(activity).apply { minValue = 1; maxValue = 31; value = 1 }
        month = NumberPicker(activity).apply {
            minValue = 1; maxValue = 12; value = 1
            displayedValues = Array(12) { java.time.Month.of(it + 1).getDisplayName(TextStyle.SHORT_STANDALONE, locale) }
        }
        year = NumberPicker(activity).apply { minValue = now.year - 120; maxValue = now.year; value = now.year; wrapSelectorWheel = false }
        month.setOnValueChangedListener { _, _, _ -> fitDays() }
        year.setOnValueChangedListener { _, _, _ -> fitDays() }
        day.contentDescription = activity.getString(R.string.age_gate_day)
        month.contentDescription = activity.getString(R.string.age_gate_month)
        year.contentDescription = activity.getString(R.string.age_gate_year)
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        error = TextView(activity).apply {
            setTextColor(0xFFB3261E.toInt())
            gravity = Gravity.CENTER
        }
        val wheels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            listOf(day, month, year).forEach {
                addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(pad / 4, 0, pad / 4, 0) })
            }
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(wheels)
            addView(error)
        }
        val shown = AlertDialog.Builder(activity)
            .setTitle(R.string.age_gate_title)
            .setMessage(R.string.age_gate_message)
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.age_gate_exit) { _, _ -> activity.finish() }
            .setCancelable(false)
            .create()
        dialog = shown
        shown.show()
        shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirm() }
    }

    /** The day wheel only offers the days of the picked month (29 February only in leap years). */
    internal fun fitDays() {
        day.maxValue = YearMonth.of(year.value, month.value).lengthOfMonth()
    }

    /** OK: a real, past date decides the band; under 13 is remembered and ends here. */
    internal fun confirm() {
        val now = today()
        val birthDate = AgeGate.of(year.value, month.value, day.value, now)
        if (birthDate == null) {
            error.text = activity.getString(R.string.age_gate_invalid)
            return
        }
        dialog?.dismiss()
        val band = AgeGate.band(birthDate, now)
        if (band == AgeGate.Band.UNDER_13) {
            AgeGate.saveUnderAge(activity)
            showMinimum()
            return
        }
        AgeGate.save(activity, birthDate)
        onAnswer(band)
    }

    private fun showMinimum() {
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.age_gate_title)
            .setMessage(R.string.age_gate_minimum)
            .setPositiveButton(R.string.age_gate_exit) { _, _ -> activity.finish() }
            .setCancelable(false)
            .show()
    }
}
