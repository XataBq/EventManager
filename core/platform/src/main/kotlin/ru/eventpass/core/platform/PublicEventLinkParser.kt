package ru.eventpass.core.platform

import java.net.URI
import ru.eventpass.core.domain.event.EventSlug

/** Explicit paste/import only; never an Android App Link or authentication token parser. */
object PublicEventLinkParser {
    private val hosts = setOf("staging.eventpass.dikanskii.ru", "eventpass.dikanskii.ru")
    fun parse(raw: String): EventSlug? = runCatching {
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", true) && uri.host?.lowercase(java.util.Locale.ROOT) in hosts)
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.port == -1 || uri.port == 443))
        val path = uri.path
        require(path.startsWith("/e/"))
        val slug = path.removePrefix("/e/")
        require(slug.isNotBlank() && slug.none(Char::isWhitespace) && slug !in setOf(".", ".."))
        EventSlug(slug)
    }.getOrNull()
}
