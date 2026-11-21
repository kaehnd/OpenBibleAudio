package com.openbibleaudio.openbibleaudio.ui.plans

import android.content.Context
import android.net.Uri
import com.openbibleaudio.core.model.pack.Issue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.db.PlanEntity
import com.openbibleaudio.openbibleaudio.plans.PlanCard
import com.openbibleaudio.openbibleaudio.plans.PlanImportResult
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import com.openbibleaudio.openbibleaudio.playback.VersionInfo
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodes
import com.openbibleaudio.openbibleaudio.podcasts.DownloadPlanner
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeProgress
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloads
import com.openbibleaudio.openbibleaudio.podcasts.FeedRefreshResult
import com.openbibleaudio.openbibleaudio.podcasts.PodcastStorage
import com.openbibleaudio.openbibleaudio.podcasts.FeedRefreshWorker
import com.openbibleaudio.openbibleaudio.podcasts.PodcastRepository
import androidx.work.WorkManager
import com.openbibleaudio.core.model.plan.CycleKey
import com.openbibleaudio.core.podcast.Cycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDate
import javax.inject.Inject

data class PlansUi(
    val cards: List<PlanCard>,
    /** The newest version of each imported plan. */
    val plans: List<PlanEntity>,
    /** READY Bible versions, for the enroll dialog. */
    val versions: List<VersionInfo>,
)

/** A plan version to enroll in. */
data class PlanChoice(val id: String, val version: Int, val name: String)

sealed interface PlansDialog {
    data object Importing : PlansDialog

    /** Import didn't produce a new plan: invalid, unreadable, or already imported. */
    data class ImportProblem(val result: PlanImportResult) : PlansDialog

    /**
     * [notes] are non-error findings from import, e.g. partial coverage. [podcast] is null for a
     * plan without a podcast.
     */
    data class Enroll(val plan: PlanChoice, val notes: List<Issue> = emptyList(), val podcast: EnrollPodcast? = null, val dayCount: Int = 1) : PlansDialog
}

/**
 * One podcast cycle as the enroll dialog lists it (spec 0003 §7.2), with the download sizes for
 * ALL ([totalBytes]) and for the first ROLLING window ([rollingBytes]) of a new enrollment.
 */
data class CycleOption(
    val key: Int?,
    val year: Int?,
    val matched: Int,
    val dayCount: Int,
    val totalBytes: Long,
    val rollingBytes: Long,
) {
    val complete: Boolean get() = matched == dayCount

    companion object {
        fun from(c: Cycle, rollingDays: Int = EnrollmentEntity.DEFAULT_ROLLING_DAYS) = CycleOption(
            c.key, c.year, c.matchedDays, c.dayCount,
            totalBytes = DownloadPlanner.estimateBytes(c.byDay.values.map { it.item.enclosureLength }),
            rollingBytes = DownloadPlanner.estimateBytes((1..c.dayCount).take(rollingDays).mapNotNull { c.byDay[it]?.item?.enclosureLength }),
        )
    }
}

/** The podcast part of the enroll dialog. */
sealed interface EnrollPodcast {
    data object Loading : EnrollPodcast

    /**
     * [cycles] is newest first. [cycleKind] is null for a plan without a cycle block (then
     * [cycles] has at most one entry). [refreshError] is set when the feed couldn't be fetched
     * just now and the list comes from an earlier snapshot. [freeBytes] sizes the download choice.
     */
    data class Ready(
        val cycles: List<CycleOption>,
        val defaultKey: Int?,
        val cycleKind: CycleKey?,
        val refreshError: String?,
        val freeBytes: Long,
    ) : EnrollPodcast

    /** No snapshot and the feed couldn't be fetched. Enrolling still works; matching happens later. */
    data class Failed(val message: String) : EnrollPodcast
}

/** The Plans tab (spec 0003 §9.3): enrollment cards, import, and enroll. */
@HiltViewModel
class PlansViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plans: PlanRepository,
    private val podcasts: PodcastRepository,
    private val downloads: EpisodeDownloads,
    private val storage: PodcastStorage,
    private val workManager: WorkManager,
    podcastDao: PodcastDao,
    planDao: PlanDao,
    library: LibraryRepository,
) : ViewModel() {
    init {
        // Opening Plans refreshes feeds older than 12 h (spec 0003 §7.1).
        viewModelScope.launch {
            if (plans.activePodcastPlans().isNotEmpty()) {
                FeedRefreshWorker.schedule(workManager)
                podcasts.refreshActive()
                downloads.startAll()
            }
        }
    }

    val ui: StateFlow<PlansUi?> = combine(
        plans.enrollments, plans.plans, library.snapshot.map { it.versions }, podcastDao.observeAllEpisodes(), planDao.observeAllPins(),
    ) { enrollments, imported, versions, episodes, pins ->
        val byFeed = episodes.groupBy { it.feedId }
        val cards = enrollments.map { s ->
            // Episode downloads on the card (spec 0003 §9.3).
            val progress = s.plan.podcast?.let { p ->
                val view = DayEpisodes.view(s.plan, s.enrollment.podcastCycle, byFeed[PodcastRepository.feedId(p)].orEmpty(), pins.filter { it.enrollmentId == s.enrollment.id })
                EpisodeProgress.of(view, s.plan.dayCount)
            }
            PlanCard.from(s, progress)
        }
        PlansUi(cards, imported, versions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _dialog = MutableStateFlow<PlansDialog?>(null)
    val dialog: StateFlow<PlansDialog?> = _dialog.asStateFlow()

    fun onFileChosen(uri: Uri) {
        _dialog.value = PlansDialog.Importing
        viewModelScope.launch {
            val result = try {
                val input = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri) }
                    ?: throw FileNotFoundException(uri.toString())
                plans.importPlan(input)
            } catch (e: Exception) {
                PlanImportResult.Unreadable(e.message ?: e.toString())
            }
            // A freshly imported plan goes straight to Enroll (spec 0003 §6.1).
            _dialog.value = when (result) {
                is PlanImportResult.Imported -> {
                    openEnroll(PlanChoice(result.id, result.version, result.name), result.notes)
                    return@launch
                }
                else -> PlansDialog.ImportProblem(result)
            }
        }
    }

    fun showEnroll(plan: PlanEntity) {
        viewModelScope.launch { openEnroll(PlanChoice(plan.id, plan.version, plan.name)) }
    }

    /** Shows the dialog straight away; for a podcast plan, fetches the feed to list its cycles. */
    private suspend fun openEnroll(choice: PlanChoice, notes: List<Issue> = emptyList()) {
        val plan = plans.plan(choice.id, choice.version)
        val podcast = plan?.podcast
        _dialog.value = PlansDialog.Enroll(choice, notes, if (podcast != null) EnrollPodcast.Loading else null, plan?.dayCount ?: 1)
        if (plan == null || podcast == null) return

        val refresh = podcasts.refresh(plan)
        val cycles = podcasts.cycles(plan)
        val error = (refresh as? FeedRefreshResult.Failed)?.message
        val state = if (cycles.isEmpty() && error != null) {
            EnrollPodcast.Failed(error)
        } else {
            val free = withContext(Dispatchers.IO) { storage.allocatableBytes() }
            EnrollPodcast.Ready(cycles.map { CycleOption.from(it) }, podcasts.defaultCycle(plan, cycles)?.key, podcast.cycle?.key, error, free)
        }
        // Only if the same dialog is still open.
        _dialog.update { d -> if (d is PlansDialog.Enroll && d.plan == choice) d.copy(podcast = state) else d }
    }

    fun enroll(plan: PlanChoice, versionId: String?, startDate: LocalDate, podcastCycle: Int?, downloadMode: DownloadMode, startDay: Int = 1) {
        _dialog.value = null
        viewModelScope.launch {
            val id = plans.enroll(
                plan.id, plan.version, versionId = versionId, startDate = startDate,
                podcastCycle = podcastCycle, downloadMode = downloadMode, startDay = startDay,
            )
            if (plans.plan(plan.id, plan.version)?.podcast != null) {
                FeedRefreshWorker.schedule(workManager)
                if (downloadMode != DownloadMode.OFF) downloads.start(id)
            }
        }
    }

    fun dismissDialog() {
        _dialog.value = null
    }
}
