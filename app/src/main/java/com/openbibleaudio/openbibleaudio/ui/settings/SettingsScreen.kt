package com.openbibleaudio.openbibleaudio.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.playback.ContinuousPlayScope
import com.openbibleaudio.openbibleaudio.ui.browse.SettingsViewModel

private val SEEK_CHOICES = listOf(5_000L, 10_000L, 15_000L, 30_000L)
private val RETENTION_CHOICES = listOf(0, 1, 3, 7, 14, 30)
private val SPEED_CHOICES = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, contentPadding: PaddingValues, onAbout: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val s = settings ?: return
    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())) {
        Header(stringResource(R.string.settings_continuous_play))
        for ((scope, label) in listOf(ContinuousPlayScope.BOOK to R.string.settings_scope_book, ContinuousPlayScope.BIBLE to R.string.settings_scope_bible)) {
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                leadingContent = { RadioButton(selected = s.continuousPlayScope == scope, onClick = null) },
                modifier = Modifier.clickable { viewModel.setScope(scope) },
            )
        }
        HorizontalDivider()
        SeekRow(stringResource(R.string.settings_seek_back), s.seekBackMs, viewModel::setSeekBack)
        SeekRow(stringResource(R.string.settings_seek_forward), s.seekForwardMs, viewModel::setSeekForward)
        Text(stringResource(R.string.settings_seek_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Header(stringResource(R.string.settings_plans))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_continue_next_day)) },
            supportingContent = { Text(stringResource(R.string.settings_continue_next_day_summary)) },
            trailingContent = { Switch(checked = s.continueToNextDay, onCheckedChange = { viewModel.setContinueToNextDay(it) }) },
        )
        HorizontalDivider()
        // Spec 0003 §9.3: Podcasts.
        Header(stringResource(R.string.settings_podcasts))
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_unmetered_only)) },
            supportingContent = { Text(stringResource(R.string.settings_unmetered_only_summary)) },
            trailingContent = { Switch(checked = s.podcastUnmeteredOnly, onCheckedChange = { viewModel.setUnmeteredOnly(it) }) },
        )
        ChipRow(
            stringResource(R.string.settings_retention),
            RETENTION_CHOICES, s.podcastRetentionDays, enabled = !s.keepPlayedEpisodes,
            label = { if (it == 0) stringResource(R.string.settings_retention_now) else pluralStringResource(R.plurals.settings_days, it, it) },
        ) { viewModel.setRetentionDays(it) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_keep_played)) },
            supportingContent = { Text(stringResource(R.string.settings_keep_played_summary)) },
            trailingContent = { Switch(checked = s.keepPlayedEpisodes, onCheckedChange = { viewModel.setKeepPlayed(it) }) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_streaming)) },
            supportingContent = { Text(stringResource(R.string.settings_streaming_summary)) },
            trailingContent = { Switch(checked = s.allowEpisodeStreaming, onCheckedChange = { viewModel.setAllowStreaming(it) }) },
        )
        ChipRow(stringResource(R.string.settings_podcast_speed), SPEED_CHOICES, s.podcastSpeed, label = { stringResource(R.string.settings_speed, it) }) {
            viewModel.setPodcastSpeed(it)
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_verify_hashes)) },
            supportingContent = { Text(stringResource(R.string.settings_verify_hashes_summary)) },
            trailingContent = { Switch(checked = s.verifyHashesOnImport, onCheckedChange = { viewModel.setVerifyHashes(it) }) },
        )
        HorizontalDivider()
        ListItem(headlineContent = { Text(stringResource(R.string.about)) }, modifier = Modifier.clickable(onClick = onAbout))
    }
}

@Composable
private fun SeekRow(label: String, value: Long, onChange: (Long) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (ms in SEEK_CHOICES) {
                FilterChip(
                    selected = value == ms,
                    onClick = { onChange(ms) },
                    label = { Text(stringResource(R.string.settings_seconds, (ms / 1000).toInt())) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun <T> ChipRow(title: String, choices: List<T>, value: T, enabled: Boolean = true, label: @Composable (T) -> String, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow {
            for (c in choices) {
                FilterChip(
                    selected = value == c,
                    onClick = { onChange(c) },
                    enabled = enabled,
                    label = { Text(label(c)) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
}

/** Each version's attribution (spec 0001 §4.3, spec 0002 §8). */
@Composable
fun AboutScreen(viewModel: SettingsViewModel, contentPadding: PaddingValues) {
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())) {
        Header(stringResource(R.string.app_name))
        Header(stringResource(R.string.about_versions))
        for (v in versions) {
            ListItem(
                headlineContent = { Text("${v.abbreviation} — ${v.name}") },
                supportingContent = {
                    Column {
                        v.narrator?.let { Text(it) }
                        Text(v.attribution ?: stringResource(R.string.about_no_attribution))
                    }
                },
            )
            HorizontalDivider()
        }
    }
}
