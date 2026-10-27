package ru.eventpass.core.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoLinkParserTest {
    @Test fun acceptsOnlyAnIdOnTheDemoSchemeAndHost() {
        assertEquals("demo", DemoLinkParser.parse("eventpass-demo://demo/demo"))
        assertEquals("missing-id", DemoLinkParser.parse("eventpass-demo://demo/missing-id"))
        listOf(
            null, "", "https://demo/demo", "eventpass-demo://wrong/demo",
            "eventpass-demo://demo/", "eventpass-demo://demo/a/b",
            "eventpass-demo://demo/demo?role=owner", "eventpass-demo://demo/demo#fragment",
            "eventpass-demo://user@demo/demo", "eventpass-demo://demo:80/demo",
            "eventpass-demo://demo/%2F", "eventpass-demo://demo/" + "a".repeat(65),
            "not a uri",
        ).forEach { assertNull(it, DemoLinkParser.parse(it)) }
    }
}
