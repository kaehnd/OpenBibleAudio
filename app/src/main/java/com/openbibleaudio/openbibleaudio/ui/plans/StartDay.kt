package com.openbibleaudio.openbibleaudio.ui.plans

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openbibleaudio.openbibleaudio.R

/** A day number in 1..[dayCount]; [valid] is the parsed day, or null to show the range as an error. */
@Composable
fun StartDayField(text: String, valid: Int?, dayCount: Int, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = { s -> onChange(s.filter(Char::isDigit).take(4)) },
        label = { Text(stringResource(R.string.plan_start_day)) },
        isError = valid == null,
        supportingText = {
            Text(
                if (valid == null) stringResource(R.string.plan_start_day_range, dayCount)
                else if (valid > 1) pluralStringResource(R.plurals.plan_start_day_note, valid - 1, valid - 1)
                else "",
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * "Start from day N…" (spec 0003 §6.3): days before N become done, N and later go back to not
 * done, and N becomes today's day. [currentDay] pre-fills the field.
 */
@Composable
fun StartFromDayDialog(currentDay: Int, dayCount: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(currentDay.toString()) }
    val day = text.toIntOrNull()?.takeIf { it in 1..dayCount }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_start_from_title)) },
        text = {
            Column {
                Text(stringResource(R.string.plan_start_from_body), style = MaterialTheme.typography.bodyMedium)
                StartDayField(text, day, dayCount, modifier = Modifier.padding(top = 12.dp)) { text = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { day?.let { onDismiss(); onConfirm(it) } }, enabled = day != null) {
                Text(day?.let { stringResource(R.string.plan_start_from_confirm, it) } ?: stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
