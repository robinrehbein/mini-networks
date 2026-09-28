package com.mininetworks.game.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * docs/TOP100.md F1: the game is translated into 12 languages and every language has every string key.
 * Plain JVM test: parses every res/values*&#47;strings.xml of the app and checks each translation against the default
 * (German) file: same keys (strings and plurals), every plural quantity the language needs (CLDR plural rules, which
 * [LocalizationRuntimeTest] checks against Android's ICU), the same format arguments (so no translation can crash
 * `getString` or show the wrong number), nothing left empty, no key that is not translatable.
 */
class LocalizationTest {

    /** A string resource file, parsed. */
    private class Strings(val dir: String, val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>, val untranslatable: Set<String>)

    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun parse(dir: String): Strings {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val root = doc.documentElement
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val untranslatable = HashSet<String>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val e = children.item(i) as? Element ?: continue
            val name = e.getAttribute("name")
            if (e.getAttribute("translatable") == "false") untranslatable += name
            when (e.tagName) {
                "string" -> {
                    assertTrue("$dir: $name twice", strings.put(name, e.textContent) == null)
                }
                "plurals" -> {
                    val items = LinkedHashMap<String, String>()
                    val list = e.getElementsByTagName("item")
                    for (k in 0 until list.length) {
                        val item = list.item(k) as Element
                        assertTrue("$dir: $name/${item.getAttribute("quantity")} twice", items.put(item.getAttribute("quantity"), item.textContent) == null)
                    }
                    assertTrue("$dir: $name twice", plurals.put(name, items) == null)
                }
                else -> error("$dir: unexpected <${e.tagName}> $name")
            }
        }
        return Strings(dir, strings, plurals, untranslatable)
    }

    @Test
    fun exactlyTheTwelveLanguagesExist() {
        val dirs = res.listFiles()!!.filter { it.isDirectory && File(it, "strings.xml").isFile }.map { it.name }.toSet()
        assertEquals(LOCALES.keys, dirs)
    }

    @Test
    fun everyLanguageHasEveryKeyAndPluralQuantity() {
        val base = parse("values")
        val translatable = base.strings.keys - base.untranslatable
        var checked = 0
        for ((dir, lang) in LOCALES) {
            if (dir == "values") continue
            val t = parse(dir)
            assertEquals("$dir: string keys", translatable, t.strings.keys)
            assertEquals("$dir: plurals keys", base.plurals.keys, t.plurals.keys)
            assertTrue("$dir translates untranslatable keys: ${t.untranslatable}", t.untranslatable.isEmpty())
            val need = PLURAL_CATEGORIES.getValue(lang)
            for ((name, items) in t.plurals) {
                assertEquals("$dir: $name has the quantities $lang needs", need, items.keys)
                checked += items.size
            }
            checked += t.strings.size
        }
        // The default language itself needs German's quantities.
        for ((name, items) in base.plurals) assertEquals("values: $name", PLURAL_CATEGORIES.getValue("de"), items.keys)
        println("LocalizationTest: ${LOCALES.size} languages, ${translatable.size} strings + ${base.plurals.size} plurals each, $checked translated texts checked")
    }

    @Test
    fun formatArgumentsMatchTheDefault() {
        val base = parse("values")
        for (dir in LOCALES.keys - "values") {
            val t = parse(dir)
            for ((name, text) in t.strings) {
                assertEquals("$dir: $name has the arguments of the default: \"$text\"", args(base.strings.getValue(name)), args(text))
            }
            for ((name, items) in t.plurals) {
                val default = base.plurals.getValue(name)
                val all = default.values.flatMap(::args).toSet()
                assertEquals("$dir: $name/other has the arguments of the default", args(default.getValue("other")), args(items.getValue("other")))
                for ((q, text) in items) {
                    assertTrue("$dir: $name/$q uses an argument the default does not have: \"$text\"", all.containsAll(args(text)))
                }
            }
        }
    }

    @Test
    fun noTextIsEmptyOrBrokenByAapt() {
        for (dir in LOCALES.keys) {
            val t = parse(dir)
            val texts = t.strings.map { (k, v) -> k to v } + t.plurals.flatMap { (k, items) -> items.map { (q, v) -> "$k/$q" to v } }
            for ((key, v) in texts) {
                assertTrue("$dir: $key is empty", v.isNotBlank())
                // aapt drops unescaped double quotes and fails on unescaped apostrophes; only a fully quoted value is fine.
                val quoted = v.length >= 2 && v.startsWith('"') && v.endsWith('"')
                assertTrue("$dir: $key has an unescaped \": $v", quoted || !Regex("(?<!\\\\)\"").containsMatchIn(v))
                assertTrue("$dir: $key has an unescaped ': $v", quoted || !Regex("(?<!\\\\)'").containsMatchIn(v))
                assertTrue("$dir: $key has a stray % that is not a format argument: $v", !Regex("%(?![0-9]+\\$|[,d s])").containsMatchIn(v))
            }
        }
    }

    /**
     * UI text lives only in strings.xml: no dialog title, message, button, hint, toast or menu entry in the app's code
     * is a string literal (lint's HardcodedText only looks at layouts, and the game has none).
     */
    @Test
    fun noUiTextIsHardCodedInTheCode() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val literalUi = Regex(
            "(?:setTitle|setMessage|setPositiveButton|setNegativeButton|setNeutralButton|makeText\\([^,]+,|hint\\s*=|text\\s*=|" +
                "MenuItem\\.(?:Button|Toggle)\\([^,]+,)\\s*\\(?\\s*(?:if \\([^)]*\\)\\s*)?\"[^\"]*[A-Za-z][^\"]*\""
        )
        val found = src.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (literalUi.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null }
        }.toList()
        assertTrue("hard-coded UI text:\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** The format arguments of [text] as "index:conversion", sorted; "%d" counts as argument 1. */
    private fun args(text: String): List<String> =
        Regex("%(?:(\\d+)\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])").findAll(text)
            .map { "${it.groupValues[1].ifEmpty { "1" }}:${it.groupValues[2]}" }.sorted().toList()

    companion object {
        /** Resource folder → language (docs/TOP100.md F1); "values" is German, the default. */
        val LOCALES = mapOf(
            "values" to "de",
            "values-en" to "en",
            "values-fr" to "fr",
            "values-es" to "es",
            "values-it" to "it",
            "values-pt-rBR" to "pt",
            "values-pl" to "pl",
            "values-nl" to "nl",
            "values-tr" to "tr",
            "values-ja" to "ja",
            "values-ko" to "ko",
            "values-zh-rCN" to "zh",
        )

        /**
         * Plural categories per language after CLDR (as in Android's ICU since API 34): French, Spanish, Italian and
         * Portuguese have "many" for round millions ("1 000 000 de paquets"), Polish one/few/many (+ other for
         * fractions), Japanese, Korean and Chinese do not inflect. Checked against the platform in [LocalizationRuntimeTest].
         */
        val PLURAL_CATEGORIES = mapOf(
            "de" to setOf("one", "other"),
            "en" to setOf("one", "other"),
            "fr" to setOf("one", "many", "other"),
            "es" to setOf("one", "many", "other"),
            "it" to setOf("one", "many", "other"),
            "pt" to setOf("one", "many", "other"),
            "pl" to setOf("one", "few", "many", "other"),
            "nl" to setOf("one", "other"),
            "tr" to setOf("one", "other"),
            "ja" to setOf("other"),
            "ko" to setOf("other"),
            "zh" to setOf("other"),
        )
    }
}
