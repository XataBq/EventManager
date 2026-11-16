package ru.eventpass.core.platform

import org.junit.Assert.*
import org.junit.Test

class PublicEventLinkParserTest {
 @Test fun codePointSlugLimitAndLiteralPercentAreNotDoubleDecoded() {
  val slug="😀".repeat(300)
  assertEquals(slug,PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/$slug")?.value)
  assertNull(PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/${slug}a"))
  assertEquals("one%2Ftwo",PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/one%252Ftwo")?.value)
  assertNull(PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/%2e%2e"))
 }
 @Test fun unicodeAndEncodedSlugDecodeExactlyOnce() {
  assertEquals("лекция-123",PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/лекция-123")?.value)
  assertEquals("лекция-123",PublicEventLinkParser.parse("https://staging.eventpass.dikanskii.ru/e/%D0%BB%D0%B5%D0%BA%D1%86%D0%B8%D1%8F-123")?.value)
 }
 @Test fun untrustedAuthGuestAndAmbiguousUrisAreRejected() {
  listOf("http://staging.eventpass.dikanskii.ru/e/one", "https://foreign.example/e/one",
   "https://staging.eventpass.dikanskii.ru/account-link#token=secret", "https://staging.eventpass.dikanskii.ru/e/one?token=secret",
   "https://user@staging.eventpass.dikanskii.ru/e/one", "https://staging.eventpass.dikanskii.ru/e/one%2Ftwo",
   "https://staging.eventpass.dikanskii.ru/e/..", "https://staging.eventpass.dikanskii.ru/e/one/extra").forEach { assertNull(it,PublicEventLinkParser.parse(it)) }
 }
}
