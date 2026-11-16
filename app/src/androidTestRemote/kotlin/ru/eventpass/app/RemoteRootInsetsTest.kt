package ru.eventpass.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RemoteRootInsetsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun publicLoginContentIsInsideSystemBarsBeforeEventImport() {
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("login-screen").fetchSemanticsNodes().isNotEmpty()}
        val bounds=compose.onNodeWithTag("login-screen").fetchSemanticsNode().boundsInRoot
        compose.runOnUiThread {
            val decor=compose.activity.window.decorView
            val insets=requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            assertTrue(bounds.top>=insets.top);assertTrue(bounds.left>=insets.left)
            assertTrue(bounds.right<=decor.width-insets.right);assertTrue(bounds.bottom<=decor.height-insets.bottom)
        }
    }
}
