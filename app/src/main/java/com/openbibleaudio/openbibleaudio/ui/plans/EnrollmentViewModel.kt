package com.openbibleaudio.openbibleaudio.ui.plans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.ManualPinEntity
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PodcastDao
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.EnrollmentSummary
import com.openbibleaudio.openbibleaudio.plans.PlanCard
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodeInfo
import com.openbibleaudio.openbibleaudio.podcasts.DayEpisodes
import com.openbibleaudio.openbibleaudio.podcasts.EnrollmentEpisodeView
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeDownloads
import com.openbibleaudio.openbibleaudio.podcasts.EpisodeProgress
import com.openbibleaudio.openbibleaudio.podcasts.PodcastRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** [episode] is null for a plan without a podcast. */
data class DayRow(val day: Int, val readings: List<Reading>, val status: DayStatus, val current: Boolean, val episode: DayEpisodeInfo? = null)

/** An episode the pin dialog offers (spec 0003 §7.4). */
data class PinCandidate(val guidHash: String, val title: String, val pubDate: Long)

/** The enrollment's podcast side: download mode and progress, and the pin dialog's lists. */
data class EnrollmentPodcastUi(
    val mode: DownloadMode,
    val rollingDays: Int,
    val progress: EpisodeProgress,
    /** The cycle's episodes no day uses, newest first. */
    val unassigned: List<PinCandidate>,
    /** Every episode in the feed, newest first ("Show all"). */
    val all: List<PinCandidate>,
)

/** [gone] is true once the enrollment was deleted or archived, so the screen closes. */
data class EnrollmentUi(val card: PlanCard, val days: List<DayRow>, val podcast: EnrollmentPodcastUi? = null, val gone: Boolean = false)

/** One enrollment: its day list, day actions and episode actions (spec 0003 §9.3, §6.3, §7.4). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val plans: PlanRepository,
    private val planDao: PlanDao,
    private val podcastDao: PodcastDao,
    private val downloads: EpisodeDownloads,
) : ViewModel() {
    val enrollmentId: Long = checkNotNull(savedState.get<Long>("enrollmentId"))

    val ui: StateFlow<EnrollmentUi?> = plans.enrollments
        .map { list -> list.firstOrNull { it.enrollment.id == enrollmentId } }
        .flatMapLatest { s ->
            val podcast = s?.plan?.podcast
            when {
                s == null -> flowOf(EnrollmentUi(PlanCard(enrollmentId, "", null, 0, 0, null, null, false), emptyList(), gone = true))
                podcast == null -> flowOf(build(s, null))
                else -> {
                    val feedId = PodcastRepository.feedId(podcast)
                    combine(podcastDao.observeAllEpisodes(), planDao.observeAllPins()) { rows, pins ->
                        build(s, DayEpisodes.view(s.plan, s.enrollment.podcastCycle, rows.filter { it.feedId == feedId }, pins.filter { it.enrollmentId == enrollmentId }))
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun build(s: EnrollmentSummary, view: EnrollmentEpisodeView?): EnrollmentUi {
        val days = s.plan.days.map { d ->
            DayRow(
                d.day, d.readings, s.statuses[d.day] ?: DayStatus.PENDING, current = d.day == s.position.currentDay,
                episode = view?.let { DayEpisodeInfo.of(d.day, it) },
            )
        }
        val podcast = view?.let { v ->
            val progress = EpisodeProgress.of(v, s.plan.dayCount)
            EnrollmentPodcastUi(
                s.enrollment.downloadMode, s.enrollment.rollingDays, progress,
                unassigned = v.unassigned.map { PinCandidate(it.guidHash, it.title, it.pubDate) },
                all = v.all.map { PinCandidate(it.guidHash, it.title, it.pubDate) },
            )
        }
        return EnrollmentUi(PlanCard.from(s, podcast?.progress), days, podcast)
    }

    fun markComplete(day: Int) = viewModelScope.launch { plans.markComplete(enrollmentId, day) }
    fun markNotDone(day: Int) = viewModelScope.launch { plans.markNotDone(enrollmentId, day) }
    fun skipToToday() = viewModelScope.launch { plans.skipToToday(enrollmentId) }
    fun restart() = viewModelScope.launch { plans.restart(enrollmentId) }
    fun matchSchedule() = viewModelScope.launch { plans.matchSchedule(enrollmentId) }
    fun startFromDay(day: Int) = viewModelScope.launch {
        plans.startFromDay(enrollmentId, day)
        // The rolling window moves to the new day, and the days now done may free their episodes.
        downloads.applyRetention()
        downloads.start(enrollmentId)
    }
    fun setPaused(paused: Boolean) = viewModelScope.launch { plans.setPaused(enrollmentId, paused) }
    fun archive() = viewModelScope.launch { plans.archive(enrollmentId) }
    fun delete() = viewModelScope.launch {
        plans.unenroll(enrollmentId)
        // Files no other enrollment uses go now (spec 0003 §8.4).
        downloads.applyRetention()
    }

    // --- Episodes (spec 0003 §7.4, §8.1, §9.3) -----------------------------------------------

    fun setDownloadMode(mode: DownloadMode) = viewModelScope.launch {
        downloads.setMode(enrollmentId, mode, ui.value?.podcast?.rollingDays ?: com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity.DEFAULT_ROLLING_DAYS)
    }

    fun download(info: DayEpisodeInfo) = viewModelScope.launch {
        if (info.feedId != null && info.guidHash != null) downloads.requestDownload(info.feedId, info.guidHash)
    }

    fun deleteEpisode(info: DayEpisodeInfo) = viewModelScope.launch {
        if (info.feedId != null && info.guidHash != null) downloads.deleteEpisode(info.feedId, info.guidHash)
    }

    fun pin(day: Int, guidHash: String) = viewModelScope.launch {
        val s = plans.summary(enrollmentId) ?: return@launch
        val podcast = s.plan.podcast ?: return@launch
        planDao.upsertPin(ManualPinEntity(enrollmentId, day, PodcastRepository.feedId(podcast), guidHash))
        downloads.start(enrollmentId)
    }

    fun unpin(day: Int) = viewModelScope.launch {
        planDao.deletePin(enrollmentId, day)
        downloads.start(enrollmentId)
    }
}
