import SwiftUI

/// «Раскидки и скрипты команд» (п. 13 ТЗ) + личный дневник CS2 игрока.
enum CSMap: String, Codable, CaseIterable, Identifiable {
    case mirage, inferno, nuke, ancient, anubis, dust2, train

    var id: String { rawValue }

    var title: String {
        switch self {
        case .mirage: "Mirage"
        case .inferno: "Inferno"
        case .nuke: "Nuke"
        case .ancient: "Ancient"
        case .anubis: "Anubis"
        case .dust2: "Dust II"
        case .train: "Train"
        }
    }
}

enum LineupType: String, Codable, CaseIterable, Identifiable {
    case smoke, flash, molotov, grenade, script, note

    var id: String { rawValue }

    var title: String {
        switch self {
        case .smoke: "Смок"
        case .flash: "Флешка"
        case .molotov: "Молотов"
        case .grenade: "HE"
        case .script: "Скрипт"
        case .note: "Заметка"
        }
    }

    var icon: String {
        switch self {
        case .smoke: "cloud"
        case .flash: "sun.max"
        case .molotov: "flame"
        case .grenade: "burst"
        case .script: "list.bullet.rectangle"
        case .note: "note.text"
        }
    }

    var tint: Color {
        switch self {
        case .smoke: Theme.Palette.textSecondary
        case .flash: Theme.Palette.yellow
        case .molotov: Theme.Palette.orange
        case .grenade: Theme.Palette.red
        case .script: Theme.Palette.cyan
        case .note: Theme.Palette.purple
        }
    }
}

enum LineupSide: String, Codable, CaseIterable, Identifiable {
    case t, ct

    var id: String { rawValue }
    var title: String { self == .t ? "T" : "CT" }
}

/// How the grenade is thrown — the main thing that differs between variations of one lineup.
enum ThrowTechnique: String, Codable, CaseIterable, Identifiable {
    case standard, jump, run, runJump, walk, rightClick

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: "Обычный"
        case .jump: "Jump-throw"
        case .run: "Run-throw"
        case .runJump: "Run + jump"
        case .walk: "Shift + бросок"
        case .rightClick: "ПКМ (под себя)"
        }
    }
}

/// One way to land the same grenade: another spot, another technique, another timing.
struct LineupVariation: Codable, Identifiable, Equatable {
    let id: UUID
    var position: String
    var technique: ThrowTechnique
    var aim: String
    var videoURL: String?

    init(id: UUID = UUID(), position: String, technique: ThrowTechnique, aim: String, videoURL: String? = nil) {
        self.id = id
        self.position = position
        self.technique = technique
        self.aim = aim
        self.videoURL = videoURL
    }

    var isBlank: Bool {
        position.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && aim.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

/// Who sees a diary entry: only its author, or the whole team (staff included).
enum LineupVisibility: String, Codable {
    case personal, team

    var title: String { self == .personal ? "Личная" : "Команде" }
    var icon: String { self == .personal ? "lock" : "person.3" }
}

struct Lineup: Codable, Identifiable, Equatable {
    let id: UUID
    let title: String
    let map: CSMap
    let side: LineupSide
    let type: LineupType
    let details: String
    let videoURL: String?
    let author: String
    let createdAt: Date
    var visibility: LineupVisibility = .team
    var isFavorite = false
    /// Alternative ways to throw the same grenade (not used for scripts and notes).
    var variations: [LineupVariation] = []

    var supportsVariations: Bool { type != .note && type != .script }

    var subtitle: String {
        type == .note ? "\(map.title) · Заметка" : "\(map.title) · \(side.title) · \(type.title)"
    }

    func toggledFavorite() -> Lineup {
        var copy = self
        copy.isFavorite.toggle()
        return copy
    }
}

extension Lineup {
    private enum CodingKeys: String, CodingKey {
        case id, title, map, side, type, details, videoURL, author, createdAt, visibility, isFavorite, variations
    }

    /// Tolerates entries saved before `visibility` / `isFavorite` / `variations` existed.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            id: try c.decode(UUID.self, forKey: .id),
            title: try c.decode(String.self, forKey: .title),
            map: try c.decode(CSMap.self, forKey: .map),
            side: try c.decode(LineupSide.self, forKey: .side),
            type: try c.decode(LineupType.self, forKey: .type),
            details: try c.decode(String.self, forKey: .details),
            videoURL: try c.decodeIfPresent(String.self, forKey: .videoURL),
            author: try c.decode(String.self, forKey: .author),
            createdAt: try c.decode(Date.self, forKey: .createdAt),
            visibility: try c.decodeIfPresent(LineupVisibility.self, forKey: .visibility) ?? .team,
            isFavorite: try c.decodeIfPresent(Bool.self, forKey: .isFavorite) ?? false,
            variations: try c.decodeIfPresent([LineupVariation].self, forKey: .variations) ?? []
        )
    }
}

/// Which entries a user may see and the diary scope filter.
enum DiaryScope: String, CaseIterable, Identifiable {
    case all, mine, team, favorites

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: "Все"
        case .mine: "Мои"
        case .team: "Команда"
        case .favorites: "Избранное"
        }
    }

    /// Solo players and staff have no «Мои / Команда» split — only «Все / Избранное».
    static func options(for role: UserRole?) -> [DiaryScope] {
        role == .teamPlayer ? allCases : [.all, .favorites]
    }
}

enum LineupValidator {
    static func validate(title: String, videoURL: String) -> String? {
        if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return AuthError.emptyFields.message
        }
        let link = videoURL.trimmingCharacters(in: .whitespaces)
        guard !link.isEmpty else { return nil }
        let ok = EventValidator.validate(title: title, start: .distantPast, end: .distantFuture, links: [link]) == nil
        return ok ? nil : EventValidationError.invalidLink.message
    }

    /// A variation needs at least a spot to throw from; its video link must be valid when present.
    static func validate(variation: LineupVariation) -> String? {
        if variation.position.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "Укажи, откуда бросать"
        }
        return validate(title: variation.position, videoURL: variation.videoURL ?? "")
    }
}
