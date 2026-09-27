package com.mininetworks.game.render

/**
 * The screen formats the game is checked on (docs/TOP100.md A6), landscape as the game starts. [qualifiers] are
 * Robolectric resource qualifiers that give the view the matching density and size in dp.
 */
enum class FormFactor(val id: String, val widthPx: Int, val heightPx: Int, val qualifiers: String) {
    /** Phone 20:9 as sold today, 2400 × 1080 at xxhdpi (800 × 360 dp). */
    PHONE_20_9("phone-20x9", 2400, 1080, "w800dp-h360dp-land-xxhdpi"),
    /** Older 16:9 phone, 1920 × 1080 at xxhdpi (640 × 360 dp): the narrowest landscape screen. */
    PHONE_16_9("phone-16x9", 1920, 1080, "w640dp-h360dp-land-xxhdpi"),
    /** 7" tablet, 1920 × 1200 at hdpi (1280 × 800 dp). */
    TABLET_7("tablet-7", 1920, 1200, "w1280dp-h800dp-land-hdpi"),
    /** 7" tablet at its real density, 1920 × 1200 at xhdpi (960 × 600 dp); checked for touch targets, no screenshot. */
    TABLET_7_XHDPI("tablet-7-xhdpi", 1920, 1200, "w960dp-h600dp-land-xhdpi"),
    /** 10" tablet, 2560 × 1600 at xhdpi (1280 × 800 dp). */
    TABLET_10("tablet-10", 2560, 1600, "w1280dp-h800dp-land-xhdpi"),
    /** Unfolded foldable (inner screen), 2208 × 1840 at 420 dpi (841 × 701 dp), almost square. */
    FOLDABLE("foldable", 2208, 1840, "w841dp-h700dp-land-420dpi"),
    ;

    /** The five formats with a screenshot each. */
    val screenshot get() = this != TABLET_7_XHDPI
}
