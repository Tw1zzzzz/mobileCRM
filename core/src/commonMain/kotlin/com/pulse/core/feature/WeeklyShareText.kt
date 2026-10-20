package com.pulse.core.feature

import com.pulse.core.model.TeamWeeklyReport
import com.pulse.core.model.WeeklyReport
import com.pulse.core.util.Formatters
import com.pulse.core.util.roundToIntSwift

/** Verdict line and plain-text versions of the weekly reports for «Поделиться». */
object WeeklyShareText {
    fun verdict(score: Int?): String = when {
        score == null -> "Мало данных"
        score >= 80 -> "Сильная неделя"
        score >= 60 -> "Хорошая неделя"
        score >= 40 -> "Средняя неделя"
        else -> "Тяжёлая неделя"
    }

    fun personal(report: WeeklyReport): String = buildList {
        add("Pulse · итоги недели ${report.week.title}")
        report.score?.let { add("Неделя: $it/100 — ${verdict(it)}") }
        report.metrics.forEach { metric -> add("• ${metric.title}: ${metric.value}${metric.change?.let { " ($it)" } ?: ""}") }
        report.tips.firstOrNull()?.let { add("План: $it") }
    }.joinToString("\n")

    fun team(report: TeamWeeklyReport): String = buildList {
        add("Pulse · сводка команды ${report.week.title}")
        report.readiness?.let { add("Готовность: ${Formatters.decimal(it)}/10") }
        report.sleepMinutes?.let { add("Средний сон: ${Formatters.duration(it)}") }
        add("Матчи: ${report.matches}${report.winRate?.let { ", победы ${it.roundToIntSwift()}%" } ?: ""}")
        report.highlights.forEach { add("★ $it") }
        report.risks.forEach { add("⚠︎ $it") }
    }.joinToString("\n")
}
