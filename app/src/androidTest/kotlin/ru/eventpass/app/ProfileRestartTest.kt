package ru.eventpass.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import ru.eventpass.core.domain.profile.*
import ru.eventpass.core.domain.auth.*
import ru.eventpass.data.fake.product.FakeFixtures

/** Run methods individually with an external OS process kill between them. */
class ProfileRestartTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun stageSavedProfileAndAbandonedTemp()=runBlocking {
        val e=compose.backend().environment();e.scenarios.reset();e.scenarios.signInAs(FakeFixtures.ALEX)
        assertTrue(e.profiles.saveProfile(ProfileFields("После процесса","+79991234567","Политех",false)) is ProfileResult.Ready)
        val folder=File(compose.activity.cacheDir,"eventpass-documents").apply {mkdirs()};File(folder,"abandoned.tmp").writeText("fictional incomplete export")
        assertTrue(e.auth.currentSession().state is SessionState.Authenticated)
    }
    @Test fun newProcessReadsSavedProfileButNotOldSessionOrTemp()=runBlocking {
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("login-screen").fetchSemanticsNodes().isNotEmpty()}
        val e=compose.backend().environment();assertEquals(SessionState.Guest,e.auth.currentSession().state)
        assertTrue(e.auth.signIn("alex@example.com",AuthSecret("DemoPass123!")) is AuthResult.Success)
        val fields=(e.profiles.observeProfile().first() as ProfileResult.Ready).profile.fields
        assertEquals(ProfileFields("После процесса","+79991234567","Политех",false),fields)
        assertEquals(0,File(compose.activity.cacheDir,"eventpass-documents").listFiles()!!.size)
    }
}
