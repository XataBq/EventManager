package ru.eventpass.core.domain.auth

/** Private persistent credential boundary. Implementations must never expose credentials in logs.
 * Failed storage operations throw IOException. Missing or invalidated credentials read as null.
 */
interface SessionTokenStore {
    suspend fun read(): AuthSecret?
    suspend fun write(token: AuthSecret)
    suspend fun clear()
}
