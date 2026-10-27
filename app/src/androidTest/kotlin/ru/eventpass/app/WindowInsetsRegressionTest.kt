package ru.eventpass.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.performClick
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WindowInsetsRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun profileActionAndContentRemainOutsideSystemAreasAfterRotation() {
        val orientations = listOf(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        )
        try {
            for (orientation in orientations) {
                compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
                val (expected, rotation) = when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> Configuration.ORIENTATION_PORTRAIT to Surface.ROTATION_0
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> Configuration.ORIENTATION_LANDSCAPE to Surface.ROTATION_90
                    else -> Configuration.ORIENTATION_LANDSCAPE to Surface.ROTATION_270
                }
                compose.waitUntil(10_000) {
                    compose.activity.resources.configuration.orientation == expected &&
                        compose.activity.window.decorView.display.rotation == rotation
                }
                compose.waitForIdle()
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
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
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

    private fun tapScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + 40, MotionEvent.ACTION_UP, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            assertTrue(automation.injectInputEvent(down, true))
            assertTrue(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitForIdle()
    }
}
