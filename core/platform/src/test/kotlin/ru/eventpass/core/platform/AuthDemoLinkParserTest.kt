package ru.eventpass.core.platform

import org.junit.Assert.*
import org.junit.Test

class AuthDemoLinkParserTest {
    @Test fun onlyExplicitEventAndConfirmationPathsAreAccepted() {
        assertEquals(AuthDemoLink.Event("open-registration"), AuthDemoLinkParser.parse("eventpass-demo://demo/e/open-registration"))
        val token = "abcdefghijklmnop_123"
        val confirmation = AuthDemoLinkParser.parse("eventpass-demo://demo/confirm/challenge-1?token=$token") as AuthDemoLink.Confirm
        assertEquals(token, confirmation.token)
        assertFalse(confirmation.toString().contains(token))
        listOf("https://demo/e/event", "eventpass-demo://other/e/event", "eventpass-demo://demo:42/e/event",
            "eventpass-demo://user@demo/e/event", "eventpass-demo://demo/e/event?role=owner",
            "eventpass-demo://demo/e/%2e%2e", "eventpass-demo://demo/e/event/extra", "eventpass-demo://demo/reset/code",
            "eventpass-demo://demo/confirm/c?token=$token&token=$token", "eventpass-demo://demo/confirm/c?token=short",
            "eventpass-demo://demo/confirm/c?token=$token#fragment").forEach {
            assertNull(it, AuthDemoLinkParser.parse(it))
        }
        assertEquals("demo", DemoLinkParser.parse("eventpass-demo://demo/demo"))
    }
}
