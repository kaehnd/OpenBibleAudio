# Spec 0000 — Project Vision, Architecture & Roadmap

| | |
|---|---|
| **Status** | Draft |
| **Created** | 2026-11-08 |
| **Author** | Danny Kaehn |
| **Supersedes** | — |

This is the root specification for OpenBibleAudio. It records the product vision, the
up-front architectural decisions that every later spec has to respect, and the phased roadmap.
Later specs (`0001-…`, `0002-…`) go deeper on each phase and may change a decision here only
by saying so explicitly and updating this document.

---

## 1. Vision

OpenBibleAudio is an Android audio player built to **listen to the Bible in the car**.

1. The user **brings their own audio Bible** (for example a purchased CSB audio Bible). The app
   ships no copyrighted Bible content.
2. The user navigates **by book and chapter**, and does it mainly through **Android Auto**. The
   phone UI is secondary but becomes a first-class surface over time.
3. The app follows **reading plans**. Each day of a plan is a set of chapter ranges plus,
   optionally, a **companion podcast episode** that plays after the reading. The motivating use
   case is *The Bible Recap*: play the day's chronological reading, then that day's Bible Recap
   episode.
4. Podcast episodes are **downloaded ahead of time** so nothing has to stream while driving.
5. Later, the app knows **where each verse starts**. It can show the current verse, offer a
   "back one verse" button, and eventually sync a text reader to the audio.

### 1.1 Non-goals (for now)

- A general-purpose podcast app (no browsing or subscribing to arbitrary feeds; see AD-9).
- Shipping, selling or redistributing Bible audio or text.
- iOS or other platforms.
- Cloud accounts or cross-device sync.

---

## 2. Glossary

| Term | Meaning |
|---|---|
| **Bible version** | One imported translation/recording, e.g. "CSB (narrator X)". Identified by a stable `versionId` slug. |
| **Book ID** | USFM 3-letter book code (`GEN`, `EXO`, … `REV`). The canonical book key everywhere in the app. |
| **Chapter audio** | The audio span for one chapter: a file plus an optional start/end offset within that file. |
| **Audio Bible Pack** | A folder of audio files plus a `manifest.json` describing them (AD-4). The unit of import. |
| **Layout profile** | Importer configuration describing how a vendor's files and folders map to book/chapter (AD-5). |
| **Plan** | A reading-plan definition: an ordered list of days, each with readings and an optional podcast link. |
| **Enrollment** | A user's instance of a plan: start date, Bible version, progress. |
| **Plan day queue** | The playback queue for one plan day: chapter audio for each reading, then the podcast episode. |

---

## 3. Architectural Decisions

Each decision has an ID so later specs and code comments can refer to it ("per AD-4").

### AD-1 — Platform & UI toolkit
- Kotlin, single-activity, **Jetpack Compose + Material 3** for the phone UI (already in the skeleton).
- `minSdk 28`, `targetSdk`/`compileSdk 37`, as configured.
- Bump the JVM target from 11 to 17 when the first pure-Kotlin modules are added, so all modules match.

### AD-2 — Playback & Android Auto: Media3 `MediaLibraryService`
- A single `PlaybackService : MediaLibraryService` owns one `ExoPlayer` and one `MediaLibrarySession`.
- **Android Auto is served entirely through the Media3 browse tree**: the standard media-app
  integration, which Play also requires for Auto audio apps. No Car App Library templates.
- **The phone UI is just another `MediaController` client** of the same session. Playback state
  has one source of truth, and Auto and the phone can never disagree.
- Features the player has to provide:
  - Continuous play: starting a chapter queues the chapters after it (to the end of the book by default, with a setting for the end of the Bible).
  - Chapters that are sub-spans of a larger file use `MediaItem.ClippingConfiguration`.
  - **Playback resumption** (`onPlaybackResumption`) so Auto and Bluetooth "play" resume where the user left off, even after process death.
  - Custom session commands for non-standard buttons (e.g. "back one verse", "skip back 15 s"), shown via media button preferences / custom layout so they appear in Auto.
  - Voice search ("play Genesis 3 on OpenBibleAudio") through the session's search and `onSetMediaItems` hooks, which parse a scripture reference.
  - Playback-speed setting, remembered separately for Bible audio and podcasts.
- A completion listener in the service records chapter and episode completions (≥ ~95 % played or naturally ended) to the database. Plan progress is driven from these records.

### AD-3 — Stable media IDs
Every browsable or playable node has a stable, parseable string ID. Auto hands back IDs
without URIs, so the service must be able to rebuild any item from its ID alone:

```
root
bible                                  # versions (or skip straight to books if only one)
bible/{versionId}
bible/{versionId}/{testament}          # OT | NT
bible/{versionId}/{BOOK}               # e.g. bible/csb/GEN
bible/{versionId}/{BOOK}/{chapter}     # playable
plan/{enrollmentId}                    # list of days
plan/{enrollmentId}/today              # playable: plan day queue for current day
plan/{enrollmentId}/day/{n}            # playable
podcast/{feedId}/{episodeGuidHash}     # playable
```

The ID grammar lives in `:core:model` and is unit-tested in both directions (format ↔ parse).

**Browse tree (Auto):** root tabs = **Today** (one item per active enrollment, plus "Continue
listening"), **Bible**, **Plans**. Auto limits depth and list length, so the 66-book list is split by
testament, and long chapter lists (Psalms) use grid content-style hints.

### AD-4 — The Audio Bible Pack manifest is the import contract
The app never guesses at file layouts during import. It imports an **Audio Bible Pack**: a
folder containing audio files plus `manifest.json`. Everything vendor-specific lives in the
importer that *produces* the manifest (AD-5). This keeps the app's import path small and
testable, and lets the same app code import packs produced by any tool.

Sketch of format version 1 (the full schema belongs in the Phase 1 spec):

```jsonc
{
  "formatVersion": 1,
  "bible": {
    "versionId": "csb",
    "abbreviation": "CSB",
    "name": "Christian Standard Bible",
    "language": "en",
    "versification": "eng",          // reserved for verse mapping; see AD-11
    "attribution": "…"               // shown in app's About for this version
  },
  "files": [
    { "path": "01 Genesis/Genesis 001.mp3", "sizeBytes": 1234567, "sha256": "…", "durationMs": 312000 }
  ],
  "chapters": [
    {
      "book": "GEN", "chapter": 1,
      "file": "01 Genesis/Genesis 001.mp3",
      "startMs": 0, "endMs": null,   // null = to end of file; supports book-per-file layouts
      "verses": null                 // Phase 4: [ {"verse":1,"startMs":0}, … ]
    }
  ]
}
```

- `formatVersion` lets the format evolve. The app rejects packs with a newer major version than it understands.
- The schema is defined once as `kotlinx.serialization` classes in `:core:model` and shared by the CLI and the app.
- Any audio format ExoPlayer decodes is allowed (MP3, M4A/AAC, Opus/Ogg, FLAC). The manifest stores no codec details.

### AD-5 — Importer is shared pure-Kotlin code; a CLI comes first
- `:core:importer` (pure Kotlin/JVM, no Android dependency) scans a file tree and emits a manifest.
  - Scanning goes through a small `FileTree` abstraction, so the same code runs over
    `java.io.File` (CLI) and, later, SAF `DocumentFile` (on-device import).
  - **Layout profiles** turn vendor layouts into book/chapter. A profile is mainly declarative
    (filename/folder regexes plus book-name aliases) with a code escape hatch for odd cases.
    Phase 1 ships one profile: the user's CSB layout.
  - Durations come from a pluggable `AudioProbe`. The CLI can use a JVM library or `ffprobe`;
    on device, `MediaMetadataRetriever`.
- `:tools:importer-cli` is a JVM application (`./gradlew :tools:importer-cli:run --args=…`) that
  writes `manifest.json` into the source folder, ready for the user to copy to the phone.
- Verse timing (Phase 4) plugs in as another importer stage that fills in `chapters[].verses`.
  It can come from vendor-supplied timing files, open datasets, or forced alignment against
  the text. The manifest is where it lands, whatever the source.

### AD-6 — Imported audio is copied into app-private storage, with an optional "move"
- Import flow: the user picks the pack folder (`ACTION_OPEN_DOCUMENT_TREE`). The app validates
  `manifest.json` and copies each file to `filesDir/bibles/{versionId}/{path}`, checking
  size (and hash when present).
- **"Move instead of copy" checkbox**: with this on, each source file is deleted right after its
  copy is verified, one file at a time. Peak extra space needed is about one file, not the whole
  Bible, which matters for phones that can't hold two copies.
- A free-space check runs before import. Without "move", the full pack size is required. With
  "move", the largest file plus a margin is required, and the dialog warns that the originals
  will be deleted.
- Import runs as a long-running `WorkManager` job with a progress notification. It can resume:
  files already verified are skipped on retry.
- After import, playback reads only from app storage. Moving or renaming the source folder
  later can't break anything.
- Deleting a Bible version removes its files and rows.

### AD-7 — Persistence
- **Room** for the library, plans and progress, podcast episodes, and (later) verse timings.
  **DataStore (Preferences)** for settings and small playback-resumption state.
- First-pass tables (details in phase specs):
  - `bible_version`, `audio_file`, `chapter_audio(versionId, book, chapter, fileId, startMs, endMs, durationMs)`
  - `verse_timing(versionId, book, chapter, verse, startMs)` (Phase 4)
  - `plan(id, version, definitionJson)`, `enrollment`, `enrollment_day_progress`, `chapter_completion`
  - `podcast_feed`, `podcast_episode(guid, title, pubDate, enclosureUrl, matchedDay, downloadState, localPath, positionMs)`
- Room migrations are written and tested from the first release that ships data. No destructive migrations.

### AD-8 — Reading plans are data, not code
- A plan is a versioned JSON document, defined in `:core:model` and parsed with kotlinx.serialization:

```jsonc
{
  "formatVersion": 1,
  "id": "bible-recap-chronological",
  "version": 1,
  "name": "The Bible Recap (Chronological)",
  "days": [
    { "day": 1, "readings": [ { "book": "GEN", "from": 1, "to": 3 } ] },
    { "day": 2, "readings": [ { "book": "GEN", "from": 4, "to": 7 } ] }
  ],
  "podcast": {                                   // optional
    "feedUrl": "https://…/rss",
    "position": "afterReadings",
    "match": { "titleRegex": "^Day (\\d{1,3})\\b", "dayGroup": 1 },
    "overrides": { "60": { "guid": "…" } }       // pin specific days when titles misbehave
  }
}
```

- Readings support whole chapters now. The model leaves room for verse-range readings
  (`"from": "1:1", "to": "2:3"`) once verse timing exists (Phase 4). Until then, a verse range
  plays the containing chapters.
- **Progress model:** "next unfinished day" is primary, because people fall behind and catch up.
  The enrollment's start date also gives a calendar position, so the UI can say "3 days behind"
  and offer to skip ahead. A day is complete when every item in its queue has completed (AD-2).
- Plans are added by importing a JSON file in the app. Whether any plan ships built in is an
  open question (§6).

### AD-9 — Podcasts: plan-linked RSS only, downloaded ahead
- Podcasts exist only as a property of a plan (AD-8). There is no general podcast browsing.
- An RSS fetcher (OkHttp plus `XmlPullParser`) refreshes linked feeds and matches episodes to
  plan days with the plan's rule. Ambiguous or unmatched days show up in the phone UI for
  manual pinning.
- A periodic `WorkManager` job **pre-downloads episodes for the next N days** of each active
  enrollment (default 7, configurable). It can be limited to unmetered networks, cleans up
  played episodes after a retention window, and stores files under `filesDir/podcasts/`.
- Playback of a plan day **never streams by default**. If an episode isn't downloaded yet, the
  queue plays the readings and Auto shows the episode as unavailable. A setting allows
  streaming as a fallback.
- Per-episode resume position is stored, so a podcast interrupted mid-drive picks up where it left off.

### AD-10 — Audio sources are abstracted for future streaming
- Playback resolves a chapter through a `BibleAudioSource` interface → `(Uri, clipStart, clipEnd)`.
  Phase 1 has one implementation: local imported files.
- Future streaming sources (open/free audio Bibles) implement the same interface, populate the
  same `chapter_audio`-shaped data (possibly lazily), and declare `requiresNetwork`. The browse
  tree, queues and plans don't need to change.

### AD-11 — References, canon & versification
- **USFM book codes** are the only book identifiers in data and IDs. Display names come from
  string resources, and later per version.
- `:core:model` owns the canon (the 66-book Protestant order to start with). It's modeled as
  data, so a version can declare additional books (deuterocanon) later.
- A small reference parser ("Gen 3", "1 John 2", "Song of Songs 4:2") is shared by voice
  search, plan authoring and the CLI.
- Versification differences (e.g. Psalm superscriptions, Malachi 3/4) only matter once verse
  timing and text sync exist. The manifest carries a `versification` tag now so that data
  doesn't have to be guessed later.

### AD-12 — Module structure
Kotlin-only domain code stays Android-free, so it's reusable by the CLI and fast to unit-test.
The Android app starts as **one module organized by feature package**. It will be split into
more Gradle modules only when build time or boundaries call for it.

```
:core:model        (Kotlin/JVM)  BookId/canon, references, media-ID grammar, manifest & plan schemas
:core:importer     (Kotlin/JVM)  FileTree, layout profiles, AudioProbe, manifest builder
:tools:importer-cli(Kotlin/JVM)  CLI wrapper around :core:importer
:app               (Android)     packages:
    data/          Room DB, DAOs, repositories, DataStore
    library/       pack import (SAF copy/move worker), BibleAudioSource impls
    plans/         plan import, enrollment & progress logic
    podcast/       RSS fetch/match, download worker
    playback/      PlaybackService, browse tree, queue builder, custom commands
    ui/            Compose screens (import, library, plans, now playing, settings)
```

### AD-13 — Dependency injection, concurrency, networking
- **Hilt** for DI. It integrates with `WorkManager`, `ViewModel` and the `MediaLibraryService`.
- **Coroutines + Flow** throughout. Room and DataStore expose `Flow`s that the UI and the
  browse tree observe; the browse tree notifies Auto of changes with `notifyChildrenChanged`.
- **OkHttp** for RSS and episode downloads. No other network use in early phases.

### AD-14 — Play Store readiness (eventual distribution target)
- No bundled copyrighted audio or text. Bible audio is always user-provided (or, later, from
  sources whose licenses allow redistribution or streaming).
- Third-party names and trademarks (e.g. "The Bible Recap") stay out of the app name, icon and
  listing. A plan definition that refers to a public podcast feed is user data, not branding.
- Request only the permissions needed: `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`,
  `INTERNET`, and `FOREGROUND_SERVICE_DATA_SYNC` for import/download workers. **No broad storage
  permissions**: all file access goes through SAF and app-private storage.
- Follow the Android Auto media-app quality guidelines (no video, no distracting UI, voice
  search, error states with clear messages).
- During development, Auto testing uses the **Desktop Head Unit (DHU)**. Sideloaded builds on a
  real car need Android Auto's developer setting "Unknown sources".

### AD-15 — Testing strategy
- Pure-Kotlin modules: thorough JVM unit tests (reference parsing, media-ID round-trips,
  manifest/plan parsing, layout profiles against fixture file trees, podcast title matching).
- `:app`: Room DAO/migration tests, queue-builder tests, and a Robolectric or instrumented test
  for the browse tree via `MediaBrowser`.
- Manual Auto verification on DHU, following a checklist kept in each phase spec.

---

## 4. Roadmap

Each phase gets its own spec before implementation starts. A phase is "done" when its spec's
acceptance criteria pass, including a DHU walkthrough where relevant.

### Phase 1 — Play my CSB Bible in the car (MVP)
- `:core:model` (books, references, media IDs, manifest schema) and `:core:importer` with a CSB layout profile.
- `:tools:importer-cli` generates `manifest.json` for the user's CSB files.
- In-app pack import via SAF with copy / **move** (AD-6), and a library screen.
- `PlaybackService` with the Bible browse tree, chapter playback with continuous play,
  resumption, and a minimal now-playing screen on the phone.
- **Exit criterion:** pick Genesis 1 in Auto (DHU), listen through to Genesis 2, kill the app,
  and resume from the steering wheel.

### Phase 2 — Reading plans
- Plan JSON format and in-app plan import. A Bible Recap plan definition is authored (§6).
- Enrollment, progress, "N days behind", a **Today** tab in Auto, and plan-day queues.

### Phase 3 — Companion podcasts
- RSS fetch and episode→day matching, manual pinning UI, pre-download worker, retention, and
  per-episode resume.
- Plan-day queue = readings + downloaded episode.
- **Exit criterion:** in airplane mode, "Today" in Auto plays the day's chapters and then the
  matching Bible Recap episode.

### Phase 4 — Verse awareness
- Manifest `verses` populated by an importer stage (method chosen in the phase spec).
- `verse_timing` table, current verse in now-playing (phone and Auto subtitle), and a
  **"back one verse"** custom command.
- Verse-range readings in plans.

### Phase 5 — Robust importing
- More layout profiles and a profile-authoring guide.
- **On-device import of a folder without a manifest**, running `:core:importer` over SAF.
- Book-per-file and multi-file-per-chapter layouts; multiple versions side by side.

### Phase 6 — Beyond
- Streaming `BibleAudioSource`s for openly licensed audio Bibles.
- A full phone browsing UI, and a Bible text reader that follows the playing verse (where the
  text license allows).
- Plan sharing/export, and additional canons.

---

## 5. Specification process

- Specs live in `specifications/` as `NNNN-short-title.md`, numbered in creation order.
- Each spec has a header table (Status: Draft → Accepted → Implemented / Superseded), a
  problem statement, the design, acceptance criteria, and open questions.
- Changing an architectural decision (AD-n) means updating this spec in the same change and
  noting why in a "Decision log" entry below.

### Decision log
| Date | Decision | Notes |
|---|---|---|
| 2026-11-08 | AD-1 … AD-15 adopted | Initial architecture. Copy-into-app-storage with optional move (AD-6), shared Kotlin importer (AD-5), plan-linked RSS only (AD-9), Play Store as eventual target (AD-14). |

---

## 6. Open questions

1. **CSB file layout.** What are the exact folder and file naming conventions, and the format
   (MP3? bitrate?) of the CSB set? Needed for the Phase 1 layout profile.
2. **Bible Recap plan data.** Author the day→chapters definition by hand from the published
   plan, or derive it from the podcast episode titles? Can a definition be redistributed in a
   Play build, or should it stay user-imported?
3. **Bible Recap feed.** Confirm the RSS URL and episode-title conventions (day numbering,
   special or bonus episodes, year-to-year reuse) for the matching rule.
4. **Continuous-play boundary.** Should play stop at the end of the book or continue into the
   next book? (Default: end of book, with a setting.)
5. **Verse timing source** (Phase 4): vendor timing data, an open alignment dataset, or our
   own forced alignment (e.g. Whisper-style alignment against public-domain text, or
   licensed text for the matching translation)?
6. **Project license** (if open-sourced) and app name and branding for Play.
