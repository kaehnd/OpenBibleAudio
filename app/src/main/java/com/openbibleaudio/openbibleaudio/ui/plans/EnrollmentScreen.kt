package com.openbibleaudio.openbibleaudio.ui.plans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.playback.BookTitles
import com.openbibleaudio.openbibleaudio.plans.DayStatus

private enum class Confirm { RESTART, DELETE }

/** One enrollment (spec 0003 §9.3): Play today, header actions and the day list. */
@Composable
fun EnrollmentScreen(
    viewModel: EnrollmentViewModel,
    contentPadding: PaddingValues,
    onPlay: (mediaId: String) -> Unit,
    onClosed: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val books = remember(context) { BookTitles(context) }
    var confirm by rememberSaveable { mutableStateOf<Confirm?>(null) }
    val e = viewModel.enrollmentId

    val state = ui
    if (state == null) {
        Box(Modifier.fillMaxSize().padding(contentPadding)) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
        return
    }
    LaunchedEffect(state.gone) { if (state.gone) onClosed() }
    if (state.gone) return
    val card = state.card

    // Open with the current day in view, a day of context above it (spec 0003 §9.3). Item 0 is
    // the header and item n is day n, so early days still show the header's Play button.
    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((card.currentDay ?: 1) - 2).coerceAtLeast(0))

    LazyColumn(Modifier.fillMaxSize().padding(contentPadding), state = list) {
        item {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(card.planName, style = MaterialTheme.typography.titleLarge)
                        Text(progressLine(card), style = MaterialTheme.typography.bodyMedium)
                    }
                    HeaderMenu(
                        paused = card.paused,
                        finished = card.finished,
                        onSkip = viewModel::skipToToday,
                        onPause = { viewModel.setPaused(!card.paused) },
                        onRestart = { confirm = Confirm.RESTART },
                        onArchive = viewModel::archive,
                        onDelete = { confirm = Confirm.DELETE },
                    )
                }
                card.keepGoingDay?.let {
                    Text(stringResource(R.string.plan_card_keep_going, it), color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                if (!card.finished) {
                    Button(onClick = { onPlay("plan/$e/today") }, modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.plan_play_today), Modifier.padding(start = 8.dp))
                    }
                }
            }
            HorizontalDivider()
        }
        items(state.days, key = { it.day }) { row ->
            DayItem(
                row = row,
                readings = readingsText(context, books, row.readings),
                onPlay = { onPlay("plan/$e/day/${row.day}") },
                onMarkComplete = { viewModel.markComplete(row.day) },
                onMarkNotDone = { viewModel.markNotDone(row.day) },
            )
        }
    }

    when (confirm) {
        null -> Unit
        Confirm.RESTART -> ConfirmDialog(
            title = stringResource(R.string.plan_restart_title, card.planName),
            body = stringResource(R.string.plan_restart_body),
            action = stringResource(R.string.plan_restart),
            onDismiss = { confirm = null },
        ) { viewModel.restart() }
        Confirm.DELETE -> ConfirmDialog(
            title = stringResource(R.string.plan_delete_title, card.planName),
            body = stringResource(R.string.plan_delete_body),
            action = stringResource(R.string.plan_delete),
            onDismiss = { confirm = null },
        ) { viewModel.delete() }
    }
}

@Composable
private fun HeaderMenu(
    paused: Boolean,
    finished: Boolean,
    onSkip: () -> Unit,
    onPause: () -> Unit,
    onRestart: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val items = buildList {
                if (!finished) {
                    add(R.string.plan_skip_to_today to onSkip)
                    add((if (paused) R.string.plan_resume else R.string.plan_pause) to onPause)
                }
                add(R.string.plan_restart to onRestart)
                add(R.string.plan_archive to onArchive)
                add(R.string.plan_delete to onDelete)
            }
            for ((label, action) in items) {
                DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { open = false; action() })
            }
        }
    }
}

@Composable
private fun DayItem(row: DayRow, readings: String, onPlay: () -> Unit, onMarkComplete: () -> Unit, onMarkNotDone: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val (icon, description) = when (row.status) {
        DayStatus.COMPLETE -> Icons.Default.CheckCircle to R.string.plan_status_complete
        DayStatus.SKIPPED -> Icons.Default.Remove to R.string.plan_status_skipped
        DayStatus.IN_PROGRESS -> Icons.Default.Schedule to R.string.plan_status_started
        DayStatus.PENDING -> Icons.Default.RadioButtonUnchecked to (if (row.current) R.string.plan_status_current else null)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.plan_day, row.day)) },
        supportingContent = { Text(readings) },
        leadingContent = {
            Icon(
                icon,
                description?.let { stringResource(it) },
                tint = if (row.current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.play)) }, onClick = { menu = false; onPlay() })
                    if (row.status == DayStatus.COMPLETE || row.status == DayStatus.SKIPPED) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_mark_not_done)) }, onClick = { menu = false; onMarkNotDone() })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_mark_complete)) }, onClick = { menu = false; onMarkComplete() })
                    }
                }
            }
        },
        colors = if (row.current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
