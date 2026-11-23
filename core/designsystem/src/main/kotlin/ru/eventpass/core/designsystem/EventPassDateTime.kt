package ru.eventpass.core.designsystem

import android.widget.NumberPicker
import android.view.ContextThemeWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.time.*
import java.time.format.DateTimeFormatter

/** Values keep their existing API format. Only their presentation and input method change. */
enum class TemporalInput { DATE, DATE_TIME, TIME }
private val DateTimeWire = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
private val DateTimeDisplay = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private val DateDisplay = DateTimeFormatter.ofPattern("dd.MM.yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemporalField(value:String,onValueChange:(String)->Unit,label:String,modifier:Modifier=Modifier,
    input:TemporalInput=TemporalInput.DATE_TIME,enabled:Boolean=true,error:String?=null,
    zoneId:String=ZoneId.systemDefault().id,initialValue:String="",supportingText:String?=null,allowClear:Boolean=false) {
    val zone=runCatching {ZoneId.of(zoneId)}.getOrDefault(ZoneId.systemDefault())
    fun parse(text:String):LocalDateTime?=runCatching {when(input) {
        TemporalInput.DATE_TIME -> LocalDateTime.parse(text,DateTimeWire)
        TemporalInput.DATE -> LocalDate.parse(text).atStartOfDay()
        TemporalInput.TIME -> LocalDate.now(zone).atTime(LocalTime.parse(text.take(5)))
    }}.getOrNull()
    val initial=parse(value) ?: parse(initialValue) ?: LocalDateTime.now(zone)
    val display=parse(value)?.format(when(input) {TemporalInput.DATE_TIME->DateTimeDisplay;TemporalInput.DATE->DateDisplay;TemporalInput.TIME->DateTimeFormatter.ofPattern("HH:mm")}) ?: value
    var step by rememberSaveable(value,input) {mutableIntStateOf(0)}
    var date by rememberSaveable(value,input) {mutableStateOf(initial.toLocalDate().toString())}
    var hour by rememberSaveable(value,input) {mutableIntStateOf(initial.hour)}
    var minute by rememberSaveable(value,input) {mutableIntStateOf(initial.minute)}
    val keyboard=LocalSoftwareKeyboardController.current
    val choose=stringResource(if(input==TemporalInput.TIME) R.string.ep_choose_time else R.string.ep_choose_date)
    fun open() {if(enabled) {keyboard?.hide();step=if(input==TemporalInput.TIME) 2 else 1}}
    Box(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription=listOf(label,display).filter(String::isNotBlank).joinToString(", ")
        role=Role.Button
        if(enabled) onClick(choose) {open();true} else disabled()
        if(error!=null) error(error)
    }) {
        FormField(display,{},label,enabled=enabled,error=error,readOnly=true,supportingText=supportingText,
            placeholder=choose,trailingContent={EventPassIcon(if(input==TemporalInput.TIME) EventPassGlyph.Clock else EventPassGlyph.Calendar,null)})
        Box(Modifier.matchParentSize().clickable(enabled=enabled,onClickLabel=choose,onClick=::open))
    }
    if(step==1) {
        val picker=rememberDatePickerState(initialSelectedDateMillis=LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest={step=0},confirmButton={
            TextButton(onClick={picker.selectedDateMillis?.let {millis ->
                date=Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
                if(input==TemporalInput.DATE) {step=0;onValueChange(date)} else step=2
            }},enabled=picker.selectedDateMillis!=null) {Text(stringResource(if(input==TemporalInput.DATE) R.string.ep_picker_done else R.string.ep_picker_next))}
        },dismissButton={Row {
            if(allowClear && value.isNotBlank()) TextButton(onClick={step=0;onValueChange("")}) {Text(stringResource(R.string.ep_picker_clear))}
            TextButton(onClick={step=0}) {Text(stringResource(R.string.ep_picker_cancel))}
        }}) {
            DatePicker(picker,showModeToggle=false)
        }
    }
    if(step==2) Dialog(onDismissRequest={step=0}) {
        Surface(shape=EventPassShapes.large,color=EventPassColors.card,contentColor=EventPassColors.textPrimary) {
            Column(Modifier.fillMaxWidth().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*0.85f)
                .verticalScroll(rememberScrollState()).padding(EventPassDimens.page),verticalArrangement=Arrangement.spacedBy(EventPassDimens.medium)) {
                Text(label,style=EventPassTypography.cardTitle)
                if(input==TemporalInput.DATE_TIME) Text(LocalDate.parse(date).format(DateDisplay),color=EventPassColors.textSecondary)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                    TimeWheel(hour,23,stringResource(R.string.ep_picker_hours)) {hour=it}
                    Text(":",style=EventPassTypography.heading)
                    TimeWheel(minute,59,stringResource(R.string.ep_picker_minutes)) {minute=it}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                    if(allowClear && value.isNotBlank()) TextButton(onClick={step=0;onValueChange("")}) {Text(stringResource(R.string.ep_picker_clear))}
                    TextButton(onClick={step=if(input==TemporalInput.DATE_TIME) 1 else 0}) {Text(stringResource(if(input==TemporalInput.DATE_TIME) R.string.ep_picker_back else R.string.ep_picker_cancel))}
                    TextButton(onClick={
                        val time=LocalTime.of(hour,minute)
                        val result=if(input==TemporalInput.TIME) time.format(DateTimeFormatter.ofPattern("HH:mm")) else LocalDate.parse(date).atTime(time).format(DateTimeWire)
                        step=0;onValueChange(result)
                    }) {Text(stringResource(R.string.ep_picker_done))}
                }
            }
        }
    }
}

@Composable private fun TimeWheel(value:Int,max:Int,label:String,change:(Int)->Unit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(label,style=EventPassTypography.label,color=EventPassColors.muted)
        val latestChange by rememberUpdatedState(change)
        AndroidView(modifier=Modifier.width(96.dp).height(160.dp),factory={context ->
            NumberPicker(ContextThemeWrapper(context,android.R.style.Theme_DeviceDefault)).apply {
                minValue=0;maxValue=max;wrapSelectorWheel=true
                descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setFormatter {it.toString().padStart(2,'0')}
                contentDescription=label
                setOnValueChangedListener {_,_,next -> latestChange(next)}
            }
        },update={if(it.value!=value) it.value=value})
    }
}
