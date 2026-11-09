# Spec 0003 — Phases 2–3: Reading Plans & Companion Podcasts

| | |
|---|---|
| **Status** | Draft |
| **Created** | 2026-11-08 |
| **Author** | Danny Kaehn |
| **Phase** | 2 (reading plans) and 3 (companion podcasts); see spec 0000 §4 |
| **Depends on** | [0002 — Phase 1b: In-app Import & Playback](0002-phase-1b-import-and-playback.md) |
| **Implements** | AD-3 (plan/podcast IDs), AD-7 (schema v2), AD-8, AD-9, AD-13 (OkHttp), AD-14 (`INTERNET`), AD-15 |
| **Changes** | AD-3, AD-8, AD-9 (podcast *cycles*, whole-cycle download, feed snapshot), AD-12 (`:core:podcast`); see §13 and the spec 0000 decision log |

Phases 2 and 3 share one spec because the first plan, *The Bible Recap*, is defined by its podcast.
The two phases still ship as separate milestones (§12): plans work with no network at all, and
podcasts layer on top.

---

## 1. Problem & exit criteria

The user wants to follow *The Bible Recap* (TBR) chronological plan in the car: each day, play
that day's chapters from their own Bible audio, then that day's TBR episode, with nothing
streamed while driving.

TBR is unusual for a podcast. A new "year" of the same 365 episodes is published every
calendar year: Day 1 on Jan 1, Day 365 on Dec 31. The content and the readings are the same each
year (§2). A listener who starts in November shouldn't start at *Day 312* of the current year.
They start at **Day 1 of the most recent year that is complete in the feed**, and can download that
whole year ahead of time. Today (2026-11-08) that is **Year 7 = 2025**.

**Phase 2 exit criterion.** Import the TBR plan file and enroll with the BSB version. In Auto
(DHU), **Today → "The Bible Recap · Day 1"** plays Genesis 1, 2, 3 and stops. Day 1 shows as
complete, and Today now offers Day 2. On the phone, the enrollment says how many days behind the
calendar it is.

**Phase 3 exit criterion (spec 0000).** Enroll in the TBR plan. The app picks Year 7 (2025). Choose
**"Download whole year"** and let the download finish. In **airplane mode**, Today in Auto plays
Day 1's chapters and then the *Day 001 (Genesis 1-3) – Year 7* episode. Kill the app partway through the
episode, then press play: the episode resumes where it stopped.

### 1.1 In scope
- `:core:model`: plan schema v1 with podcast block, plan validator, reading-list parser, new media IDs.
- `:tools:importer-cli`: `plan from-feed` and `plan validate` commands. The TBR plan file is authored with them.
- App: plan import, enrollment, progress, plan-day queues, Today/Plans in Auto, phone plan screens.
- RSS fetch, episode→day matching with cycles, manual pinning, download worker (rolling or whole cycle),
  retention, per-episode resume, separate podcast speed.
- Room schema v2 with a tested migration from v1.

### 1.2 Out of scope
- General podcast browsing or subscribing (AD-9 non-goal).
- Verse-range readings (Phase 4). The schema reserves room for them (§3.1).
- Streaming a Bible version (Phase 6). Streaming an *episode* is an opt-in fallback only (§8.6).
- Plan authoring UI, plan sharing/export (Phase 6).

---

## 2. Research: the Bible Recap feed (fetched 2026-11-08)

Feed: `https://feed.podbean.com/thebiblerecap/feed.xml` (Podbean, RSS 2.0 + iTunes namespace, about 6.2 MB).

| Finding | Consequence for the design |
|---|---|
| The feed holds the **999 most recent items** and has no paging (`?page=2` returns the same items). The oldest item is now *Day 105 – Year 6* (2024-04-14). | Old years fall off the end. Year 7 (2025) is complete today, and Day 1 of 2025 will drop out around mid-2027. **Enrollment snapshots every episode of its cycle into the DB** (§7.3), and offers a whole-cycle download (§8). |
| Daily episodes: `<title>Day 001 (Genesis 1-3) - Year 7</title>`, `itunes:episodeType=full`, `itunes:season=7`, `itunes:episode=1` (sometimes `"010"`). | The day comes from the title (`^Day (\d{1,3}) \(`). The cycle comes from `itunes:season`. |
| `itunes:season` = "Year N", and Year N = calendar year 2018 + N (Year 7 = 2025, Year 8 = 2026). | The cycle label is shown as "Year 7 · 2025". The calendar year comes from the episodes' `pubDate`, not from a formula. |
| Day 209 of 2026 is titled *"… – Year **7**"* but tagged `season=8`. One-off double spaces (`") -  Year 8"`). | **`itunes:season` wins over the title's "Year N".** The title's year is only a fallback. Regexes allow any amount of whitespace. |
| Bonus items in every cycle: monthly *Reflections & Corrections*, *KNEEcap Preview*, *Prep Episode n*, *New Testament Prep*, *Deep Dive*, live-tour promos. Almost all are `episodeType=bonus`. | Matching requires `episodeType=full` **and** the title regex. |
| *"Day 274 The Bible KNEEcap Preview"* (2024) is `episodeType=full`, `itunes:episode=366`, with no season. | The title regex needs `Day N (`, so this item doesn't match. It's a fixture case. |
| Years 6, 7 and 8 have identical readings for every day they share (0 differences). Each complete year has exactly 365 matched days and no duplicates. 2024 was a leap year and still had 365 days (one day published a day late). | The plan is the same every year. Days are numbered, not dated. |
| `pubDate` for Day N ≈ Jan 1 + (N − 1) of the cycle's year. | Only used to label the cycle and to order duplicates. |
| One year ≈ **2.75 GB**, average 7.5 MB and 7.8 min per episode, 47.6 h in total. MP3 (`audio/mpeg`). | The whole-cycle download needs a free-space check and defaults to unmetered networks only (§8). |
| Enclosures are `https://mcdn.podbean.com/mf/web/…/TBR_001_….mp3` and **302-redirect** to a signed, time-limited URL on `s313.podbean.com`. The final response has `Accept-Ranges: bytes`, `ETag` and `Content-Length` (matches enclosure `length`). | Store the stable `mcdn` URL and follow redirects at download time. Never store the signed URL. Resume partial downloads with `Range`. |
| The feed response has an `etag`. | Refresh with a conditional GET (`If-None-Match` / `If-Modified-Since`). |

The reading plan PDF (*365-Day Chronological Reading Plan*, © The Bible Recap) lists the same
readings as the episode titles, in short form ("Gen 1-3", "1 Sam 18-20 / Ps 11, 59"). Every
reading is whole chapters. Neither source uses verse ranges.

---

## 3. `:core:model` additions

### 3.1 Plan schema v1 (`com.openbibleaudio.core.model.plan`)

This extends the AD-8 sketch. `kotlinx.serialization` classes, `PlanCodec` (decode/encode, pretty
and deterministic like `PackManifestCodec`).

```jsonc
{
  "formatVersion": 1,
  "id": "bible-recap-chronological",       // slug, [a-z0-9-]{1,64}; stable across plan versions
  "version": 1,                            // bump when days or podcast rules change
  "name": "The Bible Recap (Chronological)",
  "description": "365-day chronological plan with a daily recap podcast.",   // optional
  "source": "https://www.thebiblerecap.com",                                  // optional, shown in About
  "days": [
    { "day": 1,   "readings": [ { "book": "GEN", "from": 1, "to": 3 } ] },
    { "day": 105, "readings": [ { "book": "PSA", "from": 7 }, { "book": "PSA", "from": 27 },
                                { "book": "PSA", "from": 31 }, { "book": "PSA", "from": 34 },
                                { "book": "PSA", "from": 52 } ] },
    { "day": 60,  "readings": [ { "book": "NUM", "from": 14, "to": 15 }, { "book": "PSA", "from": 90 } ] }
  ],
  "podcast": {                                                   // optional
    "feedUrl": "https://feed.podbean.com/thebiblerecap/feed.xml",
    "position": "afterReadings",                                 // afterReadings | beforeReadings
    "match": {
      "episodeType": "full",                                     // optional itunes:episodeType filter
      "titleRegex": "^Day (\\d{1,3}) \\(",
      "dayGroup": 1
    },
    "cycle": {                                                   // optional; absent = one cycle (plain feed)
      "key": "itunesSeason",                                     // itunesSeason | titleRegex | pubDateYear
      "fallbackTitleRegex": "-\\s*Year\\s+(\\d+)\\s*$",          // used when key is missing on an item
      "select": "latestComplete"                                 // latestComplete | latest
    },
    "overrides": { "7/60": { "guid": "thebiblerecap.podbean.com/…" } }   // "{cycle}/{day}" → episode
  }
}
```

- **Readings** are `{ book, from, to? }` in chapters. `to` defaults to `from`. They play **in the
  listed order**, which is chronological, not canonical. Verse ranges arrive in Phase 4 as
  `"from": "1:1"`. The format version stays 1, because a v1 reader rejects string `from` values
  with a clear error.
- **Cycles.** A *cycle* is one complete run of a recurring feed. `cycle.key` names where an
  episode's cycle key comes from: `itunesSeason` (the TBR case), group 1 of a title regex, or the
  `pubDate` year. Keys are compared as integers. When an item lacks the key, `fallbackTitleRegex` is
  tried, and an item with no key at all belongs to no cycle and is ignored.
  `select` is the default cycle for a new enrollment (§7.2).
- **Overrides** pin `{cycle}/{day}` (or just `{day}` when there's no cycle block) to an episode
  GUID, for days whose titles misbehave. TBR v1 needs none (§2).

### 3.2 `PlanValidator`
Errors: unsupported `formatVersion`; bad `id`; `days` not exactly `1..N` with no gaps or
duplicates; a day with no readings; an unknown book; a chapter outside `Book.chapterCount`;
`from > to`; a regex that doesn't compile or lacks `dayGroup`; an override day outside `1..N`; a
`feedUrl` that isn't `https`. Info (`PARTIAL_COVERAGE`): the plan doesn't read every chapter
exactly once. The note gives the chapter count, repeats and books not read. Partial plans are
fine, so this is never an error or warning. The TBR plan covers all 1189 chapters exactly once, and the CLI
test checks that.

### 3.3 `ReadingListParser`
`fun parse(text: String): List<Reading>?` turns a TBR-style reading list into readings, built on
`ReferenceParser`'s book table (AD-11):

- Comma-separated segments. A segment that starts with a book name sets the current book. A bare
  number or range carries the previous book over: `"Psalm 7, 27, 31"` → PSA 7, PSA 27, PSA 31.
- Ranges with `-`, en dash or spaces around them (`"140 - 142"`).
- Accepts title-style full names ("1 Samuel", "Song of Solomon") and PDF-style short forms
  ("1 Sam", "Ps", "Jdg", "Php", "Phm", "1 Th", "Jas").
- Returns null on anything it can't parse fully. It never guesses.
- Test cases include every one of the 365 TBR title strings (as a test resource, §10.1).

### 3.4 Media IDs (extends AD-3)

```
plan                                   # "Plans" tab: one node per active enrollment
plan/{enrollmentId}                    # enrollment: Today + day groups
plan/{enrollmentId}/today              # playable: resolves to the current day's queue
plan/{enrollmentId}/days/{from}-{to}   # day group, e.g. plan/3/days/1-30   (NEW)
plan/{enrollmentId}/day/{n}            # playable: the day's queue
plan/{enrollmentId}/day/{n}/{i}        # playable leaf: item i of day n's queue (NEW)
today                                  # "Today" tab (NEW)
podcast/{feedId}/{guidHash}            # an episode outside a plan queue (resume, search)
```

- `enrollmentId` is the Room row ID (decimal). `feedId` = the first 12 hex characters of
  SHA-256(`feedUrl`). `guidHash` = the first 16 hex characters of SHA-256(`guid`).
- **Queue items always use `plan/{e}/day/{n}/{i}`**, never a bare `bible/…` or `podcast/…` ID, so
  completion recording and resumption know which enrollment and day an item belongs to. The
  service rebuilds the item from its ID alone (AD-3): `i` indexes the day's queue as built by §6.2.
- Round-trip and rejection tests follow the same pattern as spec 0002 §2.2.

---

## 4. Authoring the TBR plan (`:tools:importer-cli`)

The plan file is **generated from the feed** and **checked against the PDF by hand once**. Titles are
cleaner to parse than the PDF's table layout, and they are the same data (§2).

```bash
./gradlew :tools:importer-cli:run --args="plan from-feed https://feed.podbean.com/thebiblerecap/feed.xml \
    --id bible-recap-chronological --name 'The Bible Recap (Chronological)' \
    --description 'A 365-day chronological reading plan with a daily recap podcast. Readings play in the listed order, followed by that day’s episode.' \
    --source-url https://www.thebiblerecap.com \
    --match-title '^Day (\d{1,3}) \((.+)\)' --readings-group 2 --episode-type full \
    --cycle itunesSeason --fallback-cycle-regex '-\s*Year\s+(\d+)\s*$' \
    --out plans/bible-recap-chronological.json"
./gradlew :tools:importer-cli:run --args="plan validate plans/bible-recap-chronological.json \
    --feed https://feed.podbean.com/thebiblerecap/feed.xml --readings-group 2"
```

(`SOURCE` may also be a saved `feed.xml`; then `--feed-url` gives the URL to store in the plan.)

- `from-feed` takes a URL or a local `feed.xml`. It builds the readings from the most recent
  complete cycle, and **fails if any other complete cycle in the feed disagrees** on any day's
  readings (the check that found 0 differences in §2). It prints a per-cycle summary
  (`Year 7: 365/365 days, 2.75 GB`).
- `validate` runs `PlanValidator`. When `--feed` is given, it also runs the matcher (§7) and
  reports per-cycle coverage, the cycle new enrollments would use, unmatched days and duplicates.
  With `--readings-group N` it also compares each matched title's readings with the plan and
  fails on any disagreement. Exit codes match the pack commands (0 / 1 / 2).
- The output, `plans/bible-recap-chronological.json`, is committed in the repo (a new top-level
  `plans/` folder). Whether the app may *bundle* it is open question 1. Until that's settled, the user
  imports it like any other plan.
- A one-off manual check of the generated JSON against the PDF is recorded in the PR that adds it.

---

## 5. Persistence (AD-7) — Room schema v2

```
plan
  id TEXT, version INTEGER, name TEXT, definitionJson TEXT, importedAt INTEGER
  PK(id, version)                  -- an older version stays while enrollments still use it

enrollment
  id INTEGER PK autoinc
  planId TEXT, planVersion INTEGER  FK→plan
  versionId TEXT?                   -- Bible version; null = "whichever is the default at play time"
  startDate TEXT                    -- ISO local date; calendar position = today − startDate + 1
  podcastCycle INTEGER?             -- pinned cycle key (e.g. 7); null when no podcast or no cycle block
  downloadMode TEXT                 -- ROLLING | ALL | OFF
  rollingDays INTEGER               -- default 14
  state TEXT                        -- ACTIVE | PAUSED | FINISHED | ARCHIVED
  createdAt INTEGER

enrollment_day
  enrollmentId FK ON DELETE CASCADE, day INTEGER,
  status TEXT                       -- PENDING | IN_PROGRESS | COMPLETE | SKIPPED
  completedAt INTEGER?, PK(enrollmentId, day)

enrollment_item_completion
  enrollmentId FK ON DELETE CASCADE, day INTEGER, itemKey TEXT, completedAt INTEGER
  PK(enrollmentId, day, itemKey)    -- itemKey: "ch/GEN/1" or "ep/{guidHash}" (stable, unlike queue index i)

podcast_feed
  feedId TEXT PK, url TEXT, title TEXT?, etag TEXT?, lastModified TEXT?,
  lastFetchedAt INTEGER?, lastError TEXT?

podcast_episode
  feedId FK ON DELETE CASCADE, guidHash TEXT, guid TEXT,
  title TEXT, pubDate INTEGER, enclosureUrl TEXT, enclosureLength INTEGER?, durationMs INTEGER?,
  episodeType TEXT?, cycle INTEGER?, matchedDay INTEGER?,
  matchSource TEXT?                 -- TITLE | PLAN_OVERRIDE | MANUAL
  inFeed INTEGER (bool)             -- false once it has dropped out of the feed; the row is kept
  downloadState TEXT                -- NONE | QUEUED | DOWNLOADING | DOWNLOADED | FAILED
  localPath TEXT?, downloadedBytes INTEGER?, downloadError TEXT?,
  positionMs INTEGER, lastPlayedAt INTEGER?, completedAt INTEGER?
  PK(feedId, guidHash), INDEX(feedId, cycle, matchedDay)

manual_pin
  enrollmentId FK ON DELETE CASCADE, day INTEGER, feedId, guidHash, PK(enrollmentId, day)
```

- **Migration 1 → 2** only adds tables. It ships with a `MigrationTestHelper` test, and `2.json` is exported and committed.
- `chapter_completion` (spec 0002) stays the global listening log. Plan progress uses
  `enrollment_item_completion`, so listening to Genesis 1 from the Bible tab doesn't tick off Day 1
  (but see the "Mark complete" action, §9).
- Episode rows are keyed per feed, not per enrollment. Two enrollments on the same cycle share the
  downloaded files.
- DataStore settings gain `podcastSpeed` (1.0), `podcastUnmeteredOnly` (true), `podcastRetentionDays`
  (7), `keepPlayedEpisodes` (false), `allowEpisodeStreaming` (false) and `continueToNextDay` (false).

---

## 6. Plans & enrollment (AD-8, Phase 2)

### 6.1 Import & enroll
1. **Plans → "Import plan"** opens `ACTION_OPEN_DOCUMENT` for `application/json`. The file is
   decoded and validated, and the first 20 issues are shown on failure. The same `id` + `version`
   that's already imported is a no-op. A newer `version` is stored next to the old one, and
   existing enrollments keep their version until the user chooses "Update plan" (open question 4).
2. **Enroll** dialog: Bible version (if more than one is READY), start date (default today; the
   date only drives "days behind"), and, for plans with a podcast, the cycle and download choices
   in §7.2 / §8.1. One plan can have several enrollments (e.g. two people on one phone), with no
   uniqueness constraint.

### 6.2 Day queue (`PlanQueueBuilder`, pure Kotlin)
`build(plan, day, versionId, available: Set<ChapterRef>, episode: EpisodeAvailability): List<QueueEntry>`
- Each reading expands to its chapters in order. Chapters missing from the Bible version are
  dropped, and the day is flagged "Genesis 3 is missing in BSB". Nothing else changes.
- The episode is appended (or prepended for `beforeReadings`) when it's **downloaded**, or when
  it's matched, streaming is allowed and the network is up (§8.6). Otherwise there's no episode
  entry, and the day carries `episodeUnavailableReason` (NOT_MATCHED, NOT_DOWNLOADED, …).
- `QueueEntry(itemKey, mediaId = plan/{e}/day/{n}/{i})`. The `MediaItem` mapping uses the
  spec 0002 chapter metadata. Episodes use `MEDIA_TYPE_PODCAST_EPISODE`, the title "Day 1 · The Bible
  Recap", the artist = feed title and the episode title as subtitle.
- At the end of a day's queue playback stops, unless `continueToNextDay` is on. Then the next
  incomplete day is appended when the last item starts (regardless of the calendar, §6.3), so
  listening can run on through several days in one drive.
- With `continueToNextDay` off, the listener continues manually: **Today** plays the following
  day straight away. The phone's Now Playing shows a "Play Day 48 next" button when a
  plan day's queue has ended.

### 6.3 Progress
- **Current day** = the lowest day whose status is PENDING or IN_PROGRESS. When none is left the
  enrollment becomes FINISHED.
- **Calendar day** = `today − startDate + 1`, clamped to 1..N. **Behind** = calendar − current
  (only shown when > 0). **Ahead** = current − calendar − 1 (when > 0), i.e. how many days past
  the calendar the listener has gone.
- **The calendar never gates playback.** Progress is driven only by completion, so after finishing
  a day — including when the listener is caught up or already ahead of the calendar — the next
  day is immediately the current day and is playable from Today, Plans, voice and the phone. There
  is no "come back tomorrow" state. Any day can also be played out of order from the day list;
  that doesn't move the current day except by completing it.
- **Caught-up presentation:** when the calendar day is already COMPLETE, the Today entry still
  plays the current day, but its title reads *"The Bible Recap · Day 48 (up next)"* and the phone
  card says *"Today's reading done ✓ — keep going with Day 48"*. Behind/ahead badges are
  informational only.
- A day becomes COMPLETE when every *reading* item has an `enrollment_item_completion` row **and**
  the episode is complete or was unavailable when the day's queue was built (open question 3).
  "Complete" for an item uses the spec 0002 rule (≥ 95 % or a natural end).
- **Skip to today** marks every PENDING day before the calendar day as SKIPPED. **Mark complete** /
  **Mark not done** per day on the phone. **Restart plan** resets every day to PENDING.
- `PlanProgress` (pure Kotlin) computes all of this from rows, and is unit-tested.

---

## 7. Feeds, cycles & matching (AD-9, Phase 3)

### 7.1 Fetch (`FeedFetcher`, OkHttp)
- Conditional GET with the stored `etag` / `lastModified`. `304` means nothing changed.
- `XmlPullParser` stream-parses the body (6+ MB). It reads `title`, `guid`, `pubDate`, `enclosure@url|length|type`,
  `itunes:duration` (seconds or `h:mm:ss`), `itunes:episodeType`, `itunes:season` and
  `itunes:episode`. HTML descriptions are skipped. The body is capped at 32 MB. Items with no
  audio enclosure are ignored.
- The parser, matcher and cycle selection live in a **new pure-Kotlin module `:core:podcast`**
  (AD-12), fed an `InputStream`, so JVM tests run against fixture XML and the CLI's `plan from-feed`
  and `plan validate --feed` share the code. Only the OkHttp call is Android-side.
- **When it runs:** on enrollment, when the Plans screen opens and the last fetch is more than 12 h
  old, and in a daily `FeedRefreshWorker` (network-connected constraint). The user-agent is
  `OpenBibleAudio/<versionName>`, with no personal data.

### 7.2 Cycles
- Each parsed item gets `cycle` = its key from §3.1, or null.
- A cycle is **complete** when every plan day `1..N` has exactly one matched episode (after
  overrides and pins).
- `latestComplete` picks the highest complete cycle key. If none is complete, it picks the cycle
  with the most matched days, and the enrollment dialog says *"Only 261 of 365 episodes are
  available for Year 6."* `latest` picks the highest key, complete or not, so the listener follows
  the year as it's published.
- **Enroll dialog (podcast plans):** *"Recap episodes: Year 7 · 2025 (all 365 available)"*, with a
  picker listing every cycle and its coverage, e.g. *"Year 8 · 2026 — 312 / 365 so far"*. The
  chosen key is stored in `enrollment.podcastCycle`. **An enrollment never changes cycle by
  itself.** When Year 8 is complete, *new* enrollments default to it, and existing ones stay on Year 7.
- The cycle label's year is the most common `pubDate` year among the cycle's matched episodes.

### 7.3 Matching & snapshot
- For each item in the feed whose `episodeType` passes the filter and whose title matches
  `titleRegex`: `matchedDay = group(dayGroup).toInt()`, if it's in `1..N`.
- **Duplicates** (same cycle and day): the latest `pubDate` wins, and the others get
  `matchedDay = null`. They're listed in the pin UI.
- Precedence per `(enrollment, day)`: **manual pin → plan override → title match**.
- Every refresh **upserts** `podcast_episode` rows. Items no longer in the feed get `inFeed = false`
  and are **never deleted** while an enrollment's cycle references them. That's the snapshot that
  keeps a 2025 enrollment working after 2025 drops out of the 999-item window. Downloading needs only
  the stored `enclosureUrl`.
- Risk: Podbean may stop serving enclosures once they've left the feed. The enroll dialog
  recommends a whole-year download for a cycle that is older than the current one (§8.1), and
  open question 5 tracks it.

### 7.4 Manual pinning (phone)
The enrollment's day list flags days that are *unmatched* or *duplicated*. Tapping one lists the
cycle's unmatched episodes (and the feed's other episodes behind a "Show all"), newest first.
Picking one writes `manual_pin`. Unpin goes back to automatic.

---

## 8. Downloads (AD-9, Phase 3)

### 8.1 Modes (per enrollment)
| Mode | Behaviour |
|---|---|
| **ALL** ("Download whole year") | Queue every matched episode of the cycle, in day order starting at the current day, wrapping round to earlier days last. |
| **ROLLING** (default `rollingDays = 14`) | Keep the episodes for the current day and the next `rollingDays − 1` incomplete days downloaded. Topped up after each day completes and by the daily worker. |
| **OFF** | Nothing is downloaded automatically. The day list still has per-day "Download". |

- The enroll dialog shows both main options with sizes from `enclosureLength`, e.g. *"Whole
  year — 2.75 GB"* and *"Next 14 days — about 105 MB"*, plus the free space. **ALL is preselected**
  when free space ≥ total + 1 GB, and **always recommended for a past cycle** (§7.3). Otherwise
  ROLLING is.
- The mode can be changed later from the enrollment screen. Switching from ALL to ROLLING
  doesn't delete anything; retention (§8.4) tidies up.

### 8.2 `EpisodeDownloadWorker` (CoroutineWorker, Hilt)
- Unique work `podcast-download/{feedId}` with policy `APPEND_OR_REPLACE`. One episode at a time,
  in queue order. Constraints: `NetworkType.UNMETERED` when `podcastUnmeteredOnly`, otherwise
  `CONNECTED`, plus `requiresStorageNotLow`.
- Runs in the foreground (`setForeground`, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`) when more than 3
  episodes are queued. The notification reads *"Downloading recaps: 41 / 365"* and has a Cancel
  action. A short top-up runs without a notification.
- Per episode:
  1. GET `enclosureUrl`, following redirects (OkHttp default). If the `.part` file exists, send
     `Range: bytes={size}-` with `If-Range: {etag}`. A `200` instead of a `206` restarts the file.
  2. Stream to `filesDir/podcasts/{feedId}/{guidHash}.mp3.part`. The expected size is
     `Content-Length` (the full length on a 206 comes from `Content-Range`). A difference from
     `enclosureLength` is logged as a warning and isn't an error.
  3. Check the byte count, `fsync`, rename atomically, then set `DOWNLOADED`, `localPath` and
     `durationMs` (from `MediaMetadataRetriever` if `itunes:duration` was missing). The order
     matches spec 0002 §6.2.
  4. `setProgress` about every 500 ms.
- Errors: IO or HTTP 5xx → `Result.retry()` (exponential backoff), and the `.part` is kept for
  resuming. HTTP 404/410 → that episode is `FAILED` ("No longer available from the publisher") and
  the worker moves on to the next one. Disk full → stop and set `FAILED` with a message.
- **API 35+ dataSync limit:** a `dataSync` foreground service may run about 6 h per 24 h. A full year
  on Wi-Fi is well within that, and if the system stops the worker it resumes from the `.part` and the
  remaining queue. (Moving to user-initiated data-transfer jobs is open question 6.)

### 8.3 Storage & paths
- Files go under `filesDir/podcasts/{feedId}/`. Paths are app-chosen, never derived from feed
  content, and checked with `canonicalPath` like spec 0002.
- The free-space check for ALL uses the same `getAllocatableBytes` call as import, with a 256 MB margin.
- Plans → overflow → **Storage** shows the space used per enrollment, with "Delete downloaded
  episodes" (played only / all).

### 8.4 Retention
After each completion and once a day: delete the file of every episode whose day is COMPLETE or
SKIPPED in *every* enrollment that references it and that was completed more than
`podcastRetentionDays` ago, unless `keepPlayedEpisodes` is on. The row stays (`downloadState = NONE`),
along with its position and completion. Deleted episodes aren't downloaded again unless the user
reopens the day (*Mark not done*).

### 8.5 Per-episode resume
`podcast_episode.positionMs` is saved on the same schedule as the spec 0002 §7.6 resumption
store, along with the `plan/…/{i}` media ID in `ResumptionStore`. Starting an episode item seeks to
`positionMs − 2 s` (0 if it's complete). An episode is complete at ≥ 95 % or a natural end, which
also sets `completedAt` and the enrollment item completion.

### 8.6 Streaming fallback (opt-in)
Off by default (AD-9). When `allowEpisodeStreaming` is on and an episode isn't downloaded, the
queue uses the `enclosureUrl` (ExoPlayer follows the redirect) as long as the network is up. Auto
shows the subtitle "Streaming".

---

## 9. Playback & UI

### 9.1 Auto browse tree (extends spec 0002 §7.4)
```
root
├── today  "Today"
│   ├── plan/3/today        "The Bible Recap · Day 47"   subtitle "Genesis 46-47 · Recap ✓"   (playable)
│   └── [resumption item]   "Continue: Genesis 12"                                         (playable)
├── bible  "Bible"          (unchanged)
└── plan   "Plans"
    └── plan/3  "The Bible Recap (Chronological)"   subtitle "Day 47 · 3 days behind"
        ├── plan/3/today
        └── plan/3/days/1-30 … plan/3/days/361-365     (groups of 30)
            └── plan/3/day/1 "Day 1 ✓"  subtitle "Genesis 1-3"   (playable)
```
- The root order is **Today, Bible, Plans**. With no enrollments, Today shows only the resumption
  item (or the info item *"No plans yet. Add one on your phone."*), and Plans shows that same info item.
- `plan/{e}/today` always means the *current* day (§6.3), even when the calendar day is already
  done; its title adds "(up next)" in that case.
- The `today` subtitle shows the episode's state: *Recap ✓* (downloaded), *Recap not downloaded* or
  *No recap for this day*.
- The day group size is 30, so the list stays under Auto's limits. The group that holds the current
  day comes first in the list.
- `notifyChildrenChanged` fires for `today`, `plan` and `plan/{e}` when progress, downloads or
  enrollments change (AD-13).

### 9.2 Session behaviour
- `plan/{e}/today` and `plan/{e}/day/{n}` resolve to that day's queue. `today` is resolved when
  it's played, not when it's browsed.
- **Speed:** on every media-item transition, apply `bibleSpeed` or `podcastSpeed` depending on the
  item type. The Now Playing speed chip edits the setting for the current type (AD-2).
- **Voice search:** before reference parsing, check for *"today"*, *"today's reading"*, *"my
  plan"* or an enrollment's plan name ("play the bible recap"). These map to that enrollment's
  `today` (or the most recently played active enrollment). Everything else goes through spec 0002 §7.7.
- **Resumption:** a stored `plan/…/{i}` ID rebuilds that day's queue and starts at item `i`. If
  the day has since been completed, it resumes the current day instead.
- Completion recording (spec 0002 §7.8) also writes `enrollment_item_completion` for `plan/…` items,
  and then recomputes the day status. When it becomes COMPLETE, the rolling download is topped up.

### 9.3 Phone UI
| Screen | Contents |
|---|---|
| **Plans** (new bottom-nav destination) | Enrollment cards: name, "Day 47 of 365", behind badge, download status ("312 / 365 downloaded"). An "Import plan" FAB. A list of imported plans with "Enroll". |
| **Enroll** | §6.1 and §7.2 / §8.1 choices, with sizes and free space. |
| **Enrollment** | A "Play today" button. Day list (lazy, the current day scrolled into view): readings, status icon, episode state, per-day overflow (Play, Mark complete / not done, Download / Delete episode, Pin episode). Header actions: Skip to today, Download mode, Restart, Archive, Delete. |
| **Settings** | A Podcasts section: unmetered only, retention days, keep played, streaming fallback, podcast speed, continue to next day. |

Strings live in `strings.xml`. The plan name, episode titles and readings are user data and shown as-is.

---

## 10. Testing (AD-15)

### 10.1 JVM unit tests
- `:core:model`: `PlanCodec` round-trip; `PlanValidator` (every error case in §3.2);
  `ReadingListParser` across all 365 TBR title strings (as a resource file
  `tbr-readings.txt`, readings only, no audio or descriptions) and the PDF short forms; plan
  `MediaId` round-trip and rejection.
- **Feed fixture:** a hand-written `tbr-shaped-feed.xml` in test fixtures (synthetic GUIDs and URLs).
  It reproduces every quirk in §2: two complete cycles plus a partial third, bonus items, a season-8
  item titled "Year 7", the season-less `full` KNEEcap item with `episode=366`, double spaces,
  `"010"` episode numbers, a duplicate day and `h:mm:ss` durations. The parser, cycle selection
  (`latestComplete` → the older complete cycle, `latest` → the partial one) and match precedence
  (pin > override > title) are tested against it.
- **Opt-in real-feed test**, like the BSB corpus test:
  `./gradlew :core:podcast:test --tests "*TbrFeedTest*" -Poba.feed.tbr=<abs path>/feed.xml`. It
  asserts that the latest complete cycle has 365/365 days and that the readings agree with
  `plans/bible-recap-chronological.json`.
- `:app` (pure): `PlanQueueBuilder` (order, missing chapters, before/after, episode unavailable
  reasons), `PlanProgress` (current/calendar/behind, skip to today, completion with and without an
  episode), rolling-window selection, retention selection, download-size estimates.
- `:tools:importer-cli`: `plan from-feed` against the fixture feed (deterministic output, golden
  file), plus the cross-cycle disagreement failure and `plan validate` exit codes.

### 10.2 Robolectric / instrumented
- Room: DAO tests for the new tables, and **`MIGRATION_1_2` with `MigrationTestHelper`**.
- `EpisodeDownloader` (the logic behind the worker) with **OkHttp `MockWebServer`** (new test
  dependency): a 302 → 200 chain, a resume with `206`, a `200` to a `Range` request (restart), 404 →
  FAILED and on to the next, a size mismatch and cancel.
- **End-to-end** (instrumented, managed device, `PlanPlaybackTest`): the mini pack (GEN 1–3, …) plus
  a tiny plan (Day 1 = GEN 1–2 + episode, Day 2 = GEN 3) whose feed is served by `MockWebServer` with
  a synthetic MP3 episode. Enroll in ALL mode and wait for the download. Then `MediaBrowser`:
  root → today → play, assert the queue is GEN 1, GEN 2, episode; play through and assert that Day 1 is
  COMPLETE and `today` now resolves to Day 2. The network is disabled in the test after the
  download (the `MockWebServer` is shut down).

### 10.3 DHU checklist (manual)
1. No enrollments: the root shows Today, Bible, Plans, and Today has the info item.
2. Import the TBR plan on the phone and enroll with BSB. The dialog picks **Year 7 · 2025 (365/365)**
   and preselects "Whole year — 2.75 GB". The notification shows download progress. Auto's Today
   shows "The Bible Recap · Day 1" without restarting Auto.
3. Plans → enrollment → day groups of 30. Day 105 lists five psalms, and playing it queues PSA 7, 27, 31, 34, 52 and then the episode.
4. Today plays GEN 1, 2, 3 at Bible speed, then the Day 001 episode at podcast speed (e.g. 1.0 vs 1.5).
5. **Airplane mode** (after the download): Today still plays the episode.
6. Kill the process mid-episode and press play. The episode resumes about 2 s before where it stopped.
7. When the episode ends, Day 1 is ✓ and Today offers "Day 2 (up next)" even though the calendar
   is still on Day 1. Playback stops (continue to next day is off). Playing Today now plays Day 2.
   With continue to next day on, Day 2 follows Day 1 without stopping.
8. Voice: "Play today's reading on OpenBibleAudio", "Play the Bible Recap".
9. With an episode file deleted by hand (Storage screen) and streaming off, the Today subtitle shows
   "Recap not downloaded" and the day plays readings only.
10. Set the phone date three days ahead: the enrollment card says "3 days behind", and Skip to today works.

---

## 11. Manifest & dependencies

- Add `<uses-permission android:name="android.permission.INTERNET" />` (AD-14 already allows it). Spec
  0002 acceptance criterion 4 ("no INTERNET") now applies to the Phase 1 build only.
- `ACCESS_NETWORK_STATE` comes in through WorkManager's network constraints (manifest merge).
- New catalog entries: `com.squareup.okhttp3:okhttp`, `okhttp-bom`, test `mockwebserver`.
- New module `:core:podcast` (Kotlin/JVM 17, depends on `:core:model`), included by `:app` and
  `:tools:importer-cli`. Its parser is written against the `org.xmlpull.v1` API. Android provides
  that API, and the JVM (tests, CLI) gets it from `net.sf.kxml:kxml2`, which is declared
  `compileOnly` + `testImplementation` in `:core:podcast` and `implementation` in the CLI, so the
  app never bundles a second copy. `FeedParser.parse` takes an `XmlPullParser` instance
  (`Xml.newPullParser()` on Android, `KXmlParser()` on the JVM). It parses strict XML on purpose
  and requires the closing `</rss>`, so a truncated download fails instead of looking like a feed
  whose old items were removed (§7.3).

---

## 12. Milestones & acceptance criteria

**M2 — Phase 2 (plans, no network).** §3 (no `podcast` handling beyond parsing), §4, §5 (all
tables, so there's only one migration), §6, the §9 browse tree and phone screens without episode
states, and the §10 tests that apply. Exit: the Phase 2 criterion in §1.

**M3 — Phase 3 (podcasts).** §7, §8, the episode parts of §6.2/§9, `INTERNET`. Exit: the Phase 3 criterion in §1.

For each milestone:
1. `./gradlew test`, `./gradlew lint` and `./gradlew pixel9Api37DebugAndroidTest` are green.
2. The DHU checklist items for that milestone pass (M2: 1, 3 without the episode, 7, 10; M3: all).
3. M3: the real TBR feed run through `plan validate --feed` reports Year 7 as 365/365, with 0 disagreements against the committed plan.
4. M3: a whole-year download of Year 7 completes on an emulator over Wi-Fi, and survives a process kill mid-way without downloading completed files again.

---

## 13. Changes to spec 0000

- **AD-3:** adds `today`, `plan`, `plan/{e}/days/{from}-{to}` and `plan/{e}/day/{n}/{i}`; queue items always use plan-scoped IDs.
- **AD-8:** the podcast block gains `match.episodeType`, `cycle` and `"{cycle}/{day}"` override keys.
  Readings play in listed order and `to` is optional.
- **AD-9:** episodes are grouped into *cycles*. An enrollment pins a cycle (default: latest complete).
  Matched episodes are snapshotted and never deleted while referenced, and the download mode is
  ALL / ROLLING (default 14) / OFF instead of a fixed "next N days (default 7)".
- **AD-12:** adds `:core:podcast` (Kotlin/JVM): RSS parser, episode→day matcher, cycle selection.
- **§6 open questions 2 and 3** are answered by §4 and §2 here.

---

## 14. Open questions

1. **Bundling the TBR plan.** The day→chapters list is factual, but the PDF is marked © The Bible
   Recap, and AD-14 keeps third-party names out of branding. Bundle `plans/bible-recap-chronological.json`
   in the app (as a "suggested plan"), or keep it user-imported? Asking the publisher would settle it.
   Current plan: user-imported. Debug builds may preload it for testing.
2. **Feed terms.** Check that Podbean's / the publisher's terms allow whole-year pre-download by a
   non-podcast app. A private, single-user download is the same as any podcast app's "download all".
3. **Episode required for day completion?** The current rule counts the day complete when the episode
   wasn't available at queue time, so a failed download doesn't block Today in the car. The alternative
   is to require it and show a "recap pending" state. Decide after driving with it.
4. **Plan updates.** When `version` 2 of an imported plan arrives, migrate enrollments automatically
   if the day count is unchanged, or always ask?
5. **Old enclosures.** Do `mcdn.podbean.com` enclosure URLs still work after their item leaves the
   999-item feed? Test in mid-2027 against Year 6 URLs captured now. If they don't, the
   whole-year download for a past cycle becomes required rather than recommended.
6. **Download job type.** On API 34+, would a user-initiated data transfer job (`JobScheduler`,
   `RUN_USER_INITIATED_JOBS`) suit the whole-year download better than WorkManager plus `dataSync`?
7. **Day group size** (30) and whether the Today tab also lists *tomorrow* for listeners who like to
   get ahead.
