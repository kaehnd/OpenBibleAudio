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

    /** [notes] are non-error findings from import, e.g. partial coverage. */
    data class Enroll(val plan: PlanChoice, val notes: List<Issue> = emptyList()) : PlansDialog
}

/** The Plans tab (spec 0003 §9.3): enrollment cards, import, and enroll. */
@HiltViewModel
class PlansViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plans: PlanRepository,
    library: LibraryRepository,
) : ViewModel() {
    val ui: StateFlow<PlansUi?> = combine(plans.enrollments, plans.plans, library.snapshot.map { it.versions }) { enrollments, imported, versions ->
        PlansUi(enrollments.map(PlanCard::from), imported, versions)
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
                is PlanImportResult.Imported -> PlansDialog.Enroll(PlanChoice(result.id, result.version, result.name), result.notes)
                else -> PlansDialog.ImportProblem(result)
            }
        }
    }

    fun showEnroll(plan: PlanEntity) {
        _dialog.value = PlansDialog.Enroll(PlanChoice(plan.id, plan.version, plan.name))
    }

    fun enroll(plan: PlanChoice, versionId: String?, startDate: LocalDate) {
        _dialog.value = null
        viewModelScope.launch { plans.enroll(plan.id, plan.version, versionId = versionId, startDate = startDate) }
    }

    fun dismissDialog() {
        _dialog.value = null
    }
}
