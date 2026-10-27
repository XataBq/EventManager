package ru.eventpass.core.platform

import java.net.URI

/** Deliberately separate from future production App Links. Returns a non-secret ID. */
object DemoLinkParser {
    fun parse(value: String?): String? {
        val uri = try {
            value?.let(::URI) ?: return null
        } catch (_: java.net.URISyntaxException) {
            return null
        }
        if (uri.scheme != "eventpass-demo" || uri.host != "demo" ||
            uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null
        ) return null
        return uri.path.removePrefix("/").takeIf {
            uri.path == "/$it" && it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))
        }
    }
}
