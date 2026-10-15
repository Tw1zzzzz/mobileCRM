import SwiftUI

// MARK: - Fitness

enum WorkoutKind: String, Codable, CaseIterable, Identifiable {
    case strength, hiit, cardio, yoga, stretch

    var id: String { rawValue }

    var title: String {
        switch self {
        case .strength: "Силовая"
        case .hiit: "HIIT"
        case .cardio: "Кардио"
        case .yoga: "Йога"
        case .stretch: "Растяжка"
        }
    }

    var icon: String {
        switch self {
        case .strength: "dumbbell"
        case .hiit: "bolt"
        case .cardio: "figure.run"
        case .yoga: "figure.mind.and.body"
        case .stretch: "figure.flexibility"
        }
    }

    var tint: Color {
        switch self {
        case .strength: Theme.Palette.green
        case .hiit: Theme.Palette.orange
        case .cardio: Theme.Palette.cyan
        case .yoga: Theme.Palette.purple
        case .stretch: Theme.Palette.pink
        }
    }
}

struct Workout: Codable, Identifiable, Equatable {
    let id: UUID
    let kind: WorkoutKind
    let detail: String
    let date: Date
    let minutes: Int
    let kcal: Int
    let averageHeartRate: Int
    /// Perceived effort 1–10 after the workout; nil = not rated (estimated from pulse or type).
    var rpe: Int?

    var title: String { detail.isEmpty ? kind.title : "\(kind.title) · \(detail)" }

    func rated(_ value: Int) -> Workout {
        var copy = self
        copy.rpe = value
        return copy
    }
}

struct HeartRateZone: Identifiable {
    let id = UUID()
    let name: String
    let range: String
    let percent: Int
    let color: Color
}

struct BodyMetric: Identifiable {
    let id = UUID()
    let title: String
    let value: Double
    let unit: String
    /// Change since the previous measurement; nil when unknown (Apple Health latest value).
    var delta: Double?
}

struct DailyValue: Identifiable, Equatable {
    var id: Date { date }
    let date: Date
    let value: Double
}

// MARK: - Screen time

enum AppCategory: String, CaseIterable, Identifiable {
    case social, games, browser, work, video, health, other

    var id: String { rawValue }

    var title: String {
        switch self {
        case .social: "Соцсети"
        case .games: "Игры"
        case .browser: "Браузер"
        case .work: "Работа"
        case .video: "Видео"
        case .health: "Здоровье"
        case .other: "Прочее"
        }
    }

    var color: Color {
        switch self {
        case .social: Theme.Palette.cyan
        case .games: Theme.Palette.pink
        case .browser: Theme.Palette.blue
        case .work: Theme.Palette.purple
        case .video: Theme.Palette.red
        case .health: Theme.Palette.green
        case .other: Theme.Palette.restZone
        }
    }
}

struct AppUsage: Identifiable {
    let id = UUID()
    let name: String
    let category: AppCategory
    let minutes: Int
    let icon: String
    let tint: Color
}

struct ScreenTimeDay: Identifiable {
    var id: Date { date }
    let date: Date
    let minutes: Int
    let categories: [AppCategory: Int]

    var topCategory: AppCategory? {
        categories.max { $0.value < $1.value }?.key
    }
}
