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
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodeInfo
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodeStatus
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.RadioButton
import androidx.compose.ui.res.pluralStringResource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    var choosingMode by rememberSaveable { mutableStateOf(false) }
    var pinningDay by rememberSaveable { mutableStateOf<Int?>(null) }
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
                        hasPodcast = state.podcast != null,
                        onDownloadMode = { choosingMode = true },
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
                state.podcast?.let { p ->
                    Text(
                        pluralStringResource(R.plurals.plan_episodes_line, p.progress.matched, p.progress.downloaded, p.progress.matched, modeLabel(p.mode, p.rollingDays)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
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
                mode = state.podcast?.mode,
                onPlay = { onPlay("plan/$e/day/${row.day}") },
                onMarkComplete = { viewModel.markComplete(row.day) },
                onMarkNotDone = { viewModel.markNotDone(row.day) },
                onDownload = { row.episode?.let(viewModel::download) },
                onDeleteEpisode = { row.episode?.let(viewModel::deleteEpisode) },
                onPin = { pinningDay = row.day },
                onUnpin = { viewModel.unpin(row.day) },
            )
        }
    }

    val podcast = state.podcast
    if (choosingMode && podcast != null) {
        DownloadModeDialog(podcast.mode, podcast.rollingDays, onDismiss = { choosingMode = false }) {
            choosingMode = false
            viewModel.setDownloadMode(it)
        }
    }
    val pinDay = pinningDay
    if (pinDay != null && podcast != null) {
        PinDialog(pinDay, podcast, onDismiss = { pinningDay = null }) { guidHash ->
            pinningDay = null
            viewModel.pin(pinDay, guidHash)
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
    hasPodcast: Boolean,
    onDownloadMode: () -> Unit,
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
                if (hasPodcast) add(R.string.plan_download_mode to onDownloadMode)
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
private fun DayItem(
    row: DayRow,
    readings: String,
    mode: DownloadMode?,
    onPlay: () -> Unit,
    onMarkComplete: () -> Unit,
    onMarkNotDone: () -> Unit,
    onDownload: () -> Unit,
    onDeleteEpisode: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (icon, description) = when (row.status) {
        DayStatus.COMPLETE -> Icons.Default.CheckCircle to R.string.plan_status_complete
        DayStatus.SKIPPED -> Icons.Default.Remove to R.string.plan_status_skipped
        DayStatus.IN_PROGRESS -> Icons.Default.Schedule to R.string.plan_status_started
        DayStatus.PENDING -> Icons.Default.RadioButtonUnchecked to (if (row.current) R.string.plan_status_current else null)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.plan_day, row.day)) },
        supportingContent = {
            Column {
                Text(readings)
                row.episode?.let { ep ->
                    Text(
                        episodeLine(ep),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ep.needsAttention || ep.status == DayEpisodeStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
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
                    val done = row.status == DayStatus.COMPLETE || row.status == DayStatus.SKIPPED
                    if (done) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_mark_not_done)) }, onClick = { menu = false; onMarkNotDone() })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.plan_mark_complete)) }, onClick = { menu = false; onMarkComplete() })
                    }
                    row.episode?.let { ep ->
                        if (ep.status == DayEpisodeStatus.NOT_DOWNLOADED || ep.status == DayEpisodeStatus.FAILED) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.episode_download)) }, onClick = { menu = false; onDownload() })
                        }
                        // An open day in an automatic mode would just download it again (spec 0003 §8.1).
                        if (ep.status == DayEpisodeStatus.DOWNLOADED && (done || mode == DownloadMode.OFF)) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.episode_delete)) }, onClick = { menu = false; onDeleteEpisode() })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.episode_pin)) }, onClick = { menu = false; onPin() })
                        if (ep.pinned) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.episode_unpin)) }, onClick = { menu = false; onUnpin() })
                        }
                    }
                }
            }
        },
        colors = if (row.current) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

/** The day's episode in one line: its state, and why it needs attention (spec 0003 §7.4). */
@Composable
private fun episodeLine(ep: DayEpisodeInfo): String {
    val state = when (ep.status) {
        DayEpisodeStatus.NONE -> return stringResource(R.string.episode_line_none)
        DayEpisodeStatus.NOT_DOWNLOADED -> stringResource(R.string.episode_line_not_downloaded)
        DayEpisodeStatus.QUEUED -> stringResource(R.string.episode_line_queued)
        DayEpisodeStatus.DOWNLOADING -> stringResource(R.string.episode_line_downloading)
        DayEpisodeStatus.DOWNLOADED -> stringResource(R.string.episode_line_downloaded)
        DayEpisodeStatus.FAILED -> stringResource(R.string.episode_line_failed, ep.error ?: "")
    }
    return when {
        ep.pinned -> stringResource(R.string.episode_line_pinned, state, ep.title.orEmpty())
        ep.duplicated -> stringResource(R.string.episode_line_duplicated, state)
        else -> state
    }
}

@Composable
private fun modeLabel(mode: DownloadMode, rollingDays: Int): String = when (mode) {
    DownloadMode.ALL -> stringResource(R.string.download_mode_all)
    DownloadMode.ROLLING -> pluralStringResource(R.plurals.download_mode_rolling, rollingDays, rollingDays)
    DownloadMode.OFF -> stringResource(R.string.download_mode_off)
}

/** Change how episodes are fetched ahead of time (spec 0003 §8.1). Nothing is deleted on switching. */
@Composable
private fun DownloadModeDialog(current: DownloadMode, rollingDays: Int, onDismiss: () -> Unit, onChoose: (DownloadMode) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_download_mode)) },
        text = {
            Column {
                for (mode in DownloadMode.entries) {
                    Row(
                        Modifier.fillMaxWidth().clickable { onChoose(mode) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Text(modeLabel(mode, rollingDays), Modifier.padding(start = 8.dp))
                    }
                }
                Text(stringResource(R.string.download_mode_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Pick the episode for [day] (spec 0003 §7.4): the cycle's episodes that no day uses, newest
 * first, and the whole feed behind "Show all".
 */
@Composable
private fun PinDialog(day: Int, podcast: EnrollmentPodcastUi, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(podcast.unassigned.isEmpty()) }
    val candidates = if (showAll) podcast.all else podcast.unassigned
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.episode_pin_title, day)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (candidates.isEmpty()) item { Text(stringResource(R.string.episode_pin_none)) }
                items(candidates, key = { it.guidHash }) { c ->
                    ListItem(
                        headlineContent = { Text(c.title) },
                        supportingContent = { Text(Instant.ofEpochMilli(c.pubDate).atZone(ZoneId.systemDefault()).toLocalDate().format(dates)) },
                        modifier = Modifier.clickable { onPick(c.guidHash) },
                    )
                }
            }
        },
        confirmButton = {
            if (!showAll) TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.episode_pin_show_all)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
