package com.mininetworks.game

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.StrictMode
import com.mininetworks.game.data.SettingsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Large screens (docs/TOP100.md A6): phones play in landscape; tablets, unfolded foldables and desktop windows follow
 * the device's orientation instead of letterboxing a locked landscape; the activity is resizable and survives folding
 * without being recreated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LargeScreenTest {

    @After
    fun laxStrictMode() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test
    fun policyLocksLandscapeOnlyOnPhones() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.forSmallestWidth(360))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, OrientationPolicy.forSmallestWidth(599))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationPolicy.forSmallestWidth(600))
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, OrientationPolicy.forSmallestWidth(800))
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xxhdpi")
    fun phoneStaysLandscape() {
        SettingsStore(RuntimeEnvironment.getApplication()).tutorialSeen = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, controller.get().requestedOrientation)
        controller.pause().stop().destroy()
    }

    @Test
    @Config(qualifiers = "sw800dp-w1280dp-h800dp-land-xhdpi")
    fun tabletFollowsTheDeviceAndUnfoldingSwitches() {
        SettingsStore(RuntimeEnvironment.getApplication()).tutorialSeen = true
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
        // Folding to the outer (phone-sized) screen: the same activity locks landscape again.
        val folded = Configuration(activity.resources.configuration).apply { smallestScreenWidthDp = 360; screenWidthDp = 800; screenHeightDp = 360 }
        activity.onConfigurationChanged(folded)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, activity.requestedOrientation)
        controller.pause().stop().destroy()
    }

    @Test
    fun activityIsResizableAndKeepsItsStateOnFolding() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
        val handled = info.configChanges
        for (change in listOf(ActivityInfo.CONFIG_SCREEN_SIZE, ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE, ActivityInfo.CONFIG_SCREEN_LAYOUT, ActivityInfo.CONFIG_ORIENTATION)) {
            assertTrue("configChanges handles $change", handled and change != 0)
        }
        // The canvas UI fixes text sizes, touch slop and HUD sizes from the density when the view is built, so a
        // density change (display size setting, another display) must recreate the activity; restoreState then goes on
        // from the autosave.
        assertTrue("a density change recreates the activity", handled and ActivityInfo.CONFIG_DENSITY == 0)
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertTrue("resizeableActivity is declared", manifest.contains("android:resizeableActivity=\"true\""))
        assertTrue("no max aspect ratio that would letterbox wide screens", !manifest.contains("maxAspectRatio"))
    }
}
