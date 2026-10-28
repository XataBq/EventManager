package ru.eventpass.core.domain

import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.auth.*

class AuthInputPolicyTest {
    @Test fun demoInputPolicyAllowsAnyWritingSystemAndRedactsSecrets() {
        val policy = AuthInputPolicy()
        assertTrue(policy.validName("李")); assertTrue(policy.validName("أحمد"))
        assertFalse(policy.validName("  ")); assertFalse(policy.validName("Name\nOther"))
        assertTrue(policy.validEmail("person@example.com")); assertFalse(policy.validEmail("not-an-email"))
        assertFalse(policy.validPassword(AuthSecret("short")))
        assertTrue(policy.validPassword(AuthSecret("DemoPass123!")))
        assertEquals("[redacted]", AuthSecret("never-log-this").toString())
    }
}
