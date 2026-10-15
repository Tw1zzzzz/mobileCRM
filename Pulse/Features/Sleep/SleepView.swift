import SwiftUI

/// Mockup 1 — «Сон»: score, phases, cycles & quality + tracking controls and history from the spec.
struct SleepView: View {
    @Environment(SleepStore.self) private var store
    @Environment(AppRouter.self) private var router

    /// Screens below the tracker need a night; a new account sees a hint instead.
    private var night: SleepNight { store.displayNight ?? .placeholder }
    private var hasNight: Bool { store.displayNight != nil }
    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Сон") {
                ShareLink(item: shareText) { HeaderIconLabel(systemName: "point.3.connected.trianglepath.dotted") }
                    .accessibilityLabel("Поделиться")
            }
        } content: {
            if !hasNight {
                PulseSection(title: "Трекинг сна") { SleepTrackingCard() }
                NoSleepDataCard()
            } else if store.isSleeping {
                // While a session runs the timer matters most.
                PulseSection(title: "Трекинг сна") { SleepTrackingCard() }
                scoreCard
            } else {
                scoreCard
                PulseSection(title: "Трекинг сна") { SleepTrackingCard() }
            }
            if hasNight {
                PulseSection(title: "Фазы сна") { phasesCard }
                PulseSection(title: "Циклы и качество") { qualityGrid }
            }
            recoveryLink
            PulseSection(title: "Статистика · 7 дней") { statsRow }
            PulseSection(title: "История сна") { SleepHistoryList(sessions: Array(store.sessions.prefix(10))) }
        }
    }

    private var shareText: String {
        "Сон \(Formatters.duration(minutes: night.totalMinutes)), оценка \(night.score) — Pulse"
    }

    private var scoreCard: some View {
        HStack(alignment: .center, spacing: 14) {
            ScoreRing(progress: Double(night.score) / 100, value: "\(night.score)", caption: "ОЦЕНКА СНА", size: 138, lineWidth: 13, valueSize: 50)
            VStack(alignment: .trailing, spacing: 12) {
                Text(nightTitle)
                    .font(.system(size: 14, weight: .medium))
                    .tracking(1.4)
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                VStack(alignment: .leading, spacing: 10) {
                    DurationText(minutes: night.totalMinutes, valueSize: 42, unitSize: 26)
                    HStack(spacing: 10) {
                        Image(systemName: "moon").foregroundStyle(Theme.Palette.purple)
                        Text("\(Formatters.time(night.start)) → \(Formatters.time(night.end))")
                            .foregroundStyle(Theme.Palette.textSecondary)
                            .monospacedDigit()
                    }
                    .font(.system(size: 18))
                    StatusPill(text: "\(night.scoreLabel) · \(night.baselineDelta >= 0 ? "+" : "")\(night.baselineDelta) к норме")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .pulseCard(padding: 16)
    }

    private var nightTitle: String {
        let start = Calendar.pulse.component(.day, from: night.start)
        let end = Formatters.dayMonth(night.end)
        return "НОЧЬ \(start) → \(end.uppercased())"
    }

    @ViewBuilder
    private var phasesCard: some View {
        if night.hasStages && !night.segments.isEmpty {
            VStack(spacing: 20) {
                HypnogramView(night: night)
                DividerLine()
                SleepStageLegend(night: night)
            }
            .pulseCard(padding: 20)
        } else {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: "applewatch")
                    .font(.system(size: 22))
                    .foregroundStyle(Theme.Palette.purple)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Фазы сна недоступны")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text("Глубокий, REM и лёгкий сон записывают Apple Watch и трекеры, подключённые к Apple Health. Сейчас показана длительность из трекинга сна.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .pulseCard(padding: 16)
        }
    }

    private var qualityGrid: some View {
        LazyVGrid(columns: columns, spacing: 14) {
            SleepMetricTile(icon: "arrow.triangle.2.circlepath", tint: Theme.Palette.purple, title: "Циклов",
                            value: "\(night.cycles)", unit: nil, caption: "по ~\(night.averageCycleMinutes) мин")
            SleepMetricTile(icon: "target", tint: Theme.Palette.green, title: "Эффективность",
                            value: "\(night.efficiency)", unit: "%", caption: "время в кровати")
            SleepMetricTile(icon: "clock", tint: Theme.Palette.blue, title: "Засыпание",
                            value: "\(night.latencyMinutes)", unit: "мин", caption: night.latencyMinutes <= 20 ? "Нормально" : "Долго")
            SleepMetricTile(icon: "eye", tint: Theme.Palette.orange, title: "Пробуждений",
                            value: "\(night.awakenings)", unit: nil, caption: night.awakenings <= 3 ? "Короткие" : "Частые")
        }
    }

    private var recoveryLink: some View {
        Button {
            router.open(.sleepRecovery, in: router.selectedTab)
        } label: {
            HStack(spacing: 16) {
                IconBadge(systemName: "heart.text.square", tint: Theme.Palette.cyan, size: 52, iconSize: 22)
                VStack(alignment: .leading, spacing: 4) {
                    Text("РЕЖИМ И ВОССТАНОВЛЕНИЕ")
                        .font(.system(size: 14, weight: .semibold))
                        .tracking(1.4)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(Theme.Palette.cyan)
                    Text("Пульс, HRV, неделя и окно сна")
                        .font(.system(size: 15))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Theme.Palette.textSecondary)
            }
            .pulseCard()
        }
        .buttonStyle(.pressable)
    }

    private var statsRow: some View {
        HStack(spacing: 14) {
            statTile(title: "Средняя", minutes: store.averageMinutes(lastDays: 7) ?? 0)
            statTile(title: "Общая", minutes: store.totalMinutes(lastDays: 7))
        }
    }

    private func statTile(title: String, minutes: Int) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textSecondary)
            DurationText(minutes: minutes, valueSize: 28, unitSize: 18)
        }
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }
}

struct SleepMetricTile: View {
    let icon: String
    let tint: Color
    let title: String
    let value: String
    let unit: String?
    let caption: String

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                IconBadge(systemName: icon, tint: tint, size: 34, iconSize: 15)
                Text(title)
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
            }
            ValueUnitText(value: value, unit: unit, valueSize: 36, unitSize: 22)
            Text(caption)
                .font(.system(size: 16))
                .foregroundStyle(Theme.Palette.textSecondary)
        }
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }
}

/// A new account before the first night: how to start collecting sleep data.
struct NoSleepDataCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "moon.zzz")
                .font(.system(size: 24))
                .foregroundStyle(Theme.Palette.purple)
            VStack(alignment: .leading, spacing: 4) {
                Text("Данных о сне пока нет")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("Нажми «Начать сон», когда ложишься, или подключи Apple Health в «Фитнесе» — ночи с часов подтянутся сами.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 16)
    }
}
