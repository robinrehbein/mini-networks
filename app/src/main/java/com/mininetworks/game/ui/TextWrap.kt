package com.mininetworks.game.ui

/**
 * Line breaking for the canvas-drawn texts in all twelve languages (docs/TOP100.md F1).
 *
 * Latin, Cyrillic, Turkish and Korean texts break at spaces (Korean UI text keeps its words whole). Japanese and Chinese
 * write without spaces, so a line may also break between two CJK characters, following the basic Japanese/Chinese
 * rules (kinsoku): never before closing punctuation, small kana or the long-vowel mark, never after opening
 * brackets. Latin runs inside CJK text ("ISDN", "5 GHz", "12個") stay whole. Pure Kotlin, measured through [measure]
 * so it works with any [android.graphics.Paint] and is tested on the JVM (TextWrapTest).
 */
object TextWrap {

    /** A piece of text between two break opportunities and the exact whitespace in front of it ("" inside CJK text). */
    data class Piece(val text: String, val sep: String)

    /** [s] cut at every break opportunity. */
    fun pieces(s: String): List<Piece> {
        val out = ArrayList<Piece>()
        val piece = StringBuilder()
        var sep = ""
        var spaces = StringBuilder()
        var prev: Char? = null
        for (c in s) {
            if (c == ' ') {
                if (piece.isNotEmpty()) {
                    out += Piece(piece.toString(), sep)
                    piece.setLength(0)
                    sep = ""
                }
                spaces.append(c)
                prev = c
                continue
            }
            if (spaces.isNotEmpty()) {
                sep += spaces.toString()
                spaces = StringBuilder()
            } else if (piece.isNotEmpty() && prev != null && breaksBetween(prev, c)) {
                out += Piece(piece.toString(), sep)
                piece.setLength(0)
                sep = ""
            }
            piece.append(c)
            prev = c
        }
        if (piece.isNotEmpty()) out += Piece(piece.toString(), sep)
        return out
    }

    /**
     * [s] broken into lines no wider than [maxWidth] as measured by [measure]. With [maxLines], the text that does not
     * fit is put on the last line, which is then shortened with "…" if needed (see [fit]); without it, every line is
     * kept (a single piece wider than [maxWidth] gets a line of its own).
     */
    fun wrap(s: String, maxWidth: Float, maxLines: Int = Int.MAX_VALUE, measure: (String) -> Float): List<String> {
        val pieces = pieces(s)
        val lines = ArrayList<String>()
        var line = ""
        for ((i, p) in pieces.withIndex()) {
            val candidate = if (line.isEmpty()) p.text else line + p.sep + p.text
            if (line.isEmpty() || measure(candidate) <= maxWidth) {
                line = candidate
                continue
            }
            if (lines.size == maxLines - 1) {
                val rest = StringBuilder(line)
                for (q in pieces.subList(i, pieces.size)) rest.append(q.sep).append(q.text)
                lines += fit(rest.toString(), maxWidth, measure)
                return lines
            }
            lines += line
            line = p.text
        }
        if (line.isNotEmpty()) lines += if (maxLines == Int.MAX_VALUE) line else fit(line, maxWidth, measure)
        return lines
    }

    /** [s], shortened with an ellipsis if it is wider than [maxWidth]. */
    fun fit(s: String, maxWidth: Float, measure: (String) -> Float): String {
        if (measure(s) <= maxWidth) return s
        val ellipsis = measure(ELLIPSIS)
        var end = s.length
        while (end > 1 && measure(s.substring(0, end)) + ellipsis > maxWidth) end--
        return s.substring(0, end).trimEnd() + ELLIPSIS
    }

    /** True if a line may break between [a] and [b] although no space separates them. */
    fun breaksBetween(a: Char, b: Char): Boolean =
        isCjk(a) && isCjk(b) && b !in NO_LINE_START && a !in NO_LINE_END

    /** Japanese kana, CJK ideographs, CJK punctuation and full-width forms (not Hangul: Korean breaks at spaces). */
    fun isCjk(c: Char): Boolean = c in '　'..'ヿ' || c in 'ㇰ'..'ㇿ' || c in '㐀'..'䶿' ||
        c in '一'..'鿿' || c in '豈'..'﫿' || c in '＀'..'￯'

    const val ELLIPSIS = "…"

    /** Characters a line must not start with (closing punctuation, small kana, iteration and long-vowel marks). */
    private const val NO_LINE_START = "、。，．・：；？！ー～〜）」』】〕〉》〙〗’”々ゝゞヽヾぁぃぅぇぉっゃゅょゎゕゖァィゥェォッャュョヮヵヶㇰㇱㇲㇳㇴㇵㇶㇷㇸㇹㇺㇻㇼㇽㇾㇿ％）］｝｡､･ｧｨｩｪｫｯｬｭｮｰ"

    /** Characters a line must not end with (opening brackets). */
    private const val NO_LINE_END = "（「『【〔〈《〘〖‘“［｛｢"
}
