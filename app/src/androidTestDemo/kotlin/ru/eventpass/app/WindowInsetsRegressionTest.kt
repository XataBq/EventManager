package ru.eventpass.app

import android.app.UiAutomation
import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class WindowInsetsRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun profileActionAndContentRemainOutsideSystemAreasAfterRotation() {
        compose.login()
        val orientations = listOf(
            Configuration.ORIENTATION_PORTRAIT to Surface.ROTATION_0,
            Configuration.ORIENTATION_LANDSCAPE to Surface.ROTATION_90,
            Configuration.ORIENTATION_LANDSCAPE to Surface.ROTATION_270,
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val settings = compose.activity.contentResolver
        val autoRotate = Settings.System.getInt(settings, Settings.System.ACCELEROMETER_ROTATION, 0)
        val initialRotation = compose.activity.window.decorView.display.rotation
        try {
            for ((expected, rotation) in orientations) {
                assertTrue("System rejected rotation $rotation", automation.setRotation(rotation))
                compose.waitUntil(10_000) {
                    compose.activity.resources.configuration.orientation == expected &&
                        compose.activity.window.decorView.display.rotation == rotation &&
                        compose.activity.window.decorView.hasWindowFocus()
                }
                compose.waitForIdle()
                // Compose/configuration idle can precede completion of the system's rotation.
                automation.waitForIdle(500, 5_000)
                awaitWindowFrame()
                val profile = compose.onNodeWithContentDescription("Профиль")
                profile.assertIsDisplayed()
                val action = profile.fetchSemanticsNode().boundsInRoot
                assertInsideSafeArea(action, "profile")
                assertInsideSafeArea(compose.onNodeWithTag("root-Tickets").fetchSemanticsNode().boundsInRoot, "content")

                // Inject via the system, so a navigation bar can intercept an overlapping tap.
                val location = IntArray(2)
                compose.activityRule.scenario.onActivity { it.window.decorView.getLocationOnScreen(location) }
                tapScreen(action.center.x + location[0], action.center.y + location[1])
                compose.waitUntil(5_000) { compose.onNodeWithTag("root-Profile").isDisplayed() }
                compose.onNodeWithTag("root-Profile").assertIsDisplayed()
                compose.onNodeWithTag("tab-Profile").assertIsSelected()
                compose.onNodeWithTag("tab-Tickets").performClick()
            }
        } finally {
            val restoreRotation = if (autoRotate == 1) UiAutomation.ROTATION_UNFREEZE else initialRotation
            assertTrue("System rejected restoring rotation", automation.setRotation(restoreRotation))
        }
    }

    private fun assertInsideSafeArea(bounds: Rect, label: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(view))
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            Log.i("InsetsRegression", "$label rotation=${view.display.rotation}: $bounds / $insets; window=${view.width}x${view.height}")
            assertTrue("Control overlaps left system area: $bounds / $insets", bounds.left >= insets.left)
            assertTrue("Control overlaps right system area: $bounds / $insets", bounds.right <= view.width - insets.right)
            assertTrue("Control overlaps top system area: $bounds / $insets", bounds.top >= insets.top)
            assertTrue("Control overlaps bottom system area: $bounds / $insets", bounds.bottom <= view.height - insets.bottom)
        }
    }

    private fun awaitWindowFrame() {
        val committed = CountDownLatch(1)
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            if (Build.VERSION.SDK_INT >= 29) {
                view.viewTreeObserver.registerFrameCommitCallback(committed::countDown)
            } else {
                view.doOnPreDraw { it.post { committed.countDown() } }
            }
            view.postInvalidateOnAnimation()
        }
        assertTrue("Window did not draw after rotation", committed.await(5, TimeUnit.SECONDS))
    }

    private fun tapScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val displayId = compose.activity.window.decorView.display.displayId
        val command = "input touchscreen -d $displayId tap ${x.roundToInt()} ${y.roundToInt()}"
        val output = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
        assertTrue("System tap command failed: $output", output.isBlank())
        Log.i("InsetsRegression", "screen tap ($x, $y) on display $displayId completed")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }
}
