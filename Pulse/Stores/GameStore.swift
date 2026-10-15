import Foundation
import Observation

/// Player-side CS2 data for every game screen: real Faceit data when connected, demo matches otherwise.
@Observable
final class GameStore {
    struct EloSnapshot: Codable, Equatable {
        let date: Date
        let elo: Int
    }

    struct Saved: Codable {
        var nickname: String?
        var playerId: String?
        var snapshots: [EloSnapshot]
    }

    static let apiKeyAccount = "faceit.apiKey"

    private(set) var faceit: FaceitProfile?
    private(set) var isSyncing = false
    private(set) var error: String?
    private(set) var nickname: String?
    /// One ELO value per day, collected on every refresh — the Data API has no ELO history.
    private(set) var snapshots: [EloSnapshot]

    private let storage: JSONFileStorage<Saved>?
    private let secrets: SecretStore
    private let makeClient: (String) -> FaceitProviding

    init(
        storage: JSONFileStorage<Saved>? = JSONFileStorage(filename: "faceit.json"),
        secrets: SecretStore = KeychainStore(),
        makeClient: @escaping (String) -> FaceitProviding = { FaceitClient(apiKey: $0) }
    ) {
        self.storage = storage
        self.secrets = secrets
        self.makeClient = makeClient
        let saved = storage?.load()
        self.nickname = saved?.nickname
        self.snapshots = saved?.snapshots ?? []
    }

    /// Demo accounts fall back to demo matches; real accounts show nothing until Faceit is connected.
    private(set) var isDemo = true

    /// Something to show on game screens: real Faceit data, or the demo for demo accounts.
    var hasData: Bool { faceit != nil || isDemo }

    func reload(isDemo: Bool) {
        self.isDemo = isDemo
        let saved = storage?.load()
        nickname = saved?.nickname
        snapshots = saved?.snapshots ?? []
        faceit = nil
        error = nil
    }

    var hasApiKey: Bool { !(secrets.read(Self.apiKeyAccount) ?? "").isEmpty }
    var isConnected: Bool { nickname != nil && hasApiKey }

    // MARK: Sync

    @MainActor
    func connect(nickname rawNickname: String, apiKey: String?) async {
        let name = FaceitMapper.nickname(fromProfileLink: rawNickname) ?? rawNickname.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else {
            error = "Укажи ник или ссылку на профиль Faceit"
            return
        }
        if let apiKey = apiKey?.trimmingCharacters(in: .whitespacesAndNewlines), !apiKey.isEmpty {
            secrets.write(apiKey, for: Self.apiKeyAccount)
        }
        guard hasApiKey else {
            error = "Вставь API-ключ Faceit"
            return
        }
        if name != nickname {
            snapshots = []
        }
        nickname = name
        persist(playerId: nil)
        await refresh()
    }

    @MainActor
    func refresh(now: Date = .now) async {
        guard let nickname, let key = secrets.read(Self.apiKeyAccount), !key.isEmpty, !isSyncing else { return }
        isSyncing = true
        defer { isSyncing = false }
        do {
            let profile = try await makeClient(key).profile(nickname: nickname)
            faceit = profile
            error = nil
            snapshots = Self.recording(profile.elo, at: now, into: snapshots)
            persist(playerId: profile.playerId)
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? "Не удалось обновить данные Faceit"
        }
    }

    func disconnect(forgetKey: Bool = false) {
        faceit = nil
        nickname = nil
        error = nil
        snapshots = []
        if forgetKey { secrets.write(nil, for: Self.apiKeyAccount) }
        storage?.save(Saved(nickname: nil, playerId: nil, snapshots: []))
    }

    /// Keeps one snapshot per day (the latest value of that day).
    static func recording(_ elo: Int, at date: Date, into list: [EloSnapshot]) -> [EloSnapshot] {
        (list.filter { !$0.date.isSameDay(as: date) } + [EloSnapshot(date: date, elo: elo)])
            .sorted { $0.date < $1.date }
            .suffix(400)
            .map { $0 }
    }

    private func persist(playerId: String?) {
        storage?.save(Saved(nickname: nickname, playerId: playerId, snapshots: snapshots))
    }

    // MARK: Data for screens (Faceit → demo fallback)

    var usesRealData: Bool { faceit != nil }

    func profile(for user: User?) -> PlayerProfile {
        let demo = GameData.profile(for: user)
        guard let faceit else {
            guard isDemo else {
                return PlayerProfile(id: demo.id, nickname: user?.nickname ?? "", realName: "", gameRole: "", lastActive: .now,
                                     stats: demo.stats, elo: 0, seed: demo.seed)
            }
            return demo
        }
        return PlayerProfile(id: demo.id, nickname: faceit.nickname, realName: demo.realName, gameRole: demo.gameRole,
                             lastActive: faceit.matches.first?.date ?? demo.lastActive, stats: demo.stats,
                             elo: faceit.elo, seed: demo.seed, readinessRange: demo.readinessRange)
    }

    /// Newest first.
    func matches(for user: User?, days: Int = 30, now: Date = .now) -> [GameMatch] {
        guard let faceit else { return isDemo ? GameData.matches(for: GameData.profile(for: user), days: days, now: now) : [] }
        let from = now.startOfDay.adding(days: -(days - 1))
        return faceit.matches.filter { $0.date >= from }
    }

    /// Recent matches regardless of date (goals and summaries use «last N matches»).
    func recentMatches(for user: User?, count: Int) -> [GameMatch] {
        guard let faceit else { return isDemo ? Array(GameData.matches(for: GameData.profile(for: user)).prefix(count)) : [] }
        return Array(faceit.matches.prefix(count))
    }

    func eloHistory(for user: User?, days: Int, now: Date = .now) -> [DailyValue] {
        guard faceit != nil else { return isDemo ? GameData.eloHistory(for: GameData.profile(for: user), days: days, now: now) : [] }
        let from = now.startOfDay.adding(days: -(days - 1))
        return snapshots.filter { $0.date >= from }.map { DailyValue(date: $0.date.startOfDay, value: Double($0.elo)) }
    }

    func daily(_ metric: GameMetric, for user: User?, days: Int, now: Date = .now) -> [DailyValue] {
        guard faceit != nil else { return isDemo ? GameData.daily(metric, for: GameData.profile(for: user), days: days) : [] }
        let list = matches(for: user, days: days, now: now)
        return (0..<days).reversed().compactMap { offset in
            let day = now.startOfDay.adding(days: -offset)
            let values = list.filter { $0.date.isSameDay(as: day) }.map(metric.value)
            return StatisticsMath.mean(values).map { DailyValue(date: day, value: $0) }
        }
    }
}
