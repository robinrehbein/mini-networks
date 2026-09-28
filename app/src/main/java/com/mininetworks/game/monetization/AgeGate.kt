package com.mininetworks.game.monetization

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** A neutral date-of-birth screen runs before advertising or consent SDKs start. */
object AgeGate {
    enum class Band { UNDER_13, TEEN, ADULT }

    fun band(birthDate: LocalDate, today: LocalDate): Band = when {
        birthDate.plusYears(13).isAfter(today) -> Band.UNDER_13
        birthDate.plusYears(18).isAfter(today) -> Band.TEEN
        else -> Band.ADULT
    }

    fun parse(input: String, today: LocalDate): LocalDate? = try {
        LocalDate.parse(input.trim()).takeIf { !it.isAfter(today) && !it.isBefore(today.minusYears(120)) }
    } catch (_: DateTimeParseException) {
        null
    }

    fun savedBirthDate(context: Context): LocalDate? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BIRTH_DATE, null)
            ?.let { parse(it, LocalDate.now()) }

    fun save(context: Context, birthDate: LocalDate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(BIRTH_DATE, birthDate.toString()).apply()
    }

    /**
     * An under-13 answer is remembered (only as this flag, the date itself is not kept), so relaunching the app does not
     * offer the date screen again. Clearing the app's data resets it, like any other local answer.
     */
    fun saveUnderAge(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(UNDER_13, true).apply()
    }

    fun isUnderAge(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(UNDER_13, false)

    /** The picked day, month (1-12) and year, or null when that day does not exist or lies in the future. */
    fun of(year: Int, month: Int, day: Int, today: LocalDate): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
            ?.takeIf { !it.isAfter(today) && !it.isBefore(today.minusYears(120)) }

    private const val PREFS = "age_gate"
    private const val BIRTH_DATE = "birth_date"
    private const val UNDER_13 = "under_13"
}
