import Foundation
import os

/// Which account's data folder is active. Every account (and every demo role) has its own folder,
/// so demo data never mixes with the user's own records.
enum DataScope {
    /// Set by `AppDependencies.activate(user:)` before stores reload.
    static var current = "guest"

    static var root: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Pulse", isDirectory: true)
    }

    static func folder(for scope: String) -> URL {
        root.appendingPathComponent("accounts", isDirectory: true).appendingPathComponent(scope, isDirectory: true)
    }

    static func id(for user: User) -> String {
        user.isDemo ? "demo-\(user.role.rawValue)" : user.id.uuidString
    }

    /// Account deletion: removes every file of that account.
    static func erase(_ scope: String) {
        try? FileManager.default.removeItem(at: folder(for: scope))
    }

    /// Before accounts existed every store wrote straight into `Pulse/`. Those files move into the folder
    /// of the account that was signed in, so nothing is lost on update.
    static func migrateLegacyFiles(into scope: String) {
        let manager = FileManager.default
        guard let files = try? manager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil) else { return }
        let legacy = files.filter { $0.pathExtension == "json" && !globalFiles.contains($0.lastPathComponent) }
        guard !legacy.isEmpty else { return }
        let target = folder(for: scope)
        try? manager.createDirectory(at: target, withIntermediateDirectories: true)
        for file in legacy {
            let destination = target.appendingPathComponent(file.lastPathComponent)
            if manager.fileExists(atPath: destination.path) {
                try? manager.removeItem(at: file)
            } else {
                try? manager.moveItem(at: file, to: destination)
            }
        }
    }

    /// Files shared by all accounts (current session, local account list, sync ledger).
    static let globalFiles: Set<String> = ["session.json", "accounts.json"]
}

/// Persists a Codable value as JSON in Application Support, inside the active account's folder
/// (or the shared folder for `global` files). Stores use it until the backend API is wired in.
struct JSONFileStorage<Value: Codable> {
    let filename: String
    private let global: Bool
    private let directory: URL?
    private let logger = Logger(subsystem: "com.pulse.mobile", category: "Storage")

    init(filename: String, global: Bool = false) {
        self.filename = filename
        self.global = global
        self.directory = nil
    }

    /// Fixed folder (tests).
    init(filename: String, directory: URL) {
        self.filename = filename
        self.global = false
        self.directory = directory
    }

    private var fileURL: URL {
        if let directory { return directory.appendingPathComponent(filename) }
        let folder = global ? DataScope.root : DataScope.folder(for: DataScope.current)
        return folder.appendingPathComponent(filename)
    }

    func load() -> Value? {
        let url = fileURL
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        do {
            let data = try Data(contentsOf: url)
            return try Self.decoder.decode(Value.self, from: data)
        } catch {
            logger.error("Failed to load \(filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
            return nil
        }
    }

    func save(_ value: Value) {
        let url = fileURL
        do {
            try FileManager.default.createDirectory(
                at: url.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let data = try Self.encoder.encode(value)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            if !global && directory == nil { SyncLedger.markChanged(filename) }
        } catch {
            logger.error("Failed to save \(filename, privacy: .public): \(error.localizedDescription, privacy: .public)")
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    private static var encoder: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }
}

/// Deterministic pseudo-random generator so demo data looks the same on every launch.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    /// Seeds are scrambled with SplitMix64: raw small seeds made the first xorshift outputs
    /// nearly identical (every demo series started at its minimum).
    init(seed: UInt64) {
        var z = seed &+ 0x9E37_79B9_7F4A_7C15
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        z ^= z >> 31
        state = z == 0 ? 0x9E37_79B9_7F4A_7C15 : z
    }

    mutating func next() -> UInt64 {
        state ^= state << 13
        state ^= state >> 7
        state ^= state << 17
        return state
    }

    mutating func double(in range: ClosedRange<Double>) -> Double {
        Double.random(in: range, using: &self)
    }

    mutating func int(in range: ClosedRange<Int>) -> Int {
        Int.random(in: range, using: &self)
    }
}
