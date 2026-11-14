package ru.eventpass.app
import org.junit.Assert.*
import org.junit.Test
class VariantLinksTest {
 @Test fun externalDemoCredentialDeliveryIsFlavorBound() { assertEquals(false,acceptVariantLink("eventpass-demo://demo/confirm/id?token=secret")); assertFalse(acceptVariantLink(null)); assertFalse(acceptVariantLink("https://foreign.example/confirm?token=secret")) }
}
