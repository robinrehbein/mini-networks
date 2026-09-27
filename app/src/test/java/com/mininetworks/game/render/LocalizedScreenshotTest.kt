package com.mininetworks.game.render

import android.graphics.Bitmap
import android.graphics.Canvas
import com.mininetworks.game.data.HighscoreStore
import com.mininetworks.game.data.SettingsStore
import com.mininetworks.game.game.DebugApi
import com.mininetworks.game.game.PlayerStats
import com.mininetworks.game.monetization.Entitlements
import com.mininetworks.game.monetization.FakeMonetization
import com.mininetworks.game.ui.GameView
import com.mininetworks.game.ui.menu.Screen
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * docs/TOP100.md F1: the screens with the most text in all 12 languages on the narrowest landscape phone (16:9,
 * 640 × 360 dp at xxhdpi), to look for cut, overlapping or badly wrapped text: main menu, achievements, settings,
 * the first tutorial step, the week reward and the game-over card. Written to docs/screenshots/i18n/<language>-<screen>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
@OptIn(DebugApi::class)
class LocalizedScreenshotTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val shots get() = File(System.getProperty("screenshots.dir") ?: "build/screenshots", "i18n").apply { mkdirs() }

    @Before
    fun setUp() {
        SettingsStore(app).tutorialSeen = true
        HighscoreStore(app).submit(1187)
    }

    private val stats = PlayerStats(
        delivered = 1_240, bestGame = 412, bestWeek = 9, cablesLaid = 131, fiberLaid = 12, cableUpgrades = 7, routersPlaced = 23,
        accessPoints = 2, serverUpgrades = 6, dataCenters = 1, repairs = 3, gamesFinished = 8, weeksPlayed = 61,
        sceneries = setOf("river_town", "metropolis"), streamingDelivered = 180, dailyDone = 4, bestStreak = 3, richest = 212,
    )

    @Test
    fun textHeavyScreensInEveryLanguage() {
        for (lang in LANGUAGES) {
            RuntimeEnvironment.setQualifiers("$lang-${FormFactor.PHONE_16_9.qualifiers}")
            val bmp = Bitmap.createBitmap(FormFactor.PHONE_16_9.widthPx, FormFactor.PHONE_16_9.heightPx, Bitmap.Config.ARGB_8888)
            fun view() = GameView(app).also {
                it.monetization = FakeMonetization(prices = mapOf(Entitlements.REMOVE_ADS to "2,99 €"), privacyOptionsRequired = true)
                it.setAchievementStats(stats)
            }
            fun save(name: String) = File(shots, "$lang-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val game = FormFactorScreenshotTest.busyHud()
            view().let { it.drawSnapshot(Canvas(bmp), it.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = null) }
            save("menu")
            bmp.eraseColor(0)
            view().let { it.drawSnapshot(Canvas(bmp), it.currentWorld, bmp.width, bmp.height, time = 1.3f, screen = Screen.ACHIEVEMENTS) }
            save("achievements")
            bmp.eraseColor(0)
            view().drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.SETTINGS)
            save("settings")
            bmp.eraseColor(0)
            view().drawSnapshot(Canvas(bmp), game, bmp.width, bmp.height, time = 1.3f, style = "Iso", screen = Screen.GAME_OVER)
            save("game-over")
            bmp.eraseColor(0)
            view().drawSnapshot(Canvas(bmp), FormFactorScreenshotTest.rewardWorld(), bmp.width, bmp.height, time = 1.3f, style = "Iso")
            save("reward")
            SettingsStore(app).tutorialSeen = false
            val t = GameView(app)
            bmp.eraseColor(0)
            t.drawSnapshot(Canvas(bmp), t.currentTutorial!!.world, bmp.width, bmp.height, time = 0.3f, screen = null)
            t.advance(1f)
            t.drawCurrent(Canvas(bmp))
            save("tutorial")
            SettingsStore(app).tutorialSeen = true
        }
        RuntimeEnvironment.setQualifiers("de")
    }

    private companion object {
        val LANGUAGES = listOf("de", "en", "fr", "es", "it", "pt-rBR", "pl", "nl", "tr", "ja", "ko", "zh-rCN")
    }
}
