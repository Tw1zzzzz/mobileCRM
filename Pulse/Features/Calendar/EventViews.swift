import SwiftUI

/// Экран события (игрок / менеджер): название, тэг, описание, начало, окончание, ссылки.
struct EventDetailView: View {
    let eventID: UUID

    @Environment(CalendarStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var showEdit = false
    @State private var confirmDelete = false
    @State private var notice: String?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Событие") {
                if store.event(id: eventID) != nil {
                    HeaderIconButton(systemName: "pencil") { showEdit = true }
                        .accessibilityLabel("Редактировать")
                }
            }
        } content: {
            if let event = store.event(id: eventID) {
                content(event)
            } else {
                EmptyStateView(systemName: "calendar.badge.exclamationmark", title: "Событие не найдено",
                               message: "Возможно, оно было удалено")
            }
        }
        .sheet(isPresented: $showEdit) {
            if let event = store.event(id: eventID) {
                EventFormView(mode: .edit(event)) { saved in
                    if session.isStaff && saved.tag != .personal {
                        notice = "Игрокам отправлено уведомление об изменении"
                    }
                }
                .presentationDetents([.large])
            }
        }
        .confirmationDialog("Удалить событие?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive) {
                NotificationScheduler.cancelReminder(for: eventID)
                store.delete(id: eventID)
                dismiss()
            }
        }
    }

    private func content(_ event: CalendarEvent) -> some View {
        VStack(alignment: .leading, spacing: 18) {
            if let notice { StatusPill(text: notice, tint: Theme.Palette.cyan) }
            VStack(alignment: .leading, spacing: 14) {
                if session.isStaff {
                    Text(event.tag.title)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(event.tag.tint)
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(Capsule().fill(event.tag.tint.opacity(0.14)))
                }
                Text(event.title).font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                if !event.details.isEmpty {
                    Text(event.details).font(.system(size: 17)).foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .pulseCard(padding: 20)

            VStack(spacing: 0) {
                timeRow("Начало", event.start, icon: "play.circle")
                DividerLine()
                timeRow("Окончание", event.end, icon: "stop.circle")
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)

            if !event.links.isEmpty {
                PulseSection(title: "Ссылки") {
                    VStack(spacing: 10) {
                        ForEach(event.links, id: \.self) { link in
                            if let url = URL(string: link) {
                                Link(destination: url) {
                                    HStack {
                                        Image(systemName: "link")
                                        Text(link).lineLimit(1)
                                        Spacer()
                                        Image(systemName: "arrow.up.right")
                                    }
                                    .font(.system(size: 16))
                                    .foregroundStyle(Theme.Palette.cyan)
                                    .pulseCard(padding: 14)
                                }
                            }
                        }
                    }
                }
            }
            SecondaryButton(title: "Удалить событие", systemImage: "trash", tint: Theme.Palette.red) { confirmDelete = true }
        }
    }

    private func timeRow(_ title: String, _ date: Date, icon: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(Theme.Palette.cyan)
            Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textSecondary)
            Spacer()
            Text(Formatters.dayMonthTime(date)).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
        }
        .padding(.vertical, 14)
    }
}

/// Форма создания / редактирования события с валидацией из ТЗ.
struct EventFormView: View {
    enum Mode {
        case create(day: Date, tag: EventTag)
        case edit(CalendarEvent)
    }

    let mode: Mode
    let onSaved: (CalendarEvent) -> Void

    @Environment(CalendarStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var details = ""
    @State private var start = Date.now
    @State private var end = Date.now
    @State private var linksText = ""
    @State private var tag: EventTag = .personal
    @State private var error: String?

    private var isEditing: Bool {
        if case .edit = mode { return true }
        return false
    }

    var body: some View {
        SheetScaffold(title: isEditing ? "Редактирование" : "Новое событие") {
            VStack(alignment: .leading, spacing: 10) {
                PulseTextField(title: "Название *", text: $title, placeholder: "Тренировка")
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(EventValidator.templates, id: \.self) { template in
                            ChipButton(title: template, isSelected: title == template) { title = template }
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            if session.isStaff { tagField }
            PulseTextField(title: "Описание", text: $details, placeholder: "Что планируется", axis: .vertical)
            VStack(spacing: 12) {
                DatePicker("Начало", selection: $start)
                DividerLine()
                DatePicker("Окончание", selection: $end)
            }
            .font(.system(size: 16))
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard(padding: 16)
            PulseTextField(title: "Ссылки (через запятую или с новой строки)", text: $linksText,
                           placeholder: "https://…", keyboard: .URL, axis: .vertical)
            if let error { ErrorBanner(message: error) }
            GradientButton(title: session.isStaff ? "Сохранить событие" : "Сохранить", systemImage: "checkmark") { save() }
        }
        .onAppear(perform: populate)
        .onChange(of: start) { oldStart, newStart in
            // Keep duration when start moves past end.
            if end <= newStart { end = newStart.addingTimeInterval(max(end.timeIntervalSince(oldStart), 3600)) }
        }
    }

    private var tagField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Тэг").font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.Palette.textSecondary)
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach([EventTag.personal, .team] + store.playerTags) { item in
                        ChipButton(title: item.title, isSelected: tag == item) { tag = item }
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func populate() {
        switch mode {
        case .create(let day, let defaultTag):
            let base = day.isSameDay(as: .now) ? Date.now.adding(minutes: 60) : day.setting(hour: 12, minute: 0)
            let rounded = Calendar.pulse.date(bySetting: .minute, value: 0, of: base) ?? base
            start = rounded
            end = rounded.adding(minutes: 60)
            tag = session.isStaff ? defaultTag : .personal
        case .edit(let event):
            title = event.title
            details = event.details
            start = event.start
            end = event.end
            linksText = event.links.joined(separator: "\n")
            tag = event.tag
        }
    }

    private func save() {
        let links = EventValidator.parseLinks(linksText)
        if let validation = EventValidator.validate(title: title, start: start, end: end, links: links) {
            withAnimation { error = validation.message }
            return
        }
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedDetails = details.trimmingCharacters(in: .whitespacesAndNewlines)
        let event: CalendarEvent
        switch mode {
        case .create:
            event = CalendarEvent(id: UUID(), title: trimmedTitle, details: trimmedDetails, start: start, end: end,
                                  links: links, tag: tag, ownerId: session.user?.id)
        case .edit(let existing):
            event = existing.updated(title: trimmedTitle, details: trimmedDetails, start: start, end: end, links: links, tag: tag)
        }
        store.upsert(event)
        Task {
            await NotificationScheduler.scheduleReminder(for: event)
            await NotificationScheduler.scheduleGameTests(for: [event])
        }
        onSaved(event)
        dismiss()
    }
}
