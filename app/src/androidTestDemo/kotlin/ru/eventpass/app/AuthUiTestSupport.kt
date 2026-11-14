package ru.eventpass.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import ru.eventpass.app.di.DemoBackendEntryPoint

fun AndroidComposeTestRule<*, MainActivity>.backend(): DemoBackendEntryPoint =
    EntryPointAccessors.fromApplication(activity.applicationContext, DemoBackendEntryPoint::class.java)

fun AndroidComposeTestRule<*, MainActivity>.login(email: String = "alex@example.com", password: String = "DemoPass123!", prepareGuest: Boolean = true) {
    if (prepareGuest) runBlocking { backend().auth().signOut() }
    waitUntil(10_000) { onAllNodesWithTag("auth-email").fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag("auth-email").performScrollTo().performTextReplacement(email)
    onNodeWithTag("auth-password").performScrollTo().performTextReplacement(password)
    onNodeWithTag("auth-submit").performScrollTo().performClick()
    waitUntil(10_000) { onAllNodesWithTag("root-Tickets").fetchSemanticsNodes().isNotEmpty() }
}
