import Foundation
import Observation
import UIKit

/// Contract for the AI food recognizer. The production implementation should call a backend
/// endpoint (which holds the AI provider key) — never embed API keys in the app.
protocol FoodAnalyzing {
    func analyze(imageData: Data) async throws -> FoodAnalysis
}

enum FoodAnalysisError: Error {
    case unrecognized

    var message: String { "Не удалось распознать блюдо. Попробуйте другое фото или введите данные вручную." }
}

/// Offline stand-in for the AI API: returns a plausible dish after a short delay.
struct MockFoodAnalyzer: FoodAnalyzing {
    var latency: Duration = .seconds(2)

    private static let catalog: [FoodAnalysis] = [
        FoodAnalysis(dishName: "Курица с рисом и овощами", kcal: 520, protein: 42, fat: 14, carbs: 58, confidence: 0.86),
        FoodAnalysis(dishName: "Овсянка с ягодами", kcal: 340, protein: 11, fat: 8, carbs: 56, confidence: 0.91),
        FoodAnalysis(dishName: "Паста болоньезе", kcal: 640, protein: 31, fat: 22, carbs: 78, confidence: 0.82),
        FoodAnalysis(dishName: "Салат Цезарь", kcal: 410, protein: 24, fat: 26, carbs: 18, confidence: 0.88),
        FoodAnalysis(dishName: "Омлет с тостом", kcal: 380, protein: 23, fat: 22, carbs: 24, confidence: 0.84),
        FoodAnalysis(dishName: "Боул с лососем", kcal: 590, protein: 35, fat: 21, carbs: 62, confidence: 0.87)
    ]

    func analyze(imageData: Data) async throws -> FoodAnalysis {
        try await Task.sleep(for: latency)
        guard imageData.count > 1024 else { throw FoodAnalysisError.unrecognized }
        let index = abs(imageData.count.hashValue) % Self.catalog.count
        return Self.catalog[index]
    }
}

@Observable
final class NutritionStore {
    struct State: Codable {
        var meals: [Meal]
        var water: [String: Int]
        var goals: NutritionGoals
    }

    private(set) var meals: [Meal]
    private(set) var waterByDay: [String: Int]
    private(set) var goals: NutritionGoals

    let analyzer: FoodAnalyzing
    private let storage: JSONFileStorage<State>?

    init(
        analyzer: FoodAnalyzing = MockFoodAnalyzer(),
        storage: JSONFileStorage<State>? = JSONFileStorage(filename: "nutrition.json"),
        seed: [Meal] = DemoData.mealHistory()
    ) {
        self.analyzer = analyzer
        self.storage = storage
        let saved = storage?.load()
        self.meals = (saved?.meals ?? seed).sorted { $0.date > $1.date }
        self.waterByDay = saved?.water ?? [:]
        self.goals = saved?.goals ?? NutritionGoals()
    }

    /// Demo accounts keep the offline «analysis» stand-in; real accounts enter food manually
    /// until the server-side AI recognition exists (no made-up results).
    private(set) var isDemo = true
    var canAnalyzePhotos: Bool { isDemo }

    func reload(isDemo: Bool) {
        self.isDemo = isDemo
        let saved = storage?.load()
        meals = (saved?.meals ?? (isDemo ? DemoData.mealHistory() : [])).sorted { $0.date > $1.date }
        waterByDay = saved?.water ?? [:]
        goals = saved?.goals ?? NutritionGoals()
    }

    func meals(on day: Date) -> [Meal] {
        meals.filter { $0.date.isSameDay(as: day) }
    }

    func totals(on day: Date) -> NutritionTotals {
        NutritionTotals(meals: meals(on: day))
    }

    func water(on day: Date) -> Int {
        waterByDay[Self.key(for: day)] ?? 0
    }

    func addWater(milliliters: Int = 250, on day: Date = .now) {
        let key = Self.key(for: day)
        waterByDay = waterByDay.merging([key: (waterByDay[key] ?? 0) + milliliters]) { _, new in new }
        persist()
    }

    func addMeal(
        name: String,
        kcal: Int,
        protein: Int = 0,
        fat: Int = 0,
        carbs: Int = 0,
        photo: Data? = nil,
        source: MealSource,
        date: Date = .now,
        author: String? = nil
    ) {
        let meal = Meal(
            id: UUID(), date: date, name: name, kcal: max(kcal, 0),
            protein: max(protein, 0), fat: max(fat, 0), carbs: max(carbs, 0),
            photo: photo, source: source, author: author
        )
        meals = ([meal] + meals).sorted { $0.date > $1.date }
        persist()
    }

    func deleteMeal(id: UUID) {
        meals = meals.filter { $0.id != id }
        persist()
    }

    /// Daily kcal totals for the last `days` days (oldest first), skipping days with no meals.
    func dailyCalories(lastDays days: Int, now: Date = .now) -> [DailyValue] {
        (0..<days).reversed().compactMap { offset in
            let day = now.startOfDay.adding(days: -offset)
            let dayMeals = meals(on: day)
            guard !dayMeals.isEmpty else { return nil }
            return DailyValue(date: day, value: Double(NutritionTotals(meals: dayMeals).kcal))
        }
    }

    func averageCalories(lastDays days: Int, now: Date = .now) -> Int? {
        let values = dailyCalories(lastDays: days, now: now).map(\.value)
        return StatisticsMath.mean(values).map { Int($0) }
    }

    func mealCount(lastDays days: Int, now: Date = .now) -> Int {
        let from = now.startOfDay.adding(days: -days)
        return meals.filter { $0.date >= from }.count
    }

    func updateGoals(_ newGoals: NutritionGoals) {
        goals = newGoals
        persist()
    }

    func resetToDemo() {
        meals = DemoData.mealHistory().sorted { $0.date > $1.date }
        waterByDay = [:]
        goals = NutritionGoals()
        persist()
    }

    private static func key(for day: Date) -> String {
        let components = Calendar.pulse.dateComponents([.year, .month, .day], from: day)
        return "\(components.year ?? 0)-\(components.month ?? 0)-\(components.day ?? 0)"
    }

    private func persist() {
        storage?.save(State(meals: meals, water: waterByDay, goals: goals))
    }
}

enum ImageCompressor {
    /// Downscales and JPEG-compresses a photo so it can be stored with the meal.
    static func thumbnailData(from data: Data, maxDimension: CGFloat = 640) -> Data? {
        guard let image = UIImage(data: data) else { return nil }
        let scale = min(1, maxDimension / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let renderer = UIGraphicsImageRenderer(size: size)
        let resized = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        return resized.jpegData(compressionQuality: 0.7)
    }
}
