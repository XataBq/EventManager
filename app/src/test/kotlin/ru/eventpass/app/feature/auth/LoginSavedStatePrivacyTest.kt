package ru.eventpass.app.feature.auth

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class LoginSavedStatePrivacyTest {
    @Test fun legacyEmailIsRemovedAndNewCredentialsStayOnlyInLiveViewModel() {
        val saved=SavedStateHandle(mapOf("email" to "legacy@example.test"))
        val vm=LoginViewModel(saved)
        assertEquals("",vm.state.value.email);assertTrue(saved.keys().isEmpty())
        vm.email("current@example.test");vm.password("Synthetic password")
        assertEquals("current@example.test",vm.state.value.email);assertTrue(saved.keys().isEmpty())
    }
}
