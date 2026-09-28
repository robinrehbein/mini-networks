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
) {
    /**
     * Translucent haze over the locked ground: a light veil in the scenery's own backdrop colour, so the land outside
     * the board reads as a soft, paler version of the scenery instead of a grey wash (judge panel).
     */
    val fog: Int = (background and 0xFFFFFF) or 0x47000000
}

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

    /**
     * The palette for a map of scenery [scenarioId]: with the default theme ([ColorTheme.MEADOW]) every scenery has a
     * ground of its own (cool asphalt for the metropolis, sand and deep water for the island, alpine green and earth for
     * the mountains, dusk violet for 2030), so five sceneries read as five worlds at thumbnail size (judge panel); an
     * unlocked theme the player picked wins over it.
     */
    fun paletteFor(scenarioId: String): MapPalette =
        if (theme != ColorTheme.MEADOW) palette else SCENERY_PALETTES[scenarioId] ?: MEADOW

    /** Resets to the defaults, for tests. */
    fun reset() {
        theme = ColorTheme.MEADOW
        skin = CableSkin.CLASSIC
    }

    /** Linear mix of two opaque colors, [f] = 0 gives [a]. */
    fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1f - f) + ((b shr shift) and 0xFF) * f).toInt() shl shift
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    /** A palette whose locked tiles are its own land and water faded towards its backdrop (tinted, never grey). */
    private fun palette(
        landA: Int, landB: Int, waterA: Int, waterB: Int, boardLit: Int, boardShade: Int, background: Int, grass: Int,
        leaf: Int, leafDark: Int, pine: Int, pineDark: Int, flatLand: Int, flatWater: Int, flatBackdrop: Int,
    ) = MapPalette(
        landA, landB, waterA, waterB,
        blend(landA, background, 0.5f), blend(landB, background, 0.5f), blend(waterA, background, 0.45f), blend(waterB, background, 0.45f),
        boardLit, boardShade, background, grass, leaf, leafDark, pine, pineDark, flatLand, flatWater, flatBackdrop,
    )

    /** The original look (docs/style-explorations.html). */
    private val MEADOW = MapPalette(
        landA = 0xFFD6E9C8.toInt(), landB = 0xFFCBE0BB.toInt(), waterA = 0xFF78B9DA.toInt(), waterB = 0xFF90C7E3.toInt(),
        lockedLandA = 0xFFE2EDD9.toInt(), lockedLandB = 0xFFDCE9D2.toInt(), lockedWaterA = 0xFFB3D6E6.toInt(), lockedWaterB = 0xFFBFDCEA.toInt(),
        boardLit = 0xFFABC39D.toInt(), boardShade = 0xFF93AD86.toInt(), background = 0xFFEEF3EA.toInt(),
        grass = 0xFFA9C79A.toInt(), leaf = 0xFF8CC572.toInt(), leafDark = 0xFF62A056.toInt(), pine = 0xFF6FA87A.toInt(), pineDark = 0xFF4E8660.toInt(),
        flatLand = 0xFFF3F1EC.toInt(), flatWater = 0xFFC3DCE8.toInt(), flatBackdrop = 0xFFD3CFC5.toInt(),
    )

    /** Kleinstadt am Fluss: the meadow, a little fresher. */
    private val RIVER_TOWN = palette(
        landA = 0xFFD0E8C0.toInt(), landB = 0xFFC4DFB3.toInt(), waterA = 0xFF6AB4DD.toInt(), waterB = 0xFF86C4E6.toInt(),
        boardLit = 0xFFAFC7A1.toInt(), boardShade = 0xFF9BB58D.toInt(), background = 0xFFEAF2E4.toInt(),
        grass = 0xFFA9C79B.toInt(), leaf = 0xFF8CC275.toInt(), leafDark = 0xFF67A05A.toInt(), pine = 0xFF66A674.toInt(), pineDark = 0xFF478459.toInt(),
        flatLand = 0xFFF1F3EA.toInt(), flatWater = 0xFFB9D9E8.toInt(), flatBackdrop = 0xFFD2D8CB.toInt(),
    )

    /** Großstadt: warm sandstone paving (judge panel: the cool grey read as the drabbest scenery), deep river blue, park greens. */
    private val METROPOLIS = palette(
        landA = 0xFFE6DDC8.toInt(), landB = 0xFFDCD2BB.toInt(), waterA = 0xFF3F8FD0.toInt(), waterB = 0xFF5CA5DC.toInt(),
        boardLit = 0xFFB9A98A.toInt(), boardShade = 0xFFA08F72.toInt(), background = 0xFFF1EBDD.toInt(),
        grass = 0xFFB4C28E.toInt(), leaf = 0xFF6FB865.toInt(), leafDark = 0xFF4E9450.toInt(), pine = 0xFF4E9468.toInt(), pineDark = 0xFF36724E.toInt(),
        flatLand = 0xFFF2EEE4.toInt(), flatWater = 0xFFB6CFE2.toInt(), flatBackdrop = 0xFFD6CDBB.toInt(),
    )

    /** Insel & Hafen: sandy ground, deep turquoise sea, lush palms. */
    private val ISLAND = palette(
        landA = 0xFFE9E3BD.toInt(), landB = 0xFFE1DAB1.toInt(), waterA = 0xFF3D9CC2.toInt(), waterB = 0xFF53AECE.toInt(),
        boardLit = 0xFFCDB688.toInt(), boardShade = 0xFFB8A070.toInt(), background = 0xFFE0F0F1.toInt(),
        grass = 0xFFC9C58F.toInt(), leaf = 0xFF78C16A.toInt(), leafDark = 0xFF549E4C.toInt(), pine = 0xFF4FA37C.toInt(), pineDark = 0xFF37835F.toInt(),
        flatLand = 0xFFF5F0DC.toInt(), flatWater = 0xFFA6D6E6.toInt(), flatBackdrop = 0xFFD9CFAE.toInt(),
    )

    /** Bergdorf: alpine green on brown earth. */
    private val MOUNTAIN_VILLAGE = palette(
        landA = 0xFFC9DDB6.toInt(), landB = 0xFFBFD4AA.toInt(), waterA = 0xFF77AFD0.toInt(), waterB = 0xFF8DBEDB.toInt(),
        boardLit = 0xFFA5937A.toInt(), boardShade = 0xFF8F7D66.toInt(), background = 0xFFE9EDE4.toInt(),
        grass = 0xFF9DBB89.toInt(), leaf = 0xFF7BB068.toInt(), leafDark = 0xFF5A8D4E.toInt(), pine = 0xFF4C8860.toInt(), pineDark = 0xFF356A48.toInt(),
        flatLand = 0xFFEEF1E6.toInt(), flatWater = 0xFFB6D6E6.toInt(), flatBackdrop = 0xFFCDC6B5.toInt(),
    )

    /** Zukunft 2030: the city at night (judge panel: the set needed a mood shot): deep indigo ground, electric blue water, glowing teal trees. */
    private val FUTURE = palette(
        landA = 0xFF443D7E.toInt(), landB = 0xFF3D3775.toInt(), waterA = 0xFF2D6BD6.toInt(), waterB = 0xFF3A7BE0.toInt(),
        boardLit = 0xFF2E2860.toInt(), boardShade = 0xFF241E4E.toInt(), background = 0xFF2A2552.toInt(),
        grass = 0xFF5C54A0.toInt(), leaf = 0xFF4FD6C0.toInt(), leafDark = 0xFF2FA590.toInt(), pine = 0xFF45B8B0.toInt(), pineDark = 0xFF2A8A84.toInt(),
        flatLand = 0xFFF0EDF7.toInt(), flatWater = 0xFFC0CAEE.toInt(), flatBackdrop = 0xFFCDC6E0.toInt(),
    )

    private val SCENERY_PALETTES by lazy {
        mapOf(
            "river_town" to RIVER_TOWN, "metropolis" to METROPOLIS, "island_harbor" to ISLAND,
            "mountain_village" to MOUNTAIN_VILLAGE, "future_2030" to FUTURE,
        )
    }

    /** Straw fields and orange trees. */
    private val AUTUMN = palette(
        landA = 0xFFE9E1C6.toInt(), landB = 0xFFE2D9BB.toInt(), waterA = 0xFF86B4C9.toInt(), waterB = 0xFF9CC3D5.toInt(),
        boardLit = 0xFFCDB68C.toInt(), boardShade = 0xFFB89F74.toInt(), background = 0xFFF4EFE3.toInt(),
        grass = 0xFFCBB27C.toInt(), leaf = 0xFFE59A48.toInt(), leafDark = 0xFFC4702F.toInt(), pine = 0xFF7F9A5E.toInt(), pineDark = 0xFF5E7A45.toInt(),
        flatLand = 0xFFF4EEDF.toInt(), flatWater = 0xFFBCD5E0.toInt(), flatBackdrop = 0xFFD9CDB5.toInt(),
    )

    /** Snow on the ground and on the trees. */
    private val WINTER = palette(
        landA = 0xFFE3EBF0.toInt(), landB = 0xFFD8E2E9.toInt(), waterA = 0xFF7FB2CF.toInt(), waterB = 0xFF95C3DC.toInt(),
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
