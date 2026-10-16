package com.pulse.core.feature

import com.pulse.core.model.CalendarEvent
import com.pulse.core.util.Formatters
import com.pulse.core.util.Tint
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDateTime
import com.pulse.core.util.startOfDay
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** In-app inbox behind the bell. Swiping a card away hides it (remembered between launches). */
object NotificationInbox {
    data class Item(val id: String, val icon: String, val tint: Tint, val title: String, val body: String, val time: String)

    /** What the inbox is built from (real accounts get reminders from their own data). */
    data class Context(
        val isDemo: Boolean,
        val now: Instant = Clock.System.now(),
        val events: List<CalendarEvent> = emptyList(),
        val batteryDone: Boolean = false,
        val moodSlotsFilled: Int = 0,
        val sleptLastNight: Boolean = false,
        val isSleeping: Boolean = false,
        /** «Health Connect» on Android, «Apple Health» on iOS. */
        val healthName: String = "Health Connect"
    )

    fun items(context: Context): List<Item> = if (context.isDemo) demoItems(context.healthName) else derived(context)

    /** Reminders from the account's state; ids include the day so a dismissed card comes back tomorrow. */
    fun derived(context: Context): List<Item> {
        val now = context.now
        val day = Formatters.dayKey(now)
        val hour = now.localDateTime().hour
        val result = mutableListOf<Item>()
        context.events.filter { it.start > now && it.start < now + 12.hours }.minByOrNull { it.start }?.let { next ->
            result += Item("event-${next.id.toString().uppercase()}", "calendar", Tint.CYAN, next.title,
                "Начало в ${Formatters.time(next.start)}", if (next.start.isSameDay(now)) "сегодня" else "завтра")
        }
        if (!context.batteryDone && hour >= 9) {
            result += Item("battery-$day", "brain.head.profile", Tint.GREEN, "Пройди когнитивную батарею", "5 тестов, около 4 минут", "сегодня")
        }
        val expectedSlots = if (hour < 12) 1 else if (hour < 18) 2 else 3
        if (context.moodSlotsFilled < expectedSlots) {
            result += Item("mood-$day-$expectedSlots", "face.smiling", Tint.YELLOW, "Отметь настроение и энергию",
                "Заполнено ${context.moodSlotsFilled} из 3 за сегодня", "сегодня")
        }
        if (!context.sleptLastNight && !context.isSleeping && hour < 15) {
            result += Item("sleep-$day", "moon.zzz", Tint.PURPLE, "Нет данных о прошлой ночи",
                "Вечером нажми «Начать сон» или подключи ${context.healthName}", "сегодня")
        }
        return result
    }

    fun demoItems(healthName: String = "Health Connect"): List<Item> = listOf(
        Item("training-soon", "calendar", Tint.CYAN, "Тренировка через час", "Командная практика, 12:00", "11:00"),
        Item("game-test", "brain.head.profile", Tint.GREEN, "Пройди тест до тренировки", "Игровой тест займёт 1 минуту", "10:45"),
        if (healthName == "Apple Health") {
            Item("screen-time", "hourglass", Tint.ORANGE, "Разрешение Screen Time", "Дай доступ, чтобы видеть реальное экранное время", "09:12")
        } else {
            Item("screen-time", "hourglass", Tint.ORANGE, "Доступ к статистике использования", "Дай доступ, чтобы видеть реальное экранное время", "09:12")
        },
        Item("bedtime", "moon.zzz", Tint.PURPLE, "Скоро отбой", "Начни сон в Pulse — таймер будет в уведомлении", "вчера")
    )

    fun dismissedIds(raw: String): Set<String> = raw.split(',').filter { it.isNotEmpty() }.toSet()

    fun visible(list: List<Item>, dismissedRaw: String): List<Item> {
        val dismissed = dismissedIds(dismissedRaw)
        return list.filter { it.id !in dismissed }
    }

    /** Keeps the list short: day-specific ids pile up otherwise. */
    fun dismissing(id: String, raw: String): String = (dismissedIds(raw) + id).sorted().takeLast(300).joinToString(",")
}

/** «Дней подряд»: consecutive days with any entry; a day without entries yet doesn't break the streak until it is over. */
object ActivityStreak {
    fun days(activeDays: Set<Instant>, today: Instant = Clock.System.now()): Int {
        var day = today.startOfDay()
        if (day !in activeDays) day = day.addingDays(-1)
        var count = 0
        while (day in activeDays) {
            count++
            day = day.addingDays(-1)
        }
        return count
    }
}
