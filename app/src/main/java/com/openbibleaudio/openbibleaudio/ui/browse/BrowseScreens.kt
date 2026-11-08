package com.openbibleaudio.openbibleaudio.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openbibleaudio.core.model.Book
import com.openbibleaudio.core.model.ChapterRef
import com.openbibleaudio.core.model.Testament
import com.openbibleaudio.openbibleaudio.R
import com.openbibleaudio.openbibleaudio.playback.BookTitles

@Composable
fun BooksScreen(viewModel: BrowseViewModel, versionId: String, contentPadding: PaddingValues, onBook: (Book) -> Unit) {
    val snap by viewModel.snapshot.collectAsStateWithLifecycle()
    val titles = BookTitles(LocalContext.current)
    val s = snap ?: return Loading(contentPadding)
    val books = s.books(versionId)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        for (testament in Testament.entries) {
            val inTestament = books.filter { it.testament == testament }
            if (inTestament.isEmpty()) continue
            item(key = testament.name) {
                Text(
                    stringResource(if (testament == Testament.OLD) R.string.old_testament else R.string.new_testament),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                )
            }
            items(inTestament, key = { it.usfm }) { book ->
                ListItem(headlineContent = { Text(titles.book(book)) }, modifier = Modifier.clickable { onBook(book) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun ChaptersScreen(viewModel: BrowseViewModel, versionId: String, book: Book, contentPadding: PaddingValues, onChapter: (ChapterRef) -> Unit) {
    val snap by viewModel.snapshot.collectAsStateWithLifecycle()
    val s = snap ?: return Loading(contentPadding)
    val chapters = s.chapters(versionId, book)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(64.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(chapters, key = { it.chapter }) { ref ->
            Card(Modifier.padding(4.dp).aspectRatio(1f).clickable { onChapter(ref) }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(ref.chapter.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Loading(contentPadding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
