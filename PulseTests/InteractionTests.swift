import Foundation
import Testing
@testable import Pulse

struct SleepNotificationTests {
    @Test func bedtimeReminderComesBeforeBedtimeAndWrapsMidnight() {
        let late = SleepWindow(bedtimeHour: 23, bedtimeMinute: 0, wakeHour: 7, wakeMinute: 0)
        #expect(SleepNotifications.bedtimeReminderTime(for: late) == (22, 45))
        let midnight = SleepWindow(bedtimeHour: 0, bedtimeMinute: 5, wakeHour: 8, wakeMinute: 0)
        #expect(SleepNotifications.bedtimeReminderTime(for: midnight) == (23, 50))
    }

    @Test func wakeCheckFiresAfterTheNextPlannedAlarm() {
        let window = SleepWindow(bedtimeHour: 23, bedtimeMinute: 0, wakeHour: 7, wakeMinute: 0)
        let evening = Date.now.setting(hour: 23, minute: 30)
        let check = SleepNotifications.wakeCheckDate(after: evening, window: window)
        #expect(check == evening.adding(days: 1).setting(hour: 7, minute: 30))
        // Going to bed after midnight: the alarm is the same morning.
        let night = Date.now.setting(hour: 1, minute: 15)
        #expect(SleepNotifications.wakeCheckDate(after: night, window: window) == night.setting(hour: 7, minute: 30))
    }

    @Test func storeReportsTrackingChanges() {
        let store = SleepStore(storage: nil, seed: [])
        var events: [Date?] = []
        store.onTrackingChange = { start, _ in events.append(start) }
        let start = Date.now.adding(minutes: -480)
        store.startSleep(at: start)
        store.stopSleep(at: .now)
        #expect(events == [start, nil])
        #expect(store.sessions.count == 1)
    }
}

struct NotificationInboxTests {
    @Test func dismissingHidesOnlyThatItem() {
        let first = NotificationInbox.items[0].id
        let raw = NotificationInbox.dismissing(first, from: "")
        let visible = NotificationInbox.visible(dismissedRaw: raw)
        #expect(visible.count == NotificationInbox.items.count - 1)
        #expect(!visible.contains { $0.id == first })
        // Dismissing twice is idempotent.
        #expect(NotificationInbox.dismissing(first, from: raw) == raw)
    }
}

struct LineupVariationTests {
    private let user = User(id: UUID(), nickname: "Raven", email: "r@x.gg", role: .soloPlayer, tariff: .free, team: nil, streakDays: 0)

    private func smoke(_ title: String, favorite: Bool = false, variations: [LineupVariation] = []) -> Lineup {
        Lineup(id: UUID(), title: title, map: .mirage, side: .t, type: .smoke, details: "", videoURL: nil,
               author: "Raven", createdAt: .now, visibility: .personal, isFavorite: favorite, variations: variations)
    }

    @Test func addAndRemoveVariationsReturnNewCopies() {
        let base = smoke("Смок")
        let variation = LineupVariation(position: "Рампа", technique: .jump, aim: "Антенна")
        let added = base.addingVariation(variation)
        #expect(base.variations.isEmpty)
        #expect(added.variations == [variation])
        #expect(added.removingVariation(id: variation.id).variations.isEmpty)
    }

    @Test func variationValidation() {
        #expect(LineupValidator.validate(variation: LineupVariation(position: " ", technique: .run, aim: "x")) != nil)
        #expect(LineupValidator.validate(variation: LineupVariation(position: "Рампа", technique: .run, aim: "")) == nil)
        #expect(LineupValidator.validate(variation: LineupVariation(position: "Рампа", technique: .run, aim: "", videoURL: "not a link")) != nil)
    }

    @Test func legacyEntriesDecodeWithoutVariations() throws {
        let json = """
        [{"id":"\(UUID().uuidString)","title":"Старый смок","map":"mirage","side":"t","type":"smoke",
          "details":"","author":"Coach","createdAt":"2026-10-01T10:00:00Z"}]
        """
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let decoded = try decoder.decode([Lineup].self, from: Data(json.utf8))
        #expect(decoded.first?.variations.isEmpty == true)
    }

    @Test func savedDemoDiariesGetDemoVariations() {
        let migrated = LineupStore.addingDemoVariations([smoke("Смок в окно с T-спавна"), smoke("Свой смок")])
        #expect(migrated[0].variations.count == 3)
        #expect(migrated[1].variations.isEmpty)
    }

    @Test func favoritesScope() {
        let store = LineupStore(storage: nil, seed: [smoke("A", favorite: true), smoke("B")])
        #expect(store.diary(for: user, scope: .favorites).map(\.title) == ["A"])
        #expect(DiaryScope.options(for: .soloPlayer) == [.all, .favorites])
        #expect(DiaryScope.options(for: .teamPlayer).contains(.mine))
    }
}

struct ChartScrubTests {
    @Test func nearestPointSnapsToClosestDay() throws {
        let today = Date.now.startOfDay
        let series = (0..<5).map { DailyValue(date: today.adding(days: -$0), value: Double($0)) }
        let picked = try #require(series.nearest(to: today.adding(days: -2).addingTimeInterval(3 * 3600)))
        #expect(picked.date == today.adding(days: -2))
        #expect(series.value(on: today.adding(days: -1).addingTimeInterval(600))?.value == 1)
        #expect(series.value(on: nil) == nil)
    }
}

struct VariationCountTests {
    @Test func russianPlurals() {
        #expect(VariationCount.text(1) == "1 вариация")
        #expect(VariationCount.text(3) == "3 вариации")
        #expect(VariationCount.text(5) == "5 вариаций")
        #expect(VariationCount.text(12) == "12 вариаций")
        #expect(VariationCount.text(22) == "22 вариации")
    }
}
