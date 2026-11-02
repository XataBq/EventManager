package ru.eventpass.core.domain.profile

import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.auth.AuthInputPolicy
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.registration.RegistrationValidator

data class ProfileFields(val name: String = "", val phone: String? = null, val organization: String? = null,
    val emailNotifications: Boolean = true) {
    override fun toString() = "ProfileFields([redacted])"
}
data class Profile(val account: Account, val fields: ProfileFields)
enum class ProfileError { INVALID_INPUT, AUTHENTICATION_REQUIRED, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE }
sealed interface ProfileResult {
    data class Ready(val profile: Profile) : ProfileResult
    data class Failed(val error: ProfileError) : ProfileResult
}
object ProfilePolicy {
    fun valid(fields: ProfileFields) = AuthInputPolicy().validName(fields.name) &&
        (fields.phone.isNullOrBlank() || RegistrationValidator.normalizeRussianPhone(fields.phone.trim()) != null) &&
        (fields.organization?.let { it.length <= 200 && it.none(Char::isISOControl) } != false)
    fun normalized(fields: ProfileFields) = fields.copy(name = fields.name.trim(),
        phone = fields.phone?.trim()?.takeIf(String::isNotEmpty)?.let(RegistrationValidator::normalizeRussianPhone),
        organization = fields.organization?.trim()?.takeIf(String::isNotEmpty))
}
interface ProfileRepository {
    fun observeProfile(): Flow<ProfileResult>
    suspend fun saveProfile(fields: ProfileFields): ProfileResult
}
