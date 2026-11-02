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
    onSignOut: () -> Unit, onTools: () -> Unit) {
    val vm: ProfileViewModel=hiltViewModel(); val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(visible,lifecycle) { if(visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(); try { awaitCancellation() } finally { vm.stop() }
    } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val requestLeave=remember(vm) { { action: () -> Unit -> if(vm.requestLeave()) action() else pending=action } }
    DisposableEffect(vm) { registerLeave(requestLeave); onDispose { registerLeave(null) } }
    BackHandler(visible) { requestLeave(onBack) }
    LaunchedEffect(s.exit) { if(s.exit) { vm.consumeExit(); pending?.invoke(); pending=null } }
    val context=LocalContext.current
    ProfileScreen(s,vm::edit,{vm.save()},vm::start,{
        s.profile?.account?.id?.value?.let { id -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("EventPass ID",id)) }
    },{requestLeave(onSignOut)},{requestLeave(onTools)})
    if(s.confirming) AlertDialog(onDismissRequest=vm::keepEditing,
        title={Text(stringResource(R.string.profile_unsaved))},
        text={ s.error?.let { InlineError(stringResource(profileErrorResource(it))) } ?: Text(stringResource(R.string.profile_unsaved_hint)) },
        confirmButton={EventPassButton(stringResource(R.string.profile_save),{vm.save(true)},loading=s.busy)},
        dismissButton={TextAction(stringResource(R.string.profile_discard),vm::discard,enabled=!s.busy)})
}
@Composable
fun ProfileScreen(s: ProfileUiState, edit: (ProfileFields) -> Unit, save: () -> Unit, retry: () -> Unit,
    copyId: () -> Unit, logout: () -> Unit, tools: () -> Unit) {
    AuthColumn(Modifier.testTag("root-Profile")) {
        Text(stringResource(R.string.profile_title),style=EventPassTypography.heading)
        if(s.loading) Text(stringResource(R.string.loading))
        s.profile?.let { profile ->
            Text(profile.account.email,Modifier.testTag("profile-email"))
            KeyValueRow(stringResource(R.string.profile_user_id),profile.account.id.value)
            TextAction(stringResource(R.string.profile_copy_id),copyId,Modifier.testTag("profile-copy"))
            FormField(s.fields.name,{edit(s.fields.copy(name=it))},stringResource(R.string.profile_name),Modifier.testTag("profile-name"),enabled=!s.busy)
            FormField(s.fields.phone.orEmpty(),{edit(s.fields.copy(phone=it.takeIf(String::isNotEmpty)))},stringResource(R.string.profile_phone),Modifier.testTag("profile-phone"),enabled=!s.busy)
            FormField(s.fields.organization.orEmpty(),{edit(s.fields.copy(organization=it.takeIf(String::isNotEmpty)))},stringResource(R.string.profile_organization),Modifier.testTag("profile-organization"),enabled=!s.busy)
            SettingsSwitch(s.fields.emailNotifications,{edit(s.fields.copy(emailNotifications=it))},stringResource(R.string.profile_notifications),Modifier.testTag("profile-notifications"),enabled=!s.busy)
            Text(stringResource(R.string.profile_notifications_hint),style=EventPassTypography.hint)
            EventPassButton(stringResource(R.string.profile_save),save,Modifier.testTag("profile-save"),loading=s.busy,enabled=s.dirty)
            if(s.saved) Text(stringResource(R.string.profile_saved),Modifier.testTag("profile-saved"))
        }
        s.error?.let { if(!s.confirming) InlineError(stringResource(profileErrorResource(it))) }
        if(s.profile==null && !s.loading) EventPassButton(stringResource(R.string.ticket_retry),retry)
        Text(stringResource(R.string.profile_delete_unavailable),style=EventPassTypography.hint)
        EventPassButton(stringResource(R.string.auth_logout),logout,Modifier.testTag("sign-out"),style=ActionStyle.Secondary,enabled=!s.busy)
        TextAction(stringResource(R.string.auth_demo_tools),tools)
    }
}
fun profileErrorResource(error: ProfileError): Int=when(error) {
    ProfileError.INVALID_INPUT -> R.string.profile_invalid
    ProfileError.NETWORK -> R.string.auth_network
    ProfileError.TIMEOUT -> R.string.auth_timeout
    ProfileError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    else -> R.string.auth_session_changed
}
