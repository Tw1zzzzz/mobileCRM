import Foundation
import Observation

/// Mood & energy slots + balance wheel entries.
@Observable
final class WellbeingStore {
    struct State: Codable {
        var mood: [MoodEntry]
        var balance: [BalanceEntry]
    }

    private(set) var moodEntries: [MoodEntry]
    private(set) var balanceEntries: [BalanceEntry]

    private let storage: JSONFileStorage<State>?

    init(
        storage: JSONFileStorage<State>? = JSONFileStorage(filename: "wellbeing.json"),
        moodSeed: [MoodEntry] = DemoData.moodHistory(),
        balanceSeed: [BalanceEntry] = DemoData.balanceHistory()
    ) {
        self.storage = storage
        let saved = storage?.load()
        self.moodEntries = saved?.mood ?? moodSeed
        self.balanceEntries = saved?.balance ?? balanceSeed
    }

    /// Re-reads the active account's folder; demo accounts start with demo history, real ones empty.
    func reload(isDemo: Bool) {
        let saved = storage?.load()
        moodEntries = saved?.mood ?? (isDemo ? DemoData.moodHistory() : [])
        balanceEntries = saved?.balance ?? (isDemo ? DemoData.balanceHistory() : [])
    }

    // MARK: Mood

    func moodEntry(on day: Date, slot: DaySlot) -> MoodEntry? {
        moodEntries.first { $0.day.isSameDay(as: day) && $0.slot == slot }
    }

    func filledSlots(on day: Date) -> Set<DaySlot> {
        Set(moodEntries.filter { $0.day.isSameDay(as: day) }.map(\.slot))
    }

    /// Saves (or replaces) the entry for a given day + slot.
    func saveMood(day: Date, slot: DaySlot, mood: Int, energy: Int) {
        let entry = MoodEntry(id: UUID(), day: day.startOfDay, slot: slot, mood: mood, energy: energy)
        moodEntries = moodEntries.filter { !($0.day.isSameDay(as: day) && $0.slot == slot) } + [entry]
        persist()
    }

    func averageMood(on day: Date) -> (mood: Double, energy: Double)? {
        let entries = moodEntries.filter { $0.day.isSameDay(as: day) }
        guard let mood = StatisticsMath.mean(entries.map { Double($0.mood) }),
              let energy = StatisticsMath.mean(entries.map { Double($0.energy) }) else { return nil }
        return (mood, energy)
    }

    // MARK: Balance wheel

    func balanceEntry(on day: Date) -> BalanceEntry? {
        balanceEntries.first { $0.day.isSameDay(as: day) }
    }

    var latestBalance: BalanceEntry? {
        balanceEntries.max { $0.day < $1.day }
    }

    func saveBalance(day: Date, values: [BalanceCategory: Double]) {
        let mapped = Dictionary(uniqueKeysWithValues: values.map { ($0.key.rawValue, $0.value) })
        let entry = BalanceEntry(id: UUID(), day: day.startOfDay, values: mapped)
        balanceEntries = balanceEntries.filter { !$0.day.isSameDay(as: day) } + [entry]
        persist()
    }

    func resetToDemo() {
        moodEntries = DemoData.moodHistory()
        balanceEntries = DemoData.balanceHistory()
        persist()
    }

    private func persist() {
        storage?.save(State(mood: moodEntries, balance: balanceEntries))
    }
}
