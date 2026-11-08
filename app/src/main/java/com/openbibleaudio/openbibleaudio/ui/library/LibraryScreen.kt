package com.openbibleaudio.openbibleaudio.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.data.db.VersionState
import com.openbibleaudio.openbibleaudio.library.ImportPreview

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    contentPadding: PaddingValues,
    onOpenVersion: (versionId: String, abbreviation: String) -> Unit,
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onFolderPicked(uri)
    }

    Box(Modifier.fillMaxSize().padding(contentPadding)) {
        val list = cards
        when {
            list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            list.isEmpty() -> EmptyLibrary(Modifier.align(Alignment.Center).padding(32.dp))
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.versionId }) { card ->
                    VersionCardView(
                        card = card,
                        onOpen = { if (card.ready) onOpenVersion(card.versionId, card.version.abbreviation) },
                        onResume = { viewModel.resume(card.versionId) },
                        onCancel = { viewModel.cancel(card.versionId) },
                        onDelete = { viewModel.delete(card.versionId) },
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { pickFolder.launch(null) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.import_bible)) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    when (val d = dialog) {
        null -> Unit
        ImportDialog.Checking -> AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text(stringResource(R.string.import_checking)) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
        )
        is ImportDialog.Show -> ImportPreviewDialog(
            preview = d.preview,
            onDismiss = viewModel::dismissDialog,
            onStart = { preview, move ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                viewModel.startImport(preview, move)
            },
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.padding(8.dp))
        Text(stringResource(R.string.library_empty_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun VersionCardView(
    card: VersionCard,
    onOpen: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val v = card.version

    Card(Modifier.fillMaxWidth().clickable(enabled = card.ready, onClick = onOpen)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("${v.abbreviation} — ${v.name}", style = MaterialTheme.typography.titleMedium)
                v.narrator?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.padding(4.dp))
                val job = card.job
                when {
                    v.state == VersionState.READY -> {
                        Text(stringResource(R.string.library_state_ready, card.chapters, Formatter.formatShortFileSize(context, card.bytesOnDisk)))
                        if (card.completions > 0) Text(stringResource(R.string.library_completions, card.completions), style = MaterialTheme.typography.bodySmall)
                    }
                    job != null -> {
                        Text(stringResource(R.string.library_state_importing, job.done, job.total))
                        if (job.total > 0) {
                            LinearProgressIndicator(progress = { job.done.toFloat() / job.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                    v.state == VersionState.FAILED -> Text(
                        stringResource(R.string.library_state_failed, v.failureReason.orEmpty()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(stringResource(R.string.library_state_paused))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (card.job != null) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.cancel)) }, onClick = { menu = false; onCancel() })
                    } else if (!card.ready) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.import_resume)) }, onClick = { menu = false; onResume() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.library_delete_title, v.abbreviation)) },
            text = { Text(stringResource(R.string.library_delete_body)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ImportPreviewDialog(
    preview: ImportPreview,
    onDismiss: () -> Unit,
    onStart: (ImportPreview.Ready, Boolean) -> Unit,
) {
    val context = LocalContext.current
    when (preview) {
        is ImportPreview.Invalid -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_invalid_title)) },
            text = {
                LazyColumn { items(preview.problems) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) } }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        is ImportPreview.AlreadyImported -> AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(stringResource(R.string.import_already_imported, preview.abbreviation)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        is ImportPreview.Ready -> {
            var move by rememberSaveable { mutableStateOf(false) }
            val bible = preview.manifest.bible
            val enough = preview.enoughSpace(move)
            AlertDialog(
                onDismissRequest = onDismiss,
                title = {
                    Text(stringResource(if (preview.resuming) R.string.import_resume_title else R.string.import_preview_title, bible.abbreviation))
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(bible.name, style = MaterialTheme.typography.titleSmall)
                        bible.narrator?.let { Text(it) }
                        val books = preview.manifest.chapters.map { it.book }.distinct().size
                        val missing = Book.entries.sumOf { it.chapterCount } - preview.chapterCount
                        Text(
                            if (missing <= 0) stringResource(R.string.import_chapters_complete, preview.chapterCount)
                            else stringResource(R.string.import_chapters_partial, preview.chapterCount, books, missing),
                        )
                        Text(
                            stringResource(
                                R.string.import_size,
                                Formatter.formatShortFileSize(context, preview.remainingBytes),
                                Formatter.formatShortFileSize(context, preview.freeBytes),
                            ),
                        )
                        if (preview.canMove) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = move, onCheckedChange = { move = it })
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.import_move))
                            }
                            if (move) Text(stringResource(R.string.import_move_warning), color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(stringResource(R.string.import_move_unavailable), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!enough) {
                            Text(
                                stringResource(R.string.import_needs_space, Formatter.formatShortFileSize(context, preview.requiredBytes(move))),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onStart(preview, move) }, enabled = enough) {
                        Text(stringResource(if (preview.resuming) R.string.import_resume else R.string.import_start))
                    }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
