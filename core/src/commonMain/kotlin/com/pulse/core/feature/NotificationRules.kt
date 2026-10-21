package com.pulse.core.feature

import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.SleepWindow
import com.pulse.core.model.User
import com.pulse.core.util.Formatters
import com.pulse.core.util.addingDays
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.setting
import com.pulse.core.util.startOfWeek
import kotlin.time.Instant
import kotlin.uuid.Uuid

enum class ReminderKind { EVENT, GAME_TEST, BEDTIME, WAKE_CHECK, WEEKLY }

/** One local notification to post at `at`; `id` is stable so rescheduling replaces the previous one. */
data class ScheduledReminder(
    val id: String,
    val at: Instant,
    val title: String,
    val body: String,
    val kind: ReminderKind,
    val eventId: Uuid? = null,
    val phase: GameTestPhase? = null
)

/** When and what to remind — the same rules and copy as iOS `NotificationScheduler` / `SleepNotifications`. */
object NotificationRules {
    const val EVENT_LEAD_MINUTES = 60
    const val GAME_TEST_MINUTES_BEFORE = 15
    const val GAME_TEST_MINUTES_AFTER = 5
    const val GAME_TEST_HORIZON_DAYS = 7
    const val BEDTIME_LEAD_MINUTES = 15
    const val WAKE_CHECK_DELAY_MINUTES = 30
    const val WEEKLY_HOUR = 19

    private fun key(id: Uuid) = id.toString().uppercase()

    fun eventReminder(event: CalendarEvent, now: Instant): ScheduledReminder? {
        val at = event.start.addingMinutes(-EVENT_LEAD_MINUTES)
        if (at <= now) return null
        return ScheduledReminder(
            "event-${key(event.id)}", at, event.title,
            "Через час · ${Formatters.time(event.start)}–${Formatters.time(event.end)}", ReminderKind.EVENT, eventId = event.id
        )
    }

    fun isTraining(event: CalendarEvent): Boolean = event.title.lowercase().contains("тренировк")

    fun gameTests(events: List<CalendarEvent>, now: Instant): List<ScheduledReminder> {
        val horizon = now.addingDays(GAME_TEST_HORIZON_DAYS)
        return events.filter(::isTraining).flatMap { event ->
            listOf(
                ScheduledReminder(
                    "game-beforeTraining-${key(event.id)}", event.start.addingMinutes(-GAME_TEST_MINUTES_BEFORE),
                    "Игровой тест перед тренировкой", "«${event.title}» скоро начнётся. Оцени фокус и готовность — 20 секунд.",
                    ReminderKind.GAME_TEST, event.id, GameTestPhase.BEFORE_TRAINING
                ),
                ScheduledReminder(
                    "game-afterTraining-${key(event.id)}", event.end.addingMinutes(GAME_TEST_MINUTES_AFTER),
                    "Игровой тест после тренировки", "Как прошла «${event.title}»? Отметь фокус и усталость.",
                    ReminderKind.GAME_TEST, event.id, GameTestPhase.AFTER_TRAINING
                )
            )
        }.filter { it.at > now && it.at <= horizon }
    }

    /** Next daily «Скоро отбой»: bedtime minus the lead time, wrapped around midnight. */
    fun nextBedtime(window: SleepWindow, now: Instant): ScheduledReminder {
        val total = (window.bedtimeHour * 60 + window.bedtimeMinute - BEDTIME_LEAD_MINUTES + 24 * 60) % (24 * 60)
        var at = now.setting(total / 60, total % 60)
        if (at <= now) at = at.addingDays(1)
        return ScheduledReminder(
            "sleep-bedtime", at, "Скоро отбой 🌙",
            "План — лечь в ${window.bedtimeText}. Нажми «Начать сон», когда ляжешь: таймер появится в шторке.", ReminderKind.BEDTIME
        )
    }

    /** First planned wake-up after the session start, plus a grace period. */
    fun wakeCheck(start: Instant, window: SleepWindow): ScheduledReminder {
        var wake = start.setting(window.wakeHour, window.wakeMinute)
        if (wake <= start) wake = wake.addingDays(1)
        return ScheduledReminder(
            "sleep-wake-check", wake.addingMinutes(WAKE_CHECK_DELAY_MINUTES), "Доброе утро ☀️",
            "Сон всё ещё отслеживается с ${Formatters.time(start)}. Нажми «Я проснулся», чтобы сохранить ночь.", ReminderKind.WAKE_CHECK
        )
    }

    /**
     * Everything to schedule for the signed-in user. Players never get other players' schedules; staff get event
     * reminders and the weekly report, but no game tests or bedtime reminder.
     */
    fun upcoming(events: List<CalendarEvent>, user: User?, window: SleepWindow, now: Instant): List<ScheduledReminder> {
        user ?: return emptyList()
        val isStaff = user.role.isStaff
        val visible = events.filter { event ->
            when (val tag = event.tag) {
                is EventTag.Player -> isStaff || tag.name == user.nickname
                EventTag.Personal -> event.ownerId == null || event.ownerId == user.id
                EventTag.Team -> true
            }
        }
        return buildList {
            addAll(visible.mapNotNull { eventReminder(it, now) })
            if (!isStaff) {
                addAll(gameTests(visible, now))
                add(nextBedtime(window, now))
            }
            add(nextWeekly(now))
        }
    }

    /** «Итоги недели готовы» — the next Sunday at 19:00. */
    fun nextWeekly(now: Instant): ScheduledReminder {
        var at = now.startOfWeek().addingDays(6).setting(WEEKLY_HOUR, 0)
        if (at <= now) at = at.addingDays(7)
        return ScheduledReminder(
            "weekly-report", at, "Итоги недели готовы",
            "Сон, нагрузка, настроение и игра — сравнение с прошлой неделей и план на следующую.", ReminderKind.WEEKLY
        )
    }
}
