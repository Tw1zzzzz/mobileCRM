import Foundation
import Security

/// Loads a player's CS2 data from Faceit. The app depends on this protocol, so tests use a fake
/// and, once the backend exists, a server-proxied client can replace the direct one without touching screens.
protocol FaceitProviding {
    func profile(nickname: String) async throws -> FaceitProfile
}

enum FaceitError: LocalizedError, Equatable {
    case invalidKey
    case playerNotFound
    case noCS2Profile
    case rateLimited
    case offline
    case server(Int)
    case unreadable

    var errorDescription: String? {
        switch self {
        case .invalidKey: "Faceit не принял API-ключ. Проверь ключ в настройках Faceit."
        case .playerNotFound: "Игрок с таким ником не найден на Faceit. Ник чувствителен к регистру."
        case .noCS2Profile: "У этого игрока нет профиля CS2 на Faceit."
        case .rateLimited: "Слишком много запросов к Faceit. Попробуй через минуту."
        case .offline: "Не удалось установить соединение с сервером"
        case .server(let code): "Faceit временно недоступен (код \(code))."
        case .unreadable: "Не удалось разобрать ответ Faceit."
        }
    }
}

/// Direct client for the Faceit Data API v4. Only public data is read; the key is supplied by the user
/// and kept in the Keychain (never in the source). Endpoints: players, CS2 lifetime/map stats,
/// per-match stats (last 100) and the regional ranking. ELO history is not exposed by the Data API.
final class FaceitClient: FaceitProviding {
    static let baseURL = URL(string: "https://open.faceit.com/data/v4")!
    static let game = "cs2"
    static let matchLimit = 100

    private let apiKey: String
    private let session: URLSession

    init(apiKey: String, session: URLSession = .shared) {
        self.apiKey = apiKey
        self.session = session
    }

    func profile(nickname: String) async throws -> FaceitProfile {
        let player: FaceitPlayer = try await get("players", [URLQueryItem(name: "nickname", value: nickname),
                                                             URLQueryItem(name: "game", value: Self.game)])
        guard let cs2 = player.cs2 else { throw FaceitError.noCS2Profile }
        let id = player.playerId
        async let stats: FaceitPlayerStats? = try? get("players/\(id)/stats/\(Self.game)")
        async let matchList: FaceitMatchStatsList = get("players/\(id)/games/\(Self.game)/stats",
                                                       [URLQueryItem(name: "limit", value: String(Self.matchLimit))])
        let rank: FaceitRanking? = if let region = cs2.region {
            try? await get("rankings/games/\(Self.game)/regions/\(region)/players/\(id)", [URLQueryItem(name: "limit", value: "1")])
        } else {
            nil
        }
        let lifetime = await stats
        let matches = try await matchList.items.compactMap { FaceitMapper.match($0.stats) }.sorted { $0.date > $1.date }
        return FaceitProfile(
            playerId: id,
            nickname: player.nickname,
            avatar: player.avatar.flatMap(URL.init(string:)),
            country: player.country,
            region: cs2.region,
            elo: cs2.faceitElo ?? 0,
            skillLevel: cs2.skillLevel,
            regionRank: rank?.position,
            lifetime: lifetime.map { FaceitMapper.lifetime($0.lifetime) },
            maps: FaceitMapper.maps(lifetime?.segments ?? []),
            matches: matches,
            fetchedAt: .now
        )
    }

    private func get<T: Decodable>(_ path: String, _ query: [URLQueryItem] = []) async throws -> T {
        var components = URLComponents(url: Self.baseURL.appending(path: path), resolvingAgainstBaseURL: false)
        components?.queryItems = query.isEmpty ? nil : query
        guard let url = components?.url else { throw FaceitError.unreadable }
        var request = URLRequest(url: url, timeoutInterval: 20)
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw FaceitError.offline
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 200..<300: break
        case 401, 403: throw FaceitError.invalidKey
        case 404: throw FaceitError.playerNotFound
        case 429: throw FaceitError.rateLimited
        default: throw FaceitError.server(status)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw FaceitError.unreadable
        }
    }
}

/// Minimal Keychain wrapper for user-provided secrets (the Faceit API key).
protocol SecretStore {
    func read(_ key: String) -> String?
    func write(_ value: String?, for key: String)
}

struct KeychainStore: SecretStore {
    private let service = "com.pulsecrm.mvp"

    func read(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func write(_ value: String?, for key: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(base as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }
        var item = base
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }
}
