import Foundation
import Observation

@Observable
final class CalendarStore {
    private(set) var events: [CalendarEvent]
    private let storage: JSONFileStorage<[CalendarEvent]>?

    init(
        storage: JSONFileStorage<[CalendarEvent]>? = JSONFileStorage(filename: "calendar.json"),
        seed: [CalendarEvent] = DemoData.calendarEvents()
    ) {
        self.storage = storage
        self.events = (storage?.load() ?? seed).sorted { $0.start < $1.start }
    }

    func reload(isDemo: Bool) {
        events = (storage?.load() ?? (isDemo ? DemoData.calendarEvents() : [])).sorted { $0.start < $1.start }
    }

    /// Events visible for a date under an optional tag filter (manager view).
    func events(on day: Date, tag: EventTag?) -> [CalendarEvent] {
        events
            .filter { $0.start.isSameDay(as: day) }
            .filter { tag == nil || $0.tag == tag }
            .sorted { $0.start < $1.start }
    }

    func hasEvents(on day: Date, tag: EventTag?) -> Bool {
        events.contains { $0.start.isSameDay(as: day) && (tag == nil || $0.tag == tag) }
    }

    func event(id: UUID) -> CalendarEvent? {
        events.first { $0.id == id }
    }

    func upsert(_ event: CalendarEvent) {
        events = (events.filter { $0.id != event.id } + [event]).sorted { $0.start < $1.start }
        storage?.save(events)
    }

    func delete(id: UUID) {
        events = events.filter { $0.id != id }
        storage?.save(events)
    }

    func resetToDemo() {
        events = DemoData.calendarEvents().sorted { $0.start < $1.start }
        storage?.save(events)
    }

    var playerTags: [EventTag] {
        DemoData.players.map { EventTag.player($0.nickname) }
    }
}
