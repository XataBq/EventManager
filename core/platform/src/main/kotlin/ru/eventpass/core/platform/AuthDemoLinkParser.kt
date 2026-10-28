package ru.eventpass.core.platform

import java.net.URI

/** Deliberately not Serializable: confirmation secrets must not enter saved navigation. */
sealed interface AuthDemoLink {
    data class Event(val eventId: String) : AuthDemoLink
    class Confirm(val challengeId: String, val token: String) : AuthDemoLink {
        override fun toString() = "Confirm(challengeId=$challengeId, token=[redacted])"
    }
}
object AuthDemoLinkParser {
    fun parse(value: String?): AuthDemoLink? {
        if (value == null || value.length > 2048) return null
        val uri = try { URI(value) } catch (_: java.net.URISyntaxException) { return null }
        if (uri.scheme != "eventpass-demo" || uri.host != "demo" || uri.port != -1 ||
            uri.userInfo != null || uri.rawFragment != null) return null
        val parts = uri.rawPath?.split('/') ?: return null
        if (parts.size != 3 || parts[0] != "" || !parts[2].matches(Regex("[a-zA-Z0-9_-]{1,64}"))) return null
        return when (parts[1]) {
            "e" -> if (uri.rawQuery == null) AuthDemoLink.Event(parts[2]) else null
            "confirm" -> uri.rawQuery?.takeIf { it.matches(Regex("token=[a-zA-Z0-9_-]{16,128}")) }
                ?.let { AuthDemoLink.Confirm(parts[2], it.removePrefix("token=")) }
            else -> null
        }
    }
}
