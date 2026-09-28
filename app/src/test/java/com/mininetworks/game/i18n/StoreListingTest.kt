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
     * One promise everywhere (docs/TOP100.md B4, judge panel): in every language the main menu's tagline, the feature
     * graphic's tagline and the start of the short store description are the same words, and the first screenshot's
     * caption is that tagline up to its dash.
     */
    @Test
    fun theTaglineIsTheSameInMenuFeatureGraphicStoreTextAndFirstCaption() {
        val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
        for ((locale, qualifier) in listings) {
            val tagline = StoreScreenshotTest.TAGLINES.getValue(qualifier)
            val xml = File(res, if (qualifier == "de") "values/strings.xml" else "values-$qualifier/strings.xml").readText()
            val menu = Regex("""<string name="menu_tagline">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            assertEquals("$locale: menu tagline", tagline, menu)
            val (short, _) = section(File(store, "$locale.md").readText(), "Kurzbeschreibung")
            assertTrue("$locale: the short description starts with the tagline: $short", short.startsWith(tagline))
            val caption = StoreScreenshotTest.LANGUAGES.getValue(qualifier).first()
            assertTrue("$locale: caption 1 \"$caption\" opens the tagline \"$tagline\"", tagline.startsWith(caption))
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

    /**
     * docs/privacy/index.html is exactly what tools/build_privacy_page.py makes from docs/privacy-policy-web.md, so a
     * rebuild can never drop a disclosure that only lived in the HTML. The body is rendered here the same way as the
     * script does (blocks split at blank lines, "# "/"## " headings with an optional {#anchor}, [text](url) links, HTML
     * escaping of & < > " '), and compared with the checked-in page.
     */
    @Test
    fun thePublishedPrivacyPageIsBuiltFromItsSource() {
        val docs = store.parentFile
        val page = File(docs, "privacy/index.html").readText()
        val source = File(docs, "privacy-policy-web.md").readText()
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#x27;")
        val link = Regex("\\[([^]]+)\\]\\(([^)]+)\\)")
        fun inline(t: String): String {
            val out = StringBuilder()
            var at = 0
            for (m in link.findAll(t)) {
                out.append(esc(t.substring(at, m.range.first)))
                out.append("<a href=\"${esc(m.groupValues[2])}\">${esc(m.groupValues[1])}</a>")
                at = m.range.last + 1
            }
            return out.append(esc(t.substring(at))).toString()
        }
        var firstTitle = true
        val blocks = source.trim().split("\n\n").map { block ->
            when {
                block.trim() == "---" -> "</section>\n<section lang=\"en\" id=\"en\">"
                block.startsWith("# ") -> "<h1${if (firstTitle) " id=\"top\"" else ""}>${inline(block.substring(2))}</h1>".also { firstTitle = false }
                block.startsWith("## ") -> Regex("(.*?)\\s*\\{#([A-Za-z0-9-]+)\\}").matchEntire(block.substring(3))
                    ?.let { "<h2 id=\"${it.groupValues[2]}\">${inline(it.groupValues[1])}</h2>" }
                    ?: "<h2>${inline(block.substring(3))}</h2>"
                else -> "<p>${inline(block)}</p>"
            }
        }
        val body = page.substringAfter("<section lang=\"de\">\n").substringBefore("\n</section>\n</main>")
        assertEquals("docs/privacy/index.html is out of date: run python3 tools/build_privacy_page.py", blocks.joinToString("\n"), body)
        // The disclosures the Play Console relies on: the age screen, the deletion anchor, Play Games only for adults.
        for (needed in listOf("Altersabfrage", "age screen", "id=\"deletion\"", "id=\"deletion-en\"", "nur für volljährige", "only for adult")) {
            assertTrue("privacy page covers $needed", page.contains(needed))
        }
        // The long form says the same about the date of birth and deletion.
        val policy = File(docs, "privacy-policy.md").readText()
        for (needed in listOf("Geburtsdatum", "date of birth", "Datenlöschung", "data deletion", "nur für volljährige", "only for adult")) {
            assertTrue("privacy-policy.md covers $needed", policy.contains(needed))
        }
    }
}
