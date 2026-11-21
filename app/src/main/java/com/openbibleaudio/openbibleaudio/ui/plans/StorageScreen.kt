package com.openbibleaudio.openbibleaudio.ui.plans

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.data.db.DownloadState
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodes
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloads
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeProgress
import com.openbibleaudio.openbibleaudio.podcasts.PodcastRepository
import com.openbibleaudio.openbibleaudio.podcasts.PodcastStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Space used by one enrollment's downloaded episodes. Shared episodes count in each enrollment. */
data class EnrollmentStorage(val enrollmentId: Long, val planName: String, val episodes: Int, val bytes: Long)

data class StorageUi(val enrollments: List<EnrollmentStorage>, val totalBytes: Long, val totalEpisodes: Int, val playedEpisodes: Int)

/** Plans → Storage (spec 0003 §8.3). */
@HiltViewModel
class StorageViewModel @Inject constructor(
    plans: PlanRepository,
    podcastDao: PodcastDao,
    planDao: PlanDao,
    private val downloads: EpisodeDownloads,
    private val storage: PodcastStorage,
) : ViewModel() {
    val ui: StateFlow<StorageUi?> = combine(plans.enrollments, podcastDao.observeAllEpisodes(), planDao.observeAllPins()) { enrollments, episodes, pins ->
        val byFeed = episodes.groupBy { it.feedId }
        val rows = enrollments.mapNotNull { s ->
            val podcast = s.plan.podcast ?: return@mapNotNull null
            val view = DayEpisodes.view(s.plan, s.enrollment.podcastCycle, byFeed[PodcastRepository.feedId(podcast)].orEmpty(), pins.filter { it.enrollmentId == s.enrollment.id })
            val p = EpisodeProgress.of(view, s.plan.dayCount)
            EnrollmentStorage(s.enrollment.id, s.plan.name, p.downloaded, p.downloadedBytes)
        }
        val downloaded = episodes.filter { it.downloadState == DownloadState.DOWNLOADED }
        StorageUi(rows, downloaded.sumOf { it.downloadedBytes ?: 0L }, downloaded.size, downloaded.count { it.completedAt != null })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _free = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val freeBytes: StateFlow<Long?> = _free

    init {
        viewModelScope.launch { _free.value = withContext(Dispatchers.IO) { storage.allocatableBytes() } }
    }

    fun delete(playedOnly: Boolean) = viewModelScope.launch {
        downloads.deleteDownloads(playedOnly)
        _free.value = withContext(Dispatchers.IO) { storage.allocatableBytes() }
    }
}

private enum class DeleteChoice { PLAYED, ALL }

@Composable
fun StorageScreen(viewModel: StorageViewModel, contentPadding: PaddingValues) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val free by viewModel.freeBytes.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    var confirm by rememberSaveable { mutableStateOf<DeleteChoice?>(null) }
    val state = ui ?: return

    Column(Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState())) {
        ListItem(
            headlineContent = { Text(pluralStringResource(R.plurals.storage_total, state.totalEpisodes, state.totalEpisodes, size(state.totalBytes))) },
            supportingContent = { free?.let { Text(stringResource(R.string.enroll_download_free, size(it))) } },
        )
        HorizontalDivider()
        if (state.enrollments.isEmpty()) {
            Text(stringResource(R.string.storage_none), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        for (e in state.enrollments) {
            ListItem(
                headlineContent = { Text(e.planName) },
                supportingContent = { Text(pluralStringResource(R.plurals.storage_enrollment, e.episodes, e.episodes, size(e.bytes))) },
            )
        }
        HorizontalDivider()
        Column(Modifier.padding(16.dp)) {
            OutlinedButton(onClick = { confirm = DeleteChoice.PLAYED }, enabled = state.playedEpisodes > 0) {
                Text(pluralStringResource(R.plurals.storage_delete_played, state.playedEpisodes, state.playedEpisodes))
            }
            OutlinedButton(onClick = { confirm = DeleteChoice.ALL }, enabled = state.totalEpisodes > 0, modifier = Modifier.padding(top = 8.dp)) {
                Text(stringResource(R.string.storage_delete_all))
            }
            Text(stringResource(R.string.storage_note), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }

    confirm?.let { choice ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(if (choice == DeleteChoice.PLAYED) R.string.storage_confirm_played else R.string.storage_confirm_all)) },
            text = { Text(stringResource(R.string.storage_note)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.delete(playedOnly = choice == DeleteChoice.PLAYED)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
