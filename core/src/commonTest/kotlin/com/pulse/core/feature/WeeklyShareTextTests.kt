package com.pulse.core.feature

import com.pulse.core.util.Tint
import com.pulse.core.model.WeekRange
import com.pulse.core.model.WeeklyMetric
import com.pulse.core.model.WeeklyReport
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class WeeklyShareTextTests {
    @BeforeTest fun zone() = pinTestZone()

    @Test fun verdictBands() {
        assertEquals("Мало данных", WeeklyShareText.verdict(null))
        assertEquals("Тяжёлая неделя", WeeklyShareText.verdict(39))
        assertEquals("Средняя неделя", WeeklyShareText.verdict(40))
        assertEquals("Хорошая неделя", WeeklyShareText.verdict(79))
        assertEquals("Сильная неделя", WeeklyShareText.verdict(80))
    }

    @Test fun personalText() {
        val week = WeekRange.containing(at(2026, 10, 14))
        val report = WeeklyReport(
            week, score = 72, previousScore = 70, activeDays = 6, elapsedDays = 7,
            metrics = listOf(WeeklyMetric("sleep", "Сон", "moon", Tint.PURPLE, "7 ч 40 м", "", "+12 мин", WeeklyMetric.Trend.BETTER)),
            goals = emptyList(), wins = emptyList(), concerns = emptyList(), tips = listOf("Ложись до 23:30")
        )
        assertEquals(
            "Pulse · итоги недели ${week.title}\nНеделя: 72/100 — Хорошая неделя\n• Сон: 7 ч 40 м (+12 мин)\nПлан: Ложись до 23:30",
            WeeklyShareText.personal(report)
        )
    }
}
