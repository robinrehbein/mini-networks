package com.mininetworks.game.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Line breaking for all twelve languages (docs/TOP100.md F1): spaces everywhere, and between CJK characters in Japanese
 * and Chinese, which write without spaces, following the basic kinsoku rules. Plain JVM, widths from a fake font:
 * one unit per Latin/Hangul character, two per CJK character.
 */
class TextWrapTest {

    private val measure: (String) -> Float = { s -> s.sumOf { if (TextWrap.isCjk(it)) 2.0 else 1.0 }.toFloat() }

    @Test
    fun latinTextBreaksAtSpacesOnly() {
        assertEquals(listOf("Zieh vom PC", "zum", "Mail-Server."), TextWrap.wrap("Zieh vom PC zum Mail-Server.", 12f, measure = measure))
        // A word longer than the line keeps its own line instead of being cut.
        assertEquals(listOf("a", "Überwachungskamera", "b"), TextWrap.wrap("a Überwachungskamera b", 5f, measure = measure))
        // Several spaces in a row survive on one line.
        assertEquals(listOf("Budget 3  ·  Router 2"), TextWrap.wrap("Budget 3  ·  Router 2", 40f, measure = measure))
    }

    @Test
    fun japaneseAndChineseBreakBetweenCharacters() {
        val ja = "PCがメールを送りたがっています。"
        val lines = TextWrap.wrap(ja, 10f, measure = measure)
        assertTrue("every line fits: $lines", lines.all { measure(it) <= 10f })
        assertEquals("nothing is lost or added", ja, lines.joinToString(""))
        assertTrue("more than one line: $lines", lines.size > 1)
        val zh = "电脑想发送一封邮件。用手指从电脑拖到邮件服务器。"
        val zhLines = TextWrap.wrap(zh, 16f, measure = measure)
        assertTrue(zhLines.all { measure(it) <= 16f })
        assertEquals(zh, zhLines.joinToString(""))
    }

    @Test
    fun kinsokuKeepsPunctuationAndSmallKanaOffTheLineStart() {
        for (width in 4..30) {
            for (s in listOf("ストリーミングには帯域が必要です。「ルーター」をタップ！", "网络过载，游戏结束。「路由器」")) {
                val lines = TextWrap.wrap(s, width.toFloat(), measure = measure)
                assertEquals(s, lines.joinToString(""))
                for (l in lines.drop(1)) assertFalse("width $width: line starts with ${l.first()}: $lines", l.first() in "、。，！ーッャュョ」")
                for (l in lines.dropLast(1)) assertFalse("width $width: line ends with ${l.last()}: $lines", l.last() in "「")
            }
        }
    }

    @Test
    fun latinRunsInsideCjkTextStayWhole() {
        val pieces = TextWrap.pieces("帯域幅 3 が必要、ISDNは2だけ")
        assertEquals(listOf("帯", "域", "幅", "3", "が", "必", "要、ISDNは2だ", "け"), pieces.map { it.text })
        assertEquals(listOf("", "", "", " ", " ", "", "", ""), pieces.map { it.sep })
    }

    @Test
    fun koreanBreaksAtSpacesAndKeepsWordsWhole() {
        val ko = "PC가 메일을 보내려고 합니다. 손가락으로 PC에서 메일 서버까지 드래그하세요."
        val lines = TextWrap.wrap(ko, 14f, measure = measure)
        assertEquals(ko, lines.joinToString(" "))
        for (l in lines) for (w in l.split(' ')) assertTrue("$w is a whole word of the text", ko.split(' ').contains(w))
    }

    @Test
    fun theLastAllowedLineTakesTheRestAndEndsWithAnEllipsis() {
        val lines = TextWrap.wrap("eins zwei drei vier fünf sechs", 10f, maxLines = 2, measure = measure)
        assertEquals(2, lines.size)
        assertEquals("eins zwei", lines[0])
        assertTrue(lines[1].endsWith(TextWrap.ELLIPSIS))
        assertTrue(measure(lines[1]) <= 10f)
        val ja = TextWrap.wrap("ネットワークが過負荷になりました。ゲームオーバーです。", 10f, maxLines = 2, measure = measure)
        assertEquals(2, ja.size)
        assertTrue(ja[1].endsWith(TextWrap.ELLIPSIS) && measure(ja[1]) <= 10f)
        // Text that fits is never shortened.
        assertEquals(listOf("kurz"), TextWrap.wrap("kurz", 10f, maxLines = 1, measure = measure))
    }
}
