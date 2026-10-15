import Foundation
import Observation

@Observable
final class LineupStore {
    private(set) var lineups: [Lineup]
    private let storage: JSONFileStorage<[Lineup]>?

    init(
        storage: JSONFileStorage<[Lineup]>? = JSONFileStorage(filename: "lineups.json"),
        seed: [Lineup] = LineupStore.demo()
    ) {
        self.storage = storage
        let saved = storage?.load().map(LineupStore.addingDemoVariations)
        self.lineups = (saved ?? seed).sorted { $0.createdAt > $1.createdAt }
    }

    func reload(isDemo: Bool) {
        let saved = storage?.load().map(LineupStore.addingDemoVariations)
        lineups = (saved ?? (isDemo ? LineupStore.demo() : [])).sorted { $0.createdAt > $1.createdAt }
    }

    /// Diaries saved before variations existed get the demo variations for matching demo entries.
    static func addingDemoVariations(_ items: [Lineup]) -> [Lineup] {
        items.map { entry in
            guard entry.variations.isEmpty, let demo = demoVariations[entry.title] else { return entry }
            var copy = entry
            copy.variations = demo
            return copy
        }
    }

    func filtered(map: CSMap?, type: LineupType?) -> [Lineup] {
        lineups.filter { (map == nil || $0.map == map) && (type == nil || $0.type == type) }
    }

    /// Privacy rules: staff see team entries only; team players see team + their own;
    /// solo players have a purely personal diary.
    static func visible(_ items: [Lineup], for user: User?) -> [Lineup] {
        guard let user else { return [] }
        switch user.role {
        case .staff: return items.filter { $0.visibility == .team }
        case .teamPlayer: return items.filter { $0.visibility == .team || $0.author == user.nickname }
        case .soloPlayer: return items.filter { $0.author == user.nickname }
        }
    }

    func diary(for user: User?, scope: DiaryScope = .all, map: CSMap? = nil, type: LineupType? = nil, query: String = "") -> [Lineup] {
        let text = query.trimmingCharacters(in: .whitespaces).lowercased()
        return Self.visible(lineups, for: user)
            .filter { entry in
                switch scope {
                case .all: true
                case .mine: entry.author == user?.nickname
                case .team: entry.visibility == .team
                case .favorites: entry.isFavorite
                }
            }
            .filter { (map == nil || $0.map == map) && (type == nil || $0.type == type) }
            .filter { text.isEmpty || $0.title.lowercased().contains(text) || $0.details.lowercased().contains(text) }
    }

    func toggleFavorite(id: UUID) {
        guard let entry = lineup(id: id) else { return }
        upsert(entry.toggledFavorite())
    }

    func lineup(id: UUID) -> Lineup? {
        lineups.first { $0.id == id }
    }

    func upsert(_ lineup: Lineup) {
        lineups = (lineups.filter { $0.id != lineup.id } + [lineup]).sorted { $0.createdAt > $1.createdAt }
        storage?.save(lineups)
    }

    func delete(id: UUID) {
        lineups = lineups.filter { $0.id != id }
        storage?.save(lineups)
    }

    func resetToDemo() {
        lineups = LineupStore.demo()
        storage?.save(lineups)
    }

    /// Staff edit everything; players edit only their own entries.
    static func canEdit(_ lineup: Lineup, user: User?) -> Bool {
        guard let user else { return false }
        return user.role.isStaff || lineup.author == user.nickname
    }

    static func demo(now: Date = .now) -> [Lineup] {
        typealias Seed = (String, CSMap, LineupSide, LineupType, String, String, LineupVisibility, Bool, Bool)
        let items: [Seed] = [
            ("Смок в окно с T-спавна", .mirage, .t, .smoke, "Встать в угол у ящика, прицел на антенну, jump-throw.", "Coach", .team, true, true),
            ("Флешка на выход B-апартаментов", .mirage, .t, .flash, "Попфлеш из апартаментов, бросок левой кнопкой от стены.", "Nova", .team, false, false),
            ("Молотов в дарк на банане", .inferno, .ct, .molotov, "С CT-спавна по прямой, бросок с прыжком.", "Coach", .team, true, false),
            ("Дефолт B-сайт Inferno", .inferno, .t, .script, "1. Смок CT + коффины\n2. Молотов в дарк\n3. Две флешки над стеной\n4. Заход через 3 секунды после флешек", "Coach", .team, false, false),
            ("Смок на outside", .nuke, .t, .smoke, "С улицы у гаража, прицел на верх трубы, обычный бросок.", "Blitz", .team, true, false),
            ("Ретейк A Ancient", .ancient, .ct, .script, "Сбор на CT, смок в донат, флеш от Frost, заход парами.", "Coach", .team, false, false),
            ("Разбор: проигрыш на Train", .train, .ct, .note, "Плохо держал B после пистолетки. Цель: не пикать первым без флешки, больше инфы от тиммейтов.", "Nova", .personal, false, true),
            ("Тренировка AWP", .mirage, .ct, .note, "30 минут aim_botz перед матчем — стабильнее флики. Повторять ежедневно.", "Nova", .personal, false, false),
            ("Мой смок в CT на Mirage", .mirage, .t, .smoke, "С T-рампы, прицел на край антенны, run-throw.", "Raven", .personal, true, true),
            ("Заметка после рейтинговой", .anubis, .t, .note, "Проиграл 3 дуэли подряд на мид — менять позицию после первого килла.", "Raven", .personal, false, false),
            ("Флешка за угол на Inferno", .inferno, .t, .flash, "Из библиотеки, прыжок + левая кнопка.", "Raven", .personal, false, false)
        ]
        return items.enumerated().map { index, item in
            Lineup(id: UUID(), title: item.0, map: item.1, side: item.2, type: item.3, details: item.4,
                   videoURL: item.7 ? "https://www.youtube.com" : nil, author: item.5,
                   createdAt: now.adding(days: -index), visibility: item.6, isFavorite: item.8,
                   variations: demoVariations[item.0] ?? [])
        }
    }

    /// Demo: the same grenade from different spots / techniques.
    private static let demoVariations: [String: [LineupVariation]] = [
        "Смок в окно с T-спавна": [
            LineupVariation(position: "T-спавн, угол у ящика", technique: .jump, aim: "Прицел на левый край антенны"),
            LineupVariation(position: "Верх T-рампы", technique: .standard, aim: "Прицел в стык крыши и дерева"),
            LineupVariation(position: "Палас, у двери", technique: .runJump, aim: "Центр прицела на кончик трубы", videoURL: "https://www.youtube.com")
        ],
        "Молотов в дарк на банане": [
            LineupVariation(position: "CT-спавн, у машины", technique: .jump, aim: "Прицел на верх второго окна"),
            LineupVariation(position: "Арка, ближний угол", technique: .standard, aim: "Прицел в нижний край вывески")
        ],
        "Смок на outside": [
            LineupVariation(position: "Улица у гаража", technique: .standard, aim: "Прицел на верх трубы"),
            LineupVariation(position: "Крыша, у лестницы", technique: .run, aim: "Прицел на левый угол антенны")
        ],
        "Мой смок в CT на Mirage": [
            LineupVariation(position: "T-рампа", technique: .run, aim: "Край антенны"),
            LineupVariation(position: "Верх рампы, у стены", technique: .jump, aim: "Нижний угол окна")
        ]
    ]
}
