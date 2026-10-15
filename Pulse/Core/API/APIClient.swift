import Foundation

/// Response envelope agreed for the Pulse API (see docs/API.md):
/// `{ "success": true, "data": …, "error": null, "meta": { "page": 1, "limit": 50, "total": 120 } }`.
struct APIEnvelope<T: Decodable>: Decodable {
    struct APIErrorBody: Decodable, Equatable {
        let code: String
        let message: String?
    }

    struct Meta: Decodable, Equatable {
        let page: Int?
        let limit: Int?
        let total: Int?
    }

    let success: Bool
    let data: T?
    let error: APIErrorBody?
    let meta: Meta?
}

struct EmptyResponse: Decodable {}

enum APIError: Error, Equatable {
    case notConfigured
    case offline
    case unauthorized
    /// Server-side business error with its code (e.g. `email_taken`, `invalid_credentials`, `team_not_found`).
    case rejected(code: String, message: String?)
    case server(status: Int)
    case unreadable

    /// Maps to the user-facing texts from the spec («Авторизация»).
    var authError: AuthError {
        switch self {
        case .offline, .notConfigured: .noConnection
        case .unauthorized: .invalidCredentials
        case .rejected(let code, _):
            switch code {
            case "invalid_credentials": .invalidCredentials
            case "email_taken": .emailTaken
            case "team_not_found": .teamNotFound
            case "invalid_email": .invalidEmail
            case "weak_password": .weakPassword
            default: .server
            }
        case .server, .unreadable: .server
        }
    }
}

/// Access + refresh tokens, kept in the Keychain.
struct AuthTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
}

final class TokenStore {
    private let secrets: SecretStore
    private let key = "api.tokens"

    init(secrets: SecretStore = KeychainStore()) {
        self.secrets = secrets
    }

    var tokens: AuthTokens? {
        get { secrets.read(key).flatMap { $0.data(using: .utf8) }.flatMap { try? JSONDecoder().decode(AuthTokens.self, from: $0) } }
        set { secrets.write(newValue.flatMap { try? JSONEncoder().encode($0) }.flatMap { String(data: $0, encoding: .utf8) }, for: key) }
    }
}

/// Thin JSON client for the Pulse API: bearer auth, one automatic token refresh on 401, envelope unwrapping.
final class APIClient {
    enum Method: String {
        case get = "GET", post = "POST", put = "PUT", patch = "PATCH", delete = "DELETE"
    }

    private let baseURL: URL?
    private let session: URLSession
    let tokens: TokenStore

    init(baseURL: URL? = AppConfig.apiBaseURL, session: URLSession = .shared, tokens: TokenStore = TokenStore()) {
        self.baseURL = baseURL
        self.session = session
        self.tokens = tokens
    }

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.keyEncodingStrategy = .convertToSnakeCase
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    func send<Response: Decodable>(_ method: Method, _ path: String, authorized: Bool = true) async throws -> Response {
        try await perform(method, path, body: nil, authorized: authorized, retryOnUnauthorized: authorized)
    }

    func send<Response: Decodable, Body: Encodable>(_ method: Method, _ path: String, body: Body,
                                                    authorized: Bool = true) async throws -> Response {
        try await perform(method, path, body: try Self.encoder.encode(body), authorized: authorized, retryOnUnauthorized: authorized)
    }

    /// Raw JSON body (document sync uploads the collection file as is).
    func sendRaw<Response: Decodable>(_ method: Method, _ path: String, json: Data) async throws -> Response {
        try await perform(method, path, body: json, authorized: true, retryOnUnauthorized: true)
    }

    private func perform<Response: Decodable>(_ method: Method, _ path: String, body: Data?, authorized: Bool,
                                              retryOnUnauthorized: Bool) async throws -> Response {
        guard let baseURL else { throw APIError.notConfigured }
        var request = URLRequest(url: baseURL.appending(path: path), timeoutInterval: 20)
        request.httpMethod = method.rawValue
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        if authorized, let access = tokens.tokens?.accessToken {
            request.setValue("Bearer \(access)", forHTTPHeaderField: "Authorization")
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIError.offline
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 && retryOnUnauthorized, try await refreshTokens() {
            return try await perform(method, path, body: body, authorized: authorized, retryOnUnauthorized: false)
        }
        let envelope = try? Self.decoder.decode(APIEnvelope<Response>.self, from: data)
        if let error = envelope?.error {
            throw status == 401 ? APIError.unauthorized : APIError.rejected(code: error.code, message: error.message)
        }
        switch status {
        case 200..<300:
            if let value = envelope?.data { return value }
            if Response.self == EmptyResponse.self, let empty = EmptyResponse() as? Response { return empty }
            throw APIError.unreadable
        case 401: throw APIError.unauthorized
        default: throw APIError.server(status: status)
        }
    }

    /// `POST /auth/refresh` with the refresh token; on failure the session is over.
    private func refreshTokens() async throws -> Bool {
        guard let refresh = tokens.tokens?.refreshToken else { return false }
        struct Body: Encodable { let refreshToken: String }
        do {
            let new: AuthTokens = try await perform(.post, "auth/refresh", body: try Self.encoder.encode(Body(refreshToken: refresh)),
                                                    authorized: false, retryOnUnauthorized: false)
            tokens.tokens = new
            return true
        } catch {
            tokens.tokens = nil
            return false
        }
    }
}
