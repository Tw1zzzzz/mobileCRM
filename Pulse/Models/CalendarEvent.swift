import SwiftUI

enum EventTag: Codable, Hashable, Identifiable {
    case personal
    case team
    case player(String)

    var id: String {
        switch self {
        case .personal: "personal"
        case .team: "team"
        case .player(let name): "player-\(name)"
        }
    }

    var title: String {
        switch self {
        case .personal: "#личный"
        case .team: "#командный"
        case .player(let name): "#\(name)"
        }
    }

    var tint: Color {
        switch self {
        case .personal: Theme.Palette.purple
        case .team: Theme.Palette.cyan
        case .player: Theme.Palette.orange
        }
    }
}

struct CalendarEvent: Codable, Identifiable, Equatable {
    let id: UUID
    let title: String
    let details: String
    let start: Date
    let end: Date
    let links: [String]
    let tag: EventTag
    let ownerId: UUID?

    func updated(
        title: String? = nil,
        details: String? = nil,
        start: Date? = nil,
        end: Date? = nil,
        links: [String]? = nil,
        tag: EventTag? = nil
    ) -> CalendarEvent {
        CalendarEvent(
            id: id,
            title: title ?? self.title,
            details: details ?? self.details,
            start: start ?? self.start,
            end: end ?? self.end,
            links: links ?? self.links,
            tag: tag ?? self.tag,
            ownerId: ownerId
        )
    }
}

enum EventValidationError: Error, Equatable {
    case emptyRequiredFields
    case endBeforeStart
    case invalidLink

    var message: String {
        switch self {
        case .emptyRequiredFields: "Заполните обязательные поля"
        case .endBeforeStart: "Время окончания не может быть раньше времени начала"
        case .invalidLink: "Проверьте корректность ссылок"
        }
    }
}

enum EventValidator {
    static let templates = ["Тренировка", "Матч", "Разбор игры", "Буткемп", "Отдых"]

    static func validate(title: String, start: Date, end: Date, links: [String]) -> EventValidationError? {
        if title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return .emptyRequiredFields }
        if end <= start { return .endBeforeStart }
        let invalid = links.contains { link in
            guard let url = URL(string: link), let scheme = url.scheme?.lowercased() else { return true }
            return !(scheme == "http" || scheme == "https") || url.host == nil
        }
        return invalid ? .invalidLink : nil
    }

    /// Splits a free-form links field into trimmed, non-empty lines/comma-separated values.
    static func parseLinks(_ text: String) -> [String] {
        text.components(separatedBy: CharacterSet(charactersIn: ",\n "))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }
}
