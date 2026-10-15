import Foundation
import os

/// Remembers which local collections (sleep.json, nutrition.json…) changed since the last server sync.
/// Today nothing is uploaded; when the backend exists, `SyncEngine` pushes `pending()` collections
/// and calls `markSynced`. Kept per account folder, written directly (not via JSONFileStorage) to avoid recursion.
enum SyncLedger {
    struct Entry: Codable, Equatable {
        var changedAt: Date
        var syncedAt: Date?

        var isPending: Bool { syncedAt.map { $0 < changedAt } ?? true }
    }

    private static let filename = "sync-ledger.json"
    private static let logger = Logger(subsystem: "com.pulse.mobile", category: "SyncLedger")
    private static let lock = NSLock()

    static func markChanged(_ collection: String, at date: Date = .now, in folder: URL? = nil) {
        update(folder) { entries in
            var entry = entries[collection] ?? Entry(changedAt: date)
            entry.changedAt = date
            entries[collection] = entry
        }
    }

    static func markSynced(_ collection: String, at date: Date = .now, in folder: URL? = nil) {
        update(folder) { entries in
            guard var entry = entries[collection] else { return }
            entry.syncedAt = date
            entries[collection] = entry
        }
    }

    /// Collections with local changes not yet on the server, sorted for stable upload order.
    static func pending(in folder: URL? = nil) -> [String] {
        load(folder).filter { $0.value.isPending }.map(\.key).sorted()
    }

    static func entries(in folder: URL? = nil) -> [String: Entry] {
        load(folder)
    }

    // MARK: File

    private static func url(_ folder: URL?) -> URL {
        (folder ?? DataScope.folder(for: DataScope.current)).appendingPathComponent(filename)
    }

    private static func load(_ folder: URL?) -> [String: Entry] {
        guard let data = try? Data(contentsOf: url(folder)) else { return [:] }
        return (try? decoder.decode([String: Entry].self, from: data)) ?? [:]
    }

    private static func update(_ folder: URL?, _ change: (inout [String: Entry]) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        var entries = load(folder)
        change(&entries)
        let target = url(folder)
        do {
            try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
            try encoder.encode(entries).write(to: target, options: [.atomic, .completeFileProtection])
        } catch {
            logger.error("Sync ledger write failed: \(error.localizedDescription, privacy: .public)")
        }
    }

    private static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }()
}
