package com.pulse.core.feature

import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.SleepWindow
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class NotificationRulesTest {
    @BeforeTest fun zone() = pinTestZone()

    private fun event(title: String, start: kotlin.time.Instant, end: kotlin.time.Instant, id: Uuid = Uuid.random()) =
        CalendarEvent(id, title, "", start, end, emptyList(), EventTag.Team)

    @Test fun eventReminderOneHourBefore() {
        val reminder = NotificationRules.eventReminder(event("Матч", at(2026, 10, 21, 18), at(2026, 10, 21, 19)), now = at(2026, 10, 21, 9))
        assertEquals(at(2026, 10, 21, 17), reminder?.at)
        assertEquals("Матч", reminder?.title)
        assertEquals("Через час · 18:00–19:00", reminder?.body)
        assertEquals(ReminderKind.EVENT, reminder?.kind)
    }

    @Test fun eventReminderIdIsStable() {
        val id = Uuid.random()
        val first = NotificationRules.eventReminder(event("Матч", at(2026, 10, 21, 18), at(2026, 10, 21, 19), id), now = at(2026, 10, 21, 9))
        val edited = NotificationRules.eventReminder(event("Финал", at(2026, 10, 22, 20), at(2026, 10, 22, 21), id), now = at(2026, 10, 21, 9))
        assertEquals("event-${id.toString().uppercase()}", first?.id)
        assertEquals(first?.id, edited?.id)
        assertEquals(id, edited?.eventId)
    }

    @Test fun eventReminderSkippedWhenPast() {
        assertNull(NotificationRules.eventReminder(event("Матч", at(2026, 10, 21, 18), at(2026, 10, 21, 19)), now = at(2026, 10, 21, 17, 30)))
    }

    @Test fun gameTestsOnlyForTrainingsWithin7Days() {
        val now = at(2026, 10, 21, 9)
        val training = event("Вечерняя тренировка", at(2026, 10, 21, 18), at(2026, 10, 21, 20))
        val match = event("Матч", at(2026, 10, 22, 18), at(2026, 10, 22, 19))
        val farTraining = event("Тренировка", at(2026, 10, 29, 18), at(2026, 10, 29, 20))
        val reminders = NotificationRules.gameTests(listOf(training, match, farTraining), now)
        assertEquals(2, reminders.size)
        val before = reminders.first { it.phase == GameTestPhase.BEFORE_TRAINING }
        val after = reminders.first { it.phase == GameTestPhase.AFTER_TRAINING }
        assertEquals(at(2026, 10, 21, 17, 45), before.at)
        assertEquals(at(2026, 10, 21, 20, 5), after.at)
        assertEquals("game-beforeTraining-${training.id.toString().uppercase()}", before.id)
        assertEquals("Игровой тест перед тренировкой", before.title)
        assertEquals("«Вечерняя тренировка» скоро начнётся. Оцени фокус и готовность — 20 секунд.", before.body)
        assertEquals("Как прошла «Вечерняя тренировка»? Отметь фокус и усталость.", after.body)
        assertEquals(ReminderKind.GAME_TEST, after.kind)
    }

    @Test fun bedtimeWrapsMidnight() {
        val window = SleepWindow(bedtimeHour = 0, bedtimeMinute = 10, wakeHour = 8, wakeMinute = 0)
        val evening = NotificationRules.nextBedtime(window, now = at(2026, 10, 21, 20))
        assertEquals(at(2026, 10, 21, 23, 55), evening.at)
        assertEquals("sleep-bedtime", evening.id)
        assertEquals("Скоро отбой 🌙", evening.title)
        assertEquals("План — лечь в 00:10. Нажми «Начать сон», когда ляжешь: таймер появится в шторке.", evening.body)
        val later = NotificationRules.nextBedtime(window, now = at(2026, 10, 21, 23, 58))
        assertEquals(at(2026, 10, 22, 23, 55), later.at)
    }

    @Test fun wakeCheckAfterMidnightStart() {
        val window = SleepWindow(bedtimeHour = 23, bedtimeMinute = 0, wakeHour = 7, wakeMinute = 0)
        val night = NotificationRules.wakeCheck(at(2026, 10, 22, 1, 30), window)
        assertEquals(at(2026, 10, 22, 7, 30), night.at)
        assertEquals("sleep-wake-check", night.id)
        assertEquals("Доброе утро ☀️", night.title)
        assertEquals("Сон всё ещё отслеживается с 01:30. Нажми «Я проснулся», чтобы сохранить ночь.", night.body)
        assertEquals(at(2026, 10, 22, 7, 30), NotificationRules.wakeCheck(at(2026, 10, 21, 23, 0), window).at)
    }

    @Test fun weeklyIsNextSunday19() {
        val fromWednesday = NotificationRules.nextWeekly(at(2026, 10, 21, 12))
        assertEquals(at(2026, 10, 25, 19), fromWednesday.at)
        assertEquals("weekly-report", fromWednesday.id)
        assertEquals("Итоги недели готовы", fromWednesday.title)
        assertEquals("Сон, нагрузка, настроение и игра — сравнение с прошлой неделей и план на следующую.", fromWednesday.body)
        assertEquals(at(2026, 11, 1, 19), NotificationRules.nextWeekly(at(2026, 10, 25, 20)).at)
    }

    private fun user(role: com.pulse.core.model.UserRole, nickname: String = "Nova") =
        com.pulse.core.model.User(Uuid.random(), nickname, "x@pulse.test", role, com.pulse.core.model.Tariff.FREE, streakDays = 0)

    @Test fun upcomingForPlayerSkipsOtherPlayersPersonalPlans() {
        val now = at(2026, 10, 21, 9)
        val window = SleepWindow()
        val mine = event("Тренировка", at(2026, 10, 21, 18), at(2026, 10, 21, 20)).copy(tag = EventTag.Player("Nova"))
        val other = event("Тренировка", at(2026, 10, 21, 18), at(2026, 10, 21, 20)).copy(tag = EventTag.Player("Blitz"))
        val reminders = NotificationRules.upcoming(listOf(mine, other), user(com.pulse.core.model.UserRole.TEAM_PLAYER), window, now)
        assertEquals(1, reminders.count { it.kind == ReminderKind.EVENT })
        assertEquals(2, reminders.count { it.kind == ReminderKind.GAME_TEST })
        assertEquals(setOf(mine.id), reminders.mapNotNull { it.eventId }.toSet())
        assertEquals(1, reminders.count { it.kind == ReminderKind.BEDTIME })
        assertEquals(1, reminders.count { it.kind == ReminderKind.WEEKLY })
    }

    @Test fun upcomingForStaffHasNoGameTestsOrBedtime() {
        val now = at(2026, 10, 21, 9)
        val training = event("Тренировка", at(2026, 10, 21, 18), at(2026, 10, 21, 20))
        val reminders = NotificationRules.upcoming(listOf(training), user(com.pulse.core.model.UserRole.STAFF, "Coach"), SleepWindow(), now)
        assertEquals(setOf(ReminderKind.EVENT, ReminderKind.WEEKLY), reminders.map { it.kind }.toSet())
    }

    @Test fun upcomingEmptyWithoutUser() {
        assertEquals(emptyList<ScheduledReminder>(), NotificationRules.upcoming(emptyList(), null, SleepWindow(), at(2026, 10, 21, 9)))
    }
}
