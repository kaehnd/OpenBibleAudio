package com.openbibleaudio.openbibleaudio.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openbibleaudio.openbibleaudio.R
import java.util.Locale

@Composable
fun NowPlayingScreen(state: PlayerUiState, viewModel: PlayerViewModel, contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!state.hasItem) {
            Text(stringResource(R.string.nothing_playing), style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        Text(state.title.orEmpty(), style = MaterialTheme.typography.headlineMedium)
        state.subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(32.dp))

        var dragging by remember { mutableStateOf<Float?>(null) }
        val duration = state.durationMs.coerceAtLeast(1)
        Slider(
            value = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { viewModel.seekTo((it * duration).toLong()) }
                dragging = null
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(state.positionMs), style = MaterialTheme.typography.bodySmall)
            Text("-" + formatTime((state.durationMs - state.positionMs).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = viewModel::previous) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous_chapter)) }
            IconButton(onClick = { viewModel.seekBack() }) { Icon(Icons.Default.Replay, stringResource(R.string.settings_seek_back)) }
            FilledIconButton(onClick = viewModel::playPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = { viewModel.seekForward() }) { Icon(Icons.Default.Forward30, stringResource(R.string.settings_seek_forward)) }
            IconButton(onClick = { viewModel.next() }, enabled = state.hasNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.next_chapter)) }
        }
        Spacer(Modifier.height(16.dp))
        AssistChip(onClick = viewModel::cycleSpeed, label = { Text(stringResource(R.string.speed, formatSpeed(state.speed))) })
    }
}

@Composable
fun MiniPlayer(state: PlayerUiState, viewModel: PlayerViewModel, onOpen: () -> Unit) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
            }
            IconButton(onClick = viewModel::playPause) {
                Icon(
                    if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                )
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) String.format(Locale.ROOT, "%.1f", speed) else String.format(Locale.ROOT, "%.2f", speed).trimEnd('0')
