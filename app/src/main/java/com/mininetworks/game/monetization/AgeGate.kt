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

    private const val PREFS = "age_gate"
    private const val BIRTH_DATE = "birth_date"
}
