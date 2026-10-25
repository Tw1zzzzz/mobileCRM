import Foundation
import Observation

@Observable
final class SleepStore {
    struct State: Codable {
        var sessions: [SleepSession]
        var activeStart: Date?
        var window: SleepWindow
    }

    private(set) var sessions: [SleepSession]
    private(set) var activeStart: Date?
    private(set) var window: SleepWindow

    private let storage: JSONFileStorage<State>?

    /// Nights read from Apple Health (oldest first); empty until Health is connected.
    private(set) var healthNights: [SleepNight] = []
    private(set) var isHealthConnected = false
    /// Demo accounts fall back to the demo night; real accounts show only their own data.
    private(set) var isDemo = true

    /// Fired when a session starts or ends (lock screen Live Activity, wake-up check).
    @ObservationIgnored var onTrackingChange: ((Date?, SleepWindow) -> Void)?
    /// Fired when the planned sleep window changes (bedtime reminder).
    @ObservationIgnored var onWindowChange: ((SleepWindow) -> Void)?

    init(
        storage: JSONFileStorage<State>? = JSONFileStorage(filename: "sleep.json"),
        seed: [SleepSession] = DemoData.sleepHistory()
    ) {
        self.storage = storage
        let saved = storage?.load()
        self.sessions = (saved?.sessions ?? seed).sorted { $0.start > $1.start }
        self.activeStart = saved?.activeStart
        self.window = saved?.window ?? SleepWindow()
    }

    var isSleeping: Bool { activeStart != nil }

    /// Re-reads the active account's folder (login, logout, demo switch).
    func reload(isDemo: Bool) {
        self.isDemo = isDemo
        let saved = storage?.load()
        sessions = (saved?.sessions ?? (isDemo ? DemoData.sleepHistory() : [])).sorted { $0.start > $1.start }
        activeStart = saved?.activeStart
        window = saved?.window ?? SleepWindow()
        healthNights = []
        isHealthConnected = false
    }

    func startSleep(at date: Date = .now) {
        guard activeStart == nil else { return }
        activeStart = date
        persist()
        onTrackingChange?(activeStart, window)
    }

    /// Finishes the active session, computes its duration and stores it in history.
    @discardableResult
    func stopSleep(at date: Date = .now) -> SleepSession? {
        guard let start = activeStart, date > start else {
            activeStart = nil
            persist()
            onTrackingChange?(nil, window)
            return nil
        }
        let session = SleepSession(id: UUID(), start: start, end: date)
        sessions = ([session] + sessions).sorted { $0.start > $1.start }
        activeStart = nil
        persist()
        onTrackingChange?(nil, window)
        return session
    }

    func toggleSleep(at date: Date = .now) {
        if isSleeping { stopSleep(at: date) } else { startSleep(at: date) }
    }

    func cancelActiveSession() {
        activeStart = nil
        persist()
        onTrackingChange?(nil, window)
    }

    /// Why `night` cannot be saved, or nil when it can.
    func nightError(_ night: ManualNight, now: Date = .now) -> String? {
        let (start, end) = night.interval(now: now)
        let minutes = Int(end.timeIntervalSince(start) / 60)
        if end > now { return "Подъём ещё не наступил" }
        if !ManualNight.durationMinutes.contains(minutes) { return "Сон — от 1 до 16 часов" }
        let overlapsSaved = sessions.contains { $0.start < end && start < $0.end }
        let overlapsRunning = activeStart.map { $0 < end } ?? false
        return overlapsSaved || overlapsRunning ? "Эта ночь пересекается с уже записанной" : nil
    }

    /// Saves `night` in the history; returns the reason instead when it cannot be saved.
    @discardableResult
    func addNight(_ night: ManualNight, now: Date = .now) -> String? {
        if let error = nightError(night, now: now) { return error }
        let (start, end) = night.interval(now: now)
        sessions = (sessions + [SleepSession(id: UUID(), start: start, end: end)]).sorted { $0.start > $1.start }
        persist()
        return nil
    }

    func deleteSession(id: UUID) {
        sessions = sessions.filter { $0.id != id }
        persist()
    }

    func updateWindow(_ newWindow: SleepWindow) {
        window = newWindow
        persist()
        onWindowChange?(newWindow)
    }

    func sessions(lastDays days: Int, now: Date = .now) -> [SleepSession] {
        let from = now.startOfDay.adding(days: -days)
        return sessions.filter { $0.end >= from && $0.end <= now }
    }

    func averageMinutes(lastDays days: Int, now: Date = .now) -> Int? {
        let valid = sessions(lastDays: days, now: now).filter(\.isValid)
        guard !valid.isEmpty else { return nil }
        return valid.reduce(0) { $0 + $1.durationMinutes } / valid.count
    }

    func totalMinutes(lastDays days: Int, now: Date = .now) -> Int {
        sessions(lastDays: days, now: now).reduce(0) { $0 + $1.durationMinutes }
    }

    /// Sleep minutes attributed to the day the session ended (wake-up day).
    func minutes(on day: Date) -> Int? {
        let matching = sessions.filter { $0.end.isSameDay(as: day) }
        guard !matching.isEmpty else { return nil }
        return matching.reduce(0) { $0 + $1.durationMinutes }
    }

    // MARK: Apple Health

    /// Builds nights from Health and replaces overlapping sessions in the history with them
    /// (the tracker is more precise than a manual start/stop — and it also replaces demo nights).
    func applyHealth(_ snapshot: HealthSnapshot?) {
        guard let snapshot else {
            isHealthConnected = false
            healthNights = []
            return
        }
        isHealthConnected = true
        let groups = SleepMath.nights(from: snapshot.sleepIntervals)
        var built: [SleepNight] = []
        for (index, group) in groups.enumerated() {
            let vitals = index == groups.count - 1 ? (snapshot.nightVitals ?? SleepMath.NightVitals()) : SleepMath.NightVitals()
            let previous = built.suffix(7).map(\.score)
            if let night = SleepMath.night(group, targetMinutes: window.durationMinutes, vitals: vitals, previousScores: previous) {
                built.append(night)
            }
        }
        healthNights = built
        importHealthNights(built.map { SleepSession(id: UUID(), start: $0.start, end: $0.end) })
    }

    func importHealthNights(_ nights: [SleepSession]) {
        guard !nights.isEmpty else { return }
        let kept = sessions.filter { session in
            !nights.contains { Self.overlap(session, $0) > 0.5 }
        }
        sessions = (kept + nights).sorted { $0.start > $1.start }
        persist()
    }

    /// Share of the shorter session covered by the other one.
    static func overlap(_ a: SleepSession, _ b: SleepSession) -> Double {
        let start = max(a.start, b.start)
        let end = min(a.end, b.end)
        guard end > start else { return 0 }
        let shorter = min(a.end.timeIntervalSince(a.start), b.end.timeIntervalSince(b.start))
        return shorter > 0 ? end.timeIntervalSince(start) / shorter : 0
    }

    /// The night shown on «Сон», «Сегодня» and «Восстановление»: Health → last session → demo (demo accounts only).
    /// nil = a real account without any sleep yet.
    var displayNight: SleepNight? {
        if let last = healthNights.last, last.end > Date.now.adding(days: -2) { return last }
        if isDemo && !isHealthConnected { return DemoData.lastNight() }
        return sessions.first.map { SleepMath.manualNight($0, targetMinutes: window.durationMinutes) }
    }

    var week: [SleepWeekDay] {
        if healthNights.contains(where: \.hasStages) { return SleepMath.week(healthNights) }
        if isDemo { return DemoData.sleepWeek() }
        // Manual sessions have no phases: the whole night counts as light sleep in the weekly bars.
        let nights = sessions.prefix(14).map { SleepMath.manualNight($0, targetMinutes: window.durationMinutes) }
        return (0..<7).map { index in
            let day = Date.now.startOfDay.adding(days: index - 6)
            let minutes = nights.filter { $0.end.isSameDay(as: day) }.reduce(0) { $0 + $1.totalMinutes }
            return SleepWeekDay(date: day, deep: 0, rem: 0, light: minutes)
        }
    }

    /// Restores the demo history (Профиль → Демо-режим, demo accounts only).
    func resetToDemo() {
        sessions = DemoData.sleepHistory().sorted { $0.start > $1.start }
        activeStart = nil
        window = SleepWindow()
        persist()
        onTrackingChange?(nil, window)
        onWindowChange?(window)
    }

    private func persist() {
        storage?.save(State(sessions: sessions, activeStart: activeStart, window: window))
    }
}
