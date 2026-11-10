package com.openbibleaudio.openbibleaudio.ui.plans

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.core.model.plan.Reading
import com.openbibleaudio.openbibleaudio.plans.DayStatus
import com.openbibleaudio.openbibleaudio.plans.PlanCard
import com.openbibleaudio.openbibleaudio.plans.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DayRow(val day: Int, val readings: List<Reading>, val status: DayStatus, val current: Boolean)

/** [gone] is true once the enrollment was deleted or archived, so the screen closes. */
data class EnrollmentUi(val card: PlanCard, val days: List<DayRow>, val gone: Boolean = false)

/** One enrollment: its day list and day actions (spec 0003 §9.3, §6.3). */
@HiltViewModel
class EnrollmentViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val plans: PlanRepository,
) : ViewModel() {
    val enrollmentId: Long = checkNotNull(savedState.get<Long>("enrollmentId"))

    val ui: StateFlow<EnrollmentUi?> = plans.enrollments.map { list ->
        val s = list.firstOrNull { it.enrollment.id == enrollmentId }
            ?: return@map EnrollmentUi(PlanCard(enrollmentId, "", null, 0, 0, null, null, false), emptyList(), gone = true)
        EnrollmentUi(
            PlanCard.from(s),
            s.plan.days.map { d ->
                DayRow(d.day, d.readings, s.statuses[d.day] ?: DayStatus.PENDING, current = d.day == s.position.currentDay)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun markComplete(day: Int) = viewModelScope.launch { plans.markComplete(enrollmentId, day) }
    fun markNotDone(day: Int) = viewModelScope.launch { plans.markNotDone(enrollmentId, day) }
    fun skipToToday() = viewModelScope.launch { plans.skipToToday(enrollmentId) }
    fun restart() = viewModelScope.launch { plans.restart(enrollmentId) }
    fun setPaused(paused: Boolean) = viewModelScope.launch { plans.setPaused(enrollmentId, paused) }
    fun archive() = viewModelScope.launch { plans.archive(enrollmentId) }
    fun delete() = viewModelScope.launch { plans.unenroll(enrollmentId) }
}
