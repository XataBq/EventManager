package ru.eventpass.app.feature.event

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.core.domain.checkin.ManualCodePolicy

class GuestManualCompatibilityTest {
    @Test fun oldSavedReceiptDerivesSameCodeWithoutLookupOrSavedStateSchemaChange() {
        val json = """{"ticketId":"old-ticket","eventId":"old-event","title":"Old","startsAt":"2026-10-28T09:00:00Z","zoneId":"Europe/Moscow","venue":"Hall","name":"Guest","number":"DEMO-OLD","payload":"opaque-old-secret","email":"guest@example.com"}"""
        val saved = SavedStateHandle(mapOf("guestReceipt" to json))
        val restored = GuestReceiptState(saved).read()!!
        assertEquals(ManualCodePolicy.fromQr("opaque-old-secret"), restored.ticket.manualCode)
        assertEquals("DEMO-OLD", restored.ticket.displayNumber)
        assertEquals(setOf("guestReceipt"), saved.keys())
        assertFalse(restored.ticket.toString().contains("opaque-old-secret"))
    }
}
