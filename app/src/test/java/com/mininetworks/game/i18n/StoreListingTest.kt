package com.mininetworks.game.i18n

import com.mininetworks.game.render.StoreScreenshotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * docs/TOP100.md F2: a store listing for each of the 12 languages in docs/store/<locale>.md, within Play's limits
 * (title 30, short description 80, full description 4000 characters), with the stated character counts correct and
 * the screenshot captions identical to the ones StoreScreenshotTest renders. Plain JVM.
 */
class StoreListingTest {

    private val store = listOf(File("../docs/store"), File("docs/store")).first { it.isDirectory }

    /** Listing file → resource qualifier of the screenshot captions. */
    private val listings = mapOf(
        "de-DE" to "de", "en-US" to "en", "fr-FR" to "fr", "es-ES" to "es", "it-IT" to "it", "pt-BR" to "pt-rBR",
        "pl-PL" to "pl", "nl-NL" to "nl", "tr-TR" to "tr", "ja-JP" to "ja", "ko-KR" to "ko", "zh-CN" to "zh-rCN",
    )

    /** The text under the heading that starts with [heading], and the count stated in it ("(26/30 Zeichen)"). */
    private fun section(md: String, heading: String): Pair<String, Int> {
        val m = Regex("""^## $heading \((\d+)/\d+ Zeichen\)\n\n(.*?)(?=\n## |\z)""", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(md) ?: throw AssertionError("no section $heading")
        var text = m.groupValues[2].trim()
        if (text.startsWith("```")) text = text.removePrefix("```text\n").substringBeforeLast("\n```")
        return text to m.groupValues[1].toInt()
    }

    @Test
    fun everyLanguageHasAListingWithinPlaysLimits() {
        assertEquals(LocalizationTest.LOCALES.size, listings.size)
        for ((locale, qualifier) in listings) {
            val md = File(store, "$locale.md").readText()
            for ((heading, limit) in listOf("Titel" to 30, "Kurzbeschreibung" to 80, "Ausführliche Beschreibung" to 4000)) {
                val (text, stated) = section(md, heading)
                val count = text.codePointCount(0, text.length)
                assertEquals("$locale: stated length of $heading", count, stated)
                assertTrue("$locale: $heading has $count characters, Play allows $limit", count in 1..limit)
                if (heading == "Titel") assertTrue("$locale: the title starts with the app name", text.startsWith("Mini Networks"))
            }
            val captions = Regex("""^\d\. (.+)$""", RegexOption.MULTILINE).findAll(md.substringAfter("## Screenshot-Beschriftungen"))
                .map { it.groupValues[1] }.toList()
            assertEquals("$locale: captions of the screenshots", StoreScreenshotTest.LANGUAGES.getValue(qualifier), captions)
        }
    }
}
