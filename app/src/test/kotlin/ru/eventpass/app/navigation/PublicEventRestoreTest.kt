package ru.eventpass.app.navigation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.eventpass.app.navigation.auth.ReturnDestination

class PublicEventRestoreTest {
 @Test fun unicodePublicSlugRestoresWithoutMasqueradingAsEventId() {
  val key = EventKey("", flowId = "flow", publicSlug = "лекция-123")
  assertEquals(key, Json.decodeFromString<EventKey>(Json.encodeToString(key)))
  val destination: ReturnDestination = ReturnDestination.Event("", "лекция-123")
  assertEquals(destination, Json.decodeFromString<ReturnDestination>(Json.encodeToString(destination)))
 }
 @Test fun legacyInternalReturnTargetRemainsCompatible() {
  assertEquals(ReturnDestination.Event("open-registration"), Json.decodeFromString<ReturnDestination>(Json.encodeToString<ReturnDestination>(ReturnDestination.Event("open-registration"))))
 }
}
