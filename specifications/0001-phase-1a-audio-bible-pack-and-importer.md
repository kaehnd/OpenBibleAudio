# Spec 0001 — Phase 1a: Audio Bible Pack format v1 & Importer CLI

| | |
|---|---|
| **Status** | Draft |
| **Created** | 2026-11-08 |
| **Author** | Danny Kaehn |
| **Phase** | 1a (first half of the Phase 1 MVP; see spec 0000 §4) |
| **Implements** | AD-1 (JVM 17 bump), AD-4, AD-5, AD-11 (book codes), AD-12, AD-15 |
| **Followed by** | [0002 — Phase 1b: In-app import & playback](0002-phase-1b-import-and-playback.md) |

---

## 1. Problem

The app imports an **Audio Bible Pack**: a folder of audio files plus a `manifest.json` (AD-4).
Before any Android code can import or play anything, we need:

1. A precise, versioned definition of `manifest.json` (format v1). The app (Phase 1b) and the
   importer both depend on it.
2. A tool that produces that manifest from a real vendor file layout. This is the shared
   pure-Kotlin importer (AD-5) plus a JVM CLI around it.

This phase is done when the CLI turns the test corpus (§3) into a valid, deterministic manifest
that the shared validator accepts.

### 1.1 Goals
- Manifest **format v1**, fully specified (§4), with `kotlinx.serialization` classes and a
  validator in `:core:model`.
- `:core:importer` (pure Kotlin/JVM) containing the `FileTree` abstraction, layout profiles, the
  `AudioProbe` (with a built-in MP3 frame scanner), the hasher and the manifest builder.
- `:tools:importer-cli` with `generate`, `validate` and `profiles` commands.
- One layout profile: **`bsb-gilbert`** (§3).

### 1.2 Non-goals (this phase)
- Anything in `:app`. In-app import, playback and Auto are in spec 0002.
- The CSB layout profile. It's deferred to Phase 5 until the CSB file layout is known (spec 0000 §6 Q1).
- External or user-authored profile files. Profiles are compiled-in Kotlin data for now (§6.3).
- Book-per-file or multi-file-per-chapter layouts. The **format** supports them through
  `startMs`/`endMs`, but no Phase 1 profile produces them.
- Verse timing. `chapters[].verses` is reserved (§4.5).
- Media-ID grammar and the scripture reference parser. Their only consumers are playback and
  voice search, so they're specified in 0002 even though they live in `:core:model`.

---

## 2. Background decisions made for this spec

| Question | Decision |
|---|---|
| Test corpus | The **Berean Standard Bible (BSB)**, Gilbert narration, from openbible.com. It stands in for the CSB during development. |
| Durations | A built-in **pure-Kotlin MP3 frame scanner**, with no dependency (§6.4). `ffprobe` is an optional fallback for non-MP3 files only. |
| Profiles | **Built-in Kotlin data objects**, chosen with `--profile <id>`. |
| Phase split | Phase 1 = 1a (this spec) + 1b (spec 0002). Later phase numbers stay as they are. |

---

## 3. Test corpus: BSB (Gilbert)

**How to get it.** `python tools/download_bsb_gilbert.py [dest]` downloads (and can resume) the
whole index from `https://openbible.com/audio/gilbert/` into `bible_audio/bsb_gilbert/` by default.
`/bible_audio` is gitignored. Audio never goes into the repo.

**What it looks like** (as of 2026-11-08):

| Property | Value |
|---|---|
| Files | 1189 MP3s, one per chapter, flat in one folder, no subfolders. That is the full 66-book Protestant canon. |
| Total size | ≈ 5.8 GB (smallest ≈ 0.4 MB, largest ≈ 21 MB, PSA 119) |
| Naming | `BSB_{order:2}_{abbr}_{chapter:3}_G.mp3`, e.g. `BSB_01_Gen_001_G.mp3`, `BSB_19_Psa_119_G.mp3`, `BSB_62_1Jn_005_G.mp3` |
| `{order}` | 01–66, canonical order (same as `Book.ordinal + 1`) |
| `{abbr}` | Mixed-case 3-character code. **All of them match the USFM code once uppercased, except `Tts` → `TIT`.** (`Sng`, `Ezk`, `Jol`, `Nam`, `Mrk`, `Jhn`, `Php`, `Jas`, `Jud` are all valid USFM.) |
| Codec | MPEG-1 Layer III, **192 kbps CBR, 44.1 kHz, mono** |
| VBR header | **No Xing/Info/VBRI header.** The duration has to be computed by walking frames, or estimated from size ÷ bitrate. This matters for player seeking too (0002 §7.2). |
| Tags | ID3v2.3 holding only a `PRIV` frame with Adobe XMP. There are no useful title, artist or album text frames, so the profile gets nothing from tags. |

The profile must handle both the regular `{abbr}` codes and the `Tts` exception through a
general alias map, not a special case in code.

---

## 4. Audio Bible Pack format v1

### 4.1 Pack layout
```
<pack root>/
  manifest.json            # required, UTF-8, no BOM
  <any relative paths referenced by manifest.files[].path>
```
- The pack root is the folder that holds `manifest.json`. All paths are relative to it.
- The app imports only the files that `files[]` lists. Anything else in the folder is ignored.

### 4.2 Top level

```jsonc
{
  "formatVersion": 1,
  "bible":     { … },     // §4.3, required
  "generator": { … },     // §4.6, optional
  "files":     [ … ],     // §4.4, required, ≥ 1 entry
  "chapters":  [ … ]      // §4.5, required, ≥ 1 entry
}
```

| Field | Type | Req. | Rules |
|---|---|---|---|
| `formatVersion` | int | ✔ | Must be `1`. Readers **reject** any value higher than they support, and any value < 1. |

**Evolution rules.**
- Additive, backwards-compatible changes (new optional fields) keep `formatVersion: 1`.
  Readers **must ignore unknown fields** (`Json { ignoreUnknownKeys = true }`).
- Any change that an old reader would misinterpret (renaming fields, changing meaning or units)
  bumps `formatVersion`.

### 4.3 `bible`

| Field | Type | Req. | Rules |
|---|---|---|---|
| `versionId` | string | ✔ | Stable slug, regex `^[a-z0-9][a-z0-9-]{0,31}$`. Used in media IDs (AD-3) and storage paths (AD-6). It identifies **translation + recording**, e.g. `bsb-gilbert`. |
| `abbreviation` | string | ✔ | 1–16 chars, shown in compact UI (e.g. `BSB`). |
| `name` | string | ✔ | 1–100 chars, e.g. `Berean Standard Bible`. |
| `language` | string | ✔ | BCP-47 tag, e.g. `en`. Syntax is checked loosely (`^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{1,8})*$`). |
| `versification` | string | ✔ | v1 defines only `"eng"` (the English versification that `Book.chapterCount` uses). Other values are rejected until a later spec defines them (AD-11). |
| `narrator` | string | – | Free text shown alongside the version name, e.g. "read by …". |
| `attribution` | string | – | Copyright or licence text, shown in the app's About page for this version. ≤ 2000 chars. |

### 4.4 `files[]`

| Field | Type | Req. | Rules |
|---|---|---|---|
| `path` | string | ✔ | Relative path with `/` separators. No leading `/`, no `\`, no `.` or `..` segments, no empty segments, no control characters. NFC-normalised. Unique, **compared case-insensitively** (the source may be on FAT/exFAT or SAF). |
| `sizeBytes` | int64 | ✔ | > 0. The importer checks it before and after copying. |
| `sha256` | string | – | 64 lowercase hex chars. The CLI always writes it unless you pass `--no-hash`. If present, the app verifies it on import (AD-6). |
| `durationMs` | int64 | – | > 0. If missing, the app probes the file on device (0002 §6.4). |

### 4.5 `chapters[]`

| Field | Type | Req. | Rules |
|---|---|---|---|
| `book` | string | ✔ | USFM code that `Book.fromUsfm` recognises (AD-11). Written in uppercase. |
| `chapter` | int | ✔ | `Book.hasChapter(chapter)` under `eng` versification. |
| `file` | string | ✔ | Must equal some `files[].path` exactly (case-sensitive match against the manifest's own spelling). |
| `startMs` | int64 | – | Default `0`. ≥ 0. |
| `endMs` | int64 \| null | – | Default `null` (= end of file). If set, it must be > `startMs`, and ≤ the file's `durationMs` when that is known. |
| `verses` | array \| null | – | **Reserved for Phase 4.** The planned shape is `[{"verse": 1, "startMs": 0}, …]`. v1 readers accept and ignore it, and v1 writers omit it. |

Rules across the whole list:
- `(book, chapter)` is unique.
- **Packs don't have to be complete.** An NT-only or partial pack is valid. The validator reports
  missing chapters as an *info* summary, not an error.
- Several chapters may point at one file (book-per-file layouts). The spans for a given file must
  not overlap.
- Writers sort `chapters[]` in canonical order (book ordinal, then chapter) and `files[]` by
  first use in that order. Readers **must not depend** on the order.
- A file that no chapter references is a **warning**. The app doesn't copy it.

### 4.6 `generator` (optional, informational)

```json
{ "tool": "openbibleaudio-importer-cli", "toolVersion": "0.1.0", "profile": "bsb-gilbert" }
```
For diagnostics only. Readers must not change behaviour based on it. There is **no timestamp**
on purpose, so the output is deterministic (§6.6).

### 4.7 Example (abridged, BSB; numbers are illustrative)

```json
{
  "formatVersion": 1,
  "bible": {
    "versionId": "bsb-gilbert",
    "abbreviation": "BSB",
    "name": "Berean Standard Bible",
    "language": "en",
    "versification": "eng",
    "attribution": "…"
  },
  "generator": { "tool": "openbibleaudio-importer-cli", "toolVersion": "0.1.0", "profile": "bsb-gilbert" },
  "files": [
    { "path": "BSB_01_Gen_001_G.mp3", "sizeBytes": 5891234, "sha256": "…", "durationMs": 245106 }
  ],
  "chapters": [
    { "book": "GEN", "chapter": 1, "file": "BSB_01_Gen_001_G.mp3" }
  ]
}
```
Writers leave out fields that hold their default value (`startMs: 0`, `endMs: null`, `verses`).
That keeps a 1189-chapter manifest at roughly 250 KB.

### 4.8 Kotlin model (`:core:model`, package `com.openbibleaudio.core.model.pack`)

```kotlin
@Serializable data class PackManifest(
    val formatVersion: Int,
    val bible: PackBible,
    val generator: PackGenerator? = null,
    val files: List<PackFile>,
    val chapters: List<PackChapter>,
)
@Serializable data class PackBible(
    val versionId: String, val abbreviation: String, val name: String,
    val language: String, val versification: String,
    val narrator: String? = null, val attribution: String? = null,
)
@Serializable data class PackFile(val path: String, val sizeBytes: Long,
    val sha256: String? = null, val durationMs: Long? = null)
@Serializable data class PackChapter(
    @Serializable(with = BookUsfmSerializer::class) val book: Book,
    val chapter: Int, val file: String,
    val startMs: Long = 0, val endMs: Long? = null,
    val verses: JsonElement? = null,   // reserved (Phase 4); ignored in v1
)
@Serializable data class PackGenerator(val tool: String, val toolVersion: String, val profile: String? = null)

object PackManifestCodec {
    const val SUPPORTED_FORMAT_VERSION = 1
    fun decode(text: String): PackManifest     // throws ManifestFormatException with a readable message
    fun encode(m: PackManifest): String        // pretty-printed, explicitNulls=false, encodeDefaults=false, trailing \n
}
```

- An unknown book code is a decode-time error, and the message names the offending entry.
- `decode` checks `formatVersion` **before** the full decode, using a lenient pre-parse of that one field.
  A pack from a newer tool then gets "This pack needs a newer version of OpenBibleAudio
  (format 2 > 1)" rather than a confusing field error.

### 4.9 Validation (`ManifestValidator`, `:core:model`)

`fun validate(m: PackManifest): ValidationReport`. This is **structural only**: no file access.
It's shared by the CLI and the app.

```kotlin
data class ValidationReport(val issues: List<Issue>) {
    val hasErrors: Boolean
    enum class Severity { ERROR, WARNING, INFO }
    data class Issue(val severity: Severity, val code: IssueCode, val message: String, val pointer: String?) // pointer e.g. "chapters[12]"
}
```

| Code | Severity | Condition |
|---|---|---|
| `UNSUPPORTED_FORMAT` | ERROR | formatVersion ≠ 1 |
| `BAD_VERSION_ID`, `BAD_LANGUAGE`, `BAD_FIELD_LENGTH`, `UNSUPPORTED_VERSIFICATION` | ERROR | §4.3 |
| `BAD_PATH`, `DUPLICATE_PATH`, `BAD_SIZE`, `BAD_HASH`, `BAD_DURATION` | ERROR | §4.4 |
| `NO_SUCH_CHAPTER`, `DUPLICATE_CHAPTER`, `UNKNOWN_FILE`, `BAD_SPAN`, `OVERLAPPING_SPANS`, `SPAN_EXCEEDS_FILE` | ERROR | §4.5 |
| `UNREFERENCED_FILE` | WARNING | §4.5 |
| `NO_DURATION` | WARNING | a referenced file has no `durationMs` |
| `MISSING_CHAPTERS` | INFO | one issue summarising the gaps as compact ranges, e.g. `missing: PSA 23; JHN 1–3; all of JUD` |

Checking a pack against actual files (existence, size, hash, duration) is in `:core:importer` (§6.7).

---

## 5. Build & module changes

- **JVM 17** for all modules (AD-1): `:core:model` and `:app` move from 11 to 17.
  `:core:importer` and `:tools:importer-cli` start at 17.
- **New modules** in `settings.gradle.kts`: `:core:importer` (kotlin-jvm), `:tools:importer-cli`
  (kotlin-jvm + `application`).
- **New catalog entries** (`gradle/libs.versions.toml`):
  - plugin `kotlin-serialization` (`org.jetbrains.kotlin.plugin.serialization`, `version.ref = "kotlin"`)
  - `kotlinx-serialization-json` (`:core:model`, api)
  - `kotlinx-coroutines-core` (`:core:importer`, for parallel probe and hash)
  - `clikt` (`:tools:importer-cli` only), for argument parsing
- Dependency graph: `:tools:importer-cli → :core:importer → :core:model`. `:core:importer` must
  not depend on Android, java.awt or javax.sound.
- The CLI's `run` task sets `workingDir = rootProject.projectDir`, so relative paths such as
  `bible_audio/bsb_gilbert` resolve from the repo root.

---

## 6. `:core:importer` design

Package `com.openbibleaudio.core.importer`.

### 6.1 Pipeline

```
FileTree ──scan──▶ [FileEntry] ──LayoutProfile.match──▶ [ChapterMatch] + unmatched
        ──probe+hash (one streaming pass per file, parallel)──▶ [FileFacts]
        ──ManifestBuilder──▶ PackManifest ──ManifestValidator + PackVerifier──▶ ImportReport
```

`ImporterPipeline.run(tree, profile, options): ImportResult` returns the manifest (or nothing,
if there are errors) plus an `ImportReport`: every issue, matched/unmatched counts, and the time taken.

### 6.2 `FileTree`

```kotlin
interface FileTree {
    /** All regular files under the root, recursively; paths relative, '/'-separated. */
    fun walk(): Sequence<FileEntry>
    fun open(path: String): InputStream
}
data class FileEntry(val path: String, val sizeBytes: Long)
```
- `LocalFileTree(root: java.nio.file.Path)` for the CLI. `InMemoryFileTree` (in test fixtures)
  for unit tests. A SAF implementation comes in Phase 5.
- `walk()` skips dot-files, `manifest.json` at the root, `*.part`, `Thumbs.db` and `desktop.ini`.
- It doesn't follow symlinks.

### 6.3 Layout profiles

```kotlin
data class LayoutProfile(
    val id: String,                        // e.g. "bsb-gilbert"
    val description: String,
    val pathPattern: Regex,                // matched against the full relative path
    val books: BookAliases,                // resolves the `book` group
    val bibleDefaults: PackBible?,         // CLI flags override individual fields
    val checks: List<ProfileCheck> = emptyList(),
    val custom: ((FileEntry, MatchResult) -> List<ChapterMatch>)? = null,  // escape hatch (AD-5)
)
data class ChapterMatch(val book: Book, val chapter: Int, val path: String, val startMs: Long = 0, val endMs: Long? = null)
```
- `pathPattern` must define the named groups `book` and `chapter`. The group `order` is optional.
  `chapter` is parsed as a decimal int, so leading zeros are allowed.
- `BookAliases` is a case-insensitive `String → Book` map. It always contains every USFM code.
  Profiles add their own extras.
- `ProfileCheck` is a per-match predicate that reports an ERROR when it fails, e.g. *order group == book ordinal + 1*.
- A file that doesn't match `pathPattern` is reported as an `UNMATCHED_FILE` WARNING (listed in
  the report, never silently dropped). It does **not** go into `files[]`.
- A match whose book alias doesn't resolve is an ERROR (`UNKNOWN_BOOK_ALIAS`). The usual cause
  is a profile bug, and guessing would mislabel audio.
- Registered in `LayoutProfiles.all`. Phase 1 contains only `bsb-gilbert`.

**`bsb-gilbert` profile:**

```kotlin
LayoutProfile(
    id = "bsb-gilbert",
    description = "Berean Standard Bible, Gilbert narration (openbible.com/audio/gilbert)",
    pathPattern = Regex("""^BSB_(?<order>\d{2})_(?<book>[1-3]?[A-Za-z]{2,3})_(?<chapter>\d{3})_G\.mp3$"""),
    books = BookAliases.usfm() + mapOf("TTS" to Book.TITUS),
    bibleDefaults = PackBible(
        versionId = "bsb-gilbert", abbreviation = "BSB", name = "Berean Standard Bible",
        language = "en", versification = "eng",
        narrator = null,            // see Open question 2
        attribution = null,         // see Open question 1
    ),
    checks = listOf(ProfileCheck.orderMatchesCanon("order")),
)
```

### 6.4 `AudioProbe`

```kotlin
interface AudioProbe {
    /** False if this probe can't handle the file (the caller tries the next probe). */
    fun accepts(entry: FileEntry): Boolean
    /** Consumes the stream; must be usable as a sink in a single streaming pass (see 6.5). */
    fun newSession(entry: FileEntry): ProbeSession
}
interface ProbeSession { fun update(buf: ByteArray, off: Int, len: Int); fun finish(): ProbeResult }
sealed interface ProbeResult { data class Ok(val durationMs: Long) : ProbeResult; data class Failed(val reason: String) : ProbeResult }
```

**`Mp3FrameProbe`** (accepts `*.mp3`). It's an incremental parser with no seeking, fed the same
bytes the hasher reads:
1. Skip an ID3v2 tag if present: synchsafe size, plus 10 bytes if the footer flag is set. Repeat
   for consecutive tags.
2. Find frame sync (11 set bits), then decode the header: version (1, 2, 2.5), layer (only
   Layer III is accepted), bitrate index, sample rate, padding. Free-format and reserved values
   reject the header.
3. **If the first frame contains a Xing/Info or VBRI header that has a frame count**:
   duration = frames × samplesPerFrame / sampleRate. Stop parsing frame-by-frame, but keep
   accepting bytes for the hash.
4. **Otherwise walk every frame** (this is the BSB case): the frame length comes from the header,
   and samples are summed (1152 per frame for MPEG-1 L3, 576 for MPEG-2/2.5 L3). Before accepting
   a resync, check that the *next* header starts exactly where expected, so random `0xFFE`
   bytes in the audio aren't mistaken for headers.
5. Trailing ID3v1 (`TAG`, 128 B), APEv2 and Lyrics3 tags, and junk bytes, end the frame walk
   without error. A truncated final frame is dropped.
6. Result = `round(totalSamples × 1000 / sampleRate)`. If zero valid frames were found, the result
   is `Failed`. Encoder delay and padding (the LAME header) are ignored in v1: they're about 50 ms, which doesn't matter here.

**`FfprobeProbe`** (optional fallback for formats that aren't MP3). It's used only if `ffprobe` is
on `PATH`, and it runs `ffprobe -v error -show_entries format=duration -of csv=p=0 <file>`.
It works on a file path, so it only runs with `LocalFileTree` and not inside the streaming pass.
If no probe accepts a file, `durationMs` is left out and the validator reports `NO_DURATION`.

### 6.5 Hashing and performance
- One streaming pass per file: 64 KiB reads → `MessageDigest("SHA-256")` **and** the probe session.
  Each byte is read once.
- Files are processed in parallel with `Dispatchers.IO.limitedParallelism(n)`, where n defaults
  to `min(4, availableProcessors)` and is set with `--jobs`. Results are re-sorted afterwards, so
  parallelism never changes the output.
- Progress is reported through a callback (`onProgress(doneFiles, totalFiles, doneBytes, totalBytes)`),
  which the CLI renders as one updating line.
- Target: the full 5.8 GB corpus is limited by disk speed. Expect roughly 1 minute on an NVMe SSD.

### 6.6 `ManifestBuilder`
- Merges profile defaults with CLI overrides into `bible`.
- Builds `files[]` and `chapters[]`, sorted as in §4.5.
- **Duplicate `(book, chapter)` matches** (e.g. two encodings of GEN 1): ERROR `DUPLICATE_CHAPTER`,
  listing both paths. There's no "pick one" heuristic.
- **Deterministic**: the same tree, profile and options always give byte-identical output.

### 6.7 `PackVerifier`
`fun verify(tree: FileTree, m: PackManifest, checkHashes: Boolean, probes: List<AudioProbe>): ValidationReport`
Checks that every `files[].path` exists in the tree (`MISSING_FILE`, ERROR) and that sizes match
(`SIZE_MISMATCH`, ERROR). It optionally re-hashes (`HASH_MISMATCH`, ERROR) and re-probes durations
(`DURATION_MISMATCH` when the difference is over 1 s, WARNING). Used by `cli validate` and by the
generator's own last step.

---

## 7. `:tools:importer-cli`

Main class `com.openbibleaudio.tools.importer.MainKt`. It runs with
`./gradlew :tools:importer-cli:run --args="…"` or, after `installDist`, with
`tools/importer-cli/build/install/importer-cli/bin/importer-cli`.

```
importer-cli profiles
    Lists the built-in profiles: id and description.

importer-cli generate <packDir> --profile <id>
        [--version-id <slug>] [--abbreviation <s>] [--name <s>] [--language <tag>]
        [--narrator <s>] [--attribution <s> | --attribution-file <path>]
        [--no-hash] [--jobs <n>] [--output <file>] [--force] [--dry-run]
    Scans <packDir> and writes <packDir>/manifest.json (or --output).
    Refuses to overwrite an existing manifest unless --force.
    --dry-run prints the report and a summary, and writes nothing.

importer-cli validate <packDir> [--no-hash]
    Decodes and validates <packDir>/manifest.json, then verifies it against the files.
```

- **Output:** the issues grouped by severity, then a summary such as
  `1189 chapters (66 books, complete) · 1189 files · <total size> · <total duration> · 0 errors, 0 warnings`.
- **Exit codes:** `0` ok (warnings allowed), `1` validation errors, `2` usage or IO error.
- **Writing is atomic:** the manifest is written to `manifest.json.tmp` and then moved into place.
  If there are errors, nothing is written.
- Paths in messages are relative to `<packDir>`.

---

## 8. Testing (AD-15)

All tests are JVM tests (`kotlin.test`).

**`:core:model`**
- `PackManifestCodec`: round-trip, default-field omission, unknown-field tolerance, a
  formatVersion 2 rejection message, unknown-book message, and that `verses` is accepted and ignored.
- `ManifestValidator`: one test per `IssueCode`. A complete synthetic 66-book manifest gives
  zero issues. Tests for missing-chapter range summarisation (`PSA 23; JHN 1–3; all of JUD`).
- Path rules: `..`, a leading `/`, `\`, and duplicates that differ only in case.

**`:core:importer`**
- `Mp3FrameProbe`, against **synthetic MP3s built in-test** by a small `Mp3Fixtures` helper that
  writes valid Layer III headers with zeroed payloads:
  - CBR without a Xing header (the BSB shape), MPEG-1 192k/44.1k mono: exact sample count.
  - A Xing frame count and a VBRI frame count.
  - MPEG-2 and MPEG-2.5 sample-per-frame variants.
  - A leading ID3v2.3 tag with a large `PRIV` frame, an ID3v2.4 tag with a footer, and a trailing ID3v1 tag.
  - False sync bytes inside a payload, a truncated last frame, and zero frames (→ `Failed`).
  - Byte-at-a-time feeding gives the same result as 64 KiB chunks.
- `bsb-gilbert` profile, against an `InMemoryFileTree` of all **1189 BSB filenames generated from
  `Book`**: every file matches, and the result is complete. `Tts` → TIT. A wrong `order` fails the check.
  Files named `notes.txt` and `BSB_01_Gen_001_G (1).mp3` come out as `UNMATCHED_FILE`.
- `ManifestBuilder`: sorting, and a duplicate chapter → error. **Golden test**: a small fixed tree
  produces a manifest equal to the committed `src/test/resources/golden/mini-manifest.json`.
- Determinism: the same tree run with `jobs = 1` and with `jobs = 8` gives identical output.
- `PackVerifier`: a missing file, a size mismatch and a hash mismatch.

**Real-corpus test (opt-in).** `BsbCorpusTest` runs only when the system property
`oba.corpus.bsb` (or the env var `OBA_CORPUS_BSB`) points at the downloaded folder, and is
skipped otherwise. It asserts 1189 chapters, a complete canon, no errors, and every
`durationMs` within ±1 % of `sizeBytes × 8 / 192 kbps` (this cross-checks the frame walker on real data).

**`:tools:importer-cli`**: Clikt `test()`-based tests for exit codes, `--force`/`--dry-run`
behaviour and the atomic write, using a temp directory of synthetic MP3s.

---

## 9. Acceptance criteria

1. `./gradlew test` is green, and includes the tests in §8.
2. `./gradlew :tools:importer-cli:run --args="generate bible_audio/bsb_gilbert --profile bsb-gilbert"`
   writes `bible_audio/bsb_gilbert/manifest.json` with exit code 0, **1189 chapters, 66 books,
   complete, 0 errors, 0 warnings**, and every file has `sha256` and `durationMs`.
3. Running it again with `--force` produces a byte-identical file.
4. `… validate bible_audio/bsb_gilbert` exits 0.
5. Spot check: the manifest durations of GEN 1, PSA 119 and 3JN 1 are within 1 s of what a
   desktop player (or ffprobe, if installed) reports.
6. Corrupting one byte of a file, or deleting one, makes `validate` exit 1 with
   `HASH_MISMATCH` or `MISSING_FILE` respectively.
7. `:core:model` and `:core:importer` have no Android dependencies, and every module targets JVM 17.

---

## 10. Open questions

1. **BSB attribution text.** The BSB *text* has been public domain since 2023. Confirm the
   licence and the attribution wording for the Gilbert *audio* on openbible.com, then put it in
   the profile defaults. Until then, pass it with `--attribution`.
2. **Narrator name** for the Gilbert recording (for `bible.narrator`). Confirm it from the
   source before putting it in the profile defaults.
3. **Hash cost on device.** SHA-256 over 5.8 GB on a phone takes a few minutes, on top of the
   copy. If 0002 measurements show that's too slow, keep `sha256` in the format but let the
   app make verification optional (size check only). The format doesn't need to change.
4. **Encoder delay.** Should the LAME delay/padding be subtracted for gapless-accurate
   durations? It doesn't matter in v1 and may matter for verse timing (Phase 4).
