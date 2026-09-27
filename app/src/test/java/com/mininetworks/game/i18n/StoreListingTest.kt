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

    /**
     * docs/TOP100.md F5: the privacy page at the URL for the Play Console and the UMP message covers every SDK of the
     * release build (ads with UMP, billing, Play Games with its cloud save, in-app review) in German and in English,
     * names the controller and holds no placeholder; so does the long form in docs/privacy-policy.md.
     */
    @Test
    fun thePublishedPrivacyPageCoversEverySdkInGermanAndEnglish() {
        val docs = store.parentFile
        val page = File(docs, "privacy/index.html").readText()
        val source = File(docs, "privacy-policy-web.md").readText()
        val german = page.substringAfter("<section lang=\"de\">").substringBefore("</section>")
        val english = page.substringAfter("<section lang=\"en\" id=\"en\">").substringBefore("</section>")
        assertTrue("an English version", english.isNotBlank() && english != page)
        for ((lang, text) in listOf("de" to german, "en" to english)) {
            for (sdk in listOf("AdMob", "User Messaging Platform", "Google Play Billing", "Google Play", "Play Games", "Spiele-Dienste|Games services", "In-App Review", "Cloud|cloud")) {
                if (sdk == "Play Games" && lang == "de") continue
                assertTrue("$lang: privacy page mentions $sdk", Regex(sdk).containsMatchIn(text))
            }
            assertTrue("$lang: names the controller", text.contains("Robin Rehbein") && text.contains("hello@robinrehbein.de"))
        }
        // The page is built from its Markdown source; the long form carries no draft placeholders.
        for (sdk in listOf("Spiele-Dienste", "In-App Review")) assertTrue("source mentions $sdk", source.contains(sdk))
        val policy = File(docs, "privacy-policy.md").readText()
        assertTrue("placeholders left in privacy-policy.md", !Regex("`<[^>]*>`").containsMatchIn(policy))
        val gradle = File(docs.parentFile, "app/build.gradle.kts").readText()
        for ((dependency, named) in listOf("play-services-ads" to "AdMob", "user-messaging-platform" to "User Messaging Platform", "billing" to "Billing", "play-services-games" to "Spiele-Dienste", "com.google.android.play:review" to "In-App Review")) {
            if (gradle.contains(dependency)) assertTrue("$dependency ships, so the page names $named", german.contains(named))
        }
    }
}
