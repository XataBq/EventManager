package ru.eventpass.core.domain.profile

import kotlinx.coroutines.flow.Flow
import ru.eventpass.core.domain.auth.AuthInputPolicy
import ru.eventpass.core.domain.event.Account
import ru.eventpass.core.domain.registration.RegistrationValidator

data class ProfileFields(val name: String = "", val phone: String? = null, val organization: String? = null,
    val emailNotifications: Boolean = true,val email:String?=null) {
    override fun toString() = "ProfileFields([redacted])"
}
data class Profile(val account: Account, val fields: ProfileFields,val publicId:String?=null) {
    init {require(fields.email==null || fields.email==account.email)}
}
enum class ProfileError { FEATURE_UNAVAILABLE, INVALID_INPUT, AUTHENTICATION_REQUIRED, SESSION_CHANGED, NETWORK, TIMEOUT, STORAGE_UNAVAILABLE, PROTOCOL, FORBIDDEN, SERVER_UNAVAILABLE, EMAIL_TAKEN, RATE_LIMITED, BUSY, DELETE_UNCONFIRMED }
sealed interface ProfileResult {
    data class Ready(val profile: Profile,val deletionUncertain:Boolean=false) : ProfileResult
    data class Failed(val error: ProfileError) : ProfileResult
}
object ProfilePolicy {
    fun valid(fields: ProfileFields):Boolean {
        if(fields.email!=null) return fields.name.trim().isNotEmpty() && fields.name.codePointCount(0,fields.name.length)<=300 && fields.name.none(Char::isISOControl) &&
            AuthInputPolicy().validEmail(fields.email.trim()) && (fields.phone?.let {it.length<=50 && it.none(Char::isISOControl)}!=false) &&
            (fields.organization?.let {it.codePointCount(0,it.length)<=300 && it.none(Char::isISOControl)}!=false)
        return AuthInputPolicy().validName(fields.name) &&
        (fields.phone.isNullOrBlank() || RegistrationValidator.normalizeRussianPhone(fields.phone.trim()) != null) &&
        (fields.organization?.let { it.length <= 200 && it.none(Char::isISOControl) } != false)
    }
    fun normalized(fields: ProfileFields) = fields.copy(name = fields.name.trim(),
        phone = fields.phone?.trim()?.takeIf(String::isNotEmpty)?.let {if(fields.email==null) RegistrationValidator.normalizeRussianPhone(it) else it},
        organization = fields.organization?.trim()?.takeIf(String::isNotEmpty),email=fields.email?.trim())
}
sealed interface DeleteAccountResult {
    data class Deleted(val localCleanupError:ProfileError?=null):DeleteAccountResult
    data class Present(val profile:Profile):DeleteAccountResult
    data object AccessRevoked:DeleteAccountResult
    data class Uncertain(val error:ProfileError):DeleteAccountResult
    data class Failed(val error:ProfileError):DeleteAccountResult
}
interface ProfileRepository {
    fun observeProfile(): Flow<ProfileResult>
    suspend fun refreshProfile(): ProfileResult
    suspend fun saveProfile(fields: ProfileFields): ProfileResult
    suspend fun deleteAccount():DeleteAccountResult=DeleteAccountResult.Failed(ProfileError.FEATURE_UNAVAILABLE)
    suspend fun reconcileDeletion():DeleteAccountResult=DeleteAccountResult.Failed(ProfileError.FEATURE_UNAVAILABLE)
}
