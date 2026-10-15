import SwiftUI

/// What the player wants from Pulse (first-login questionnaire, step «Цели»).
enum FocusGoal: String, Codable, CaseIterable, Identifiable {
    case rankUp, consistency, proTeam, tilt, focus, sleep, energy, fitness, nutrition, screen

    var id: String { rawValue }

    var title: String {
        switch self {
        case .rankUp: "Поднять ELO и уровень"
        case .consistency: "Стабильная игра"
        case .proTeam: "Попасть в основу / про-сцену"
        case .tilt: "Меньше тильта и стресса"
        case .focus: "Концентрация"
        case .sleep: "Наладить сон"
        case .energy: "Больше энергии"
        case .fitness: "Физическая форма"
        case .nutrition: "Питание"
        case .screen: "Меньше экранного времени"
        }
    }

    var icon: String {
        switch self {
        case .rankUp: "chart.line.uptrend.xyaxis"
        case .consistency: "metronome"
        case .proTeam: "trophy"
        case .tilt: "flame"
        case .focus: "scope"
        case .sleep: "moon"
        case .energy: "bolt"
        case .fitness: "figure.run"
        case .nutrition: "fork.knife"
        case .screen: "iphone"
        }
    }

    var tint: Color {
        switch self {
        case .rankUp, .proTeam: Theme.Palette.cyan
        case .consistency, .focus: Theme.Palette.blue
        case .tilt: Theme.Palette.orange
        case .sleep: Theme.Palette.purple
        case .energy: Theme.Palette.yellow
        case .fitness, .nutrition: Theme.Palette.green
        case .screen: Theme.Palette.pink
        }
    }

    static let maxSelected = 3
}

enum PlayerGameRole: String, Codable, CaseIterable, Identifiable {
    case entry, awper, support, igl, lurker, rifler, flex

    var id: String { rawValue }

    var title: String {
        switch self {
        case .entry: "Entry"
        case .awper: "AWPer"
        case .support: "Support"
        case .igl: "IGL"
        case .lurker: "Lurker"
        case .rifler: "Rifler"
        case .flex: "Флекс"
        }
    }

    var subtitle: String {
        switch self {
        case .entry: "Первым заходит на точку"
        case .awper: "Снайпер команды"
        case .support: "Гранаты и размен"
        case .igl: "Капитан, колы по ходу раунда"
        case .lurker: "Играет в одиночку, ловит ротации"
        case .rifler: "Основной стрелок"
        case .flex: "Играю любую позицию"
        }
    }
}

enum SkillLevel: String, Codable, CaseIterable, Identifiable {
    case beginner, amateur, semiPro, pro

    var id: String { rawValue }

    var title: String {
        switch self {
        case .beginner: "Новичок"
        case .amateur: "Любитель"
        case .semiPro: "Полупро"
        case .pro: "Про"
        }
    }

    var subtitle: String {
        switch self {
        case .beginner: "FACEIT 1–4, учусь основам"
        case .amateur: "FACEIT 5–7, играю регулярно"
        case .semiPro: "FACEIT 8–10, турниры и праки"
        case .pro: "Контракт, лиги и LAN"
        }
    }
}

enum PlayTime: String, Codable, CaseIterable, Identifiable {
    case morning, day, evening, night

    var id: String { rawValue }

    var title: String {
        switch self {
        case .morning: "Утро"
        case .day: "День"
        case .evening: "Вечер"
        case .night: "Ночь"
        }
    }
}

enum PlayerMotivation: String, Codable, CaseIterable, Identifiable {
    case winning, growth, team, fun, career

    var id: String { rawValue }

    var title: String {
        switch self {
        case .winning: "Победы и рейтинг"
        case .growth: "Рост скилла"
        case .team: "Команда и общение"
        case .fun: "Удовольствие от игры"
        case .career: "Карьера в киберспорте"
        }
    }
}

/// Answers of the first-login questionnaire («Цели» + «Что ты за игрок»).
struct PlayerSurvey: Codable, Equatable {
    var goals: [FocusGoal] = []
    var role: PlayerGameRole = .rifler
    var level: SkillLevel = .amateur
    var yearsPlaying = 2
    var hoursPerWeek = 15
    var playTime: PlayTime = .evening
    var motivation: PlayerMotivation = .growth
    var updatedAt = Date.now

    /// «AWPer · Полупро · 4 года в CS · 20 ч/нед».
    var summary: String {
        "\(role.title) · \(level.title) · \(Formatters.years(yearsPlaying)) в CS · \(hoursPerWeek) ч/нед"
    }
}

extension Formatters {
    /// «1 год», «3 года», «7 лет».
    static func years(_ value: Int) -> String {
        let mod10 = value % 10, mod100 = value % 100
        let word = mod10 == 1 && mod100 != 11 ? "год"
            : (2...4).contains(mod10) && !(12...14).contains(mod100) ? "года" : "лет"
        return "\(value) \(word)"
    }
}
