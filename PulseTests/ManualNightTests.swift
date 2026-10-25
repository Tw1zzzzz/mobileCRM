import Foundation
import Testing
@testable import Pulse

/// Swift twin of the Kotlin `ManualNightTests`: «Добавить ночь» when «Начать сон» was forgotten.
struct ManualNightTests {
    private func at(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        Calendar.pulse.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private var now: Date { at(2026, 10, 25) }
    private func store() -> SleepStore { SleepStore(storage: nil, seed: []) }

    @Test func manualNightBeforeMidnight() {
        let store = store()
        #expect(store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 23, bedMinute: 30, wakeHour: 7, wakeMinute: 15), now: now) == nil)
        let saved = store.sessions.first
        #expect(saved?.start == at(2026, 10, 24, 23, 30))
        #expect(saved?.end == at(2026, 10, 25, 7, 15))
    }

    @Test func manualNightAfterMidnight() {
        let interval = ManualNight(wakeDaysAgo: 1, bedHour: 1, bedMinute: 0, wakeHour: 9, wakeMinute: 0).interval(now: now)
        #expect(interval.start == at(2026, 10, 24, 1))
        #expect(interval.end == at(2026, 10, 24, 9))
    }

    @Test func manualNightInFutureIsRejected() {
        let store = store()
        let error = store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 23, bedMinute: 0, wakeHour: 7, wakeMinute: 0), now: at(2026, 10, 25, 6))
        #expect(error == "Подъём ещё не наступил")
        #expect(store.sessions.isEmpty)
    }

    @Test func manualNightTooLongOrShort() {
        let store = store()
        let short = store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 7, bedMinute: 0, wakeHour: 7, wakeMinute: 30), now: now)
        let long = store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 11, bedMinute: 0, wakeHour: 5, wakeMinute: 0), now: now)
        #expect(short == "Сон — от 1 до 16 часов")
        #expect(long == "Сон — от 1 до 16 часов")
        #expect(store.sessions.isEmpty)
    }

    @Test func manualNightOverlapsSavedSession() {
        let store = store()
        #expect(store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 23, bedMinute: 0, wakeHour: 7, wakeMinute: 0), now: now) == nil)
        let error = store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 2, bedMinute: 0, wakeHour: 9, wakeMinute: 0), now: now)
        #expect(error == "Эта ночь пересекается с уже записанной")
        #expect(store.sessions.count == 1)
    }

    @Test func manualNightOverlapsRunningSession() {
        let store = store()
        store.startSleep(at: at(2026, 10, 25, 4))
        let error = store.addNight(ManualNight(wakeDaysAgo: 0, bedHour: 23, bedMinute: 0, wakeHour: 7, wakeMinute: 0), now: now)
        #expect(error == "Эта ночь пересекается с уже записанной")
    }

    @Test func suggestedNightUsesWindow() {
        let window = SleepWindow()
        #expect(ManualNight.suggested(window: window, now: at(2026, 10, 25, 6)) == ManualNight(wakeDaysAgo: 1, bedHour: 23, bedMinute: 0, wakeHour: 7, wakeMinute: 0))
        #expect(ManualNight.suggested(window: window, now: at(2026, 10, 25, 10)) == ManualNight(wakeDaysAgo: 0, bedHour: 23, bedMinute: 0, wakeHour: 7, wakeMinute: 0))
    }
}
