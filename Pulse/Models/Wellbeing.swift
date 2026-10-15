import Foundation

// MARK: - Nutrition

struct Meal: Codable, Identifiable, Equatable {
    let id: UUID
    let date: Date
    let name: String
    let kcal: Int
    let protein: Int
    let fat: Int
    let carbs: Int
    let photo: Data?
    let source: MealSource
    /// Nickname of the player who logged the meal (shown to staff). `nil` for legacy entries.
    var author: String? = nil
}

enum MealSource: String, Codable {
    case photo, manual, quick

    var title: String {
        switch self {
        case .photo: "AI-анализ фото"
        case .manual: "Вручную"
        case .quick: "Быстрое добавление"
        }
    }
}

struct NutritionGoals: Codable, Equatable {
    var kcal = 2400
    var protein = 140
    var fat = 80
    var carbs = 300
    var waterMilliliters = 2500
}

struct NutritionTotals: Equatable {
    var kcal = 0
    var protein = 0
    var fat = 0
    var carbs = 0

    init(kcal: Int = 0, protein: Int = 0, fat: Int = 0, carbs: Int = 0) {
        self.kcal = kcal
        self.protein = protein
        self.fat = fat
        self.carbs = carbs
    }

    init(meals: [Meal]) {
        self.init(
            kcal: meals.reduce(0) { $0 + $1.kcal },
            protein: meals.reduce(0) { $0 + $1.protein },
            fat: meals.reduce(0) { $0 + $1.fat },
            carbs: meals.reduce(0) { $0 + $1.carbs }
        )
    }
}

/// Result returned by the food photo analyzer (AI).
struct FoodAnalysis: Equatable {
    let dishName: String
    let kcal: Int
    let protein: Int
    let fat: Int
    let carbs: Int
    let confidence: Double
}

// MARK: - Mood & energy

enum DaySlot: String, Codable, CaseIterable, Identifiable {
    case morning, day, evening

    var id: String { rawValue }

    var title: String {
        switch self {
        case .morning: "Утро"
        case .day: "День"
        case .evening: "Вечер"
        }
    }

    var icon: String {
        switch self {
        case .morning: "sunrise"
        case .day: "sun.max"
        case .evening: "moon.stars"
        }
    }
}

struct MoodEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let day: Date
    let slot: DaySlot
    let mood: Int
    let energy: Int
}

// MARK: - Balance wheel

enum BalanceCategory: String, Codable, CaseIterable, Identifiable {
    case physical, emotional, intellectual, spiritual, professional, social, environment, financial

    var id: String { rawValue }

    var title: String {
        switch self {
        case .physical: "Физическое здоровье"
        case .emotional: "Эмоциональное состояние"
        case .intellectual: "Интеллектуальное развитие"
        case .spiritual: "Духовное развитие"
        case .professional: "Профессиональная сфера"
        case .social: "Социальные отношения"
        case .environment: "Окружающая среда"
        case .financial: "Финансовое благополучие"
        }
    }

    var shortTitle: String {
        switch self {
        case .physical: "Здоровье"
        case .emotional: "Эмоции"
        case .intellectual: "Интеллект"
        case .spiritual: "Духовность"
        case .professional: "Профессия"
        case .social: "Отношения"
        case .environment: "Среда"
        case .financial: "Финансы"
        }
    }
}

struct BalanceEntry: Codable, Identifiable, Equatable {
    let id: UUID
    let day: Date
    let values: [String: Double]

    func value(for category: BalanceCategory) -> Double {
        values[category.rawValue] ?? 5
    }

    var orderedValues: [Double] {
        BalanceCategory.allCases.map { value(for: $0) }
    }

    var average: Double {
        StatisticsMath.mean(orderedValues) ?? 0
    }
}
