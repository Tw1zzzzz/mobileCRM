import Foundation

/// Sign-in against the Pulse API (enabled by `AppConfig.backend = .remote`). Same contract as
/// `LocalAuthService`, so `SessionStore` and the screens don't change. Endpoints: docs/API.md → «Auth».
final class RemoteAuthService: AuthService {
    struct SessionResponse: Decodable {
        let user: UserDTO
        let accessToken: String
        let refreshToken: String
    }

    /// Server representation of a user (snake_case on the wire).
    struct UserDTO: Codable, Equatable {
        let id: UUID
        let nickname: String
        let email: String
        let role: UserRole
        let tariff: Tariff
        let team: String?
        let streakDays: Int?
        let faceitUrl: String?

        var model: User {
            User(id: id, nickname: nickname, email: email, role: role, tariff: tariff, team: team,
                 streakDays: streakDays ?? 0, faceitURL: faceitUrl)
        }
    }

    struct RegisterRequest: Encodable {
        let nickname: String
        let email: String
        let password: String
        let role: UserRole
        let faceitUrl: String?
        let teamCode: String?
        let teamName: String?
        let acceptedTerms: Bool
        let acceptedPrivacy: Bool
    }

    private let client: APIClient

    init(client: APIClient = APIClient()) {
        self.client = client
    }

    func login(email: String, password: String) async throws -> User {
        struct Body: Encodable { let email: String; let password: String }
        do {
            let response: SessionResponse = try await client.send(.post, "auth/login",
                                                                  body: Body(email: LocalAuthService.normalize(email), password: password),
                                                                  authorized: false)
            client.tokens.tokens = AuthTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
            return response.user.model
        } catch let error as APIError {
            throw error.authError
        }
    }

    func register(_ form: RegistrationForm) async throws -> User {
        let trimmed = { (text: String) -> String? in
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            return value.isEmpty ? nil : value
        }
        let body = RegisterRequest(
            nickname: form.nickname.trimmingCharacters(in: .whitespacesAndNewlines),
            email: LocalAuthService.normalize(form.email),
            password: form.password,
            role: form.resolvedRole,
            faceitUrl: trimmed(form.faceitURL),
            teamCode: form.isTeamPlayer ? trimmed(form.teamCode)?.uppercased() : nil,
            teamName: form.isTeamStaff ? trimmed(form.teamName) : nil,
            acceptedTerms: form.acceptedTerms,
            acceptedPrivacy: form.acceptedPrivacy
        )
        do {
            let response: SessionResponse = try await client.send(.post, "auth/register", body: body, authorized: false)
            client.tokens.tokens = AuthTokens(accessToken: response.accessToken, refreshToken: response.refreshToken)
            return response.user.model
        } catch let error as APIError {
            throw error == .offline ? AuthError.noConnection : (error.authError == .server ? AuthError.registrationServer : error.authError)
        }
    }

    func updateProfile(_ user: User) {
        struct Body: Encodable { let nickname: String; let faceitUrl: String? }
        Task { [client] in
            let _: UserDTO? = try? await client.send(.patch, "me", body: Body(nickname: user.nickname, faceitUrl: user.faceitURL))
        }
    }

    func deleteAccount(_ user: User) {
        Task { [client] in
            let _: EmptyResponse? = try? await client.send(.delete, "me")
            client.tokens.tokens = nil
        }
    }
}

/// Pushes locally changed collections to the server. Today it is idle (`AppConfig.backend == .local`);
/// with the backend it uploads each pending file to `PUT /sync/{collection}` (docs/API.md → «Sync»).
final class SyncEngine {
    private let client: APIClient

    init(client: APIClient = APIClient()) {
        self.client = client
    }

    var isEnabled: Bool { AppConfig.apiBaseURL != nil }

    /// Returns the collections that were uploaded.
    @discardableResult
    func pushPending(folder: URL = DataScope.folder(for: DataScope.current)) async -> [String] {
        guard isEnabled else { return [] }
        var uploaded: [String] = []
        for collection in SyncLedger.pending(in: folder) {
            guard let json = try? Data(contentsOf: folder.appendingPathComponent(collection)) else { continue }
            let name = collection.replacingOccurrences(of: ".json", with: "")
            do {
                let _: EmptyResponse = try await client.sendRaw(.put, "sync/\(name)", json: json)
                SyncLedger.markSynced(collection, in: folder)
                uploaded.append(collection)
            } catch {
                break // offline or server error: keep the rest pending for the next attempt
            }
        }
        return uploaded
    }
}
