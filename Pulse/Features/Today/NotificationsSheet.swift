import SwiftUI

/// In-app inbox behind the bell. Swiping a card away hides it (remembered between launches).
enum NotificationInbox {
    struct Item: Identifiable, Equatable {
        let id: String
        let icon: String
        let tint: Color
        let title: String
        let body: String
        let time: String
    }

    static let storageKey = "inbox.dismissed"

    /// What the inbox is built from (real accounts get reminders from their own data).
    struct Context {
        var isDemo: Bool
        var now: Date = .now
        var events: [CalendarEvent] = []
        var batteryDone = false
        var moodSlotsFilled = 0
        var sleptLastNight = false
        var isSleeping = false
    }

    static func items(for context: Context) -> [Item] {
        context.isDemo ? items : derived(context)
    }

    /// Reminders from the account's state; ids include the day so a dismissed card comes back tomorrow.
    static func derived(_ context: Context) -> [Item] {
        let now = context.now
        let day = Formatters.dayKey(now)
        let hour = Calendar.pulse.component(.hour, from: now)
        var result: [Item] = []
        if let next = context.events.filter({ $0.start > now && $0.start < now.addingTimeInterval(12 * 3600) }).min(by: { $0.start < $1.start }) {
            result.append(Item(id: "event-\(next.id.uuidString)", icon: "calendar", tint: Theme.Palette.cyan,
                               title: next.title, body: "Начало в \(Formatters.time(next.start))",
                               time: next.start.isSameDay(as: now) ? "сегодня" : "завтра"))
        }
        if !context.batteryDone && hour >= 9 {
            result.append(Item(id: "battery-\(day)", icon: "brain.head.profile", tint: Theme.Palette.green,
                               title: "Пройди когнитивную батарею", body: "5 тестов, около 4 минут", time: "сегодня"))
        }
        let expectedSlots = hour < 12 ? 1 : (hour < 18 ? 2 : 3)
        if context.moodSlotsFilled < expectedSlots {
            result.append(Item(id: "mood-\(day)-\(expectedSlots)", icon: "face.smiling", tint: Theme.Palette.yellow,
                               title: "Отметь настроение и энергию",
                               body: "Заполнено \(context.moodSlotsFilled) из 3 за сегодня", time: "сегодня"))
        }
        if !context.sleptLastNight && !context.isSleeping && hour < 15 {
            result.append(Item(id: "sleep-\(day)", icon: "moon.zzz", tint: Theme.Palette.purple,
                               title: "Нет данных о прошлой ночи",
                               body: "Вечером нажми «Начать сон» или подключи Apple Health", time: "сегодня"))
        }
        return result
    }

    static let items: [Item] = [
        Item(id: "training-soon", icon: "calendar", tint: Theme.Palette.cyan,
             title: "Тренировка через час", body: "Командная практика, 12:00", time: "11:00"),
        Item(id: "game-test", icon: "brain.head.profile", tint: Theme.Palette.green,
             title: "Пройди тест до тренировки", body: "Игровой тест займёт 1 минуту", time: "10:45"),
        Item(id: "screen-time", icon: "hourglass", tint: Theme.Palette.orange,
             title: "Разрешение Screen Time", body: "Дай доступ, чтобы видеть реальное экранное время", time: "09:12"),
        Item(id: "bedtime", icon: "moon.zzz", tint: Theme.Palette.purple,
             title: "Скоро отбой", body: "Начни сон в Pulse — таймер будет на экране блокировки", time: "вчера")
    ]

    static func dismissedIDs(_ raw: String) -> Set<String> {
        Set(raw.split(separator: ",").map(String.init))
    }

    static func visible(dismissedRaw raw: String) -> [Item] {
        visible(items, dismissedRaw: raw)
    }

    static func visible(_ list: [Item], dismissedRaw raw: String) -> [Item] {
        let dismissed = dismissedIDs(raw)
        return list.filter { !dismissed.contains($0.id) }
    }

    static func dismissing(_ id: String, from raw: String) -> String {
        // Keep the list short: day-specific ids pile up otherwise.
        Array((dismissedIDs(raw).union([id])).sorted().suffix(300)).joined(separator: ",")
    }
}

/// Reads the stores needed for the inbox; use as a property in views (`private var inbox = InboxSource()`).
struct InboxSource: DynamicProperty {
    @Environment(SessionStore.self) private var session
    @Environment(CalendarStore.self) private var calendar
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(SleepStore.self) private var sleep

    var items: [NotificationInbox.Item] {
        NotificationInbox.items(for: NotificationInbox.Context(
            isDemo: session.user?.isDemo ?? false,
            events: calendar.events,
            batteryDone: cognitive.completedToday,
            moodSlotsFilled: wellbeing.filledSlots(on: .now).count,
            // Sleep is attributed to the wake-up day, so last night = a session ending today.
            sleptLastNight: sleep.minutes(on: .now) != nil,
            isSleeping: sleep.isSleeping
        ))
    }
}

struct NotificationsSheet: View {
    @AppStorage(NotificationInbox.storageKey) private var dismissedRaw = ""
    var inbox = InboxSource()

    var body: some View {
        let items = NotificationInbox.visible(inbox.items, dismissedRaw: dismissedRaw)
        SheetScaffold(title: "Уведомления") {
            if items.isEmpty {
                EmptyStateView(systemName: "bell.slash", title: "Новых уведомлений нет",
                               message: "Здесь появятся напоминания о тренировках, тестах и сне")
                SecondaryButton(title: "Показать снова", systemImage: "arrow.counterclockwise") {
                    withAnimation { dismissedRaw = "" }
                }
            } else {
                HStack {
                    Label("Смахни карточку, чтобы убрать", systemImage: "hand.draw")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.Palette.textTertiary)
                    Spacer()
                    Button("Очистить всё") {
                        withAnimation {
                            dismissedRaw = items.reduce(dismissedRaw) { NotificationInbox.dismissing($1.id, from: $0) }
                        }
                    }
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.Palette.cyan)
                }
                ForEach(items) { item in
                    row(item)
                        .swipeToDismiss {
                            withAnimation(.easeInOut(duration: 0.25)) {
                                dismissedRaw = NotificationInbox.dismissing(item.id, from: dismissedRaw)
                            }
                        }
                        .transition(.asymmetric(insertion: .opacity, removal: .move(edge: .leading).combined(with: .opacity)))
                }
            }
        }
        .sensoryFeedback(.impact(weight: .light), trigger: dismissedRaw)
    }

    private func row(_ item: NotificationInbox.Item) -> some View {
        HStack(alignment: .top, spacing: 14) {
            IconBadge(systemName: item.icon, tint: item.tint, size: 44, iconSize: 18)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(item.body).font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer()
            Text(item.time).font(.system(size: 13)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 14)
    }
}

extension View {
    /// Horizontal swipe (either direction) past a threshold removes the card; a short swipe springs back.
    func swipeToDismiss(onDismiss: @escaping () -> Void) -> some View {
        modifier(SwipeToDismiss(onDismiss: onDismiss))
    }
}

private struct SwipeToDismiss: ViewModifier {
    let onDismiss: () -> Void

    @State private var offset: CGFloat = 0
    @State private var isHorizontal: Bool?
    private let threshold: CGFloat = 110

    func body(content: Content) -> some View {
        let progress = min(abs(offset) / threshold, 1)
        ZStack {
            RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                .fill(Theme.Palette.red.opacity(0.85 * progress))
                .overlay(alignment: offset < 0 ? .trailing : .leading) {
                    Label("Убрать", systemImage: "trash")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 20)
                        .opacity(progress)
                }
            content.offset(x: offset)
        }
        .simultaneousGesture(
            DragGesture(minimumDistance: 16)
                .onChanged { value in
                    if isHorizontal == nil {
                        isHorizontal = abs(value.translation.width) > abs(value.translation.height)
                    }
                    guard isHorizontal == true else { return }
                    offset = value.translation.width
                }
                .onEnded { value in
                    defer { isHorizontal = nil }
                    guard isHorizontal == true else { return }
                    let flung = abs(value.predictedEndTranslation.width) > threshold * 2.5
                    if abs(offset) > threshold || flung {
                        let direction: CGFloat = (offset == 0 ? value.predictedEndTranslation.width : offset) < 0 ? -1 : 1
                        withAnimation(.easeIn(duration: 0.18)) {
                            offset = direction * 500
                        } completion: {
                            onDismiss()
                        }
                    } else {
                        withAnimation(.spring(duration: 0.3)) { offset = 0 }
                    }
                }
        )
    }
}

/// «Дней подряд»: consecutive days with any entry (mood, test, meal, sleep, workout).
/// A day without entries yet doesn't break the streak until it is over.
enum ActivityStreak {
    static func days(activeDays: Set<Date>, today: Date = .now) -> Int {
        var day = today.startOfDay
        if !activeDays.contains(day) { day = day.adding(days: -1) }
        var count = 0
        while activeDays.contains(day) {
            count += 1
            day = day.adding(days: -1)
        }
        return count
    }
}

struct StreakSource: DynamicProperty {
    @Environment(SessionStore.self) private var session
    @Environment(WellbeingStore.self) private var wellbeing
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(NutritionStore.self) private var nutrition
    @Environment(SleepStore.self) private var sleep
    @Environment(FitnessStore.self) private var fitness

    var days: Int {
        if let user = session.user, user.isDemo { return user.streakDays }
        let dates = wellbeing.moodEntries.map(\.day) + cognitive.results.map(\.date) + nutrition.meals.map(\.date)
            + sleep.sessions.map(\.end) + fitness.workouts.map(\.date)
        return ActivityStreak.days(activeDays: Set(dates.map(\.startOfDay)))
    }
}
