import Foundation

/// Questionnaire step «О тебе»: what Pulse knows about the player's body («body-profile.json»).
/// Same JSON as the Android core: `{"birthDate":"2004-03-12","heightCm":182,"weightKg":74.0,"updatedAt":"…Z"}`.
struct BodyProfile: Codable, Equatable {
    /// Calendar date, "yyyy-MM-dd".
    var birthDate: String
    var heightCm: Int
    var weightKg: Double
    var updatedAt: Date

    /// Demo players. The old timestamp lets any Apple Health weight win, as a real measurement would.
    static let demo = BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74,
                                  updatedAt: Date(timeIntervalSince1970: 946_684_800)) // 2000-01-01

    var birthDay: DateComponents {
        let parts = birthDate.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return DateComponents() }
        return DateComponents(year: parts[0], month: parts[1], day: parts[2])
    }

    static func dateString(_ date: Date) -> String {
        let parts = Calendar.pulse.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

enum BodyMath {
    static func bmi(weightKg: Double, heightCm: Int) -> Double {
        let meters = Double(heightCm) / 100
        return (weightKg / (meters * meters) * 10).rounded() / 10
    }

    /// The Health weight when it was measured after the questionnaire, otherwise the questionnaire's.
    static func currentWeight(profile: BodyProfile?, healthKg: Double?, healthAt: Date?) -> Double? {
        guard let healthKg else { return profile?.weightKg }
        guard let profile else { return healthKg }
        if let healthAt, healthAt > profile.updatedAt { return healthKg }
        return profile.weightKg
    }

    /// «Тело»: weight, height and BMI from the profile, body fat and lean mass from Apple Health.
    static func metrics(profile: BodyProfile?, healthKg: Double?, healthAt: Date?, fatPercent: Double?, leanKg: Double?) -> [BodyMetric] {
        let weight = currentWeight(profile: profile, healthKg: healthKg, healthAt: healthAt)
        var metrics: [BodyMetric] = []
        if let weight { metrics.append(BodyMetric(title: "Вес", value: weight, unit: "кг")) }
        if let profile {
            metrics.append(BodyMetric(title: "Рост", value: Double(profile.heightCm), unit: "см"))
            if let weight { metrics.append(BodyMetric(title: "ИМТ", value: bmi(weightKg: weight, heightCm: profile.heightCm), unit: "")) }
        }
        if let fatPercent { metrics.append(BodyMetric(title: "Жир", value: fatPercent, unit: "%")) }
        if let leanKg { metrics.append(BodyMetric(title: "Сух. масса", value: leanKg, unit: "кг")) }
        return metrics
    }

    static func metrics(profile: BodyProfile?, readings: BodyReadings) -> [BodyMetric] {
        metrics(profile: profile, healthKg: readings.weightKg, healthAt: readings.weightAt, fatPercent: readings.bodyFatPercent,
                leanKg: readings.leanMassKg)
    }
}

enum AboutYouField: Hashable { case birthDate, height, weight, wake }

/// Answers of «О тебе» while they are being typed. Height and weight stay text until they parse; food kcal and water
/// follow the weight for a new player until the player sets them.
struct AboutYouForm: Equatable {
    var birthDate: Date?
    var heightText = ""
    var weightText = ""
    var bedtimeHour: Int
    var bedtimeMinute: Int
    var wakeHour: Int
    var wakeMinute: Int
    var activeMinutes: Int
    var steps: Int
    var activeKcal: Int
    var foodKcal: Int
    var waterMl: Int
    var screenLimitHours: Int
    var suggestsFromWeight: Bool
    var kcalEdited = false
    var waterEdited = false

    var heightCm: Int? { Int(heightText.trimmingCharacters(in: .whitespaces)) }
    var weightKg: Double? { Double(weightText.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")) }

    func withWeightText(_ text: String) -> AboutYouForm {
        var updated = self
        updated.weightText = text
        guard suggestsFromWeight, let weight = updated.weightKg, AboutYou.weightKg.contains(weight) else { return updated }
        if !kcalEdited { updated.foodKcal = AboutYou.suggestedKcal(weightKg: weight) }
        if !waterEdited { updated.waterMl = AboutYou.suggestedWaterMl(weightKg: weight) }
        return updated
    }

    func withFoodKcal(_ value: Int) -> AboutYouForm {
        var updated = self
        updated.foodKcal = value
        updated.kcalEdited = true
        return updated
    }

    func withWater(_ milliliters: Int) -> AboutYouForm {
        var updated = self
        updated.waterMl = milliliters
        updated.waterEdited = true
        return updated
    }
}

/// Rules of «О тебе», shared with Android (`AboutYou` in `core/…/model/BodyProfile.kt`).
enum AboutYou {
    static let ageYears = 10...80
    static let heightCm = 120...230
    static let weightKg = 30.0...200.0

    // Daily targets: the same ranges as the goal editors (Профиль → Цели, лимит экранного времени).
    static let activeMinutes = 10...180
    static let steps = 2_000...30_000
    static let stepsStep = 500
    static let activeKcal = 100...2_000
    static let foodKcal = 1_200...4_500
    static let waterMl = 1_000...5_000
    static let waterStepMl = 250
    static let screenLimitHours = 1...12

    private static let kcalPerKg = 33.0
    private static let kcalStep = 50
    private static let waterMlPerKg = 33.0

    static func suggestedKcal(weightKg: Double) -> Int {
        (Int((weightKg * kcalPerKg / Double(kcalStep)).rounded()) * kcalStep).clamped(to: foodKcal)
    }

    static func suggestedWaterMl(weightKg: Double) -> Int {
        (Int((weightKg * waterMlPerKg / Double(waterStepMl)).rounded()) * waterStepMl).clamped(to: waterMl)
    }

    static func errors(_ form: AboutYouForm, now: Date) -> [AboutYouField: String] {
        var errors: [AboutYouField: String] = [:]
        if let birthDate = form.birthDate {
            let years = Calendar.pulse.dateComponents([.year], from: birthDate, to: now).year ?? 0
            if !ageYears.contains(years) { errors[.birthDate] = "Возраст — от 10 до 80 лет" }
        } else {
            errors[.birthDate] = "Укажи дату рождения"
        }
        if !(form.heightCm.map(heightCm.contains) ?? false) { errors[.height] = "Рост — от 120 до 230 см" }
        if !(form.weightKg.map(weightKg.contains) ?? false) { errors[.weight] = "Вес — от 30 до 200 кг" }
        if form.bedtimeHour == form.wakeHour && form.bedtimeMinute == form.wakeMinute {
            errors[.wake] = "Подъём не может совпадать с отбоем"
        }
        return errors
    }

    private static let bodyFields: Set<AboutYouField> = [.birthDate, .height, .weight]

    /// Errors that may already be shown: body fields only after the player has left them, or all once the button was
    /// pressed with a wrong answer (`revealed`) — not while typing «18» on the way to «182».
    static func visibleErrors(_ form: AboutYouForm, touched: Set<AboutYouField>, revealed: Bool, now: Date) -> [AboutYouField: String] {
        errors(form, now: now).filter { revealed || !bodyFields.contains($0.key) || touched.contains($0.key) }
    }

    /// Starting values: the current window and goals; body fields from an earlier profile, if any, with `weightKg` — the
    /// weight «Тело» shows — in place of the typed one. Food kcal and water follow the weight only for a new player whose
    /// nutrition goals are still the defaults: one who skipped the survey may have set them in Цели.
    static func initial(window: SleepWindow, fitness: FitnessGoals, nutrition: NutritionGoals, screenLimitMinutes: Int,
                        profile: BodyProfile?, isNewPlayer: Bool, weightKg: Double? = nil) -> AboutYouForm {
        let defaults = NutritionGoals()
        return AboutYouForm(
            birthDate: profile.flatMap { Calendar.pulse.date(from: $0.birthDay) },
            heightText: profile.map { String($0.heightCm) } ?? "",
            weightText: (weightKg ?? profile?.weightKg).map(weightText) ?? "",
            bedtimeHour: window.bedtimeHour, bedtimeMinute: window.bedtimeMinute,
            wakeHour: window.wakeHour, wakeMinute: window.wakeMinute,
            activeMinutes: fitness.activeMinutes.clamped(to: activeMinutes),
            steps: fitness.steps.clamped(to: steps),
            activeKcal: fitness.activeKcal.clamped(to: activeKcal),
            foodKcal: nutrition.kcal.clamped(to: foodKcal),
            waterMl: nutrition.waterMilliliters.clamped(to: waterMl),
            screenLimitHours: (screenLimitMinutes / 60).clamped(to: screenLimitHours),
            suggestsFromWeight: isNewPlayer && nutrition.kcal == defaults.kcal && nutrition.waterMilliliters == defaults.waterMilliliters
        )
    }

    /// The profile to save, or nil while any answer is missing or out of range.
    static func profile(_ form: AboutYouForm, now: Date) -> BodyProfile? {
        guard errors(form, now: now).isEmpty, let birthDate = form.birthDate, let height = form.heightCm, let weight = form.weightKg else {
            return nil
        }
        return BodyProfile(birthDate: BodyProfile.dateString(birthDate), heightCm: height, weightKg: weight, updatedAt: now)
    }

    /// A real player without «О тебе» answers: the step is shown once, on its own, at the next launch.
    static func needsStep(user: User?, profile: BodyProfile?) -> Bool {
        guard let user else { return false }
        return !user.role.isStaff && !user.isDemo && profile == nil
    }

    /// Saves «О тебе» and spreads it over the metrics: body profile, sleep window (reminders), fitness and nutrition
    /// goals, screen-time limit. False, and nothing changes, while any answer is missing or out of range.
    @discardableResult
    static func apply(_ form: AboutYouForm, now: Date = .now, body: BodyProfileStore, sleep: SleepStore, fitness: FitnessStore,
                      nutrition: NutritionStore, screenTime: ScreenTimeStore) -> Bool {
        guard let profile = profile(form, now: now) else { return false }
        body.save(profile)
        fitness.bodyProfile = profile
        var window = sleep.window
        (window.bedtimeHour, window.bedtimeMinute, window.wakeHour, window.wakeMinute) =
            (form.bedtimeHour, form.bedtimeMinute, form.wakeHour, form.wakeMinute)
        sleep.updateWindow(window)
        fitness.updateGoals(FitnessGoals(activeMinutes: form.activeMinutes, steps: form.steps, activeKcal: form.activeKcal))
        var goals = nutrition.goals
        goals.kcal = form.foodKcal
        goals.waterMilliliters = form.waterMl
        nutrition.updateGoals(goals)
        screenTime.dailyLimitMinutes = form.screenLimitHours * 60
        return true
    }

    /// Профиль → «О тебе»: only birth date, height and weight change; goals and the sleep window stay as they are.
    @discardableResult
    static func updateBody(_ form: AboutYouForm, now: Date = .now, body: BodyProfileStore, fitness: FitnessStore) -> Bool {
        guard let profile = profile(form, now: now) else { return false }
        body.save(profile)
        fitness.bodyProfile = profile
        return true
    }

    /// «74» or «74,5».
    private static func weightText(_ kg: Double) -> String {
        kg.truncatingRemainder(dividingBy: 1) == 0 ? String(Int(kg)) : String(kg).replacingOccurrences(of: ".", with: ",")
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self { min(max(self, range.lowerBound), range.upperBound) }
}
