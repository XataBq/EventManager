package ru.eventpass.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.eventpass.core.domain.event.*
import ru.eventpass.data.fake.product.FakeFixtures
import ru.eventpass.core.platform.AndroidEventCoverStore

class EventEditorIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun screenshot(name: String) {
        val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val path = java.io.File(compose.activity.getExternalFilesDir(null), name)
        path.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun forgedEditorRouteDoesNotRenderForeignFields() {
        runBlocking { compose.backend().environment().scenarios.reset() }; compose.login()
        compose.runOnUiThread { compose.activity.setContent {
            ru.eventpass.core.designsystem.EventPassTheme {
                ru.eventpass.app.feature.editor.EditorRoute(null, FakeFixtures.VOLUNTEER.value, false, {}, {}, {})
            }
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(compose.activity.getString(R.string.editor_forbidden)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-title").assertDoesNotExist()
    }
    @Test fun atomicDiskSnapshotReloadRestoresDraftAndCoverWithoutSession() = runBlocking {
        val context = compose.activity.applicationContext
        val directory = java.io.File(context.cacheDir, "editor-proof-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val storage = ru.eventpass.app.di.AndroidFakeAuthStorage(directory)
        val e = ru.eventpass.data.fake.product.FakeEventPassEnvironment(authStorage = storage,
            authPolicy = ru.eventpass.data.fake.product.FakeAuthPolicy(passwordIterations = 1000))
        val asset = (AndroidEventCoverStore(context).importCover("content://ru.eventpass.android.demo.test.covers/valid") as CoverImportResult.Imported).cover
        e.scenarios.signInAs(FakeFixtures.ALEX)
        val fields = EventFields(title = "Диск", cover = asset)
        e.events.saveDraft(DraftId("disk"), null, fields)
        val next = ru.eventpass.data.fake.product.FakeEventPassEnvironment(authStorage = storage)
        assertEquals(ru.eventpass.core.domain.auth.SessionState.Guest, next.auth.observeSession().first().state)
        next.scenarios.signInAs(FakeFixtures.ALEX)
        val restored = next.events.observeEditor(EventEditorTarget.Draft(DraftId("disk"))).first() as EventEditorResult.Draft
        assertEquals(fields, restored.draft.fields)
        assertNotNull(AndroidEventCoverStore(context).preview(restored.draft.fields.cover!!))
    }
    @Test fun savedDraftRecreationPublishGuestRegistrationAndSchemaLockShareOneStore() {
        val e = compose.backend().environment()
        runBlocking { e.scenarios.reset() }; compose.login()
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("create-event").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-title").performTextReplacement("T06 device")
        compose.onNodeWithTag("save-draft").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-saved").fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("editor-title").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("editor-title").performScrollTo().assertTextEquals("T06 device")
        screenshot("t06-editor.png")
        compose.onNodeWithTag("editor-start").performScrollTo().performTextReplacement("2026-10-28 12:00")
        compose.onNodeWithTag("editor-venue").performScrollTo().performTextReplacement("Зал устройства")
        compose.onNodeWithText("Добавить: Текст").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Текст").performScrollTo().performTextReplacement("T06 вопрос")
        compose.onNodeWithTag("publish-event").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("published-screen").fetchSemanticsNodes().isNotEmpty() }
        screenshot("t06-published.png")
        compose.onNodeWithTag("copy-link").performScrollTo().performClick()
        val event = runBlocking { (e.events.observeMyEvents().first() as MyEventsResult.Ready).organizing.single { it.title == "T06 device" } }
        assertEquals("T06 вопрос", event.questions.single().title)
        compose.onNodeWithTag("open-published").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("event-${event.id.value}").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { e.auth.signOut() }
        compose.activityRule.scenario.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW,
            Uri.parse("eventpass-demo://demo/e/${event.id.value}"), it, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("registration-email").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("registration-name").performScrollTo().performTextReplacement("Мария")
        compose.onNodeWithTag("registration-email").performScrollTo().performTextReplacement("editor-guest@example.com")
        compose.onNodeWithContentDescription("T06 вопрос").performScrollTo().performTextReplacement("Ответ UI")
        compose.onNodeWithTag("registration-consent").performScrollTo().performClick()
        compose.onNodeWithTag("registration-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("guest-receipt").fetchSemanticsNodes().isNotEmpty() }
        val issued = e.scenarios.outbox().single().ticketId
        assertEquals(ru.eventpass.core.domain.registration.Answer.Text("Ответ UI"), e.scenarios.registrationFor(issued)!!.answers[event.questions.single().id])
        runBlocking { e.scenarios.signInAs(FakeFixtures.ALEX) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("root-Tickets").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-Events").performClick()
        compose.onNodeWithTag("edit-${event.id.value}").performScrollTo().performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.editor_locked)).performScrollTo().assertIsDisplayed()
        assertTrue(runBlocking { (e.events.observeEditor(EventEditorTarget.Published(event.id)).first() as EventEditorResult.Published).questionsLocked })
    }
    @Test fun coverContentValidationAndManagedCopySurviveSourceDeletion() = runBlocking {
        val context = compose.activity.applicationContext
        val store = AndroidEventCoverStore(context)
        val root = "content://ru.eventpass.android.demo.test.covers/"
        assertEquals(CoverImportResult.InvalidImage, store.importCover(root + "invalid"))
        assertEquals(CoverImportResult.TooLarge, store.importCover(root + "large"))
        assertEquals(CoverImportResult.Unavailable, store.importCover("file:///not-allowed"))
        val result = store.importCover(root + "valid") as CoverImportResult.Imported
        context.contentResolver.delete(Uri.parse(root + "valid"), null, null)
        val freshStore = AndroidEventCoverStore(context)
        val unused = store.importCover(root + "valid") as CoverImportResult.Imported
        freshStore.cleanupUnreferenced(setOf(result.cover.assetId))
        assertNull(freshStore.preview(unused.cover))
        val bitmap = freshStore.preview(result.cover)!!
        assertEquals(160, bitmap.width); assertEquals(90, bitmap.height); bitmap.recycle()
        val rotated = store.importCover(root + "rotated") as CoverImportResult.Imported
        assertEquals("image/jpeg", rotated.cover.mimeType)
        val oriented = store.preview(rotated.cover)!!
        assertEquals(90, oriented.width); assertEquals(160, oriented.height); oriented.recycle()
    }
}
