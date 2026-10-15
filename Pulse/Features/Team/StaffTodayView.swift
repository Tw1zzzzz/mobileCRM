import SwiftUI

/// «Сегодня» для стаффа: состояние команды, кто требует внимания, события дня.
struct StaffTodayView: View {
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @Environment(CalendarStore.self) private var calendar

    @State private var showNotifications = false

    var body: some View {
        let snapshots = TeamAnalytics.snapshots()
        let readiness = StatisticsMath.mean(snapshots.map(\.readiness)) ?? 0
        let attention = snapshots.filter(\.needsAttention)
        ScreenScaffold {
            TopPanel(onNotifications: { showNotifications = true })
        } content: {
            VStack(alignment: .leading, spacing: 6) {
                Text(Formatters.dayMonthWeekday(.now).uppercased())
                    .font(.system(size: 14, weight: .semibold))
                    .tracking(1.6)
                    .foregroundStyle(Theme.Palette.cyan)
                Text("Команда \(session.user?.team ?? "")")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .padding(.horizontal, 4)

            HStack(spacing: 18) {
                ScoreRing(progress: readiness / 10, value: Formatters.decimal(readiness), caption: "ГОТОВНОСТЬ",
                          size: 124, lineWidth: 12, valueSize: 36)
                VStack(alignment: .leading, spacing: 10) {
                    stat("Игроков", "\(snapshots.count)")
                    stat("Требуют внимания", "\(attention.count)", tint: attention.isEmpty ? Theme.Palette.green : Theme.Palette.orange)
                    stat("Средний score", "\(Int(StatisticsMath.mean(snapshots.map { Double($0.averageScore) }) ?? 0))")
                }
                Spacer(minLength: 0)
            }
            .pulseCard(padding: 18)

            WeeklySummaryCard()

            PulseSection(title: "Требуют внимания", actionTitle: "Аналитика") {
                router.selectedTab = .analytics
            } content: {
                attentionCard(attention)
            }

            PulseSection(title: "События сегодня", actionTitle: "Календарь") {
                router.open(.calendar(createNew: false), in: .today)
            } content: {
                eventsCard
            }
        }
        .sheet(isPresented: $showNotifications) {
            NotificationsSheet().presentationDetents([.medium, .large])
        }
    }

    private func stat(_ title: String, _ value: String, tint: Color = Theme.Palette.textPrimary) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
            Text(value).font(.system(size: 20, weight: .bold)).foregroundStyle(tint)
        }
    }

    @ViewBuilder
    private func attentionCard(_ items: [PlayerSnapshot]) -> some View {
        if items.isEmpty {
            Text("Все игроки в норме 👍")
                .font(.system(size: 16))
                .foregroundStyle(Theme.Palette.textSecondary)
                .pulseCard()
        } else {
            VStack(spacing: 0) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, snapshot in
                    Button { router.open(.player(snapshot.player.id), in: .today) } label: {
                        HStack(spacing: 12) {
                            Text(String(snapshot.player.nickname.prefix(2)).uppercased())
                                .font(.system(size: 14, weight: .bold)).foregroundStyle(.white)
                                .frame(width: 40, height: 40)
                                .background(Circle().fill(Theme.Gradients.accentDiagonal))
                            VStack(alignment: .leading, spacing: 3) {
                                Text(snapshot.player.nickname).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                                Text(snapshot.attentionReason).font(.system(size: 13)).foregroundStyle(Theme.Palette.orange)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
                        }
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    if index < items.count - 1 { DividerLine() }
                }
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)
        }
    }

    @ViewBuilder
    private var eventsCard: some View {
        // Players' personal plans are private: staff see team events and player schedules.
        let events = calendar.events(on: .now, tag: nil).filter { $0.tag != .personal }
        if events.isEmpty {
            Text("На сегодня событий нет").font(.system(size: 16)).foregroundStyle(Theme.Palette.textSecondary).pulseCard()
        } else {
            VStack(spacing: 0) {
                ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                    Button { router.open(.event(event.id), in: .today) } label: { EventRow(event: event) }
                        .buttonStyle(.plain)
                    if index < events.count - 1 { DividerLine() }
                }
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)
        }
    }
}
