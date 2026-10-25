import Foundation
import Observation

@Observable
final class FitnessStore {
    /// Everything the account keeps about fitness, in its own folder.
    struct State: Codable {
        var workouts: [Workout]
        var goals: FitnessGoals
        /// Effort ratings for Apple Health workouts (their data can't be edited, so ratings live here).
        var ratings: [UUID: Int]
        var healthConnected: Bool
    }

    static let stateFile = "fitness-state.json"

    private(set) var workouts: [Workout]
    private(set) var goals: FitnessGoals
    /// «О тебе» (set by AppDependencies): age for heart-rate estimates, height and weight for «Тело».
    var bodyProfile: BodyProfile?
    private(set) var healthRatings: [UUID: Int]
    var activeMinutesGoal: Int { goals.activeMinutes }
    var stepsGoal: Int { goals.steps }
    var kcalGoal: Int { goals.activeKcal }

    /// Real data from Apple Health; nil until connected and fetched.
    private(set) var health: HealthSnapshot?
    private(set) var isSyncing = false
    private(set) var healthError: String?
    private(set) var isHealthConnected: Bool
    /// Demo accounts show a demo baseline without Health; real accounts show only their own numbers.
    private(set) var isDemo: Bool

    /// Lets other stores (sleep, recovery) use the same Health pass; nil = disconnected.
    @ObservationIgnored var onHealthSnapshot: ((HealthSnapshot?) -> Void)?

    private let storage: JSONFileStorage<State>?
    private let healthService: HealthDataProviding?

    init(
        storage: JSONFileStorage<State>? = JSONFileStorage(filename: FitnessStore.stateFile),
        seed: [Workout] = DemoData.workouts(),
        healthService: HealthDataProviding? = HealthKitService(),
        isDemo: Bool = true
    ) {
        self.storage = storage
        self.healthService = healthService
        self.isDemo = isDemo
        let saved = storage?.load()
        self.workouts = (saved?.workouts ?? (isDemo ? seed : [])).sorted { $0.date < $1.date }
        self.goals = saved?.goals ?? FitnessGoals()
        self.healthRatings = saved?.ratings ?? [:]
        self.isHealthConnected = healthService != nil && (saved?.healthConnected ?? false)
    }

    /// Re-reads the active account's folder (login, logout, demo switch).
    func reload(isDemo: Bool) {
        self.isDemo = isDemo
        let saved = storage?.load() ?? Self.legacyState(isDemo: isDemo)
        workouts = (saved?.workouts ?? (isDemo ? DemoData.workouts() : [])).sorted { $0.date < $1.date }
        goals = saved?.goals ?? FitnessGoals()
        healthRatings = saved?.ratings ?? [:]
        isHealthConnected = healthService != nil && (saved?.healthConnected ?? false)
        health = nil
        healthError = nil
    }

    /// Before accounts, workouts lived in `fitness.json` and settings in UserDefaults.
    private static func legacyState(isDemo: Bool) -> State? {
        guard let workouts = JSONFileStorage<[Workout]>(filename: "fitness.json").load() else { return nil }
        let defaults = UserDefaults.standard
        let goals = defaults.data(forKey: "fitness.goals").flatMap { try? JSONDecoder().decode(FitnessGoals.self, from: $0) }
        let ratings = defaults.data(forKey: "fitness.rpe").flatMap { try? JSONDecoder().decode([UUID: Int].self, from: $0) }
        let connected = defaults.bool(forKey: "health.connected")
        ["fitness.goals", "fitness.rpe", "health.connected"].forEach(defaults.removeObject(forKey:))
        return State(workouts: workouts, goals: goals ?? FitnessGoals(), ratings: ratings ?? [:], healthConnected: connected)
    }

    private func persist() {
        storage?.save(State(workouts: workouts, goals: goals, ratings: healthRatings, healthConnected: isHealthConnected))
    }

    func updateGoals(_ newGoals: FitnessGoals) {
        goals = newGoals
        persist()
    }

    // MARK: Training load

    /// Effort for a workout: the player's rating, else an estimate from pulse or type.
    func effort(for workout: Workout) -> (value: Int, isEstimated: Bool) {
        if let rpe = workout.rpe ?? healthRatings[workout.id] { return (rpe, false) }
        return (TrainingLoadMath.estimatedRPE(kind: workout.kind, averageHeartRate: workout.averageHeartRate,
                                              maxHeartRate: FitnessMath.maxHeartRate(age: FitnessMath.age(birthDate: bodyProfile?.birthDay))), true)
    }

    func rate(_ workout: Workout, effort value: Int) {
        let clamped = min(max(value, 1), 10)
        if workouts.contains(where: { $0.id == workout.id }) {
            workouts = workouts.map { $0.id == workout.id ? $0.rated(clamped) : $0 }
        } else {
            healthRatings = healthRatings.merging([workout.id: clamped]) { _, new in new }
        }
        persist()
    }

    /// Workouts counted in the load: Health (28 days) + logged ones; demo history until Health is connected.
    var loadWorkouts: [Workout] {
        let device = health?.loadWorkouts ?? (isDemo ? DemoData.loadHistory() : [])
        return device + loggedWorkouts
    }

    func dailyLoad(days: Int = TrainingLoadMath.chronicDays, now: Date = .now) -> [DailyValue] {
        let entries = loadWorkouts.map { workout in
            (date: workout.date, load: Double(effort(for: workout).value * workout.minutes))
        }
        return TrainingLoadMath.dailyLoads(entries, days: days, now: now)
    }

    var loadRatio: TrainingLoadMath.Ratio {
        TrainingLoadMath.ratio(daily: dailyLoad())
    }



    var isHealthAvailable: Bool { healthService?.isAvailable ?? false }

    // MARK: Apple Health

    /// Shows the system permission sheet, then loads the data.
    @MainActor
    func connectHealth() async {
        guard let healthService, healthService.isAvailable else {
            healthError = "Apple Health недоступно на этом устройстве"
            return
        }
        do {
            try await healthService.requestAuthorization()
            isHealthConnected = true
            persist()
            await refreshHealth()
        } catch {
            healthError = "Не удалось получить доступ к Apple Health"
        }
    }

    @MainActor
    func refreshHealth(now: Date = .now) async {
        guard isHealthConnected, let healthService, !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let snapshot = try await healthService.snapshot(now: now, body: bodyProfile)
            health = snapshot
            healthError = nil
            onHealthSnapshot?(snapshot)
        } catch {
            healthError = "Не удалось обновить данные Apple Health"
        }
    }

    /// Back to demo data (the permission itself is managed in iOS Settings → Health).
    func disconnectHealth() {
        isHealthConnected = false
        health = nil
        healthError = nil
        persist()
        onHealthSnapshot?(nil)
    }

    /// Connected, but nothing was recorded today — usually access to the data types was not granted.
    var healthLooksEmpty: Bool {
        guard let health else { return false }
        return health.today.steps == 0 && health.workouts.isEmpty && health.averageHeartRate == nil
    }

    // MARK: Numbers shown on screens

    /// Workouts logged in the app (demo seeds are hidden once real data is connected).
    private var loggedWorkouts: [Workout] {
        health == nil ? workouts : workouts.filter { !DemoData.seededWorkoutIDs.contains($0.id) }
    }

    var todayWorkouts: [Workout] {
        ((health?.workouts ?? []) + loggedWorkouts)
            .filter { $0.date.isSameDay(as: .now) }
            .sorted { $0.date < $1.date }
    }

    /// Workouts added by the user today on top of the device data.
    private var manualToday: [Workout] {
        workouts.filter { $0.date.isSameDay(as: .now) && !DemoData.seededWorkoutIDs.contains($0.id) }
    }

    /// Device baseline without Health: the demo numbers for demo accounts, nothing for real accounts.
    private var showsDemoBaseline: Bool { health == nil && isDemo }

    var activeMinutesToday: Int {
        (health?.today.activeMinutes ?? (showsDemoBaseline ? DemoData.baselineActiveMinutes : 0)) + manualToday.reduce(0) { $0 + $1.minutes }
    }

    var kcalToday: Int {
        (health?.today.activeKcal ?? (showsDemoBaseline ? DemoData.baselineActiveKcal : 0)) + manualToday.reduce(0) { $0 + $1.kcal }
    }

    var steps: Int { health?.today.steps ?? (showsDemoBaseline ? DemoData.baselineSteps : 0) }
    var distanceKm: Double { health?.today.distanceKm ?? (showsDemoBaseline ? DemoData.baselineDistanceKm : 0) }

    var weeklyActiveMinutes: [DailyValue] {
        let base = health?.weeklyActiveMinutes ?? (showsDemoBaseline ? DemoData.weeklyActiveMinutes : loggedMinutesByDay)
        guard let last = base.last else { return base }
        return Array(base.dropLast()) + [DailyValue(date: last.date, value: Double(activeMinutesToday))]
    }

    /// Without Health: minutes of logged workouts for the last 7 days.
    private var loggedMinutesByDay: [DailyValue] {
        (0..<7).map { index in
            let day = Date.now.startOfDay.adding(days: index - 6)
            let minutes = workouts.filter { $0.date.isSameDay(as: day) }.reduce(0) { $0 + $1.minutes }
            return DailyValue(date: day, value: Double(minutes))
        }
    }

    /// nil = no heart-rate data (no Health / no watch).
    var heartRateZones: [HeartRateZone]? {
        showsDemoBaseline ? DemoData.heartRateZones : health?.heartRateZones
    }

    var averageHeartRate: Int? {
        showsDemoBaseline ? DemoData.averageHeartRate : health?.averageHeartRate
    }

    var bodyMetrics: [BodyMetric] {
        if let metrics = health?.bodyMetrics { return metrics }
        if showsDemoBaseline { return DemoData.bodyMetrics }
        return bodyProfile.map { BodyMath.metrics(profile: $0, healthKg: nil, healthAt: nil, fatPercent: nil, leanKg: nil) } ?? []
    }

    func add(_ workout: Workout) {
        workouts = (workouts + [workout]).sorted { $0.date < $1.date }
        persist()
    }

    func resetToDemo() {
        workouts = DemoData.workouts().sorted { $0.date < $1.date }
        persist()
    }
}

@Observable
final class ScreenTimeStore {
    /// One manually entered day (from iOS Settings → Экранное время) — automatic collection needs
    /// Apple's Family Controls entitlement and comes later.
    struct Entry: Codable, Equatable {
        let date: Date
        var minutes: Int
    }

    struct State: Codable {
        var entries: [Entry]
        var limitMinutes: Int
        var accessGranted: Bool
    }

    private(set) var entries: [Entry]
    var dailyLimitMinutes: Int {
        didSet { persist() }
    }
    /// Demo accounts: the «access to Screen Time» switch that shows the Empty State when off.
    var isAuthorized: Bool {
        didSet { persist() }
    }
    private(set) var isDemo: Bool

    private let storage: JSONFileStorage<State>?

    init(storage: JSONFileStorage<State>? = JSONFileStorage(filename: "screen-time.json"), isDemo: Bool = true) {
        self.storage = storage
        self.isDemo = isDemo
        let saved = storage?.load()
        entries = saved?.entries ?? []
        dailyLimitMinutes = saved?.limitMinutes ?? 300
        isAuthorized = saved?.accessGranted ?? true
    }

    func reload(isDemo: Bool) {
        self.isDemo = isDemo
        let saved = storage?.load()
        entries = saved?.entries ?? []
        let legacyLimit = UserDefaults.standard.integer(forKey: "screenTime.limit")
        dailyLimitMinutes = saved?.limitMinutes ?? (legacyLimit > 0 ? legacyLimit : 300)
        isAuthorized = saved?.accessGranted ?? true
    }

    private func persist() {
        storage?.save(State(entries: entries, limitMinutes: dailyLimitMinutes, accessGranted: isAuthorized))
    }

    // MARK: Manual entries

    func setMinutes(_ minutes: Int, on day: Date) {
        let clamped = min(max(minutes, 0), 24 * 60)
        entries = (entries.filter { !$0.date.isSameDay(as: day) } + [Entry(date: day.startOfDay, minutes: clamped)])
            .sorted { $0.date < $1.date }
        persist()
    }

    func minutes(on day: Date) -> Int? {
        entries.first { $0.date.isSameDay(as: day) }?.minutes
    }

    /// Hourly chart, pickups and per-app usage need automatic collection (demo only for now).
    var hasDetailedData: Bool { isDemo }
    /// Real accounts see a «введи время» prompt instead of the access Empty State.
    var showsContent: Bool { isDemo ? isAuthorized : true }

    // MARK: Numbers for screens

    var todayMinutes: Int { isDemo ? 263 : minutes(on: .now) ?? 0 }
    var yesterdayMinutes: Int { isDemo ? 308 : minutes(on: Date.now.adding(days: -1)) ?? 0 }
    let pickups = 87
    let firstPickup = "07:42"
    var hourly: [Int] { isDemo ? DemoData.screenTimeHourly : [] }
    var apps: [AppUsage] { isDemo ? DemoData.screenTimeApps : [] }

    var week: [ScreenTimeDay] {
        if isDemo { return DemoData.screenTimeWeek() }
        return (0..<7).map { index in
            let day = Date.now.startOfDay.adding(days: index - 6)
            return ScreenTimeDay(date: day, minutes: minutes(on: day) ?? 0, categories: [:])
        }
    }

    var limitProgress: Double { Double(todayMinutes) / Double(max(dailyLimitMinutes, 1)) }
    var remainingMinutes: Int { dailyLimitMinutes - todayMinutes }

    var changeVsYesterday: Double {
        StatisticsMath.percentChange(from: Double(yesterdayMinutes), to: Double(todayMinutes)) ?? 0
    }

    var weekAverage: Int {
        let days = isDemo ? week : week.filter { $0.minutes > 0 }
        guard !days.isEmpty else { return 0 }
        return days.reduce(0) { $0 + $1.minutes } / days.count
    }

    var categoryTotals: [(category: AppCategory, minutes: Int)] {
        var totals: [AppCategory: Int] = [:]
        for app in apps { totals[app.category, default: 0] += app.minutes }
        return totals.map { ($0.key, $0.value) }.sorted { $0.minutes > $1.minutes }
    }

    /// Daily totals for the last `days` days, oldest first (only entered days for real accounts).
    func daily(lastDays days: Int, now: Date = .now) -> [DailyValue] {
        if isDemo { return DemoData.screenTimeSeries(days: days, now: now) }
        let from = now.startOfDay.adding(days: -(days - 1))
        return entries.filter { $0.date >= from }.map { DailyValue(date: $0.date, value: Double($0.minutes)) }
    }
}
