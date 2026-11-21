package com.openbibleaudio.openbibleaudio.plans

import com.openbibleaudio.core.model.pack.Issue
import com.openbibleaudio.core.model.pack.Severity
import com.openbibleaudio.core.model.plan.PlanCodec
import com.openbibleaudio.core.model.plan.PlanDefinition
import com.openbibleaudio.core.model.plan.PlanFormatException
import com.openbibleaudio.core.model.plan.PlanValidator
import com.openbibleaudio.openbibleaudio.data.db.DownloadMode
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentDayEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentItemCompletionEntity
import com.openbibleaudio.openbibleaudio.data.db.EnrollmentState
import com.openbibleaudio.openbibleaudio.data.db.PlanDao
import com.openbibleaudio.openbibleaudio.data.db.PlanEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Outcome of importing a plan file (spec 0003 §6.1). */
sealed interface PlanImportResult {
    /** [replacesVersion] is the newest version that was already imported, if any. */
    data class Imported(val id: String, val version: Int, val name: String, val replacesVersion: Int?, val notes: List<Issue>) : PlanImportResult

    /** The same `id` + `version` is already imported; nothing changed. */
    data class AlreadyImported(val id: String, val version: Int, val name: String) : PlanImportResult

    /** Validation errors; [shown] holds the first [MAX_SHOWN] of [errorCount]. */
    data class Invalid(val shown: List<Issue>, val errorCount: Int) : PlanImportResult

    /** The file couldn't be read or decoded; [message] is for the user. */
    data class Unreadable(val message: String) : PlanImportResult

    companion object {
        const val MAX_SHOWN = 20
    }
}

/** An enrollment with its plan and where it stands, for the UI and the browse tree. */
data class EnrollmentSummary(
    val enrollment: EnrollmentEntity,
    val plan: PlanDefinition,
    val statuses: Map<Int, DayStatus>,
    val position: PlanPosition,
)

/** Plans, enrollments and plan progress (spec 0003 §5, §6). */
class PlanRepository(
    private val dao: PlanDao,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val decoded = ConcurrentHashMap<Pair<String, Int>, PlanDefinition>()

    /** The newest version of each imported plan. */
    val plans: Flow<List<PlanEntity>> = dao.observeLatestPlans()

    /** Non-archived enrollments with progress. Recomputed when any day status changes. */
    val enrollments: Flow<List<EnrollmentSummary>> =
        combine(dao.observeEnrollments(), dao.observeAllDayStatuses(), dao.observePlans()) { enrollments, days, plans ->
            val byEnrollment = days.groupBy({ it.enrollmentId }, { it.day to it.status })
            val planRows = plans.associateBy { it.id to it.version }
            enrollments.mapNotNull { e ->
                val row = planRows[e.planId to e.planVersion] ?: return@mapNotNull null
                val plan = decode(row)
                val statuses = byEnrollment[e.id].orEmpty().toMap()
                EnrollmentSummary(e, plan, statuses, position(e, plan, statuses))
            }
        }

    // --- Import ------------------------------------------------------------------------------

    suspend fun importPlan(input: InputStream): PlanImportResult = withContext(Dispatchers.IO) {
        val text = try {
            input.use { readLimited(it) }
        } catch (e: IOException) {
            return@withContext PlanImportResult.Unreadable(e.message ?: "The file couldn't be read")
        }
        importPlan(text)
    }

    suspend fun importPlan(text: String): PlanImportResult {
        val plan = try {
            PlanCodec.decode(text)
        } catch (e: PlanFormatException) {
            return PlanImportResult.Unreadable(e.message ?: "The plan file couldn't be read")
        }
        val report = PlanValidator.validate(plan)
        if (report.hasErrors) {
            return PlanImportResult.Invalid(report.errors.take(PlanImportResult.MAX_SHOWN), report.errors.size)
        }
        if (dao.plan(plan.id, plan.version) != null) return PlanImportResult.AlreadyImported(plan.id, plan.version, plan.name)

        val previous = latestVersion(plan.id)
        // Store the canonical encoding, so what we decode later is exactly what was validated.
        dao.upsertPlan(PlanEntity(plan.id, plan.version, plan.name, PlanCodec.encode(plan), clock.millis()))
        decoded[plan.id to plan.version] = plan
        return PlanImportResult.Imported(
            plan.id, plan.version, plan.name, previous,
            notes = report.issues.filter { it.severity != Severity.ERROR },
        )
    }

    private suspend fun latestVersion(id: String): Int? = dao.planVersions(id).maxOrNull()

    suspend fun plan(id: String, version: Int): PlanDefinition? = dao.plan(id, version)?.let(::decode)

    /** Plans of ACTIVE enrollments that have a podcast, one per feed URL (spec 0003 §7.1). */
    suspend fun activePodcastPlans(): List<PlanDefinition> =
        dao.activeEnrollments()
            .mapNotNull { plan(it.planId, it.planVersion) }
            .filter { it.podcast != null }
            .distinctBy { it.podcast!!.feedUrl }

    private fun decode(row: PlanEntity): PlanDefinition =
        decoded.getOrPut(row.id to row.version) { PlanCodec.decode(row.definitionJson) }

    // --- Enrollment --------------------------------------------------------------------------

    suspend fun enroll(
        planId: String,
        planVersion: Int,
        versionId: String? = null,
        startDate: LocalDate = LocalDate.now(clock),
        podcastCycle: Int? = null,
        downloadMode: DownloadMode = DownloadMode.ROLLING,
        rollingDays: Int = EnrollmentEntity.DEFAULT_ROLLING_DAYS,
    ): Long {
        requireNotNull(dao.plan(planId, planVersion)) { "plan $planId v$planVersion isn't imported" }
        return dao.insertEnrollment(
            EnrollmentEntity(
                planId = planId, planVersion = planVersion, versionId = versionId, startDate = startDate.toString(),
                podcastCycle = podcastCycle, downloadMode = downloadMode, rollingDays = rollingDays.coerceAtLeast(1),
                state = EnrollmentState.ACTIVE, createdAt = clock.millis(),
            )
        )
    }

    /** Deletes the enrollment and its progress, and the plan version if nothing else uses it. */
    suspend fun unenroll(enrollmentId: Long) {
        val e = dao.enrollment(enrollmentId) ?: return
        dao.deleteEnrollment(enrollmentId)
        dao.deletePlanIfUnused(e.planId, e.planVersion)
    }

    suspend fun setPaused(enrollmentId: Long, paused: Boolean) {
        val e = dao.enrollment(enrollmentId) ?: return
        if (e.state == EnrollmentState.FINISHED || e.state == EnrollmentState.ARCHIVED) return
        dao.setEnrollmentState(enrollmentId, if (paused) EnrollmentState.PAUSED else EnrollmentState.ACTIVE)
    }

    /** Archived enrollments leave Auto and the Plans list but keep their progress. */
    suspend fun archive(enrollmentId: Long) = dao.setEnrollmentState(enrollmentId, EnrollmentState.ARCHIVED)

    suspend fun summary(enrollmentId: Long): EnrollmentSummary? {
        val e = dao.enrollment(enrollmentId) ?: return null
        val plan = plan(e.planId, e.planVersion) ?: return null
        val statuses = statuses(enrollmentId)
        return EnrollmentSummary(e, plan, statuses, position(e, plan, statuses))
    }

    private fun position(e: EnrollmentEntity, plan: PlanDefinition, statuses: Map<Int, DayStatus>) =
        PlanProgress.position(plan.dayCount, statuses, LocalDate.parse(e.startDate), LocalDate.now(clock))

    private suspend fun statuses(enrollmentId: Long): Map<Int, DayStatus> =
        dao.dayStatuses(enrollmentId).associate { it.day to it.status }

    // --- Progress ----------------------------------------------------------------------------

    /**
     * Records that [itemKey] of [queue] finished (spec 0002 completion rule) and updates the day's
     * status from the queue as it was built, so a dropped chapter or an unavailable episode
     * doesn't block the day (spec 0003 §6.3). Returns the day's new status, or null when the
     * enrollment has been deleted meanwhile (its day was still playing): nothing is recorded.
     */
    suspend fun recordItemCompleted(queue: DayQueue, itemKey: String): DayStatus? {
        if (dao.enrollment(queue.enrollmentId) == null) return null
        val now = clock.millis()
        dao.insertCompletion(EnrollmentItemCompletionEntity(queue.enrollmentId, queue.day, itemKey, now))
        val completed = dao.completedItems(queue.enrollmentId, queue.day).toSet()
        val computed = PlanProgress.statusFromItems(
            queue.readingKeys, queue.episodeKey, completed, episodeWasUnavailable = queue.episodeUnavailable != null,
        )
        val existing = dao.day(queue.enrollmentId, queue.day)
        val status = PlanProgress.merge(existing?.status, computed)
        if (status != existing?.status) {
            dao.upsertDays(listOf(EnrollmentDayEntity(queue.enrollmentId, queue.day, status, if (status == DayStatus.COMPLETE) now else null)))
            if (status == DayStatus.COMPLETE) updateFinished(queue.enrollmentId)
        }
        return status
    }

    suspend fun markComplete(enrollmentId: Long, day: Int) {
        dao.upsertDays(listOf(EnrollmentDayEntity(enrollmentId, day, DayStatus.COMPLETE, clock.millis())))
        updateFinished(enrollmentId)
    }

    suspend fun markNotDone(enrollmentId: Long, day: Int) {
        dao.resetDay(enrollmentId, day)
        updateFinished(enrollmentId)
    }

    /** Marks every open day before the calendar day SKIPPED. Returns how many days were skipped. */
    suspend fun skipToToday(enrollmentId: Long): Int {
        val s = summary(enrollmentId) ?: return 0
        val skipped = PlanProgress.skipToToday(s.position, s.statuses)
        dao.upsertDays(skipped.map { (day, status) -> EnrollmentDayEntity(enrollmentId, day, status, null) })
        updateFinished(enrollmentId)
        return skipped.size
    }

    suspend fun restart(enrollmentId: Long) = dao.restart(enrollmentId)

    /** The next open day after [day], for "continue to next day" and "Play Day N next" (§6.2). */
    suspend fun nextDayAfter(enrollmentId: Long, day: Int): Int? {
        val s = summary(enrollmentId) ?: return null
        return PlanProgress.nextDayAfter(day, s.plan.dayCount, s.statuses)
    }

    /** FINISHED once no day is open; back to ACTIVE when a day is reopened. */
    private suspend fun updateFinished(enrollmentId: Long) {
        val s = summary(enrollmentId) ?: return
        val state = s.enrollment.state
        when {
            s.position.finished && (state == EnrollmentState.ACTIVE || state == EnrollmentState.PAUSED) ->
                dao.setEnrollmentState(enrollmentId, EnrollmentState.FINISHED)
            !s.position.finished && state == EnrollmentState.FINISHED ->
                dao.setEnrollmentState(enrollmentId, EnrollmentState.ACTIVE)
        }
    }

    private fun readLimited(input: InputStream): String {
        // Not readNBytes: that's API 33, and minSdk is 28.
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_PLAN_BYTES) throw IOException("The file is too large to be a plan (over ${MAX_PLAN_BYTES / 1_000_000} MB)")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        /** The TBR plan is ~60 KB; anything this big isn't a plan file. */
        const val MAX_PLAN_BYTES = 4_000_000
    }
}
