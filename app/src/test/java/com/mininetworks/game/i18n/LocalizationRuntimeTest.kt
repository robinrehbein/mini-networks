package com.mininetworks.game.i18n

import android.content.Context
import android.content.res.Configuration
import android.icu.text.PluralRules
import android.icu.util.ULocale
import com.mininetworks.game.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * docs/TOP100.md F1 on the Android runtime: every one of the 12 languages is really picked for its locale, every string
 * and every plural formats without an exception for typical counts, and the plural table of [LocalizationTest]
 * matches the plural rules of Android's ICU.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalizationRuntimeTest {

    /** Device locale for each resource folder. */
    private val deviceLocales = mapOf(
        "values" to Locale.GERMANY,
        "values-en" to Locale.US,
        "values-fr" to Locale.FRANCE,
        "values-es" to Locale.forLanguageTag("es-ES"),
        "values-it" to Locale.ITALY,
        "values-pt-rBR" to Locale.forLanguageTag("pt-BR"),
        "values-pl" to Locale.forLanguageTag("pl-PL"),
        "values-nl" to Locale.forLanguageTag("nl-NL"),
        "values-tr" to Locale.forLanguageTag("tr-TR"),
        "values-ja" to Locale.JAPAN,
        "values-ko" to Locale.KOREA,
        "values-zh-rCN" to Locale.SIMPLIFIED_CHINESE,
    )

    private fun context(locale: Locale): Context {
        val config = Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        config.setLocale(locale)
        return RuntimeEnvironment.getApplication().createConfigurationContext(config)
    }

    @Test
    fun thePluralTableMatchesAndroidsIcu() {
        for ((dir, lang) in LocalizationTest.LOCALES) {
            val icu = PluralRules.forLocale(ULocale.forLocale(deviceLocales.getValue(dir))).keywords
            assertEquals("$dir ($lang)", icu, LocalizationTest.PLURAL_CATEGORIES.getValue(lang))
        }
    }

    @Test
    fun everyLanguageIsPickedAndEveryTextFormats() {
        val german = context(Locale.GERMANY).getString(R.string.menu_play)
        val stringIds = R.string::class.java.fields.map { it.name to it.getInt(null) }
        val pluralIds = R.plurals::class.java.fields.map { it.name to it.getInt(null) }
        val own = LocalizationTest.LOCALES.keys
        for ((dir, locale) in deviceLocales) {
            check(dir in own)
            val ctx = context(locale)
            val play = ctx.getString(R.string.menu_play)
            if (dir == "values") assertEquals(german, play) else assertNotEquals("$dir is used for $locale", german, play)
            var formatted = 0
            for ((name, id) in stringIds) {
                val raw = runCatching { ctx.getString(id) }.getOrNull() ?: continue
                // Arguments by conversion: numbers for %d, text for %s.
                val args = argTypes(raw)
                if (args.isEmpty()) continue
                val values = (1..args.keys.max()).map { if (args[it] == "s") "Router" else 1234 }.toTypedArray<Any>()
                val text = ctx.getString(id, *values)
                assertTrue("$dir: $name left a format argument: $text", !text.contains(Regex("%\\d*\\$")))
                formatted++
            }
            for ((name, id) in pluralIds) {
                for (n in listOf(0, 1, 2, 3, 5, 12, 22, 1_000_000)) {
                    val raw = ctx.resources.getQuantityText(id, n).toString()
                    val args = argTypes(raw)
                    // The count is the first number; share_text also takes the scenery and the count as text.
                    val values = if (args.isEmpty()) emptyArray() else (1..args.keys.max()).map { i ->
                        when {
                            args[i] == "s" -> "Router"
                            i == 1 -> n
                            else -> 2020
                        }
                    }.toTypedArray<Any>()
                    val text = ctx.resources.getQuantityString(id, n, *values)
                    assertTrue("$dir: $name for $n: $text", text.isNotBlank() && !text.contains(Regex("%\\d*\\$")))
                }
                formatted++
            }
            println("LocalizationRuntimeTest: $dir ($locale) → \"$play\", $formatted texts formatted")
        }
    }

    /** Argument index → conversion ("d" or "s") of the format arguments in [raw]; "%d" is argument 1. */
    private fun argTypes(raw: String): Map<Int, String> = Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*([ds])").findAll(raw)
        .associate { (it.groupValues[1].ifEmpty { "1" }).toInt() to it.groupValues[2] }
}
