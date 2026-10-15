import SwiftUI

/// «Сегодня» — дашборд игрока. Пять блоков без перегруза:
/// форма дня → игра (ELO) → восстановление → один вывод → план на сегодня.
struct TodayView: View {
    @Environment(GameStore.self) private var game
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @Environment(SleepStore.self) private var sleep
    @Environment(FitnessStore.self) private var fitness
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(NutritionStore.self) private var nutrition
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(CalendarStore.self) private var calendar
    @Environment(PlayerSurveyStore.self) private var survey

    @State private var showNotifications = false

    private var night: SleepNight? { sleep.displayNight }
    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        let player = game.profile(for: session.user)
        ScreenScaffold(spacing: 22) {
            TopPanel(onNotifications: { showNotifications = true })
        } content: {
            greeting
            if !survey.isComplete { SurveyReminderCard() }
            if let form = formIndex { FormDayCard(result: form) { router.open(.statistics, in: .today) } }
            PulseSection(title: "Игра") {
                if game.hasData {
                    GameSummaryCard(player: player) { router.open(.gameStats, in: .today) }
                } else {
                    FaceitSourceCard()
                }
            }
            PulseSection(title: "Восстановление") { metricsGrid }
            if let insight = insight(for: player) {
                InsightCard(insight: insight) { router.open(.statistics, in: .today) }
            }
            PulseSection(title: "Неделя") { WeeklySummaryCard() }
            PulseSection(title: "План на сегодня") { planCard }
        }
        .sheet(isPresented: $showNotifications) {
            NotificationsSheet().presentationDetents([.medium, .large])
        }
    }

    // MARK: Data

    private var formIndex: FormIndex.Result? {
        let today = Date.now
        let mood = wellbeing.averageMood(on: today) ?? wellbeing.averageMood(on: today.adding(days: -1))
        let cognitiveScore = cognitive.averageScore(on: today) ?? cognitive.dailyScores(lastDays: 7).last?.value
        return FormIndex.compute(
            sleepMinutes: sleep.minutes(on: today) ?? night?.totalMinutes,
            energy: mood?.energy,
            mood: mood?.mood,
            cognitive: cognitiveScore,
            fatigue: cognitive.gameResults.first.map { Double($0.fatigue) }
        )
    }

    private func insight(for player: PlayerProfile) -> GameInsight.Result? {
        GameInsight.sleepVersusKD(matches: game.matches(for: session.user)) { day in
            sleep.minutes(on: day)
        }
    }

    // MARK: Blocks

    private var greeting: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(Formatters.dayMonthWeekday(.now).uppercased())
                .font(.system(size: 13, weight: .semibold))
                .tracking(1.6)
                .foregroundStyle(Theme.Palette.cyan)
            Text("\(greetingWord), \(session.user?.nickname ?? "игрок")")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 4)
    }

    private var greetingWord: String {
        switch Calendar.pulse.component(.hour, from: .now) {
        case 5..<12: "Доброе утро"
        case 12..<18: "Добрый день"
        default: "Добрый вечер"
        }
    }

    private var metricsGrid: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            SummaryTile(title: "Сон", icon: "moon", tint: Theme.Palette.purple,
                        value: night.map { Formatters.duration(minutes: $0.totalMinutes) } ?? "—",
                        caption: sleep.isSleeping ? "Сон активен" : night.map { "Оценка \($0.score)" } ?? "Нет данных",
                        progress: Double(night?.score ?? 0) / 100) { router.open(.sleep, in: .today) }
            SummaryTile(title: "Активность", icon: "flame", tint: Theme.Palette.orange,
                        value: "\(fitness.activeMinutesToday) мин", caption: "цель \(fitness.activeMinutesGoal) мин",
                        progress: Double(fitness.activeMinutesToday) / Double(fitness.activeMinutesGoal)) { router.open(.fitness, in: .today) }
            SummaryTile(title: "Экран", icon: "iphone", tint: Theme.Palette.cyan,
                        value: Formatters.duration(minutes: screenTime.todayMinutes),
                        caption: "лимит \(screenTime.dailyLimitMinutes / 60) ч",
                        progress: screenTime.limitProgress) { router.open(.screenTime, in: .today) }
            SummaryTile(title: "Питание", icon: "fork.knife", tint: Theme.Palette.green,
                        value: "\(nutrition.totals(on: .now).kcal) ккал", caption: "из \(String(nutrition.goals.kcal))",
                        progress: Double(nutrition.totals(on: .now).kcal) / Double(nutrition.goals.kcal)) { router.show([.nutrition], in: .state) }
        }
    }

    /// Чек-лист дня: батарея тестов, слоты настроения и ближайшее событие — вместо трёх отдельных блоков.
    private var planCard: some View {
        let filled = wellbeing.filledSlots(on: .now).count
        let nextEvent = calendar.events(on: .now, tag: nil).first { $0.end > .now }
        return VStack(spacing: 0) {
            PlanRow(icon: "brain.head.profile", tint: Theme.Palette.green, title: "Когнитивная батарея",
                    detail: cognitive.completedToday ? "Пройдена" : "5 тестов · ~4 мин", isDone: cognitive.completedToday) {
                router.open(.cognitive, in: .today)
            }
            DividerLine()
            PlanRow(icon: "face.smiling", tint: Theme.Palette.yellow, title: "Настроение и энергия",
                    detail: "Заполнено \(filled) из 3", isDone: filled == 3) {
                router.open(.mood, in: .today)
            }
            DividerLine()
            if let nextEvent {
                PlanRow(icon: "calendar", tint: Theme.Palette.cyan, title: nextEvent.title,
                        detail: "\(Formatters.time(nextEvent.start)) – \(Formatters.time(nextEvent.end))", isDone: false) {
                    router.open(.event(nextEvent.id), in: .today)
                }
            } else {
                PlanRow(icon: "calendar", tint: Theme.Palette.cyan, title: "Событий больше нет",
                        detail: "Открыть календарь", isDone: true) {
                    router.open(.calendar(createNew: false), in: .today)
                }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }
}

/// Hero card: composite «Форма дня» with its three main contributors.
struct FormDayCard: View {
    let result: FormIndex.Result
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 18) {
                ScoreRing(progress: Double(result.score) / 100, value: "\(result.score)", caption: "ФОРМА ДНЯ",
                          size: 112, lineWidth: 11, valueSize: 38)
                VStack(alignment: .leading, spacing: 10) {
                    Text(result.label)
                        .font(.system(size: 19, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    ForEach(result.components.prefix(3)) { component in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(component.title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                                Spacer()
                                Text("\(Int(component.value.rounded()))")
                                    .font(.system(size: 13, weight: .semibold))
                                    .foregroundStyle(Theme.Palette.textPrimary)
                                    .monospacedDigit()
                            }
                            GradientBar(progress: component.value / 100, height: 5)
                        }
                    }
                }
            }
            .pulseCard(padding: 18)
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("Форма дня \(result.score) из 100, \(result.label)")
    }
}

/// One-sentence insight linking recovery to game results.
struct InsightCard: View {
    let insight: GameInsight.Result
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(alignment: .top, spacing: 14) {
                IconBadge(systemName: "sparkles", tint: insight.isPositive ? Theme.Palette.cyan : Theme.Palette.orange,
                          size: 42, iconSize: 18)
                VStack(alignment: .leading, spacing: 4) {
                    Text("СОН И ИГРА")
                        .font(.system(size: 12, weight: .semibold))
                        .tracking(1.6)
                        .foregroundStyle(Theme.Palette.section)
                    Text(insight.text)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textTertiary)
                    .padding(.top, 4)
            }
            .pulseCard(padding: 16)
        }
        .buttonStyle(.pressable)
    }
}

struct PlanRow: View {
    let icon: String
    let tint: Color
    let title: String
    let detail: String
    let isDone: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                IconBadge(systemName: icon, tint: tint, size: 40, iconSize: 17)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                    Text(detail).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer()
                Image(systemName: isDone ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 22))
                    .foregroundStyle(isDone ? Theme.Palette.green : Theme.Palette.textTertiary)
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

struct SummaryTile: View {
    let title: String
    let icon: String
    let tint: Color
    let value: String
    let caption: String
    let progress: Double
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    Image(systemName: icon).font(.system(size: 18)).foregroundStyle(tint)
                    Text(title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                    Spacer()
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
                }
                Text(value)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                GradientBar(progress: progress, height: 6)
                Text(caption).font(.system(size: 13)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .pulseCard(padding: 16, radius: Theme.Metrics.tileRadius)
        }
        .buttonStyle(.pressable)
    }
}

/// Верхняя панель: статус профиля слева; огонёк (серия дней), календарь, уведомления и аккаунт справа.
/// Тариф по просьбе заказчика показывается только в профиле.
struct TopPanel: View {
    let onNotifications: () -> Void
    @AppStorage(NotificationInbox.storageKey) private var dismissedRaw = ""
    var inbox = InboxSource()
    var streak = StreakSource()

    private var unread: Int { NotificationInbox.visible(inbox.items, dismissedRaw: dismissedRaw).count }
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @Environment(CalendarStore.self) private var calendar

    /// Slightly smaller than screen header buttons so the status badge and 4 actions fit one row.
    private let buttonSize: CGFloat = 42

    var body: some View {
        HStack(spacing: 6) {
            if let role = session.user?.role {
                RoleBadge(role: role, compact: true)
                    .fixedSize()
            }
            Spacer(minLength: 0)
            HStack(spacing: 6) {
                Image(systemName: "flame.fill").foregroundStyle(Theme.Palette.orange)
                Text("\(streak.days)")
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
            }
            .padding(.horizontal, 10)
            .frame(height: buttonSize)
            .fixedSize()
            .background(RoundedRectangle(cornerRadius: Theme.Metrics.buttonRadius, style: .continuous).fill(Theme.Palette.card))
            .overlay(RoundedRectangle(cornerRadius: Theme.Metrics.buttonRadius, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
            .accessibilityLabel("Серия заполнения: \(streak.days) дней")

            Button {
                router.open(.calendar(createNew: false), in: .today)
            } label: {
                HeaderIconLabel(systemName: "calendar", size: buttonSize)
                    .overlay(alignment: .topTrailing) {
                        if hasEventsToday {
                            Circle()
                                .fill(Theme.Palette.cyan)
                                .frame(width: 9, height: 9)
                                .overlay(Circle().strokeBorder(Theme.Palette.background, lineWidth: 1.5))
                                .offset(x: 2, y: -2)
                        }
                    }
            }
            .buttonStyle(.pressable)
            .accessibilityLabel(hasEventsToday ? "Календарь, есть события сегодня" : "Календарь")

            Button(action: onNotifications) {
                HeaderIconLabel(systemName: "bell", size: buttonSize)
                    .overlay(alignment: .topTrailing) {
                        if unread > 0 {
                            Text("\(unread)")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundStyle(.white)
                                .frame(minWidth: 18, minHeight: 18)
                                .background(Circle().fill(Theme.Palette.red))
                                .offset(x: 4, y: -4)
                                .transition(.scale.combined(with: .opacity))
                        }
                    }
            }
            .buttonStyle(.pressable)
            .accessibilityLabel("Уведомления")

            Menu {
                Button("Профиль", systemImage: "person") { router.selectedTab = .profile }
                Button("Выйти", systemImage: "rectangle.portrait.and.arrow.right", role: .destructive) { session.logout() }
            } label: {
                Text(session.user?.initials ?? "?")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: buttonSize, height: buttonSize)
                    .background(Circle().fill(Theme.Gradients.accentDiagonal))
            }
            .accessibilityLabel("Аккаунт")
        }
    }

    private var hasEventsToday: Bool {
        !calendar.events(on: .now, tag: nil).isEmpty
    }
}
