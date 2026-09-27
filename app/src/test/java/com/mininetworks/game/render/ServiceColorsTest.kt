package com.mininetworks.game.render

import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.CableType
import com.mininetworks.game.game.Service
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The colorblind palette keeps every pair of services apart under simulated protanopia and deuteranopia
 * (Machado et al. 2009, severity 1), measured as CIE76 ΔE in Lab. The default palette does not, which is why it exists.
 * Dichromats see a two-dimensional color space (lightness and blue-yellow), so seven services cannot all keep the
 * ΔE 30 that four could; [MIN_DELTA_E] is 25, and the shapes stay the primary signal. Every service in both palettes
 * also keeps [MIN_DARK_DELTA_E] from the dark cables and the icon ink.
 */
class ServiceColorsTest {

    @After
    fun reset() {
        ServiceColors.colorblind = false
    }

    @Test
    fun switchSelectsPalette() {
        for (s in Service.entries) {
            assertEquals(ServiceColors.defaultOf(s), ServiceColors.of(s))
            ServiceColors.colorblind = true
            assertEquals(ServiceColors.colorblindOf(s), ServiceColors.of(s))
            ServiceColors.colorblind = false
        }
    }

    @Test
    fun colorblindPaletteStaysDistinct() {
        for ((name, m) in listOf("normal" to IDENTITY, "protanopia" to PROTAN, "deuteranopia" to DEUTAN)) {
            val d = minDistance(ServiceColors::colorblindOf, m)
            assertTrue("$name: closest pair ΔE $d", d >= MIN_DELTA_E)
        }
    }

    /**
     * Blue-yellow blindness (tritanopia, docs/TOP100.md A7) is rarer; the colorblind palette still keeps every pair of
     * services apart by [MIN_TRITAN_DELTA_E], and the shapes tell them apart as well.
     */
    @Test
    fun colorblindPaletteStaysDistinctForTritanopia() {
        val d = minDistance(ServiceColors::colorblindOf, TRITAN)
        assertTrue("tritanopia: closest pair ΔE $d", d >= MIN_TRITAN_DELTA_E)
    }

    /** Every service has its own packet shape, so no information rests on color alone. */
    @Test
    fun everyServiceHasItsOwnShape() {
        val shapes = Service.entries.map { it.shape }
        assertEquals(shapes.size, shapes.toSet().size)
    }

    /** Menu and HUD text colors reach WCAG AA contrast (4.5:1) on the card and button faces, switches 3:1. */
    @Test
    fun uiTextHasEnoughContrast() {
        val faces = listOf(0xFFFAFAF7.toInt(), 0xFFFFFFFF.toInt(), 0xFFF1F3EE.toInt())
        for (face in faces) {
            for ((name, ink) in listOf("ink" to INK, "muted" to MUTED)) {
                val c = contrast(ink, face)
                assertTrue("$name on ${Integer.toHexString(face)}: $c", c >= 4.5)
            }
        }
        assertTrue("white on the dark active button", contrast(0xFFFFFFFF.toInt(), INK) >= 4.5)
        assertTrue("switch track (off) against the row", contrast(SWITCH_OFF, 0xFFF1F3EE.toInt()) >= 3.0)
    }

    private fun luminance(color: Int): Double {
        val r = toLinear((color shr 16) and 0xFF)
        val g = toLinear((color shr 8) and 0xFF)
        val b = toLinear(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun defaultPaletteBlursForRedGreenBlindness() {
        assertTrue(minDistance(ServiceColors::defaultOf, PROTAN) < MIN_DELTA_E)
        assertTrue(minDistance(ServiceColors::defaultOf, DEUTAN) < MIN_DELTA_E)
    }

    @Test
    fun packetsStandApartFromDarkCablesAndInk() {
        val dark = listOf(CableStyles.of(CableType.DSL).color, CableStyles.of(CableType.COAX).color, INK)
        for ((palette, mats) in listOf(ServiceColors::defaultOf to listOf(IDENTITY), ServiceColors::colorblindOf to listOf(IDENTITY, PROTAN, DEUTAN))) {
            for (s in Service.entries) for (m in mats) for (d in dark) {
                val e = distance(lab(simulate(palette(s), m)), lab(simulate(d, m)))
                assertTrue("$s vs ${Integer.toHexString(d)}: ΔE $e", e >= MIN_DARK_DELTA_E)
            }
        }
    }

    /**
     * docs/TOP100.md C5: every cable skin keeps the four technologies apart (and the widths stay the same, so a skin
     * never makes one easier to read than in another), and packets stay visible on its darker cables like on the
     * classic ones.
     */
    @Test
    fun everyCableSkinKeepsTechnologiesApartAndPacketsVisible() {
        for (skin in CableSkin.entries) {
            val labs = CableType.entries.map { lab(simulate(CableStyles.of(skin, it).color, IDENTITY)) }
            for (i in labs.indices) for (j in i + 1 until labs.size) {
                val e = distance(labs[i], labs[j])
                assertTrue("$skin: ${CableType.entries[i]} vs ${CableType.entries[j]}: ΔE $e", e >= MIN_CABLE_DELTA_E)
            }
            for (t in CableType.entries) {
                assertEquals("$skin keeps the width of $t", CableStyles.of(CableSkin.CLASSIC, t).width, CableStyles.of(skin, t).width)
            }
            val dark = listOf(CableStyles.of(skin, CableType.DSL).color, CableStyles.of(skin, CableType.COAX).color)
            for ((palette, mats) in listOf(ServiceColors::defaultOf to listOf(IDENTITY), ServiceColors::colorblindOf to listOf(IDENTITY, PROTAN, DEUTAN))) {
                for (s in Service.entries) for (m in mats) for (d in dark) {
                    val e = distance(lab(simulate(palette(s), m)), lab(simulate(d, m)))
                    assertTrue("$skin: $s vs ${Integer.toHexString(d)}: ΔE $e", e >= MIN_DARK_DELTA_E)
                }
            }
        }
    }

    /** The default skin keeps the four technologies far apart in hue and lightness (judge feedback: greys blurred). */
    @Test
    fun classicCablesHaveStronglyDistinctColors() {
        val labs = CableType.entries.map { lab(simulate(CableStyles.of(CableSkin.CLASSIC, it).color, IDENTITY)) }
        for (i in labs.indices) for (j in i + 1 until labs.size) {
            val e = distance(labs[i], labs[j])
            assertTrue("${CableType.entries[i]} vs ${CableType.entries[j]}: ΔE $e", e >= MIN_CLASSIC_CABLE_DELTA_E)
        }
    }

    @Test
    fun theActiveSkinPicksTheColors() {
        Cosmetic.skin = CableSkin.NEON
        assertEquals(CableStyles.of(CableSkin.NEON, CableType.FIBER).color, CableStyles.of(CableType.FIBER).color)
        Cosmetic.reset()
        assertEquals(CableStyles.of(CableSkin.CLASSIC, CableType.FIBER).color, CableStyles.of(CableType.FIBER).color)
    }

    private fun distance(a: DoubleArray, b: DoubleArray) = hypot(hypot(a[0] - b[0], a[1] - b[1]), a[2] - b[2])

    private fun minDistance(palette: (Service) -> Int, m: Array<DoubleArray>): Double {
        val labs = Service.entries.map { lab(simulate(palette(it), m)) }
        var min = Double.MAX_VALUE
        for (i in labs.indices) for (j in i + 1 until labs.size) {
            min = minOf(min, distance(labs[i], labs[j]))
        }
        return min
    }

    /** Linear RGB after the simulation matrix. */
    private fun simulate(color: Int, m: Array<DoubleArray>): DoubleArray {
        val c = doubleArrayOf(toLinear((color shr 16) and 0xFF), toLinear((color shr 8) and 0xFF), toLinear(color and 0xFF))
        return DoubleArray(3) { i -> (m[i][0] * c[0] + m[i][1] * c[1] + m[i][2] * c[2]).coerceIn(0.0, 1.0) }
    }

    private fun toLinear(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun lab(l: DoubleArray): DoubleArray {
        val x = (0.4124 * l[0] + 0.3576 * l[1] + 0.1805 * l[2]) / 0.95047
        val y = 0.2126 * l[0] + 0.7152 * l[1] + 0.0722 * l[2]
        val z = (0.0193 * l[0] + 0.1192 * l[1] + 0.9505 * l[2]) / 1.08883
        fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116.0
        return doubleArrayOf(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private companion object {
        const val MIN_DELTA_E = 25.0
        /** Packets travel over DSL and coax cables and sit next to ink-drawn icons. */
        const val MIN_DARK_DELTA_E = 30.0
        /** The four cable technologies of one skin; the width tells them apart as well. */
        const val MIN_CABLE_DELTA_E = 15.0
        /** The default skin: no two technologies may look like shades of the same grey. */
        const val MIN_CLASSIC_CABLE_DELTA_E = 40.0
        const val INK = 0xFF262B33.toInt()
        const val MUTED = 0xFF5B6674.toInt()
        /** The off switch track in the settings (MenuPanel). */
        const val SWITCH_OFF = 0xFF7F887F.toInt()
        /** Tritanopes see fewer hue differences (closest pair today ΔE 20.1); the shapes carry the rest. */
        const val MIN_TRITAN_DELTA_E = 20.0
        val IDENTITY = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        val PROTAN = arrayOf(
            doubleArrayOf(0.152286, 1.052583, -0.204868),
            doubleArrayOf(0.114503, 0.786281, 0.099216),
            doubleArrayOf(-0.003882, -0.048116, 1.051998),
        )
        val TRITAN = arrayOf(
            doubleArrayOf(1.255528, -0.076749, -0.178779),
            doubleArrayOf(-0.078411, 0.930809, 0.147602),
            doubleArrayOf(0.004733, 0.691367, 0.303900),
        )
        val DEUTAN = arrayOf(
            doubleArrayOf(0.367322, 0.860646, -0.227968),
            doubleArrayOf(0.280085, 0.672501, 0.047413),
            doubleArrayOf(-0.011820, 0.042940, 0.968881),
        )
    }
}
