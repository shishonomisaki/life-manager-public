package com.shishonomisaki.lifemanager.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.shishonomisaki.lifemanager.core.model.Defaults
import com.shishonomisaki.lifemanager.core.model.Task
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** 先選日期再選時間。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(initial: Instant?, onDismiss: () -> Unit, onPicked: (Instant) -> Unit) {
    val zone = Defaults.ZONE
    val init = remember { (initial ?: ZonedDateTime.now(zone).plusHours(1).withMinute(0).toInstant()).atZone(zone) }
    var stage by remember { mutableIntStateOf(0) }
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = init.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val timeState = rememberTimePickerState(initialHour = init.hour, initialMinute = init.minute, is24Hour = true)
    if (stage == 0) {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = { stage = 1 }) { Text("下一步") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        ) { DatePicker(dateState) }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("幾點？") },
            text = { TimeInput(timeState) },
            confirmButton = {
                TextButton(onClick = {
                    val d = dateState.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        ?: init.toLocalDate()
                    onPicked(ZonedDateTime.of(d, LocalTime.of(timeState.hour, timeState.minute), zone).toInstant())
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { stage = 0 }) { Text("上一步") } },
        )
    }
}

/** 右滑改時間：+1 小時、明天、自訂。 */
@Composable
fun RescheduleDialog(task: Task, onDismiss: () -> Unit, onPicked: (Instant) -> Unit) {
    var custom by remember { mutableStateOf(false) }
    if (custom) {
        DateTimePickerDialog(task.dueAt ?: task.remindAt, onDismiss, onPicked)
        return
    }
    val zone = Defaults.ZONE
    val now = ZonedDateTime.now(zone)
    val base = (task.dueAt ?: task.remindAt)?.atZone(zone)
    val plusHour = (if (base != null && base.isAfter(now)) base else now).plusHours(1).toInstant()
    val tomorrow = now.plusDays(1).with(base?.toLocalTime() ?: LocalTime.of(9, 0)).toInstant()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改時間") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                TextButton(onClick = { onPicked(plusHour) }) { Text("+1 小時（${com.shishonomisaki.lifemanager.ui.Format.dateTime(plusHour)}）") }
                TextButton(onClick = { onPicked(tomorrow) }) { Text("明天（${com.shishonomisaki.lifemanager.ui.Format.dateTime(tomorrow)}）") }
                TextButton(onClick = { custom = true }) { Text("自訂…") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
