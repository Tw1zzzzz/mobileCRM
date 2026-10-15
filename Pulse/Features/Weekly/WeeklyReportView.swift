import SwiftUI

/// Collects the personal weekly inputs from the stores; use as a property in views.
struct WeeklySource: DynamicProperty {
    @Environment(SessionStore.self) private var session
    @Environment(SleepStore.self) private var sleep
    @Environment(FitnessStore.self) private var fitness
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(NutritionStore.self) private var nutrition
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(GameStore.self) private var game
    @Environment(PlayerSurveyStore.self) private var survey

    /// Enough history for the selected week and the one before it.
    private let days = 70

    var inputs: WeeklyInputs {
        WeeklyInputs(
            sleep: sleep.sessions,
            sleepGoalMinutes: sleep.window.durationMinutes,
            workouts: fitness.loadWorkouts,
            dailyLoad: fitness.dailyLoad(days: days),
            screenMinutes: screenTime.daily(lastDays: days),
            screenLimitMinutes: screenTime.dailyLimitMinutes,
            kcal: nutrition.dailyCalories(lastDays: days),
            kcalGoal: nutrition.goals.kcal,
            mood: wellbeing.moodEntries,
            cognitive: cognitive.results,
            matches: game.matches(for: session.user, days: days),
            elo: game.eloHistory(for: session.user, days: days),
            focus: survey.survey?.goals ?? []
        )
    }

    func report(for week: WeekRange) -> WeeklyReport {
        WeeklyReportBuilder.build(inputs, week: week)
    }
}

/// «Итоги недели»: personal report for players, team summary for staff.
struct WeeklyReportView: View {
    @Environment(SessionStore.self) private var session
    @State private var week = WeekRange.containing(.now)

    var body: some View {
        ScreenScaffold(spacing: 22) {
            NavHeader(title: session.isStaff ? "Сводка команды" : "Итоги недели",
                      subtitle: session.isStaff ? (session.user?.team ?? "Команда") : "Сравнение с прошлой неделей")
        } content: {
            WeekNavigator(week: $week, earliest: session.isStaff ? WeekRange.containing(Date.now.adding(days: -27)) : nil)
            if session.isStaff {
                TeamWeeklyContent(report: TeamWeeklyBuilder.demo(week: week))
            } else {
                PersonalWeeklyContent(week: week)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: week)
    }
}

struct WeekNavigator: View {
    @Binding var week: WeekRange
    /// Oldest week with data (staff demo keeps four weeks).
    var earliest: WeekRange?

    var body: some View {
        let canGoBack = earliest.map { week.start > $0.start } ?? true
        let canGoForward = !week.isCurrent()
        HStack {
            arrow("chevron.left", enabled: canGoBack) { week = week.previous }
                .accessibilityLabel("Предыдущая неделя")
            Spacer()
            VStack(spacing: 2) {
                Text(week.title).font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(week.isCurrent() ? "Текущая неделя" : "Завершённая неделя")
                    .font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer()
            arrow("chevron.right", enabled: canGoForward) { week = week.next }
                .accessibilityLabel("Следующая неделя")
        }
        .pulseCard(padding: 10)
    }

    private func arrow(_ icon: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(enabled ? Theme.Palette.cyan : Theme.Palette.textTertiary)
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }
}

// MARK: - Player

struct PersonalWeeklyContent: View {
    let week: WeekRange
    var source = WeeklySource()

    var body: some View {
        let report = source.report(for: week)
        if report.isEmpty {
            EmptyStateView(systemName: "calendar.badge.clock", title: "За эту неделю записей нет",
                           message: "Отмечай сон, настроение, тесты и еду — в воскресенье здесь появятся итоги.")
        } else {
            VStack(alignment: .leading, spacing: 22) {
                heroCard(report)
                PulseSection(title: "Показатели") {
                    VStack(spacing: 0) {
                        ForEach(Array(report.metrics.enumerated()), id: \.element.id) { index, metric in
                            WeeklyMetricRow(metric: metric)
                            if index < report.metrics.count - 1 { DividerLine() }
                        }
                    }
                    .padding(.horizontal, 16)
                    .pulseCard(padding: 0)
                }
                if !report.wins.isEmpty {
                    PulseSection(title: "Получилось") { bulletCard(report.wins, icon: "arrow.up.right.circle.fill", tint: Theme.Palette.green) }
                }
                if !report.concerns.isEmpty {
                    PulseSection(title: "Обратить внимание") { bulletCard(report.concerns, icon: "exclamationmark.circle.fill", tint: Theme.Palette.orange) }
                }
                PulseSection(title: "Цели недели") { goalsCard(report.goals) }
                PulseSection(title: "На следующую неделю") { bulletCard(report.tips, icon: "lightbulb.fill", tint: Theme.Palette.yellow) }
                ShareLink(item: WeeklyShareText.personal(report)) {
                    Label("Поделиться итогами", systemImage: "square.and.arrow.up")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.cyan)
                        .frame(maxWidth: .infinity)
                        .pulseCard(padding: 14)
                }
            }
        }
    }

    private func heroCard(_ report: WeeklyReport) -> some View {
        HStack(spacing: 18) {
            ScoreRing(progress: Double(report.score ?? 0) / 100, value: report.score.map(String.init) ?? "—",
                      caption: "НЕДЕЛЯ", size: 116, lineWidth: 11, valueSize: 40)
            VStack(alignment: .leading, spacing: 8) {
                Text(WeeklyShareText.verdict(report.score))
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                if let score = report.score, let previous = report.previousScore {
                    let delta = score - previous
                    Label("\(Formatters.signed(delta)) к прошлой неделе",
                          systemImage: delta >= 0 ? "arrow.up.right" : "arrow.down.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(delta >= 0 ? Theme.Palette.green : Theme.Palette.orange)
                }
                Text("Записи в \(report.activeDays) из \(report.elapsedDays) дн.")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer(minLength: 0)
        }
        .pulseCard(padding: 16)
    }

    private func goalsCard(_ goals: [WeeklyGoalCheck]) -> some View {
        VStack(spacing: 14) {
            ForEach(goals) { goal in
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(goal.title).font(.system(size: 14)).foregroundStyle(Theme.Palette.textPrimary)
                            .lineLimit(1).minimumScaleFactor(0.8)
                        Spacer()
                        Text("\(goal.achieved) из \(goal.total)").font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(goal.progress >= 0.7 ? Theme.Palette.green : Theme.Palette.textSecondary)
                    }
                    GradientBar(progress: goal.progress, height: 6)
                }
            }
        }
        .pulseCard(padding: 16)
    }
}

struct WeeklyMetricRow: View {
    let metric: WeeklyMetric

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(systemName: metric.icon, tint: metric.tint, size: 38, iconSize: 16)
            VStack(alignment: .leading, spacing: 2) {
                Text(metric.title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                Text(metric.value).font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.75)
                Text(metric.detail).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
                    .lineLimit(2)
            }
            Spacer(minLength: 6)
            if let change = metric.change {
                Text(change)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Capsule().fill(color.opacity(0.14)))
            }
        }
        .padding(.vertical, 12)
        .accessibilityElement(children: .combine)
    }

    private var color: Color {
        switch metric.trend {
        case .better: Theme.Palette.green
        case .worse: Theme.Palette.orange
        case .same, .none: Theme.Palette.textSecondary
        }
    }
}

private func bulletCard(_ items: [String], icon: String, tint: Color) -> some View {
    VStack(alignment: .leading, spacing: 12) {
        ForEach(items, id: \.self) { item in
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: icon).foregroundStyle(tint).font(.system(size: 15))
                Text(item).font(.system(size: 14)).foregroundStyle(Theme.Palette.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .pulseCard(padding: 16)
}

// MARK: - Staff

struct TeamWeeklyContent: View {
    let report: TeamWeeklyReport
    @Environment(AppRouter.self) private var router

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            LazyVGrid(columns: columns, spacing: 10) {
                kpi("Готовность", report.readiness.map { "\(Formatters.decimal($0)) / 10" } ?? "—",
                    delta: report.readinessDelta.map { Formatters.signedDecimal($0) }, good: (report.readinessDelta ?? 0) >= 0)
                kpi("Средний сон", report.sleepMinutes.map { Formatters.duration(minutes: $0) } ?? "—",
                    delta: report.sleepDelta.map { "\(Formatters.signed($0)) мин" }, good: (report.sleepDelta ?? 0) >= 0)
                kpi("Матчи · победы", "\(report.matches) · \(report.winRate.map { "\(Int($0.rounded()))%" } ?? "—")",
                    delta: winDelta, good: (report.winRate ?? 0) >= (report.previousWinRate ?? 0))
                kpi("В зоне риска", "\(report.rows.filter { !$0.risks.isEmpty }.count) из \(report.rows.count)",
                    delta: nil, good: report.risks.isEmpty)
            }
            if !report.highlights.isEmpty {
                PulseSection(title: "Главное за неделю") { bulletCard(report.highlights, icon: "star.fill", tint: Theme.Palette.yellow) }
            }
            if !report.risks.isEmpty {
                PulseSection(title: "Требуют внимания") { bulletCard(report.risks, icon: "exclamationmark.triangle.fill", tint: Theme.Palette.orange) }
            }
            PulseSection(title: "По игрокам") { playersCard }
            PulseSection(title: "Рекомендации") { bulletCard(report.tips, icon: "lightbulb.fill", tint: Theme.Palette.cyan) }
            ShareLink(item: WeeklyShareText.team(report)) {
                Label("Отправить сводку", systemImage: "square.and.arrow.up")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.cyan)
                    .frame(maxWidth: .infinity)
                    .pulseCard(padding: 14)
            }
            Text("Демо-данные команды. С сервером сводка будет строиться по данным игроков.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
    }

    private var winDelta: String? {
        guard let now = report.winRate, let before = report.previousWinRate else { return nil }
        let diff = Int((now - before).rounded())
        return "\(Formatters.signed(diff))%"
    }

    private func kpi(_ title: String, _ value: String, delta: String?, good: Bool) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
            Text(value).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                .lineLimit(1).minimumScaleFactor(0.7)
            if let delta {
                Text(delta).font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(good ? Theme.Palette.green : Theme.Palette.orange)
            } else {
                Text(" ").font(.system(size: 13))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }

    private var playersCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(report.rows.enumerated()), id: \.element.id) { index, row in
                Button { router.open(.player(row.id), in: router.selectedTab) } label: { playerRow(row) }
                    .buttonStyle(.plain)
                if index < report.rows.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func playerRow(_ row: TeamWeeklyRow) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(row.nickname).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    if !row.risks.isEmpty {
                        Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 12)).foregroundStyle(Theme.Palette.orange)
                    }
                }
                Text([row.sleepMinutes.map { "сон \(Formatters.duration(minutes: $0))" },
                      "\(row.matches) матч.",
                      row.kd.map { "K/D \(Formatters.decimal($0, fractionDigits: 2))" }].compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            Spacer(minLength: 6)
            VStack(alignment: .trailing, spacing: 4) {
                Text(row.readiness.map { Formatters.decimal($0) } ?? "—")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                if let delta = row.readinessDelta {
                    Text(Formatters.signedDecimal(delta))
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(delta >= 0 ? Theme.Palette.green : Theme.Palette.orange)
                }
            }
            Text(row.load.status.title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(row.load.status.color)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 7).padding(.vertical, 4)
                .background(Capsule().fill(row.load.status.color.opacity(0.14)))
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

// MARK: - Entry cards

/// Compact «Итоги недели» card for «Сегодня» (players) and staff «Сегодня».
struct WeeklySummaryCard: View {
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    var source = WeeklySource()

    var body: some View {
        Button { router.open(.weeklyReport, in: router.selectedTab) } label: {
            HStack(spacing: 14) {
                IconBadge(systemName: "calendar.badge.checkmark", tint: Theme.Palette.cyan, size: 46, iconSize: 20)
                VStack(alignment: .leading, spacing: 3) {
                    Text(session.isStaff ? "Сводка команды за неделю" : "Итоги недели")
                        .font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(2)
                }
                Spacer(minLength: 6)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .pulseCard(padding: 14)
        }
        .buttonStyle(.pressable)
    }

    private var subtitle: String {
        let week = WeekRange.containing(.now)
        if session.isStaff {
            let report = TeamWeeklyBuilder.demo(week: week)
            let readiness = report.readiness.map { "готовность \(Formatters.decimal($0))" } ?? "нет данных"
            return "\(week.title) · \(readiness) · рисков: \(report.risks.count)"
        }
        let report = source.report(for: week)
        guard let score = report.score else { return "\(week.title) · сравнение с прошлой неделей" }
        let delta = report.previousScore.map { score - $0 }
        return "\(week.title) · неделя \(score)\(delta.map { " (\(Formatters.signed($0)))" } ?? "")"
    }
}

/// Plain-text versions for «Поделиться».
enum WeeklyShareText {
    static func verdict(_ score: Int?) -> String {
        switch score ?? 0 {
        case 80...: "Сильная неделя"
        case 60..<80: "Хорошая неделя"
        case 40..<60: "Средняя неделя"
        default: score == nil ? "Мало данных" : "Тяжёлая неделя"
        }
    }

    static func personal(_ report: WeeklyReport) -> String {
        var lines = ["Pulse · итоги недели \(report.week.title)"]
        if let score = report.score { lines.append("Неделя: \(score)/100 — \(verdict(score))") }
        lines += report.metrics.map { "• \($0.title): \($0.value)\($0.change.map { " (\($0))" } ?? "")" }
        if !report.tips.isEmpty { lines.append("План: \(report.tips[0])") }
        return lines.joined(separator: "\n")
    }

    static func team(_ report: TeamWeeklyReport) -> String {
        var lines = ["Pulse · сводка команды \(report.week.title)"]
        if let readiness = report.readiness { lines.append("Готовность: \(Formatters.decimal(readiness))/10") }
        if let sleep = report.sleepMinutes { lines.append("Средний сон: \(Formatters.duration(minutes: sleep))") }
        lines.append("Матчи: \(report.matches)\(report.winRate.map { ", победы \(Int($0.rounded()))%" } ?? "")")
        lines += report.highlights.map { "★ \($0)" }
        lines += report.risks.map { "⚠︎ \($0)" }
        return lines.joined(separator: "\n")
    }
}
