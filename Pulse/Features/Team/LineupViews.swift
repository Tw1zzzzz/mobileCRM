import SwiftUI

/// Экран «База знаний» у стаффа / маршрут «Дневник CS2» у игроков.
struct LineupsView: View {
    var showsBack = true
    @Environment(SessionStore.self) private var session

    var body: some View {
        ScreenScaffold(spacing: 18) {
            NavHeader(title: session.isStaff ? "База знаний" : "Дневник CS2",
                      subtitle: session.isStaff ? "Раскидки, скрипты и заметки команды" : "Раскидки, скрипты и заметки",
                      showsBack: showsBack)
        } content: {
            DiaryContent()
        }
    }
}

/// Дневник CS2: для соло — личный, для игрока команды — личный + командный, для стаффа — командные скрипты.
struct DiaryContent: View {
    @Environment(LineupStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router

    @State private var scope: DiaryScope = .all
    @State private var map: CSMap?
    @State private var type: LineupType?
    @State private var query = ""
    @State private var newEntryType: LineupType?

    private var isTeamPlayer: Bool { session.user?.role == .teamPlayer }

    var body: some View {
        let entries = store.diary(for: session.user, scope: scope, map: map, type: type, query: query)
        let all = store.diary(for: session.user)
        let isFiltered = map != nil || type != nil || !query.isEmpty
        VStack(alignment: .leading, spacing: 16) {
            summary(all)
            quickAdd
            PulseSegmented(options: DiaryScope.options(for: session.user?.role), selection: $scope,
                           title: { $0 == .favorites ? "★ \($0.title)" : $0.title }, height: 34, fontSize: 14)
            searchField
            VStack(alignment: .leading, spacing: 10) {
                chips(all: map == nil, onAll: { map = nil }) {
                    ForEach(CSMap.allCases) { item in
                        ChipButton(title: item.title, isSelected: map == item) { map = map == item ? nil : item }
                    }
                }
                chips(all: type == nil, onAll: { type = nil }) {
                    ForEach(LineupType.allCases) { item in
                        ChipButton(title: item.title, isSelected: type == item) { type = type == item ? nil : item }
                    }
                }
            }
            if entries.isEmpty {
                emptyState(isFiltered: isFiltered)
            } else {
                PulseSection(title: sectionTitle(count: entries.count, isFiltered: isFiltered)) {
                    list(entries)
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: entries)
        .onChange(of: session.user?.role) { _, _ in scope = .all }
        .sheet(item: $newEntryType) { type in
            LineupFormSheet(existing: nil, defaultMap: map ?? .mirage, defaultType: type).presentationDetents([.large])
        }
    }

    private func sectionTitle(count: Int, isFiltered: Bool) -> String {
        if isFiltered { return "Найдено: \(count)" }
        return scope == .favorites ? "Избранное · \(count)" : "Все записи"
    }

    @ViewBuilder
    private func emptyState(isFiltered: Bool) -> some View {
        if scope == .favorites && !isFiltered {
            EmptyStateView(systemName: "star", title: "В избранном пусто",
                           message: "Нажми ☆ у раскидки, которую используешь чаще всего, — она появится здесь")
        } else {
            EmptyStateView(systemName: "book.closed", title: isFiltered ? "Ничего не найдено" : "Дневник пуст",
                           message: isFiltered ? "Измени фильтры или поиск" : "Добавь первую раскидку или заметку после матча")
        }
    }

    private func summary(_ all: [Lineup]) -> some View {
        let maps = Set(all.map(\.map)).count
        return HStack(spacing: 10) {
            summaryTile("\(all.count)", "записей", Theme.Palette.cyan)
            summaryTile("\(all.filter(\.isFavorite).count)", "в избранном", Theme.Palette.yellow)
            summaryTile("\(maps)", maps == 1 ? "карта" : "карт", Theme.Palette.purple)
        }
    }

    private func summaryTile(_ value: String, _ title: String, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: 22, weight: .bold)).foregroundStyle(tint)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
    }

    private var quickAdd: some View {
        HStack(spacing: 10) {
            quickButton("Раскидка", icon: "cloud", type: .smoke)
            quickButton("Скрипт", icon: "list.bullet.rectangle", type: .script)
            if !session.isStaff { quickButton("Заметка", icon: "note.text", type: .note) }
        }
    }

    private func quickButton(_ title: String, icon: String, type: LineupType) -> some View {
        Button { newEntryType = type } label: {
            HStack(spacing: 6) {
                Image(systemName: "plus")
                Text(title)
            }
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 40)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(AuthStyle.buttonGradient))
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("Добавить: \(title)")
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.Palette.textSecondary)
            TextField("", text: $query, prompt: Text("Поиск по дневнику").foregroundStyle(Theme.Palette.textTertiary))
                .foregroundStyle(Theme.Palette.textPrimary)
                .autocorrectionDisabled()
            if !query.isEmpty {
                Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.Palette.textTertiary) }
            }
        }
        .pulseTile(padding: 12)
    }

    private func list(_ items: [Lineup]) -> some View {
        VStack(spacing: 10) {
            ForEach(items) { lineup in
                LineupRow(lineup: lineup, showsVisibility: isTeamPlayer) {
                    store.toggleFavorite(id: lineup.id)
                }
                .contentShape(Rectangle())
                .onTapGesture { router.open(.lineup(lineup.id), in: router.selectedTab) }
                .accessibilityAddTraits(.isButton)
            }
        }
    }

    private func chips<Content: View>(all: Bool, onAll: @escaping () -> Void, @ViewBuilder content: () -> Content) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ChipButton(title: "Все", isSelected: all, action: onAll)
                content()
            }
        }
        .scrollIndicators(.hidden)
    }
}

struct LineupRow: View {
    let lineup: Lineup
    var showsVisibility = false
    /// When set, the trailing star toggles «Избранное» right from the list.
    var onToggleFavorite: (() -> Void)?

    var body: some View {
        HStack(spacing: 14) {
            IconBadge(systemName: lineup.type.icon, tint: lineup.type.tint, size: 48, iconSize: 20)
            VStack(alignment: .leading, spacing: 4) {
                Text(lineup.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(2)
                Text(lineup.subtitle).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 6) { lineupMeta }
                    VStack(alignment: .leading, spacing: 2) { lineupMeta }
                }
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textTertiary)
                .lineLimit(1)
                if lineup.variations.count > 1 {
                    Label(VariationCount.text(lineup.variations.count), systemImage: "square.stack.3d.up")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(Theme.Palette.cyan)
                        .padding(.horizontal, 8).padding(.vertical, 3)
                        .background(Capsule().fill(Theme.Palette.cyan.opacity(0.14)))
                }
            }
            Spacer(minLength: 0)
            if lineup.videoURL != nil {
                Image(systemName: "play.rectangle").foregroundStyle(Theme.Palette.cyan)
            }
            if let onToggleFavorite {
                Button(action: onToggleFavorite) {
                    Image(systemName: lineup.isFavorite ? "star.fill" : "star")
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(lineup.isFavorite ? Theme.Palette.yellow : Theme.Palette.textTertiary)
                        .frame(width: 36, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .sensoryFeedback(.impact(weight: .light), trigger: lineup.isFavorite)
                .accessibilityLabel(lineup.isFavorite ? "Убрать из избранного" : "В избранное")
            } else {
                if lineup.isFavorite {
                    Image(systemName: "star.fill").font(.system(size: 13)).foregroundStyle(Theme.Palette.yellow)
                }
                Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .pulseCard(padding: 14)
    }

    @ViewBuilder
    private var lineupMeta: some View {
        if showsVisibility {
            Label(lineup.visibility.title, systemImage: lineup.visibility.icon)
        }
        Text("Автор: \(lineup.author)")
    }
}

struct LineupDetailView: View {
    let lineupID: UUID

    @Environment(LineupStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var showEdit = false
    @State private var confirmDelete = false

    var body: some View {
        let lineup = store.lineup(id: lineupID)
        let canEdit = lineup.map { LineupStore.canEdit($0, user: session.user) } ?? false
        ScreenScaffold {
            NavHeader(title: lineup?.type == .note ? "Заметка" : "Раскидка") {
                if let lineup {
                    HStack(spacing: 8) {
                        HeaderIconButton(systemName: lineup.isFavorite ? "star.fill" : "star") {
                            store.toggleFavorite(id: lineup.id)
                        }
                        .accessibilityLabel(lineup.isFavorite ? "Убрать из избранного" : "В избранное")
                        if canEdit {
                            HeaderIconButton(systemName: "pencil") { showEdit = true }
                                .accessibilityLabel("Редактировать")
                        }
                    }
                }
            }
        } content: {
            if let lineup {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        tag(lineup.map.title, Theme.Palette.cyan)
                        if lineup.type != .note {
                            tag(lineup.side.title, lineup.side == .t ? Theme.Palette.orange : Theme.Palette.blue)
                        }
                        tag(lineup.type.title, lineup.type.tint)
                        tag(lineup.visibility.title, Theme.Palette.textSecondary)
                    }
                    Text(lineup.title).font(.system(size: 26, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(lineup.details).font(.system(size: 17)).foregroundStyle(Theme.Palette.textSecondary)
                    Text("Автор: \(lineup.author) · \(Formatters.dayMonth(lineup.createdAt))")
                        .font(.system(size: 13)).foregroundStyle(Theme.Palette.textTertiary)
                }
                .pulseCard(padding: 20)
                if let link = lineup.videoURL, let url = URL(string: link) {
                    Link(destination: url) {
                        HStack {
                            Image(systemName: "play.rectangle.fill")
                            Text("Смотреть видео")
                            Spacer()
                            Image(systemName: "arrow.up.right")
                        }
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.cyan)
                        .pulseCard(padding: 16)
                    }
                }
                if lineup.supportsVariations {
                    LineupVariationsSection(lineup: lineup, canEdit: canEdit)
                }
                if canEdit {
                    SecondaryButton(title: "Удалить", systemImage: "trash", tint: Theme.Palette.red) { confirmDelete = true }
                }
            } else {
                EmptyStateView(systemName: "scope", title: "Запись не найдена", message: "Возможно, она была удалена")
            }
        }
        .sheet(isPresented: $showEdit) {
            if let lineup {
                LineupFormSheet(existing: lineup, defaultMap: lineup.map, defaultType: lineup.type).presentationDetents([.large])
            }
        }
        .confirmationDialog("Удалить запись?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить", role: .destructive) {
                store.delete(id: lineupID)
                dismiss()
            }
        }
    }

    private func tag(_ text: String, _ tint: Color) -> some View {
        Text(text)
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Capsule().fill(tint.opacity(0.14)))
    }
}

extension LineupType {
    var formTitle: String {
        switch self {
        case .note: "Новая заметка"
        case .script: "Новый скрипт"
        default: "Новая раскидка"
        }
    }
}

struct LineupFormSheet: View {
    let existing: Lineup?
    let defaultMap: CSMap
    var defaultType: LineupType = .smoke

    @Environment(LineupStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var details = ""
    @State private var videoURL = ""
    @State private var map: CSMap = .mirage
    @State private var side: LineupSide = .t
    @State private var type: LineupType = .smoke
    @State private var sharedWithTeam = false
    @State private var variations: [LineupVariation] = []
    @State private var error: String?

    private var role: UserRole? { session.user?.role }

    var body: some View {
        SheetScaffold(title: existing == nil ? type.formTitle : "Редактирование") {
            PulseTextField(title: "Название *", text: $title,
                           placeholder: type == .note ? "Разбор матча на Mirage" : "Смок в окно")
            picker("Тип", LineupType.allCases.filter { role != .staff || $0 != .note }, selection: $type) { $0.title }
            picker("Карта", CSMap.allCases, selection: $map) { $0.title }
            if type != .note {
                picker("Сторона", LineupSide.allCases, selection: $side) { $0.title }
            }
            PulseTextField(title: type == .note ? "Что получилось, что улучшить" : "Описание / шаги скрипта",
                           text: $details, placeholder: type == .note ? "Мысли после матча" : "Откуда и как бросать", axis: .vertical)
            PulseTextField(title: "Ссылка на видео", text: $videoURL, placeholder: "https://…", keyboard: .URL)
            if type != .note && type != .script {
                variationsEditor
            }
            if role == .teamPlayer {
                Toggle(isOn: $sharedWithTeam) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Показать команде").font(.system(size: 16, weight: .semibold))
                        Text("Иначе запись видна только тебе").font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                    }
                }
                .tint(Theme.Palette.blue)
                .foregroundStyle(Theme.Palette.textPrimary)
                .pulseCard(padding: 14)
            }
            if let error { ErrorBanner(message: error) }
            GradientButton(title: "Сохранить", systemImage: "checkmark") { save() }
        }
        .onAppear(perform: populate)
    }

    private var variationsEditor: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Вариации").font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.Palette.textSecondary)
            Text("Та же граната с другой позиции или другой техникой броска")
                .font(.system(size: 13)).foregroundStyle(Theme.Palette.textTertiary)
            ForEach(Array(variations.indices), id: \.self) { index in
                VariationEditor(index: index + 1, variation: $variations[index]) {
                    withAnimation { variations = variations.enumerated().filter { $0.offset != index }.map(\.element) }
                }
            }
            SecondaryButton(title: "Добавить вариацию", systemImage: "plus") {
                withAnimation { variations = variations + [LineupVariation(position: "", technique: .standard, aim: "")] }
            }
        }
    }

    private func picker<T: Identifiable & Hashable>(
        _ title: String, _ items: [T], selection: Binding<T>, label: @escaping (T) -> String
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.Palette.textSecondary)
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(items) { item in
                        ChipButton(title: label(item), isSelected: selection.wrappedValue == item) { selection.wrappedValue = item }
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private func populate() {
        guard let existing else {
            map = defaultMap
            type = defaultType
            sharedWithTeam = defaultType == .script
            return
        }
        title = existing.title
        details = existing.details
        videoURL = existing.videoURL ?? ""
        map = existing.map
        side = existing.side
        type = existing.type
        sharedWithTeam = existing.visibility == .team
        variations = existing.variations
    }

    /// Staff entries are always for the team; solo diaries are always personal.
    private var visibility: LineupVisibility {
        switch role {
        case .staff: .team
        case .teamPlayer: sharedWithTeam ? .team : .personal
        default: .personal
        }
    }

    private func save() {
        if let message = LineupValidator.validate(title: title, videoURL: videoURL) {
            withAnimation { error = message }
            return
        }
        let filled = variations.filter { !$0.isBlank }
        if let message = filled.lazy.compactMap({ LineupValidator.validate(variation: $0) }).first {
            withAnimation { error = message }
            return
        }
        let link = videoURL.trimmingCharacters(in: .whitespaces)
        store.upsert(Lineup(
            id: existing?.id ?? UUID(),
            title: title.trimmingCharacters(in: .whitespacesAndNewlines),
            map: map, side: side, type: type,
            details: details.trimmingCharacters(in: .whitespacesAndNewlines),
            videoURL: link.isEmpty ? nil : link,
            author: existing?.author ?? session.user?.nickname ?? "—",
            createdAt: existing?.createdAt ?? .now,
            visibility: visibility,
            isFavorite: existing?.isFavorite ?? false,
            variations: type == .note || type == .script ? [] : filled
        ))
        dismiss()
    }
}
