import Foundation
import Testing
@testable import Pulse

/// Swift twin of the Kotlin `AboutYouTest` and the «О тебе» parts of `PulseCoreTests`: same rules, same numbers.
struct AboutYouTests {
    private func day(_ year: Int, _ month: Int, _ day: Int, hour: Int = 12) -> Date {
        Calendar.pulse.date(from: DateComponents(year: year, month: month, day: day, hour: hour))!
    }

    private var now: Date { day(2026, 10, 25) }

    private func blank(isNewPlayer: Bool = true, nutrition: NutritionGoals = NutritionGoals()) -> AboutYouForm {
        AboutYou.initial(window: SleepWindow(), fitness: FitnessGoals(), nutrition: nutrition, screenLimitMinutes: 300,
                         profile: nil, isNewPlayer: isNewPlayer)
    }

    private func complete() -> AboutYouForm {
        var form = blank().withWeightText("74")
        form.birthDate = day(2004, 3, 12)
        form.heightText = "182"
        return form
    }

    @Test func bmiFromWeightAndHeight() {
        #expect(BodyMath.bmi(weightKg: 74, heightCm: 182) == 22.3)
    }

    @Test func suggestionsFromWeight() {
        #expect(AboutYou.suggestedKcal(weightKg: 74) == 2450)
        #expect(AboutYou.suggestedWaterMl(weightKg: 74) == 2500)
        #expect(AboutYou.suggestedKcal(weightKg: 20) == 1200)
        #expect(AboutYou.suggestedWaterMl(weightKg: 200) == 5000)
    }

    @Test func newPlayerKcalFollowsWeightUntilEdited() {
        let withWeight = blank().withWeightText("74")
        #expect(withWeight.foodKcal == 2450)
        let edited = withWeight.withFoodKcal(2600).withWeightText("80")
        #expect(edited.foodKcal == 2600)
        #expect(edited.waterMl == 2750)
    }

    @Test func existingPlayerKeepsCurrentGoals() {
        var goals = NutritionGoals()
        goals.kcal = 3000
        goals.waterMilliliters = 3000
        let form = blank(isNewPlayer: false, nutrition: goals).withWeightText("74")
        #expect(form.foodKcal == 3000)
        #expect(form.waterMl == 3000)
    }

    /// Pressed «Позже» before «О тебе» existed and set 3 000 kcal in Цели: no survey, but the goals are the player's own.
    @Test func customNutritionIsKeptWithoutSurvey() {
        var goals = NutritionGoals()
        goals.kcal = 3000
        goals.waterMilliliters = 3000
        let form = blank(isNewPlayer: true, nutrition: goals).withWeightText("74")
        #expect(form.foodKcal == 3000)
        #expect(form.waterMl == 3000)
    }

    /// Профиль → «О тебе» starts from the weight «Тело» shows (newer scales), not the one typed earlier.
    @Test func sheetStartsFromCurrentWeight() {
        let profile = BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74, updatedAt: now)
        let current = AboutYou.initial(window: SleepWindow(), fitness: FitnessGoals(), nutrition: NutritionGoals(), screenLimitMinutes: 300,
                                       profile: profile, isNewPlayer: false, weightKg: 76.5)
        #expect(current.weightText == "76,5")
        let typed = AboutYou.initial(window: SleepWindow(), fitness: FitnessGoals(), nutrition: NutritionGoals(), screenLimitMinutes: 300,
                                     profile: profile, isNewPlayer: false)
        #expect(typed.weightText == "74")
    }

    /// Body errors appear once a field is left, or all of them when the button is pressed with a wrong answer.
    @Test func errorsShowAfterLeavingOrPressing() {
        var form = blank()
        form.heightText = "182"
        form = form.withWeightText("250")
        #expect(AboutYou.visibleErrors(form, touched: [], revealed: false, now: now).isEmpty)
        let left = AboutYou.visibleErrors(form, touched: [.weight], revealed: false, now: now)
        #expect(Set(left.keys) == [.weight])
        let pressed = AboutYou.visibleErrors(form, touched: [], revealed: true, now: now)
        #expect(Set(pressed.keys) == [.birthDate, .weight])
    }

    @Test func missingFieldsAreErrors() {
        let errors = AboutYou.errors(blank(), now: now)
        #expect(errors[.birthDate] == "Укажи дату рождения")
        #expect(errors[.height] == "Рост — от 120 до 230 см")
        #expect(errors[.weight] == "Вес — от 30 до 200 кг")
        #expect(AboutYou.profile(blank(), now: now) == nil)
    }

    @Test func rangesAreChecked() {
        func errors(_ form: AboutYouForm) -> [AboutYouField: String] { AboutYou.errors(form, now: now) }
        func height(_ text: String) -> AboutYouForm { var form = complete(); form.heightText = text; return form }
        #expect(errors(height("119"))[.height] != nil)
        #expect(errors(height("231"))[.height] != nil)
        #expect(errors(height("120"))[.height] == nil)
        #expect(errors(height("230"))[.height] == nil)
        #expect(errors(complete().withWeightText("29,9"))[.weight] != nil)
        let decimal = complete().withWeightText("74,5")
        #expect(decimal.weightKg == 74.5)
        #expect(errors(decimal)[.weight] == nil)
        var young = complete()
        young.birthDate = day(2017, 1, 1)
        #expect(errors(young)[.birthDate] != nil)
        #expect(errors(complete()).isEmpty)
    }

    @Test func wakeEqualToBedtimeIsAnError() {
        var same = complete()
        (same.bedtimeHour, same.bedtimeMinute, same.wakeHour, same.wakeMinute) = (23, 30, 23, 30)
        #expect(AboutYou.errors(same, now: now)[.wake] == "Подъём не может совпадать с отбоем")
        var afterMidnight = complete()
        (afterMidnight.bedtimeHour, afterMidnight.bedtimeMinute, afterMidnight.wakeHour, afterMidnight.wakeMinute) = (1, 0, 9, 0)
        #expect(AboutYou.errors(afterMidnight, now: now)[.wake] == nil)
    }

    @Test func newerHealthWeightWins() {
        let profile = BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74, updatedAt: now)
        #expect(BodyMath.currentWeight(profile: profile, healthKg: 76, healthAt: now.adding(days: 1)) == 76)
        #expect(BodyMath.currentWeight(profile: nil, healthKg: 76, healthAt: now) == 76)
    }

    @Test func olderHealthWeightLoses() {
        let profile = BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74, updatedAt: now)
        #expect(BodyMath.currentWeight(profile: profile, healthKg: 76, healthAt: now.adding(days: -1)) == 74)
        #expect(BodyMath.currentWeight(profile: profile, healthKg: nil, healthAt: nil) == 74)
    }

    @Test func metricsOrder() {
        let profile = BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74, updatedAt: now)
        let metrics = BodyMath.metrics(profile: profile, healthKg: nil, healthAt: nil, fatPercent: 14, leanKg: 60)
        #expect(metrics.map(\.title) == ["Вес", "Рост", "ИМТ", "Жир", "Сух. масса"])
        #expect(metrics.map(\.value) == [74, 182, 22.3, 14, 60])
    }

    @Test func profileFromCompleteForm() {
        #expect(AboutYou.profile(complete(), now: now) == BodyProfile(birthDate: "2004-03-12", heightCm: 182, weightKg: 74, updatedAt: now))
    }

    /// The Android core writes this exact JSON; iOS must read it (and vice versa — synced files are shared).
    @Test func readsAndroidJson() throws {
        let json = #"{"birthDate":"2004-03-12","heightCm":182,"weightKg":74.0,"updatedAt":"2026-10-25T10:00:00Z"}"#
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let profile = try decoder.decode(BodyProfile.self, from: Data(json.utf8))
        #expect(profile.birthDate == "2004-03-12")
        #expect(profile.heightCm == 182)
        #expect(profile.weightKg == 74)
        #expect(profile.updatedAt == ISO8601DateFormatter().date(from: "2026-10-25T10:00:00Z"))
        #expect(profile.birthDay == DateComponents(year: 2004, month: 3, day: 12))
    }

    // MARK: Stores

    private func user(_ email: String, _ role: UserRole) -> User {
        User(id: UUID(), nickname: "Тестер", email: email, role: role, tariff: .free, team: nil, streakDays: 0)
    }

    private func stores() -> (BodyProfileStore, SleepStore, FitnessStore, NutritionStore, ScreenTimeStore) {
        (BodyProfileStore(storage: nil), SleepStore(storage: nil, seed: []),
         FitnessStore(storage: nil, seed: [], healthService: nil), NutritionStore(storage: nil, seed: []),
         ScreenTimeStore(storage: nil, isDemo: false))
    }

    private func filled(isNewPlayer: Bool = true) -> AboutYouForm {
        var form = blank(isNewPlayer: isNewPlayer)
        form.birthDate = day(2004, 3, 12)
        form.heightText = "182"
        (form.bedtimeHour, form.bedtimeMinute, form.wakeHour, form.wakeMinute) = (23, 30, 7, 30)
        (form.activeMinutes, form.steps, form.activeKcal, form.screenLimitHours) = (45, 8_500, 500, 4)
        return form.withWeightText("74").withFoodKcal(2_600).withWater(2_750)
    }

    @Test func demoAccountsHaveAProfile() {
        let store = BodyProfileStore(storage: nil)
        store.reload(user: user("player@pulse.app", .teamPlayer))
        #expect(store.profile == .demo)
        #expect(!AboutYou.needsStep(user: user("player@pulse.app", .teamPlayer), profile: store.profile))
    }

    @Test func needsAboutYouOnlyForRealPlayers() {
        let player = user("t@team.gg", .soloPlayer)
        #expect(AboutYou.needsStep(user: player, profile: nil))
        #expect(!AboutYou.needsStep(user: player, profile: .demo))
        #expect(!AboutYou.needsStep(user: user("c@team.gg", .staff), profile: nil))
        #expect(!AboutYou.needsStep(user: nil, profile: nil))
    }

    @Test func applyAboutYouWritesEveryMetric() {
        let (body, sleep, fitness, nutrition, screenTime) = stores()
        let protein = nutrition.goals.protein
        let applied = AboutYou.apply(filled(), now: now, body: body, sleep: sleep, fitness: fitness, nutrition: nutrition, screenTime: screenTime)
        #expect(applied)
        let window = sleep.window
        #expect(window.bedtimeHour == 23)
        #expect(window.bedtimeMinute == 30)
        #expect(window.wakeHour == 7)
        #expect(window.wakeMinute == 30)
        #expect(fitness.goals == FitnessGoals(activeMinutes: 45, steps: 8_500, activeKcal: 500))
        #expect(nutrition.goals.kcal == 2_600)
        #expect(nutrition.goals.waterMilliliters == 2_750)
        #expect(nutrition.goals.protein == protein)
        #expect(screenTime.dailyLimitMinutes == 240)
        #expect(body.profile?.heightCm == 182)
        #expect(fitness.bodyProfile?.heightCm == 182)
    }

    @Test func invalidFormChangesNothing() {
        let (body, sleep, fitness, nutrition, screenTime) = stores()
        var form = filled()
        form.heightText = ""
        let applied = AboutYou.apply(form, now: now, body: body, sleep: sleep, fitness: fitness, nutrition: nutrition, screenTime: screenTime)
        #expect(!applied)
        #expect(sleep.window == SleepWindow())
        #expect(fitness.goals == FitnessGoals())
        #expect(body.profile == nil)
    }

    @Test func updateBodyKeepsGoalsAndWindow() {
        let (body, sleep, fitness, nutrition, screenTime) = stores()
        _ = AboutYou.apply(filled(), now: now, body: body, sleep: sleep, fitness: fitness, nutrition: nutrition, screenTime: screenTime)
        screenTime.dailyLimitMinutes = 330
        var edited = AboutYou.initial(window: sleep.window, fitness: fitness.goals, nutrition: nutrition.goals, screenLimitMinutes: 330,
                                      profile: body.profile, isNewPlayer: false)
        edited.heightText = "185"
        let updated = AboutYou.updateBody(edited.withWeightText("80"), now: now, body: body, fitness: fitness)
        #expect(updated)
        #expect(body.profile?.heightCm == 185)
        #expect(body.profile?.weightKg == 80)
        #expect(screenTime.dailyLimitMinutes == 330)
        #expect(fitness.goals == FitnessGoals(activeMinutes: 45, steps: 8_500, activeKcal: 500))
    }

    @Test func bodyMetricsFromProfileWithoutHealth() {
        let (body, sleep, fitness, nutrition, screenTime) = stores()
        fitness.reload(isDemo: false)
        #expect(fitness.bodyMetrics.isEmpty)
        _ = AboutYou.apply(filled(), now: now, body: body, sleep: sleep, fitness: fitness, nutrition: nutrition, screenTime: screenTime)
        #expect(fitness.bodyMetrics.map(\.title) == ["Вес", "Рост", "ИМТ"])
    }

    @Test func bodyValuesDropTrailingZero() {
        #expect(Formatters.measure(182) == "182")
        #expect(Formatters.measure(74.5) == "74,5")
        #expect(Formatters.measure(22.34) == "22,3")
    }
}
