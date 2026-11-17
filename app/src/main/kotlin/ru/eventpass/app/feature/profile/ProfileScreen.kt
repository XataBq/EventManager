package ru.eventpass.app.feature.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import ru.eventpass.app.R
import ru.eventpass.app.feature.auth.AuthColumn
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.profile.*

@Composable
fun ProfileRoute(visible: Boolean, onBack: () -> Unit, registerLeave: (((() -> Unit) -> Unit)?) -> Unit,
    onSignOut: () -> Unit, onTools: () -> Unit, vm: ProfileViewModel=hiltViewModel(),showTools:Boolean=true,enableDeletion:Boolean=false) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val activity=androidx.activity.compose.LocalActivity.current
    LaunchedEffect(visible,lifecycle) { if(visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(); vm.refresh(); try { awaitCancellation() } finally { vm.pause(activity?.isChangingConfigurations==true) }
    } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestLeave=remember(vm) { { action: () -> Unit -> if(vm.requestLeave()) action() else pending=action } }
    DisposableEffect(vm) { registerLeave(requestLeave); onDispose { registerLeave(null) } }
    BackHandler(visible) { requestLeave(onBack) }
    LaunchedEffect(s.exit) { if(s.exit) { vm.consumeExit(); pending?.invoke(); pending=null } }
    val context=LocalContext.current
    ProfileScreen(s,vm::edit,{vm.save()},vm::refresh,{
        (s.profile?.publicId ?: s.profile?.account?.id?.value)?.let { id -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("EventPass ID",id)) }
    },{requestLeave(onSignOut)},{requestLeave(onTools)},visible,vm::confirmDelete,enableDeletion,showTools)
    if(s.deleteDialog) ConfirmDialog(stringResource(R.string.m08_delete_title),
        stringResource(when {s.deleteUncertain->R.string.m08_delete_unknown;s.deletePresent->R.string.m08_delete_present;else->R.string.m08_delete_cascade}),
        stringResource(if(s.deleteUncertain) R.string.m08_delete_check else R.string.m08_delete_confirm),
        {if(s.deleteUncertain) vm.checkDeletion() else vm.deleteAccount()},vm::dismissDelete,destructive=!s.deleteUncertain,loading=s.deleting,
        errorText=s.deleteError?.let {stringResource(profileErrorResource(it))})
    if(s.confirming) AlertDialog(onDismissRequest=vm::keepEditing,
        title={Text(stringResource(R.string.profile_unsaved))},
        text={ s.error?.let { InlineError(stringResource(profileErrorResource(it))) } ?: Text(stringResource(R.string.profile_unsaved_hint)) },
        confirmButton={EventPassButton(stringResource(R.string.profile_save),{vm.save(true)},loading=s.busy)},
        dismissButton={TextAction(stringResource(R.string.profile_discard),vm::discard,enabled=!s.busy)})
}
@Composable
fun ProfileScreen(s: ProfileUiState, edit: (ProfileFields) -> Unit, save: () -> Unit, retry: () -> Unit,
    copyId: () -> Unit, logout: () -> Unit, tools: () -> Unit, visible: Boolean = true,delete:()->Unit={},enableDeletion:Boolean=false,showTools:Boolean=true) {
    EventPassRefresh(s.refreshing, retry, Modifier.testTag("refresh-profile"), enabled=visible && !s.loading && !s.busy && !s.confirming && !s.deleteDialog &&
        s.error !in setOf(ProfileError.AUTHENTICATION_REQUIRED,ProfileError.SESSION_CHANGED)) {
    AuthColumn(Modifier.testTag("root-Profile")) {
        Text(stringResource(R.string.profile_title),style=EventPassTypography.heading)
        if(s.loading) Text(stringResource(R.string.loading))
        s.profile?.let { profile ->
            Text(profile.account.email,Modifier.testTag("profile-email"))
            KeyValueRow(stringResource(R.string.profile_user_id),profile.publicId ?: profile.account.id.value)
            TextAction(stringResource(R.string.profile_copy_id),copyId,Modifier.testTag("profile-copy"))
            val editable=!s.deleting && !s.deleteUncertain && !s.deleteDialog
            FormField(s.fields.name,{edit(s.fields.copy(name=it))},stringResource(R.string.profile_name),Modifier.testTag("profile-name"),enabled=editable)
            s.fields.email?.let {email->FormField(email,{edit(s.fields.copy(email=it))},stringResource(R.string.auth_email),Modifier.testTag("profile-email-input"),enabled=editable)}
            FormField(s.fields.phone.orEmpty(),{edit(s.fields.copy(phone=it.takeIf(String::isNotEmpty)))},stringResource(R.string.profile_phone),Modifier.testTag("profile-phone"),enabled=editable)
            FormField(s.fields.organization.orEmpty(),{edit(s.fields.copy(organization=it.takeIf(String::isNotEmpty)))},stringResource(R.string.profile_organization),Modifier.testTag("profile-organization"),enabled=editable)
            SettingsSwitch(s.fields.emailNotifications,{edit(s.fields.copy(emailNotifications=it))},stringResource(R.string.profile_notifications),Modifier.testTag("profile-notifications"),enabled=editable)
            Text(stringResource(R.string.profile_notifications_hint),style=EventPassTypography.hint)
            EventPassButton(stringResource(R.string.profile_save),save,Modifier.testTag("profile-save"),loading=s.busy,enabled=s.dirty && !s.deleteUncertain)
            if(s.conflict) Text(stringResource(R.string.profile_refresh_conflict),Modifier.testTag("profile-conflict"),style=EventPassTypography.hint)
            if(s.saved) Text(stringResource(R.string.profile_saved),Modifier.testTag("profile-saved"))
        }
        s.error?.let { if(!s.confirming) InlineError(stringResource(profileErrorResource(it))) }
        s.readError?.let { InlineError(stringResource(profileErrorResource(it))); TextAction(stringResource(R.string.ticket_retry),retry) }
        if(s.profile==null && !s.loading) EventPassButton(stringResource(R.string.ticket_retry),retry)
        if(enableDeletion && s.profile!=null) {
            if(s.deleteUncertain) Text(stringResource(R.string.m08_delete_unknown),style=EventPassTypography.hint)
            EventPassButton(stringResource(if(s.deleteUncertain) R.string.m08_delete_check else R.string.m08_delete_title),delete,style=ActionStyle.Danger,enabled=!s.busy)
        }
        EventPassButton(stringResource(R.string.auth_logout),logout,Modifier.testTag("sign-out"),style=ActionStyle.Secondary,enabled=!s.busy)
        if(showTools) TextAction(stringResource(R.string.auth_demo_tools),tools)
    }
    }
}
fun profileErrorResource(error: ProfileError): Int=when(error) {
    ProfileError.EMAIL_TAKEN -> R.string.m08_email_taken
    ProfileError.RATE_LIMITED -> R.string.auth_cooldown
    ProfileError.BUSY -> R.string.m08_profile_busy
    ProfileError.DELETE_UNCONFIRMED -> R.string.m08_delete_unknown
    ProfileError.INVALID_INPUT -> R.string.profile_invalid
    ProfileError.FEATURE_UNAVAILABLE -> R.string.feature_unavailable
    ProfileError.PROTOCOL -> R.string.auth_protocol
    ProfileError.FORBIDDEN -> R.string.auth_forbidden
    ProfileError.SERVER_UNAVAILABLE -> R.string.m02_server_unavailable
    ProfileError.NETWORK -> R.string.auth_network
    ProfileError.TIMEOUT -> R.string.auth_timeout
    ProfileError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    else -> R.string.auth_session_changed
}
