package com.mininetworks.game.render

import com.mininetworks.game.game.CableSkin
import com.mininetworks.game.game.ColorTheme

/**
 * Colors of a map color theme (docs/TOP100.md C5), for both styles: tiles, water, board edge and backdrop of the
 * isometric style, trees and grass on it, and ground, water and backdrop of the flat overview. Purely cosmetic: the
 * rules never see a theme, and mountains, towers, devices, packets and alarms keep their colors in every theme.
 */
class MapPalette(
    val landA: Int,
    val landB: Int,
    val waterA: Int,
    val waterB: Int,
    val lockedLandA: Int,
    val lockedLandB: Int,
    val lockedWaterA: Int,
    val lockedWaterB: Int,
    val boardLit: Int,
    val boardShade: Int,
    val background: Int,
    val grass: Int,
    val leaf: Int,
    val leafDark: Int,
    val pine: Int,
    val pineDark: Int,
    /** Flat overview: ground, water and the backdrop around the grid. */
    val flatLand: Int,
    val flatWater: Int,
    val flatBackdrop: Int,
)

/**
 * The active cosmetics, set from the game thread (settings) and read while drawing, like [ServiceColors.colorblind].
 * [theme] picks the [MapPalette], [skin] the cable colors of [CableStyles].
 */
object Cosmetic {
    @Volatile var theme = ColorTheme.MEADOW
    @Volatile var skin = CableSkin.CLASSIC

    /** The palette of the active [theme]. */
    val palette: MapPalette get() = paletteOf(theme)

    fun paletteOf(t: ColorTheme): MapPalette = when (t) {
        ColorTheme.MEADOW -> MEADOW
        ColorTheme.AUTUMN -> AUTUMN
        ColorTheme.WINTER -> WINTER
        ColorTheme.DESERT -> DESERT
    }

    /** Resets to the defaults, for tests. */
    fun reset() {
        theme = ColorTheme.MEADOW
        skin = CableSkin.CLASSIC
    }

    private const val LOCKED_GREY = 0xFFE4E6E2.toInt()

    /** Linear mix of two opaque colors, [f] = 0 gives [a]. */
    fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** A palette whose locked tiles are its own land and water washed out towards grey. */
    private fun palette(
        landA: Int, landB: Int, waterA: Int, waterB: Int, boardLit: Int, boardShade: Int, background: Int, grass: Int,
        leaf: Int, leafDark: Int, pine: Int, pineDark: Int, flatLand: Int, flatWater: Int, flatBackdrop: Int,
    ) = MapPalette(
        landA, landB, waterA, waterB,
        blend(landA, LOCKED_GREY, 0.7f), blend(landB, LOCKED_GREY, 0.7f), blend(waterA, LOCKED_GREY, 0.55f), blend(waterB, LOCKED_GREY, 0.55f),
        boardLit, boardShade, background, grass, leaf, leafDark, pine, pineDark, flatLand, flatWater, flatBackdrop,
    )

    /** The original look (docs/style-explorations.html). */
    private val MEADOW = MapPalette(
        landA = 0xFFDDE9D6.toInt(), landB = 0xFFD5E3CD.toInt(), waterA = 0xFF8FC3DA.toInt(), waterB = 0xFFA4D0E3.toInt(),
        lockedLandA = 0xFFE3E6E0.toInt(), lockedLandB = 0xFFDDE1DA.toInt(), lockedWaterA = 0xFFC4D8E1.toInt(), lockedWaterB = 0xFFCDDEE6.toInt(),
        boardLit = 0xFFB9C9AF.toInt(), boardShade = 0xFFA7BA9C.toInt(), background = 0xFFEEF3EA.toInt(),
        grass = 0xFFB3C9A6.toInt(), leaf = 0xFF93C47D.toInt(), leafDark = 0xFF6FA262.toInt(), pine = 0xFF6FA87A.toInt(), pineDark = 0xFF4E8660.toInt(),
        flatLand = 0xFFF3F1EC.toInt(), flatWater = 0xFFC3DCE8.toInt(), flatBackdrop = 0xFFD3CFC5.toInt(),
    )

    /** Straw fields and orange trees. */
    private val AUTUMN = palette(
        landA = 0xFFE9E1C6.toInt(), landB = 0xFFE2D9BB.toInt(), waterA = 0xFF86B4C9.toInt(), waterB = 0xFF9CC3D5.toInt(),
        boardLit = 0xFFCDB68C.toInt(), boardShade = 0xFFB89F74.toInt(), background = 0xFFF4EFE3.toInt(),
        grass = 0xFFCBB27C.toInt(), leaf = 0xFFE59A48.toInt(), leafDark = 0xFFC4702F.toInt(), pine = 0xFF7F9A5E.toInt(), pineDark = 0xFF5E7A45.toInt(),
        flatLand = 0xFFF4EEDF.toInt(), flatWater = 0xFFBCD5E0.toInt(), flatBackdrop = 0xFFD9CDB5.toInt(),
    )

    /** Snow on the ground and on the trees. */
    private val WINTER = palette(
        landA = 0xFFEEF3F6.toInt(), landB = 0xFFE5ECF0.toInt(), waterA = 0xFF7FB2CF.toInt(), waterB = 0xFF95C3DC.toInt(),
        boardLit = 0xFFC5D2DB.toInt(), boardShade = 0xFFAFBFCA.toInt(), background = 0xFFF5F8FA.toInt(),
        grass = 0xFFD2DDE4.toInt(), leaf = 0xFFDDE7ED.toInt(), leafDark = 0xFFB3C4CF.toInt(), pine = 0xFF5F8E7E.toInt(), pineDark = 0xFF436F61.toInt(),
        flatLand = 0xFFF7F9FA.toInt(), flatWater = 0xFFB7D3E4.toInt(), flatBackdrop = 0xFFCFD8DE.toInt(),
    )

    /** Sand, oasis blue and dry green. */
    private val DESERT = palette(
        landA = 0xFFF0DDB4.toInt(), landB = 0xFFE9D4A7.toInt(), waterA = 0xFF6FB9C4.toInt(), waterB = 0xFF86C7D0.toInt(),
        boardLit = 0xFFD8B87E.toInt(), boardShade = 0xFFC3A165.toInt(), background = 0xFFF8EEDB.toInt(),
        grass = 0xFFD8C088.toInt(), leaf = 0xFF9DBB6A.toInt(), leafDark = 0xFF7B9A4E.toInt(), pine = 0xFF8AA86A.toInt(), pineDark = 0xFF6A8A4E.toInt(),
        flatLand = 0xFFF6EBD2.toInt(), flatWater = 0xFFB5DCE0.toInt(), flatBackdrop = 0xFFE0CFA9.toInt(),
    )
}
