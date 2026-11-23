package ru.eventpass.app.feature.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.eventpass.app.R
import ru.eventpass.app.feature.event.formatEventTime
import ru.eventpass.app.ui.LoadingLayout
import ru.eventpass.app.ui.LoadingSkeleton
import ru.eventpass.core.designsystem.*
import ru.eventpass.core.domain.attendance.*
import ru.eventpass.core.domain.registration.*
import java.text.NumberFormat
import java.util.Locale

@Composable
fun DashboardRoute(eventId:String,visible:Boolean,scanner:()->Unit,volunteers:()->Unit,onDenied:()->Unit,
    vm:DashboardViewModel=hiltViewModel(),exportEnabled:Boolean=true) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(eventId,lifecycle,visible) {if(visible) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        vm.start(eventId);vm.refreshVisible();try {awaitCancellation()} finally {vm.stop()}
    }}
    LaunchedEffect(s.denied) {if(visible && s.denied) onDenied()}
    DashboardScreen(s,vm::query,vm::refreshVisible,vm::more,vm::retryPage,vm::confirm,scanner,volunteers,
        visible=visible,onPanel=vm::panel,onDetails=vm::details,
        documents={if(exportEnabled) ru.eventpass.app.feature.documents.DocumentActions(
            ru.eventpass.core.domain.documents.DocumentRequest.AttendanceXlsx(ru.eventpass.core.domain.event.EventId(eventId)),visible,s.dashboard!=null)})
    s.selected?.let {target -> ConfirmDialog(stringResource(R.string.attendance_cancel),
        "${target.email} · ${target.displayNumber}",
        stringResource(if(s.cancelError==null) R.string.attendance_cancel else R.string.ticket_retry),vm::cancel,
        {vm.confirm(null)},destructive=true,loading=s.cancelling,
        errorText=s.cancelError?.let {stringResource(attendanceErrorResource(it))})}
}

@Composable
fun DashboardScreen(s:DashboardUiState,query:(String)->Unit,refresh:()->Unit,more:()->Unit,
    retryPage:()->Unit,confirm:(Attendee?)->Unit,scanner:()->Unit,volunteers:()->Unit,
    documents:@Composable ()->Unit={},visible:Boolean=true,onPanel:(Int)->Unit={},onDetails:(Attendee?)->Unit={}) {
    val pager=rememberPagerState(initialPage=s.panel,pageCount={2})
    val scope=rememberCoroutineScope()
    LaunchedEffect(pager) {snapshotFlow {pager.settledPage}.distinctUntilChanged().collect {onPanel(it)}}
    LaunchedEffect(s.panel) {if(pager.currentPage!=s.panel && !pager.isScrollInProgress) pager.scrollToPage(s.panel)}
    val header=if(s.panel==1) s.analytics?.dashboard ?: s.dashboard else s.dashboard
    EventPassRefresh(if(s.panel==1) s.analyticsLoading && s.analytics!=null else s.refreshing,refresh,
        Modifier.testTag("refresh-dashboard"),enabled=visible && !s.loading && !s.denied && s.selected==null && s.detail==null && !pager.isScrollInProgress) {
        Column(Modifier.fillMaxSize().testTag("dashboard-screen")) {
            Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*0.42f)
                .verticalScroll(rememberScrollState()).padding(horizontal=EventPassDimens.page,vertical=EventPassDimens.small),
                verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                Text(stringResource(R.string.dashboard_title),style=EventPassTypography.heading)
                if (s.loading && header == null) LoadingSkeleton(LoadingLayout.Dashboard)
                header?.let {d ->
                    Text(d.header.title,style=EventPassTypography.cardTitle,modifier=Modifier.testTag("dashboard-event"))
                    Text(formatEventTime(d.header.startsAt,d.header.zoneId),style=EventPassTypography.hint,color=EventPassColors.muted)
                    Text(d.header.venue,style=EventPassTypography.hint,color=EventPassColors.muted)
                    StatusBadge(stringResource(when {
                        d.header.lifecycle==ru.eventpass.core.domain.event.EventLifecycle.CANCELLED -> R.string.event_cancelled
                        d.header.finished -> R.string.event_finished
                        d.header.lifecycle==ru.eventpass.core.domain.event.EventLifecycle.LIVE || (!d.header.statusAuthoritative && d.updatedAt>=d.header.startsAt) -> R.string.event_started
                        !d.header.registrationOpen -> R.string.event_closed
                        else -> R.string.event_open
                    }),StatusTone.Neutral)
                    EventPassSection(Modifier.testTag("dashboard-totals")) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                            Total(stringResource(R.string.dashboard_registered),d.summary.registered,Modifier.weight(1f))
                            Total(stringResource(R.string.dashboard_checked),d.summary.checkedIn,Modifier.weight(1f))
                            Total(stringResource(if(d.header.finished) R.string.dashboard_absent else R.string.dashboard_expected),d.summary.notCheckedIn,Modifier.weight(1f))
                        }
                        Text(stringResource(R.string.ui_dashboard_capacity,d.summary.remaining?.toString() ?: "∞",d.summary.capacity?.toString() ?: "∞"),style=EventPassTypography.hint,color=EventPassColors.muted)
                        Text(stringResource(R.string.ui_dashboard_percent,d.summary.percent),style=EventPassTypography.hint,color=EventPassColors.muted)
                        (d.volunteerCount ?: s.dashboard?.volunteerCount)?.let {Text(stringResource(R.string.ui_dashboard_volunteers,it),style=EventPassTypography.hint,color=EventPassColors.muted)}
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                        TextAction(stringResource(R.string.scan_title),scanner,Modifier.weight(1f).testTag("dashboard-scanner"))
                        TextAction(stringResource(R.string.volunteers_title),volunteers,Modifier.weight(1f).testTag("dashboard-volunteers"))
                    }
                    Text(stringResource(R.string.m07_fetched,formatEventTime(d.fetchedAt,d.header.zoneId)),style=EventPassTypography.hint,color=EventPassColors.muted)
                    d.volunteerError?.let {InlineError(stringResource(ru.eventpass.app.feature.volunteers.volunteerErrorResource(it)))}
                }
                documents()
                s.readError?.let {InlineError(stringResource(attendanceErrorResource(it)));TextAction(stringResource(R.string.ticket_retry),refresh)}
            }
            if(s.dashboard!=null) {
                SegmentedTabs(listOf(SegmentOption(stringResource(R.string.ui_attendees_tab)),SegmentOption(stringResource(R.string.ui_answers_tab))),pager.currentPage,
                    {scope.launch {pager.animateScrollToPage(it)}},Modifier.padding(horizontal=EventPassDimens.page,vertical=EventPassDimens.small))
                HorizontalPager(pager,Modifier.fillMaxWidth().weight(1f),userScrollEnabled=s.detail==null && s.selected==null && !s.cancelling) {page ->
                    if(page==0) ParticipantsPage(s,query,more,retryPage,onDetails) else QuestionAnalyticsPage(s,refresh)
                }
            }
        }
    }
    s.detail?.let {row -> ParticipantDialog(row,s.dashboard,close={onDetails(null)},cancel={confirm(row)},busy=s.cancelling)}
}

@Composable private fun Total(label:String,value:Int,modifier:Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(EventPassDimens.tiny)) {
        Text(value.toString(),style=EventPassTypography.heading)
        Text(label,style=EventPassTypography.hint,color=EventPassColors.muted)
    }
}

@Composable private fun ParticipantsPage(s:DashboardUiState,query:(String)->Unit,more:()->Unit,retry:()->Unit,open:(Attendee?)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(EventPassDimens.page),verticalArrangement=Arrangement.spacedBy(EventPassDimens.medium)) {
        item("search") {Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
            FormField(s.query,query,stringResource(R.string.ui_attendee_search),Modifier.testTag("dashboard-query"),enabled=!s.cancelling,
                error=if(s.pageError==AttendanceError.INVALID_INPUT) stringResource(R.string.dashboard_bad_query) else null)
            if(s.matchesKnown) Text(stringResource(R.string.dashboard_matches,s.matches),style=EventPassTypography.hint,color=EventPassColors.muted)
        }}
        items(s.rows,key={it.ticketId.value}) {row ->
            EventPassSection(Modifier.testTag("attendee-${row.ticketId.value}").clickable(role=Role.Button,onClick={open(row)})) {
                Text(row.email,style=EventPassTypography.emphasis)
                Text(row.displayNumber,style=EventPassTypography.label,color=EventPassColors.accentLight)
                TicketStatusLabel(row)
            }
        }
        item("footer") {Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
            if (s.paging) LoadingSkeleton(if (s.rows.isEmpty()) LoadingLayout.List else LoadingLayout.Line)
            if(!s.paging && s.rows.isEmpty() && s.pageError==null) Text(stringResource(if(s.query.isBlank()) R.string.dashboard_empty else R.string.dashboard_no_matches))
            s.pageError?.takeUnless {it==AttendanceError.INVALID_INPUT}?.let {InlineError(stringResource(attendanceErrorResource(it)));TextAction(stringResource(R.string.ticket_retry),retry)}
            if(s.next!=null) EventPassButton(stringResource(R.string.dashboard_more),more,Modifier.testTag("dashboard-more"),loading=s.paging)
        }}
    }
}

@Composable private fun TicketStatusLabel(row:Attendee) {
    StatusBadge(stringResource(when(row.status) {TicketStatus.ACTIVE->R.string.dashboard_expected;TicketStatus.USED->R.string.ticket_used;TicketStatus.CANCELLED->R.string.ticket_cancelled}),
        if(row.status==TicketStatus.USED) StatusTone.Success else StatusTone.Neutral)
}

@Composable private fun ParticipantDialog(row:Attendee,dashboard:AttendanceDashboard?,close:()->Unit,cancel:()->Unit,busy:Boolean) {
    Dialog(onDismissRequest={if(!busy) close()},properties=DialogProperties(dismissOnBackPress=!busy,dismissOnClickOutside=!busy)) {
        Surface(shape=EventPassShapes.large,color=EventPassColors.card,contentColor=EventPassColors.textPrimary) {
            Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*0.85f)
                .verticalScroll(rememberScrollState()).padding(EventPassDimens.page),verticalArrangement=Arrangement.spacedBy(EventPassDimens.medium)) {
                Text(row.name,style=EventPassTypography.cardTitle)
                Text(row.email);Text(row.displayNumber,style=EventPassTypography.emphasis);TicketStatusLabel(row)
                row.checkedAt?.let {Text(stringResource(R.string.m06_checked_at,formatEventTime(it,dashboard?.header?.zoneId ?: "Europe/Moscow")),style=EventPassTypography.hint)}
                HorizontalDivider(color=EventPassColors.raised)
                Text(stringResource(R.string.m07_answers),style=EventPassTypography.emphasis)
                val questions=dashboard?.questions.orEmpty()
                questions.forEach {question -> AnswerRow(question.title,resolveAttendanceAnswer(question,row.answers[question.id]))}
                val removed=row.answers.keys-countedQuestionIds(questions)
                removed.forEach {AnswerRow(stringResource(R.string.ui_removed_question),ResolvedAttendanceAnswer(resolution=AnswerResolution.OUTDATED))}
                if(questions.isEmpty() && removed.isEmpty()) Text(stringResource(R.string.ui_no_answers),color=EventPassColors.muted)
                if(row.status==TicketStatus.ACTIVE) TextButton(onClick=cancel,enabled=!busy) {
                    Text(stringResource(R.string.attendance_cancel),style=EventPassTypography.label,color=EventPassColors.danger)
                }
            }
        }
    }
}
private fun countedQuestionIds(questions:List<RegistrationQuestion>)=questions.map {it.id}.toSet()

@Composable private fun AnswerRow(title:String,answer:ResolvedAttendanceAnswer) {
    Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.tiny)) {
        Text(title,style=EventPassTypography.label,color=EventPassColors.textSecondary)
        answer.values.forEach {Text(it,style=EventPassTypography.body)}
        val message=when(answer.resolution) {
            AnswerResolution.VALUE->null
            AnswerResolution.YES->R.string.m07_yes
            AnswerResolution.NO->R.string.m07_no
            AnswerResolution.MISSING->R.string.ui_answer_missing
            AnswerResolution.OUTDATED->R.string.ui_answer_outdated
            AnswerResolution.PARTIAL->R.string.ui_answer_partial
        }
        message?.let {Text(stringResource(it),style=EventPassTypography.body,color=if(answer.resolution in setOf(AnswerResolution.YES,AnswerResolution.NO)) EventPassColors.textPrimary else EventPassColors.muted)}
    }
}

@Composable private fun QuestionAnalyticsPage(s:DashboardUiState,retry:()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(EventPassDimens.page),verticalArrangement=Arrangement.spacedBy(EventPassDimens.medium)) {
        item("state") {Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
            Text(stringResource(R.string.ui_analytics_basis),style=EventPassTypography.hint,color=EventPassColors.muted)
            if (s.analyticsLoading) LoadingSkeleton(LoadingLayout.Dashboard)
            s.analyticsError?.let {InlineError(stringResource(attendanceErrorResource(it)));TextAction(stringResource(R.string.ticket_retry),retry)}
            s.analytics?.let {Text(stringResource(R.string.ui_analytics_participants,it.participants),style=EventPassTypography.emphasis)}
        }}
        s.analytics?.let {data ->
            if(data.questions.isEmpty()) item("empty") {Text(stringResource(R.string.ui_no_choice_questions))}
            items(data.questions,key={it.question.id}) {stat -> EventPassSection {
                Text(stat.question.title,style=EventPassTypography.cardTitle)
                if(stat.question is RegistrationQuestion.MultipleChoice) Text(stringResource(R.string.ui_multiple_percent),style=EventPassTypography.hint,color=EventPassColors.muted)
                stat.options.forEach {option -> Column(verticalArrangement=Arrangement.spacedBy(EventPassDimens.tiny)) {
                    val fraction=if(stat.participants==0) 0f else option.count.toFloat()/stat.participants
                    val percent=NumberFormat.getNumberInstance(Locale.forLanguageTag("ru")).apply {maximumFractionDigits=1}.format(fraction*100)
                    val label=option.title ?: stringResource(if(option.id=="true") R.string.m07_yes else R.string.m07_no)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(EventPassDimens.small)) {
                        Text(label,Modifier.weight(1f),style=EventPassTypography.body)
                        Text(stringResource(R.string.ui_analytics_count,option.count,percent),style=EventPassTypography.emphasis)
                    }
                    LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth(),color=EventPassColors.accent,trackColor=EventPassColors.raised)
                }}
                Text(stringResource(R.string.ui_analytics_missing,stat.missing),style=EventPassTypography.hint,color=EventPassColors.muted)
                if(stat.outdated>0) Text(stringResource(R.string.ui_analytics_outdated,stat.outdated),style=EventPassTypography.hint,color=EventPassColors.warning)
            }}
        }
    }
}
fun attendanceErrorResource(error: AttendanceError): Int = when (error) {
    AttendanceError.RATE_LIMITED -> R.string.auth_cooldown
    AttendanceError.FEATURE_UNAVAILABLE -> R.string.feature_unavailable
    AttendanceError.PROTOCOL -> R.string.auth_protocol
    AttendanceError.FORBIDDEN -> R.string.auth_forbidden
    AttendanceError.SERVER_UNAVAILABLE -> R.string.m02_server_unavailable
    AttendanceError.NETWORK -> R.string.auth_network
    AttendanceError.TIMEOUT -> R.string.auth_timeout
    AttendanceError.STORAGE_UNAVAILABLE -> R.string.auth_storage
    AttendanceError.USED -> R.string.ticket_used_no_cancel
    AttendanceError.STALE_PAGE -> R.string.dashboard_stale
    AttendanceError.INVALID_INPUT -> R.string.dashboard_bad_query
    else -> R.string.scan_access_lost
}
