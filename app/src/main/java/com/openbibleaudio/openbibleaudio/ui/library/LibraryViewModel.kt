package com.openbibleaudio.openbibleaudio.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openbibleaudio.openbibleaudio.data.LibraryRepository
import com.openbibleaudio.openbibleaudio.data.db.BibleVersionEntity
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.library.ImportController
import com.openbibleaudio.openbibleaudio.library.ImportJob
import com.openbibleaudio.openbibleaudio.library.ImportPreview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VersionCard(
    val version: BibleVersionEntity,
    val chapters: Int,
    val bytesOnDisk: Long,
    val completions: Int,
    val job: ImportJob?,
) {
    val versionId: String get() = version.versionId
    val ready: Boolean get() = version.state == VersionState.READY
}

sealed interface ImportDialog {
    data object Checking : ImportDialog
    data class Show(val preview: ImportPreview) : ImportDialog
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    library: LibraryRepository,
    private val imports: ImportController,
) : ViewModel() {
    val cards: StateFlow<List<VersionCard>?> = combine(library.versions, library.stats, imports.jobs) { versions, stats, jobs ->
        val byId = stats.associateBy { it.versionId }
        versions.map { v ->
            val s = byId[v.versionId]
            VersionCard(v, s?.chapters ?: 0, s?.bytesOnDisk ?: 0, s?.completions ?: 0, jobs[v.versionId])
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _dialog = MutableStateFlow<ImportDialog?>(null)
    val dialog: StateFlow<ImportDialog?> = _dialog.asStateFlow()

    fun onFolderPicked(uri: Uri) {
        _dialog.value = ImportDialog.Checking
        viewModelScope.launch { _dialog.value = ImportDialog.Show(imports.inspect(uri)) }
    }

    fun startImport(preview: ImportPreview.Ready, move: Boolean) {
        _dialog.value = null
        viewModelScope.launch { imports.start(preview, move) }
    }

    fun dismissDialog() {
        _dialog.value = null
    }

    fun resume(versionId: String) = viewModelScope.launch { imports.resume(versionId) }
    fun cancel(versionId: String) = imports.cancel(versionId)
    fun delete(versionId: String) = viewModelScope.launch { imports.delete(versionId) }
}
