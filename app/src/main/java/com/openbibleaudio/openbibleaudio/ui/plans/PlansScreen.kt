package com.openbibleaudio.openbibleaudio.ui.plans

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import com.openbibleaudio.openbibleaudio.plans.PlanProgress
import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.podcasts.DownloadPlanner
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloader
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.playback.BookTitles
import com.openbibleaudio.openbibleaudio.plans.PlanCard
import com.openbibleaudio.openbibleaudio.plans.PlanImportResult
import com.openbibleaudio.openbibleaudio.playback.VersionInfo
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Plan files are JSON, but file pickers often label them as plain text or binary. */
private val PLAN_MIME_TYPES = arrayOf("application/json", "text/plain", "application/octet-stream")

@Composable
fun PlansScreen(
    viewModel: PlansViewModel,
    contentPadding: PaddingValues,
    onOpen: (enrollmentId: Long) -> Unit,
    onPlay: (mediaId: String) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onFileChosen(uri)
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        val state = ui
        when {
            state == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.cards.isEmpty() && state.plans.isEmpty() -> EmptyPlans(Modifier.align(Alignment.Center).padding(32.dp))
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.cards.isNotEmpty()) item { SectionHeader(stringResource(R.string.plans_yours)) }
                items(state.cards, key = { "e${it.enrollmentId}" }) { card ->
                    PlanCardView(card, onOpen = { onOpen(card.enrollmentId) }, onPlay = onPlay)
                }
                if (state.plans.isNotEmpty()) item { SectionHeader(stringResource(R.string.plans_imported)) }
                items(state.plans, key = { "p${it.id}/${it.version}" }) { plan ->
                    ListItem(
                        headlineContent = { Text(plan.name) },
                        trailingContent = { TextButton(onClick = { viewModel.showEnroll(plan) }) { Text(stringResource(R.string.plans_enroll)) } },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { pickFile.launch(PLAN_MIME_TYPES) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.plans_import)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    when (val d = dialog) {
        null -> Unit
        PlansDialog.Importing -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.plan_importing)) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        is PlansDialog.ImportProblem -> ImportProblemDialog(d.result, viewModel::dismissDialog)
        is PlansDialog.Enroll -> EnrollDialog(d, ui?.versions.orEmpty(), viewModel::dismissDialog) { versionId, start, cycle, mode, startDay ->
            viewModel.enroll(d.plan, versionId, start, cycle, mode, startDay)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EmptyPlans(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.plans_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(8.dp))
        Text(stringResource(R.string.plans_empty_body), style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Day 47 · 3 days behind", "Day 47 of 365" or "Finished" (the same text Auto shows). */
@Composable
internal fun progressLine(card: PlanCard): String {
    val day = card.currentDay ?: return stringResource(R.string.plan_card_finished, card.dayCount)
    return when (val b = card.badge) {
        is PlanCard.Badge.Behind -> pluralStringResource(R.plurals.plan_behind, b.days, b.days, day)
        is PlanCard.Badge.Ahead -> pluralStringResource(R.plurals.plan_ahead, b.days, b.days, day)
        null -> stringResource(R.string.plan_day_of, day, card.dayCount)
    }
}

@Composable
private fun PlanCardView(card: PlanCard, onOpen: () -> Unit, onPlay: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(16.dp)) {
            Text(card.planName, style = MaterialTheme.typography.titleMedium)
            Text(progressLine(card), style = MaterialTheme.typography.bodyMedium)
            card.keepGoingDay?.let {
                Text(stringResource(R.string.plan_card_keep_going, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.plan_card_done, card.daysDone, card.dayCount), style = MaterialTheme.typography.bodySmall)
            card.episodes?.let {
                Text(pluralStringResource(R.plurals.plan_card_episodes, it.matched, it.downloaded, it.matched), style = MaterialTheme.typography.bodySmall)
            }
            if (card.paused) AssistChip(onClick = onOpen, label = { Text(stringResource(R.string.plan_paused)) })
            if (!card.finished) {
                Button(onClick = { onPlay("plan/${card.enrollmentId}/today") }, modifier = Modifier.padding(top = 8.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text(stringResource(R.string.plan_play_today), Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ImportProblemDialog(result: PlanImportResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.plan_import_problem)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (result) {
                    is PlanImportResult.AlreadyImported -> Text(stringResource(R.string.plan_already_imported, result.name, result.version))
                    is PlanImportResult.Unreadable -> Text(result.message)
                    is PlanImportResult.Invalid -> {
                        Text(pluralStringResource(R.plurals.plan_invalid, result.errorCount, result.errorCount))
                        result.shown.forEach { Text("• ${it.pointer?.let { p -> "$p: " }.orEmpty()}${it.message}", style = MaterialTheme.typography.bodySmall) }
                    }
                    is PlanImportResult.Imported -> Unit
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnrollDialog(
    dialog: PlansDialog.Enroll,
    versions: List<VersionInfo>,
    onDismiss: () -> Unit,
    onEnroll: (versionId: String?, startDate: LocalDate, podcastCycle: Int?, downloadMode: DownloadMode, startDay: Int) -> Unit,
) {
    var versionId by rememberSaveable { mutableStateOf<String?>(null) }
    // The listener's cycle choice; until they pick one, the plan's default applies.
    var pickedCycle by rememberSaveable { mutableStateOf<Int?>(null) }
    var cyclePicked by rememberSaveable { mutableStateOf(false) }
    val ready = dialog.podcast as? EnrollPodcast.Ready
    val cycle = when {
        ready?.cycleKind == null -> null
        cyclePicked -> pickedCycle
        else -> ready.defaultKey
    }
    val cycleOption = ready?.let { r -> if (r.cycleKind == null) r.cycles.firstOrNull() else r.cycles.firstOrNull { it.key == cycle } }
    // Like the cycle: the default (from size and free space) until the listener picks one.
    var pickedMode by rememberSaveable { mutableStateOf<DownloadMode?>(null) }
    val mode = pickedMode
        ?: cycleOption?.let { DownloadPlanner.defaultMode(it.totalBytes, ready.freeBytes) }
        ?: DownloadMode.ROLLING
    var startEpochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val start = LocalDate.ofEpochDay(startEpochDay)
    // Joining part-way (spec 0003 §6.1): days before it count as done, and it becomes today's day.
    var startDayText by rememberSaveable { mutableStateOf("1") }
    val startDay = startDayText.toIntOrNull()?.takeIf { it in 1..dialog.dayCount }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.enroll_title, dialog.plan.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                dialog.notes.forEach { Text(it.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
                // Only worth asking when there's a choice (spec 0003 §6.1).
                if (versions.size > 1) {
                    Text(stringResource(R.string.enroll_version), style = MaterialTheme.typography.titleSmall)
                    VersionOption(stringResource(R.string.enroll_version_default), versionId == null) { versionId = null }
                    versions.forEach { v -> VersionOption("${v.abbreviation} — ${v.name}", versionId == v.versionId) { versionId = v.versionId } }
                }
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.enroll_start, start.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
                }
                Text(stringResource(R.string.enroll_start_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                StartDayField(startDayText, startDay, dialog.dayCount, modifier = Modifier.padding(top = 8.dp)) { text ->
                    startDayText = text
                    text.toIntOrNull()?.takeIf { it in 1..dialog.dayCount }?.let {
                        startEpochDay = PlanProgress.startDateFor(it, LocalDate.now()).toEpochDay()
                    }
                }
                dialog.podcast?.let { podcast ->
                    EnrollPodcastSection(podcast, cycle) { key ->
                        pickedCycle = key
                        cyclePicked = true
                    }
                }
                if (ready != null && cycleOption != null) {
                    val past = ready.cycleKind != null && cycleOption.key != ready.cycles.first().key
                    DownloadSection(cycleOption, ready.freeBytes, past, mode) { pickedMode = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEnroll(versionId, start, cycle, mode, startDay ?: 1) },
                // Wait for the feed check so the default cycle is known (it fails fast offline).
                enabled = dialog.podcast !is EnrollPodcast.Loading && startDay != null,
            ) { Text(stringResource(R.string.enroll_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (picking) {
        // DatePicker works in UTC millis at midnight.
        val state = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { startEpochDay = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }
                    picking = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

/** The podcast part of the enroll dialog: which cycle's episodes to follow (spec 0003 §7.2). */
@Composable
private fun EnrollPodcastSection(podcast: EnrollPodcast, selected: Int?, onSelect: (Int?) -> Unit) {
    Text(stringResource(R.string.enroll_podcast), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    when (podcast) {
        EnrollPodcast.Loading -> Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.enroll_podcast_checking), style = MaterialTheme.typography.bodySmall)
        }
        is EnrollPodcast.Failed -> Text(
            stringResource(R.string.enroll_podcast_failed, podcast.message),
            style = MaterialTheme.typography.bodySmall,
        )
        is EnrollPodcast.Ready -> {
            podcast.refreshError?.let {
                Text(stringResource(R.string.enroll_podcast_stale, it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
            }
            val kind = podcast.cycleKind
            when {
                podcast.cycles.isEmpty() -> Text(stringResource(R.string.enroll_podcast_none), style = MaterialTheme.typography.bodySmall)
                kind == null -> Text(coverageText(podcast.cycles.first()), style = MaterialTheme.typography.bodySmall)
                else -> {
                    podcast.cycles.forEach { c ->
                        VersionOption("${cycleLabel(kind, c)} — ${coverageText(c)}", selected == c.key) { onSelect(c.key) }
                    }
                    podcast.cycles.firstOrNull { it.key == selected }?.takeIf { !it.complete }?.let { c ->
                        Text(
                            pluralStringResource(R.plurals.enroll_podcast_partial, c.matched, c.matched, c.dayCount, cycleLabel(kind, c)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** How episodes are fetched ahead of time, with sizes and free space (spec 0003 §8.1). */
@Composable
private fun DownloadSection(c: CycleOption, freeBytes: Long, pastCycle: Boolean, selected: DownloadMode, onSelect: (DownloadMode) -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Text(stringResource(R.string.enroll_download), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
    VersionOption(pluralStringResource(R.plurals.enroll_download_all, c.matched, c.matched, size(c.totalBytes)), selected == DownloadMode.ALL) {
        onSelect(DownloadMode.ALL)
    }
    val window = minOf(EnrollmentEntity.DEFAULT_ROLLING_DAYS, c.dayCount)
    VersionOption(pluralStringResource(R.plurals.enroll_download_rolling, window, window, size(c.rollingBytes)), selected == DownloadMode.ROLLING) {
        onSelect(DownloadMode.ROLLING)
    }
    VersionOption(stringResource(R.string.enroll_download_off), selected == DownloadMode.OFF) { onSelect(DownloadMode.OFF) }
    Text(stringResource(R.string.enroll_download_free, size(freeBytes)), style = MaterialTheme.typography.bodySmall)
    if (selected == DownloadMode.ALL && c.totalBytes + EpisodeDownloader.SPACE_MARGIN > freeBytes) {
        Text(stringResource(R.string.enroll_download_no_space), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    if (pastCycle) {
        // Older cycles can leave the feed, and the publisher may stop serving them (spec 0003 §7.3).
        Text(stringResource(R.string.enroll_download_past), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

/** "Season 7 · 2025" (itunes:season), "Cycle 7 · 2025" (title regex) or "2025" (publication year). */
@Composable
private fun cycleLabel(kind: CycleKey, c: CycleOption): String {
    val key = c.key ?: 0
    return when (kind) {
        CycleKey.PUB_DATE_YEAR -> key.toString()
        CycleKey.ITUNES_SEASON ->
            if (c.year != null) stringResource(R.string.cycle_season_year, key, c.year) else stringResource(R.string.cycle_season, key)
        CycleKey.TITLE_REGEX ->
            if (c.year != null) stringResource(R.string.cycle_generic_year, key, c.year) else stringResource(R.string.cycle_generic, key)
    }
}

@Composable
private fun coverageText(c: CycleOption): String =
    if (c.complete) pluralStringResource(R.plurals.cycle_coverage_all, c.dayCount, c.dayCount) else stringResource(R.string.cycle_coverage, c.matched, c.dayCount)

@Composable
private fun VersionOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

/** "Genesis 1–3, Psalms 90", using the app's book names. Pass a remembered [books]. */
internal fun readingsText(context: Context, books: BookTitles, readings: List<Reading>): String =
    readings.joinToString(", ") { r ->
        if (r.last == r.from) {
            context.getString(R.string.plan_reading_chapter, books.book(r.book), r.from)
        } else {
            context.getString(R.string.plan_reading_range, books.book(r.book), r.from, r.last)
        }
    }
