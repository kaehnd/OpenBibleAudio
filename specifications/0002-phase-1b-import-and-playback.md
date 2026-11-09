# Spec 0002 — Phase 1b: In-app Import & Playback (MVP player)

| | |
|---|---|
| **Status** | Draft |
| **Created** | 2026-11-08 |
| **Author** | Danny Kaehn |
| **Phase** | 1b (second half of the Phase 1 MVP; see spec 0000 §4) |
| **Depends on** | [0001 — Phase 1a: Audio Bible Pack format v1 & Importer CLI](0001-phase-1a-audio-bible-pack-and-importer.md) |
| **Implements** | AD-2, AD-3, AD-6, AD-7, AD-10, AD-11 (reference parser), AD-13, AD-14 (permissions), AD-15 |

---

## 1. Problem & exit criterion

With a valid pack (spec 0001) the user needs to get it into the app and listen to it, mainly
through Android Auto.

**Exit criterion (from spec 0000, using the BSB pack):** import the BSB Gilbert pack on a
device or emulator. In Auto (DHU), pick **Genesis 1**, listen through into **Genesis 2**, kill
the app process, then press play on the steering wheel (DHU media key). Playback resumes in
Genesis 2 close to where it stopped.

### 1.1 In scope
- `:core:model` additions: `ChapterRef`, the media-ID grammar (AD-3) and the scripture reference parser (AD-11).
- Room schema v1 and DataStore settings/resumption (AD-7).
- SAF pack import with copy / **move**, as a resumable foreground `WorkManager` job (AD-6).
- `PlaybackService : MediaLibraryService` (AD-2): the Bible browse tree, chapter queues with
  continuous play, playback resumption, seek-back/forward buttons, voice search, playback speed
  and completion recording.
- Minimal phone UI: Library (import / delete), book and chapter picker, mini-player, Now Playing, Settings.

### 1.2 Out of scope
- Plans, the Today tab, podcasts (Phases 2–3), verses and "back one verse" (Phase 4).
- Importing folders that have no manifest (Phase 5).
- Replacing or updating an already imported version in place (§6.6).
- Multiple versions *at the same time* is supported by the data model and the browse tree,
  but only lightly tested in this phase.

---

## 2. `:core:model` additions

### 2.1 `ChapterRef`
`data class ChapterRef(val book: Book, val chapter: Int)`. The init check enforces `book.hasChapter(chapter)`.
It's `Comparable` in canonical order and has `next()`/`previous()` within the canon (null at the ends).

### 2.2 Media-ID grammar (AD-3)

```kotlin
sealed interface MediaId {
    data object Root : MediaId                                         // "root"
    data object Bible : MediaId                                        // "bible"
    data class Version(val versionId: String) : MediaId                // "bible/{v}"
    data class TestamentNode(val versionId: String, val testament: Testament) : MediaId  // "bible/{v}/OT|NT"
    data class BookNode(val versionId: String, val book: Book) : MediaId                 // "bible/{v}/GEN"
    data class ChapterGroup(val versionId: String, val book: Book, val from: Int, val to: Int) : MediaId // "bible/{v}/PSA/1-50"
    data class Chapter(val versionId: String, val ref: ChapterRef) : MediaId              // "bible/{v}/GEN/1"   (playable)
    fun format(): String
    companion object { fun parse(id: String): MediaId? }
}
```
- **New node `ChapterGroup`** (`bible/{v}/{BOOK}/{from}-{to}`). It splits long chapter lists for
  Auto (§7.4). This extends AD-3, and spec 0000 is updated in the same change.
- Parsing is strict. It returns `null` for unknown shapes, bad book codes, out-of-range chapters
  and `from > to`. The rule `format(parse(s)) == s` holds for every valid `s`.
- `plan/…` and `podcast/…` IDs are added in Phases 2–3. `parse` returns `null` for them now.

### 2.3 Reference parser (AD-11)
`object ReferenceParser { fun parse(text: String): ParsedReference? }`, with
`data class ParsedReference(val book: Book, val chapter: Int?, val verse: Int?)`.

- Case-insensitive. Ignores punctuation (`.`, `:`, `,`) and the filler words "chapter", "verse" and "the book of".
- Book names come from `BookNames.english` in `:core:model`: full names, common abbreviations
  (`Gen`, `Ps`, `Psa`, `Psalm(s)`, `Song of Solomon`, `Song of Songs`, `Canticles`, `Jn`, `Rev`,
  `Revelations`, …) and USFM codes. This is parsing data, not display strings (display names
  come from Android string resources, AD-11).
- Numbered books accept `1`, `I`, `1st`, `first` and `one` ("first John", "1 John", "I John").
- Chapter and verse accept digits and English number words up to 150 ("psalm one hundred
  nineteen" → PSA 119), because voice transcription sometimes produces words.
- If the book is ambiguous the longest match wins ("John" vs "1 John": a leading ordinal binds to the book).
- Examples that must parse: `Gen 3`, `genesis chapter three`, `1 John 2`, `First John 2`,
  `Song of Songs 4:2` (→ SNG 4, verse 2), `Psalm 23`, `ps 119`, `Romans` (chapter null), `Jude`.
- Must reject: `Hezekiah 3`, an empty string, `Genesis 51` (out of range → null, so voice search can say "not found").

---

## 3. Getting the pack onto a device

- The BSB pack is about 5.8 GB, so the target needs at least **12 GB free for a copy import**
  (source + copy) or about 6 GB with **move**.
- **Emulator:** create the AVD with a large data partition (e.g. `disk.dataPartition.size=24G`),
  then `adb push bible_audio/bsb_gilbert /sdcard/Download/bsb_gilbert`. It will show up in the SAF picker.
- **Physical device:** copy over USB (MTP) into any user-visible folder, or use `adb push` as above.
- The **managed test device** (`pixel9Api37`) never gets the full pack. Instrumented tests use
  the mini pack (§10.3).

---

## 4. Dependencies (add to `gradle/libs.versions.toml`)

| Purpose | Artifacts |
|---|---|
| Playback | `androidx.media3:media3-exoplayer`, `media3-session` (one `media3` version ref) |
| Persistence | `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (KSP), `room-testing` |
| Settings | `androidx.datastore:datastore-preferences` |
| Work | `androidx.work:work-runtime-ktx` |
| DI | `com.google.dagger:hilt-android`, `hilt-compiler` (KSP), `androidx.hilt:hilt-work`, `hilt-navigation-compose` |
| UI | `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose`, `lifecycle-runtime-compose` |
| SAF | `androidx.documentfile:documentfile` |
| Shared | `project(":core:model")` (already transitively has `kotlinx-serialization-json`) |
| Tests | `org.robolectric:robolectric`, `kotlinx-coroutines-test`, `androidx.work:work-testing`, `media3-test-utils` |

Plugins: `com.google.devtools.ksp` and `com.google.dagger.hilt.android`. **Check KSP and Hilt
compatibility with AGP 9.5's built-in Kotlin (KGP 2.2.10) first**. If the KSP version for
2.2.10 doesn't work with built-in Kotlin, record the workaround in AGENTS.md "Known gotchas".
Existing old pins (`coreKtx 1.10.1`, `lifecycle 2.6.1`, `activityCompose 1.8.0`) get bumped as needed.

---

## 5. Persistence (AD-7)

### 5.1 Room schema v1 (`OpenBibleAudioDatabase`, `app/schemas/` exported and committed)

```
bible_version
  versionId TEXT PK, abbreviation, name, language, versification, narrator?, attribution?,
  state TEXT  -- IMPORTING | READY | FAILED
  totalBytes INTEGER, importedAt INTEGER?  (epoch ms; null until READY)
  sourceTreeUri TEXT?                      -- persisted SAF grant while IMPORTING; cleared on READY
  moveSource INTEGER (bool)

audio_file
  id INTEGER PK autoinc, versionId FK→bible_version ON DELETE CASCADE,
  path TEXT, sizeBytes, sha256?, durationMs?,
  imported INTEGER (bool)                  -- verified copy present in app storage
  UNIQUE(versionId, path)

chapter_audio
  versionId FK ON DELETE CASCADE, book TEXT (USFM), chapter INTEGER,
  fileId FK→audio_file, startMs INTEGER, endMs INTEGER?, durationMs INTEGER?   -- effective span duration
  PK(versionId, book, chapter)

chapter_completion
  id INTEGER PK autoinc, versionId, book, chapter, completedAt INTEGER
  INDEX(versionId, book, chapter)
```
- `Book` is stored as its USFM code through a `TypeConverter` (AD-11).
- `chapter_audio` rows are inserted **only when the import finishes**, so the browse tree never
  shows chapters whose files haven't been copied yet.
- Migrations: v1 is the baseline. From the first release onward, every schema change ships a
  `Migration` plus a `MigrationTestHelper` test (no destructive fallback).

### 5.2 DataStore
- **Settings** (`settings.preferences_pb`): `continuousPlayScope` = `BOOK` (default) | `BIBLE`,
  `bibleSpeed` (float, default 1.0), `seekBackMs` (15 000), `seekForwardMs` (30 000),
  `verifyHashesOnImport` (default true; see 0001 open question 3).
- **Resumption** (`playback_state.preferences_pb`): `mediaId` (chapter ID), `positionMs`,
  `updatedAt`. Only the current chapter is stored. The queue is rebuilt from it with the
  continuous-play rule (§7.3), so it can't get out of sync with the library.

### 5.3 Repositories (package `data/`)
`LibraryRepository` exposes `versions: Flow<List<BibleVersion>>`, `chapters(versionId): Flow<…>`,
`resolve(versionId, ref)` and `delete(versionId)`. `SettingsRepository` and `ResumptionStore`
wrap DataStore.

---

## 6. Import (AD-6)

### 6.1 Flow
1. **Library → "Import Bible"** launches `ACTION_OPEN_DOCUMENT_TREE`.
2. `takePersistableUriPermission(read | write)`. Write is needed for move. If the provider
   doesn't grant write, the Move option is disabled.
3. **Index the tree once.** Query children with `DocumentsContract.buildChildDocumentsUriUsingTree`
   recursively and build `Map<relativePath, documentUri>`. **Never call `DocumentFile.findFile`
   per file**: it's O(n) per call, and for 1189 files in one folder that's quadratic.
4. Read `manifest.json`, decode it with `PackManifestCodec` and run `ManifestValidator`.
   - Errors: show a dialog listing the first 20 issues and stop.
   - Check every `files[]` path against the index. A missing file is an error, and so is a size
     that doesn't match the provider's `COLUMN_SIZE`.
   - If `versionId` is already in the DB and READY: "BSB is already imported. Delete it first to
     re-import." If the existing version is IMPORTING or FAILED, offer **Resume** instead.
5. **Preview dialog**: name, abbreviation, narrator; chapter count with a "complete" or
   "missing: …" summary (the `MISSING_CHAPTERS` info); total size; free space; and the
   **"Move files instead of copying"** checkbox. When it's ticked, a warning shows: *"The
   original files will be deleted from the folder as they are imported."*
6. **Free-space check** with `StorageManager.getAllocatableBytes(getUuidForPath(filesDir))`:
   - copy: needs `remainingBytes + 64 MB`
   - move: needs `largestRemainingFile + 64 MB`
   If there isn't enough, the Import button is disabled and the dialog shows how much space is needed.
   `remainingBytes` excludes files already verified (when resuming).
7. On confirm: insert or update `bible_version(state=IMPORTING, sourceTreeUri, moveSource)` and
   all `audio_file` rows (`imported=false`). Store the manifest at
   `filesDir/bibles/{versionId}/manifest.json`, then enqueue `ImportWorker`.
   Ask for `POST_NOTIFICATIONS` here if it hasn't been granted (API 33+). Import still works
   without it, just without the notification.

### 6.2 `ImportWorker` (CoroutineWorker, Hilt)
- Unique work `import/{versionId}` with policy `KEEP`. Expedited if quota allows, otherwise
  normal. Runs in the foreground via `setForeground` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`.
  The notification shows "Importing BSB: 412 / 1189", a determinate progress bar and a Cancel action.
- For each `audio_file` with `imported=false`, in manifest order:
  1. Open the source with `contentResolver.openInputStream(docUri)` and stream it into
     `filesDir/bibles/{versionId}/{path}.part` through a `DigestInputStream` (SHA-256, only if
     the manifest has a hash and hash verification is on).
  2. Check the byte count == `sizeBytes`, and the hash if applicable. On mismatch, delete the
     `.part` and fail the import with a message naming the file.
  3. `fsync`, then atomically rename to the final path and set `imported=true` in the DB, **in that order**.
  4. With **move**, call `DocumentsContract.deleteDocument(docUri)`. If that fails, log a warning
     and keep going: the import is still correct, the user just keeps the original.
  5. `setProgress` and update the notification about every 500 ms.
- Path safety: the destination must resolve under `filesDir/bibles/{versionId}/`. The 0001 path
  rules guarantee this, and the worker checks it again with `canonicalPath`.
- **Durations missing from the manifest:** after copying, use `MediaMetadataRetriever` on the
  local file (`METADATA_KEY_DURATION`).
- **Finish:** in one transaction, insert all `chapter_audio` rows (span `durationMs` = `endMs ?: fileDuration` − `startMs`),
  then set `state=READY, importedAt=now, sourceTreeUri=null`. Release the persisted URI permission.
- **Retry and resume:** an IO exception gives `Result.retry()` (exponential backoff, at most 5
  attempts). Verified files are skipped next time (AD-6). Validation failures and a revoked SAF
  grant give `Result.failure()` with `state=FAILED` and a readable reason (kept in output data
  and shown on the Library card).
- **Cancel:** the worker stops at the next file boundary and the `.part` is deleted. The version
  stays IMPORTING, and the Library shows **Resume** and **Delete**.

### 6.3 Library screen
- One card per version: name, abbreviation, narrator, state, chapter count and size used on disk.
  IMPORTING cards show progress (from `WorkManager.getWorkInfosForUniqueWorkFlow`).
- Overflow → **Delete**: confirm, stop playback if it's playing from this version, cancel any
  running import, delete the rows (cascade) and `filesDir/bibles/{versionId}/`, and clear resumption
  state if it points at this version.
- An empty state explains what a pack is and links to the importer docs (README section, written in this phase).

### 6.4 Version replacement
Out of scope (§1.2). Delete-then-reimport is the supported path. Plan progress in Phase 2 is
keyed by `ChapterRef` and not tied to a version, so deleting a version won't lose plan progress later.

---

## 7. Playback (AD-2, AD-10)

### 7.1 `BibleAudioSource` (AD-10)
```kotlin
interface BibleAudioSource {
    val requiresNetwork: Boolean
    suspend fun resolve(versionId: String, ref: ChapterRef): ResolvedChapter?
}
data class ResolvedChapter(val uri: Uri, val clipStartMs: Long, val clipEndMs: Long?, val durationMs: Long?)
```
Phase 1 has one implementation, `LocalBibleAudioSource`. It resolves through `chapter_audio` +
`audio_file` to `file://…/files/bibles/{v}/{path}`, and returns null if the version isn't READY.

### 7.2 Player configuration
- One `ExoPlayer` per service, built with:
  - `AudioAttributes(USAGE_MEDIA, AUDIO_CONTENT_TYPE_SPEECH)` and `handleAudioFocus = true`.
  - `setHandleAudioBecomingNoisy(true)`.
  - `setWakeMode(C.WAKE_MODE_LOCAL)`.
  - `DefaultMediaSourceFactory` with `DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)`.
    **This is required for the BSB files**: they have no Xing header (spec 0001 §3), and without it
    seeking and duration on CBR MP3s are unreliable.
  - `setSeekBackIncrementMs(seekBackMs)` and `setSeekForwardIncrementMs(seekForwardMs)` from settings.
  - `setPlaybackSpeed(bibleSpeed)`, re-applied whenever the setting changes.
- `MediaItem` per chapter:
  - `mediaId` = `MediaId.Chapter.format()` and `uri` = the resolved file.
  - `ClippingConfiguration` **only if** `clipStartMs > 0 || clipEndMs != null`, so that whole-file
    chapters keep normal seeking.
  - `MediaMetadata`: `title` "Genesis 1" (localised book name + chapter), `artist` = version
    abbreviation, `albumTitle` = book name, `trackNumber` = chapter, `totalTrackCount` = chapters
    in the book, `mediaType = MEDIA_TYPE_AUDIO_BOOK_CHAPTER`, `isPlayable = true`,
    `isBrowsable = false`, and `durationMs` when known. No artwork in Phase 1 (Auto shows the app icon).

### 7.3 Queues & continuous play
`QueueBuilder` is a pure function and unit-tested on the JVM, apart from the final `MediaItem` mapping:
`build(start: ChapterRef, versionId, scope, available: SortedSet<ChapterRef>): List<ChapterRef>`.
- The queue starts at `start` and runs to the end of `start.book` (`BOOK`), or to the end of the
  canon (`BIBLE`), skipping chapters that aren't in the pack.
- A request for a **book** (voice "play Romans") starts at that book's first available chapter.
- Prev/next chapter use the standard `seekToPrevious/NextMediaItem`. `seekToPrevious` restarts
  the current chapter if the position is past 3 s (Media3 default `maxSeekToPreviousPositionMs`).
- At the end of the queue playback stops (`STATE_ENDED`). It doesn't loop.

### 7.4 Browse tree (`BrowseTree`, pure Kotlin over a library snapshot)
`fun children(id: MediaId, snapshot: LibrarySnapshot): List<BrowseNode>`. The service maps
`BrowseNode`s to `MediaItem`s, so the whole tree can be unit-tested on the JVM.

```
root
└── bible  "Bible"                          (only root child in Phase 1; Today/Plans arrive in Phase 2)
    ├── [if >1 READY version] bible/{v}  "BSB — Berean Standard Bible"
    │                                     (if exactly 1 READY version, its children are shown directly under "bible")
    ├── bible/{v}/OT "Old Testament"   bible/{v}/NT "New Testament"
    │   └── bible/{v}/GEN "Genesis" …     (books with ≥1 available chapter only)
    │       └── bible/{v}/GEN/1 … /50      (playable; grid)
    └── bible/{v}/PSA → groups: PSA/1-50, PSA/51-100, PSA/101-150 → chapters
```
- **Grouping rule:** books with more than `CHAPTER_GROUP_SIZE = 50` available chapters are split
  into groups of 50 (PSA → 3 groups, ISA → 1–50 and 51–66). Other books list chapters directly.
  The constant is in `BrowseTree` and may be tuned after testing on the DHU and real cars.
- **Content style:** set the `MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE = GRID_ITEM` and
  `…_BROWSABLE = LIST_ITEM` extras on the root `LibraryParams`, and on book and group nodes with
  `DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE = GRID_ITEM`, so chapter numbers show as a grid.
  Chapter grid items use the title "1", "2", …. The full "Genesis 1" goes in now-playing metadata only.
- **No READY versions:** `bible` returns a single non-playable, non-browsable item:
  *"No Bible imported. Open OpenBibleAudio on your phone to import one."*
- The service observes `LibraryRepository.versions` and calls `notifyChildrenChanged` for `bible`
  (and the affected version) when an import finishes or a version is deleted (AD-13).
- `onGetItem(id)` rebuilds any node from its ID alone (AD-3), which Auto requires after process death.

### 7.5 Session callbacks
- `onAddMediaItems` / `onSetMediaItems`: Auto and the phone send items **without URIs**, only IDs
  (or a search query). The service resolves them:
  - `MediaId.Chapter` → queue from §7.3, with `startIndex` 0. For `onSetMediaItems` it returns
    `MediaItemsWithStartPosition`.
  - `requestMetadata.searchQuery` set → voice search (§7.7).
  - Anything else → `LibraryResult.ofError(ERROR_BAD_VALUE)`.
- **Playback resumption** (`onPlaybackResumption`): read `ResumptionStore`. If the chapter is
  still available, build its queue and return it with `startPositionMs`. Otherwise return the
  first available chapter of the first READY version at position 0. If there's no library at
  all, return an error.
- **Media buttons:** the session's media button preferences put **seek back** (slot back, `COMMAND_SEEK_BACK`)
  and **seek forward** (slot forward, `COMMAND_SEEK_FORWARD`) next to previous/next. These are
  standard player commands, so no custom session command is needed yet. Custom commands start
  in Phase 4 ("back one verse").

### 7.6 Persistence of position
- Save `(mediaId, positionMs)` every 5 s while playing, on pause, on every media-item transition,
  and in `onTaskRemoved`/`onDestroy`. Writes go through a conflated flow, so DataStore isn't written
  more often than that.
- On resumption, start **2 s before** the saved position (clamped at 0) so the listener gets
  some context back.

### 7.7 Voice search
- `onSearch` / `onGetSearchResult` and `onSetMediaItems` with a `searchQuery`: run
  `ReferenceParser.parse(query)`. If the user has several versions, the version is the one in the
  resumption state, or else the first READY one.
  - Book + chapter → that chapter's queue. Book only → first chapter of the book.
  - An empty query ("play OpenBibleAudio") → resume (same path as §7.5 resumption).
  - Can't parse, or the chapter isn't in the pack → error result with the message *"Couldn't find
    'Hezekiah 3' in BSB"*, which Auto reads out.
- `onGetSearchResult` returns up to 10 matches: the parsed chapter, plus the book's first chapter when only a book was given.

### 7.8 Completion recording (AD-2)
A player listener inserts a `chapter_completion` row when a chapter's item stops being current
**and** the furthest position reached in it was ≥ 95 % of its duration, or when it ended
naturally (transition reason `AUTO`). There's at most one row per play-through. Nothing reads
these rows in Phase 1 other than a debug count on the Library card; Phase 2 plans use them.

### 7.9 Errors
- A playback error from a missing or corrupt local file: the session's player error is set to
  `"Audio for Genesis 3 is missing. Re-import BSB."` Auto shows it, playback stops, and the error
  is logged with the mediaId.
- If a version is deleted while the queue is playing from it: stop and clear the queue.

---

## 8. Phone UI (minimal, Compose + Navigation)

The app's UI is a `MediaController` client of the session (AD-2), held by a `PlayerConnection`
singleton that exposes `StateFlow`s of current item, position, isPlaying, speed and so on.

| Screen | Contents |
|---|---|
| **Library** (start destination) | Version cards (§6.3), an "Import Bible" FAB, and a route into the book picker per version |
| **Books** | OT / NT sections, a book list (available books only) |
| **Chapters** | Grid of chapter numbers. Tapping one sets the queue through the controller (`setMediaItems` with the chapter ID only, so it goes through the same §7.5 path as Auto) |
| **Now Playing** | Title, version, seek bar with elapsed and remaining time, play/pause, previous/next chapter, seek back/forward, a speed chip that cycles 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0 and is saved to `bibleSpeed` |
| **Mini-player** | On every screen except Now Playing whenever something is loaded; tapping it opens Now Playing |
| **Settings** | Continuous-play scope (end of book / end of Bible), seek increments (5/10/15/30 s), "Verify file hashes on import", and an About page that lists each version's attribution |

All strings live in `strings.xml`, including the 66 localised book names (`book_GEN`, …). There
are no hard-coded user-visible strings. Layout supports dark theme and font scaling; the UI isn't
polished in this phase.

---

## 9. Manifest & permissions (AD-14)

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<!-- No INTERNET in Phase 1 (arrives with podcasts, Phase 3). No storage permissions: SAF only. -->

<service android:name=".playback.PlaybackService"
         android:foregroundServiceType="mediaPlayback" android:exported="true">
  <intent-filter>
    <action android:name="androidx.media3.session.MediaLibraryService" />
    <action android:name="android.media.browse.MediaBrowserService" />
  </intent-filter>
</service>
<receiver android:name="androidx.media3.session.MediaButtonReceiver" android:exported="true">
  <intent-filter><action android:name="android.intent.action.MEDIA_BUTTON" /></intent-filter>
</receiver>
<meta-data android:name="com.google.android.gms.car.application"
           android:resource="@xml/automotive_app_desc" />   <!-- <automotiveApp><uses name="media"/></automotiveApp> -->
```
WorkManager's `SystemForegroundService` is declared with `foregroundServiceType="dataSync"`
by merging into the WorkManager manifest entry with `tools:node="merge"`. The Hilt `Application`
class implements `Configuration.Provider` (the default WorkManager initializer is removed).

---

## 10. Testing (AD-15)

### 10.1 JVM unit tests
- `:core:model`: `MediaId` round-trip for every variant, plus rejection cases (bad book, chapter 0,
  `GEN/51`, `PSA/51-40`). `ReferenceParser` covers every example in §2.3 and the number-word cases. `ChapterRef.next/previous` at book and canon boundaries.
- `:app` (pure logic, no Robolectric): `QueueBuilder` (BOOK vs BIBLE scope, gaps in the pack,
  last chapter of REV), `BrowseTree` (one vs several versions, PSA/ISA grouping, missing books
  hidden, empty library item, `onGetItem` for every node type), free-space calculation,
  completion rule (94 % vs 95 %, natural end), and the resumption 2 s rewind.

### 10.2 Robolectric / instrumented
- Room DAO tests, and a schema-export check (`app/schemas/…/1.json` committed).
- `ImportWorker` with `work-testing`, against a `PackSource` abstraction (`SafPackSource` in
  production, `FilePackSource` in tests): a happy path, a size mismatch, a hash mismatch, resume
  after a simulated crash midway (some files `imported=true`), move deleting each source right
  after its copy, and cancel.
- **Browse-tree integration** (instrumented, managed device): connect a `MediaBrowser` to the
  service with the mini pack imported. Check root → bible → OT → GEN → chapter grid, `onGetItem`
  after a service restart, and that `setMediaItems(chapter id only)` plays and advances to the next chapter.

### 10.3 Mini pack fixture
`app/src/androidTest/assets/packs/mini/`: about 6 tiny CC0 MP3s (1–2 s of tone, 32 kbps CBR, no
Xing header, to match the BSB shape) for GEN 1–3, PSA 1, JUD 1 and REV 22, plus a `manifest.json`
generated **with the 0001 CLI**. That also checks that the CLI and the app agree. The fixture
needs a profile for these filenames: it uses BSB-style names (`BSB_01_Gen_001_G.mp3`) so the
`bsb-gilbert` profile applies unchanged. The ffmpeg command used to make the files is recorded
in a `README.md` next to them.

### 10.4 DHU checklist (manual, run before marking the phase done)
1. Fresh install, no library: Auto shows the app, and Bible shows the "No Bible imported" item.
2. Import the BSB pack on the phone (copy). Auto's Bible list updates without restarting Auto.
3. Bible → Old Testament shows 39 books and New Testament shows 27. Genesis shows a chapter grid of 50.
   Psalms shows 3 groups.
4. Play Genesis 1. Now-playing shows "Genesis 1" / "BSB". Seek back and forward buttons are
   visible and work. Seeking within the chapter is accurate (CBR seeking).
5. Genesis 1 ends and Genesis 2 starts by itself.
6. Previous / next chapter from the steering-wheel controls (DHU keys).
7. Voice: "Play Psalm 23", "Play First John chapter 2", "Play Hezekiah 3" (spoken error).
8. **Exit criterion:** during Genesis 2, kill the process with
   `adb shell am kill com.openbibleaudio.openbibleaudio` (after the app is backgrounded and
   paused) **or** swipe it away and wait for the service to stop. Then press play on the DHU
   (`adb shell input keyevent KEYCODE_MEDIA_PLAY` also works). Playback resumes Genesis 2 about 2 s
   before where it stopped.
   *Note: don't use `am force-stop`. It puts the app in the stopped state, and Android won't
   deliver media-button broadcasts to it until it's launched again. That's platform behaviour, not a bug.*
9. Speed set to 1.5× on the phone also applies in Auto, and is still 1.5× after a restart.
10. Delete the version on the phone. Auto's Bible list goes back to the empty item.

---

## 11. Acceptance criteria

1. `./gradlew test` and `./gradlew lint` are green. `./gradlew pixel9Api37DebugAndroidTest` is green.
2. The full BSB pack imports on an emulator **both** with copy and with move (with move, the
   source folder ends up with only `manifest.json` in it). Import survives being killed partway
   and resumes without copying verified files again.
3. Every item on the DHU checklist (§10.4) passes, including the exit criterion.
4. No storage or `INTERNET` permission in the merged manifest.

---

## 12. Open questions

1. **Auto list limits.** Is 50 the right `CHAPTER_GROUP_SIZE`, and do 39-book lists display
   fully on the DHU and on the user's car? Adjust after testing.
2. **Continue listening at the root.** Should Phase 1b already add a playable "Continue
   listening" root item, or wait for Phase 2's Today tab? (Current plan: wait. Resumption and
   Auto's own now-playing view cover it.)
3. **Hash verification time on device** (see 0001 open question 3). Measure the BSB import time
   with hashing on and off on a mid-range phone, and decide the default for `verifyHashesOnImport`.
4. **KSP/Hilt on AGP 9 built-in Kotlin.** Confirm a working combination (§4). If Hilt is blocked,
   fall back to manual DI for this phase and record that as an AD-13 amendment.
