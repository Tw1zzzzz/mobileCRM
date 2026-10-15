import SwiftUI
import Charts

/// Вкладка «Тесты»: блоки «Ежедневные» и «Еженедельные».
struct TestsView: View {
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(WellbeingStore.self) private var wellbeing

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Тесты", subtitle: "Ежедневные и еженедельные")
        } content: {
            PulseSection(title: "Ежедневные тесты") {
                VStack(spacing: 12) {
                    HubRow(icon: "brain.head.profile", tint: Theme.Palette.green, title: "Когнитивные тесты",
                           subtitle: cognitive.completedToday ? "Батарея пройдена сегодня" : "5 тестов когнитивной формы", route: .cognitive)
                    HubRow(icon: "face.smiling", tint: Theme.Palette.yellow, title: "Настроение и энергия",
                           subtitle: "Утро · день · вечер — \(wellbeing.filledSlots(on: .now).count)/3", route: .mood)
                    HubRow(icon: "gamecontroller", tint: Theme.Palette.cyan, title: "Игровые тесты",
                           subtitle: "До и после тренировки", route: .gameTests)
                }
            }
            PulseSection(title: "Еженедельные") {
                VStack(spacing: 12) {
                    HubRow(icon: "circle.hexagongrid", tint: Theme.Palette.pink, title: "Колесо баланса",
                           subtitle: wellbeing.latestBalance.map { "Последнее: \(Formatters.dayMonth($0.day))" } ?? "Ещё не заполнено",
                           route: .balance)
                    weeklyPlaceholder
                }
            }
        }
    }

    private var weeklyPlaceholder: some View {
        HStack(spacing: 16) {
            IconBadge(systemName: "calendar.badge.clock", tint: Theme.Palette.textSecondary, size: 52, iconSize: 22)
            VStack(alignment: .leading, spacing: 4) {
                Text("Еженедельные тесты").font(.system(size: 18, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.75)
                Text("Анкеты из веб-версии — подключим с API").font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Text("скоро").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.Palette.orange)
                .padding(.horizontal, 10).padding(.vertical, 5)
                .background(Capsule().fill(Theme.Palette.orange.opacity(0.14)))
        }
        .pulseCard(padding: 14)
    }
}

/// Раздел «Когнитивные тесты»: описание, запуск батареи, история и тренд 7 дней (блюр без подписки).
struct CognitiveHubView: View {
    @Environment(CognitiveStore.self) private var store
    @Environment(SessionStore.self) private var session
    @State private var showBattery = false
    @State private var historyExpanded = true
    @State private var trendDay: Date?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Когнитивные тесты")
        } content: {
            introCard
            PulseSection(title: "Тренд последних 7 дней") {
                trendCard.lockedIfNeeded(!session.hasSubscription)
            }
            historySection
        }
        .fullScreenCover(isPresented: $showBattery) {
            BatteryRunnerView { kind, outcome in store.record(kind: kind, outcome: outcome) }
        }
    }

    private var introCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Батарея из 5 тестов измеряет твою когнитивную форму: реакцию, самоконтроль, концентрацию, память и моторику. Проходи ежедневно в одно время — так тренд будет точнее.")
                .font(.system(size: 16))
                .foregroundStyle(Theme.Palette.textSecondary)
            HStack(spacing: 8) {
                ForEach(CognitiveTestKind.allCases) { kind in
                    IconBadge(systemName: kind.icon, tint: kind.tint, size: 40, iconSize: 17)
                }
            }
            GradientButton(title: "Запустить батарею", systemImage: "play.fill") { showBattery = true }
        }
        .pulseCard(padding: 20)
    }

    private var trendCard: some View {
        let series = store.dailyScores(lastDays: 7)
        return Chart {
            ForEach(series) { item in
                LineMark(x: .value("День", item.date, unit: .day), y: .value("Score", item.value))
                    .foregroundStyle(Theme.Palette.cyan)
                    .lineStyle(StrokeStyle(lineWidth: 3))
                    .interpolationMethod(.catmullRom)
                PointMark(x: .value("День", item.date, unit: .day), y: .value("Score", item.value))
                    .foregroundStyle(Theme.Palette.cyan)
            }
            if let selected = series.value(on: trendDay) {
                DayScrubRule(date: selected.date, callout: ChartCallout(
                    title: Formatters.dayMonthWeekday(selected.date), value: "\(Int(selected.value.rounded())) баллов", tint: Theme.Palette.cyan))
                ScrubPoint(x: .value("День", selected.date, unit: .day), y: selected.value, tint: Theme.Palette.cyan)
            }
        }
        .chartScrub($trendDay) { series.nearest(to: $0)?.date }
        .chartYScale(domain: 0...100)
        .chartYAxis {
            AxisMarks(position: .leading, values: [0, 50, 100]) {
                AxisGridLine().foregroundStyle(Theme.Palette.divider)
                AxisValueLabel().foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .chartXAxis {
            AxisMarks(values: .stride(by: .day)) { value in
                AxisValueLabel { if let date = value.as(Date.self) { Text(Formatters.weekdayShort(date)) } }
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .frame(height: 170)
        .pulseCard(padding: 20)
    }

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button {
                withAnimation(.easeInOut) { historyExpanded.toggle() }
            } label: {
                HStack {
                    SectionHeader(title: "История")
                    Image(systemName: historyExpanded ? "chevron.up" : "chevron.down")
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .buttonStyle(.plain)
            if historyExpanded {
                VStack(spacing: 0) {
                    ForEach(Array(CognitiveTestKind.allCases.enumerated()), id: \.element.id) { index, kind in
                        historyRow(kind)
                        if index < CognitiveTestKind.allCases.count - 1 { DividerLine() }
                    }
                }
                .padding(.horizontal, 16)
                .pulseCard(padding: 0)
                .lockedIfNeeded(!session.hasSubscription)
            }
        }
    }

    private func historyRow(_ kind: CognitiveTestKind) -> some View {
        let latest = store.latest(for: kind)
        return HStack(spacing: 14) {
            IconBadge(systemName: kind.icon, tint: kind.tint, size: 44, iconSize: 18)
            VStack(alignment: .leading, spacing: 3) {
                Text(kind.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(kind.target).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                Text(latest.map { "Последний раз: \(Formatters.dayMonthTime($0.date))" } ?? "Ещё не проходил")
                    .font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text(latest.map { "\($0.score)" } ?? "—").font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(latest?.metric ?? "").font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .padding(.vertical, 12)
    }
}

private struct LockedOverlay: ViewModifier {
    let isLocked: Bool

    func body(content: Content) -> some View {
        if isLocked {
            content
                .blur(radius: 8)
                .allowsHitTesting(false)
                .overlay {
                    VStack(spacing: 8) {
                        Image(systemName: "lock.fill").font(.system(size: 26))
                        Text("Доступно по подписке PRO").font(.system(size: 16, weight: .semibold))
                    }
                    .foregroundStyle(Theme.Palette.textPrimary)
                }
        } else {
            content
        }
    }
}

extension View {
    func lockedIfNeeded(_ isLocked: Bool) -> some View {
        modifier(LockedOverlay(isLocked: isLocked))
    }
}
