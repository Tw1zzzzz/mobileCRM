package com.pulse.android.platform

import com.pulse.core.feature.ScheduledReminder
import kotlin.time.Instant

/**
 * Reconciles pending reminder work with the current plan. A work name carries the reminder's id, time and a hash of
 * its text, so an unchanged reminder is left alone, a moved or renamed one is replaced, and work whose time has
 * already come is never cancelled — the process may have been started precisely to run it.
 */
object ReminderSync {
    data class Result(val toEnqueue: List<ScheduledReminder>, val toCancel: List<String>)

    fun workName(reminder: ScheduledReminder): String =
        "${reminder.id}@${reminder.at.epochSeconds}#${(reminder.title + "\n" + reminder.body).hashCode().toUInt().toString(16)}"

    private const val LEGACY = "legacy:"

    /** Work scheduled by older builds has no name tag: it is tracked by its WorkManager id and always replaced. */
    fun legacyName(workId: String): String = LEGACY + workId

    fun legacyWorkId(name: String): String? = name.takeIf { it.startsWith(LEGACY) }?.removePrefix(LEGACY)

    fun atOf(workName: String): Instant? =
        workName.substringAfterLast('@', "").substringBefore('#').toLongOrNull()?.let(Instant::fromEpochSeconds)

    fun diff(existing: List<String>, planned: List<ScheduledReminder>, now: Instant): Result {
        val plannedNames = planned.associateBy(::workName)
        return Result(
            toEnqueue = plannedNames.filterKeys { it !in existing }.values.toList(),
            toCancel = existing.filter { name ->
                legacyWorkId(name) != null || (name !in plannedNames && (atOf(name)?.let { it > now } ?: true))
            }
        )
    }
}
