import SwiftUI

/// Календарь: сетка месяца, события выбранной даты, «Создать событие», тэги для менеджера.
struct CalendarScreen: View {
    var startsWithCreate = false

    @Environment(CalendarStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router

    @State private var month = Date.now.startOfDay
    @State private var selectedDay = Date.now.startOfDay
    @State private var tag: EventTag?
    @State private var showCreate = false
    @State private var didAutoOpen = false

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Календарь") {
                HeaderIconButton(systemName: "plus") { showCreate = true }
                    .accessibilityLabel("Создать событие")
            }
        } content: {
            if session.isStaff { tagPicker }
            MonthGridView(month: $month, selectedDay: $selectedDay) { day in
                store.events(on: day, tag: effectiveTag).contains(where: isVisible)
            }
            .pulseCard(padding: 16)
            PulseSection(title: Formatters.dayMonthWeekday(selectedDay)) { eventsList }
            GradientButton(title: "Создать событие", systemImage: "plus") { showCreate = true }
        }
        .sheet(isPresented: $showCreate) {
            EventFormView(mode: .create(day: selectedDay, tag: effectiveTag ?? (session.isStaff ? .team : .personal))) { saved in
                selectedDay = saved.start.startOfDay
                month = saved.start.startOfDay
                if session.isStaff { tag = saved.tag }
            }
            .presentationDetents([.large])
        }
        .onAppear {
            if session.isStaff && tag == nil { tag = .team }
            if startsWithCreate && !didAutoOpen {
                didAutoOpen = true
                showCreate = true
            }
        }
    }

    /// Players see their personal + team events; staff filter by tag.
    private var effectiveTag: EventTag? { session.isStaff ? tag : nil }

    private var tagPicker: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach([EventTag.personal, .team] + store.playerTags) { item in
                    ChipButton(title: item.title, isSelected: tag == item) { withAnimation { tag = item } }
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    @ViewBuilder
    private var eventsList: some View {
        let events = store.events(on: selectedDay, tag: effectiveTag).filter(isVisible)
        if events.isEmpty {
            EmptyStateView(systemName: "calendar", title: "Событий нет", message: "На выбранную дату ничего не запланировано")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(events.enumerated()), id: \.element.id) { index, event in
                    Button { router.open(.event(event.id), in: router.selectedTab) } label: { EventRow(event: event) }
                        .buttonStyle(.plain)
                    if index < events.count - 1 { DividerLine() }
                }
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)
        }
    }

    /// Staff see everything for the selected tag; players never see other players' personal schedules.
    private func isVisible(_ event: CalendarEvent) -> Bool {
        guard !session.isStaff else { return true }
        if case .player(let name) = event.tag { return name == session.user?.nickname }
        return true
    }
}

struct EventRow: View {
    let event: CalendarEvent

    var body: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: 2).fill(event.tag.tint).frame(width: 4, height: 44)
            VStack(alignment: .leading, spacing: 4) {
                Text(event.title).font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("\(Formatters.time(event.start)) – \(Formatters.time(event.end))  ·  \(event.tag.title)")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .monospacedDigit()
            }
            Spacer()
            Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}

struct MonthGridView: View {
    @Binding var month: Date
    @Binding var selectedDay: Date
    let hasEvents: (Date) -> Bool

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 4), count: 7)
    private let weekdays = ["Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"]

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Text(Formatters.monthYear(month)).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                monthButton("chevron.left", offset: -1)
                monthButton("chevron.right", offset: 1)
            }
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(weekdays, id: \.self) { day in
                    Text(day).font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.Palette.textTertiary)
                }
                ForEach(Array(cells.enumerated()), id: \.offset) { _, day in
                    if let day { dayCell(day) } else { Color.clear.frame(height: 44) }
                }
            }
        }
        .gesture(DragGesture(minimumDistance: 30).onEnded { value in
            if value.translation.width < -40 { shift(1) } else if value.translation.width > 40 { shift(-1) }
        })
    }

    private var cells: [Date?] {
        let calendar = Calendar.pulse
        guard let interval = calendar.dateInterval(of: .month, for: month) else { return [] }
        let first = interval.start
        let weekday = calendar.component(.weekday, from: first)
        let leading = (weekday - calendar.firstWeekday + 7) % 7
        let count = calendar.range(of: .day, in: .month, for: first)?.count ?? 30
        return Array(repeating: nil, count: leading) + (0..<count).map { Optional(first.adding(days: $0)) }
    }

    private func dayCell(_ day: Date) -> some View {
        let isSelected = day.isSameDay(as: selectedDay)
        let isToday = day.isSameDay(as: .now)
        return Button {
            withAnimation(.easeInOut(duration: 0.15)) { selectedDay = day }
        } label: {
            VStack(spacing: 3) {
                Text("\(Calendar.pulse.component(.day, from: day))")
                    .font(.system(size: 16, weight: isSelected || isToday ? .bold : .regular))
                    .foregroundStyle(isSelected ? .white : (isToday ? Theme.Palette.cyan : Theme.Palette.textPrimary))
                Circle()
                    .fill(hasEvents(day) ? (isSelected ? Color.white : Theme.Palette.cyan) : .clear)
                    .frame(width: 5, height: 5)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 44)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(isSelected ? AnyShapeStyle(Theme.Gradients.accentDiagonal) : AnyShapeStyle(Color.clear))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .strokeBorder(isToday && !isSelected ? Theme.Palette.cyan.opacity(0.6) : .clear, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func monthButton(_ icon: String, offset: Int) -> some View {
        Button { shift(offset) } label: {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .frame(width: 36, height: 36)
                .background(Circle().fill(Theme.Palette.track))
        }
        .buttonStyle(.pressable)
    }

    private func shift(_ offset: Int) {
        withAnimation(.easeInOut(duration: 0.2)) {
            month = Calendar.pulse.date(byAdding: .month, value: offset, to: month) ?? month
        }
    }
}
