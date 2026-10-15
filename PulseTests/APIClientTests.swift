import Foundation
import Testing
@testable import Pulse

/// Fake server: tests register a handler per request.
final class StubURLProtocol: URLProtocol {
    nonisolated(unsafe) static var handler: ((URLRequest) -> (Int, String))?
    nonisolated(unsafe) static var requests: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var recorded = request
        if recorded.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            var data = Data()
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
            stream.close()
            recorded.httpBody = data
        }
        Self.requests.append(recorded)
        let (status, body) = Self.handler?(recorded) ?? (500, "{}")
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class MemorySecrets: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) -> String? { values[key] }
    func write(_ value: String?, for key: String) { values[key] = value }
}

@Suite(.serialized)
struct APIClientTests {
    private let base = URL(string: "https://api.test/v1")!

    private func makeClient() -> APIClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        StubURLProtocol.requests = []
        return APIClient(baseURL: base, session: URLSession(configuration: config), tokens: TokenStore(secrets: MemorySecrets()))
    }

    private let userJSON = #"{"id":"6F1B6A2E-1C2D-4E5F-8A9B-0C1D2E3F4A5B","nickname":"Nova","email":"nova@team.gg","role":"teamPlayer","tariff":"free","team":"Pulse","streak_days":4,"faceit_url":null}"#

    @Test func loginStoresTokensAndMapsUser() async throws {
        let client = makeClient()
        StubURLProtocol.handler = { request in
            #expect(request.url?.path == "/v1/auth/login")
            #expect(request.httpMethod == "POST")
            return (200, #"{"success":true,"data":{"user":\#(userJSON),"access_token":"A1","refresh_token":"R1"},"error":null}"#)
        }
        let user = try await RemoteAuthService(client: client).login(email: "Nova@Team.gg ", password: "secret123")
        #expect(user.nickname == "Nova")
        #expect(user.streakDays == 4)
        #expect(client.tokens.tokens == AuthTokens(accessToken: "A1", refreshToken: "R1"))
        let body = String(data: StubURLProtocol.requests.first?.httpBody ?? Data(), encoding: .utf8) ?? ""
        #expect(body.contains("nova@team.gg"))
    }

    @Test func serverErrorsBecomeSpecMessages() async {
        let client = makeClient()
        StubURLProtocol.handler = { _ in (409, #"{"success":false,"data":null,"error":{"code":"email_taken","message":"exists"}}"#) }
        var form = RegistrationForm()
        form.nickname = "Nova"; form.email = "nova@team.gg"; form.password = "secret123"; form.confirmPassword = "secret123"
        await #expect(throws: AuthError.emailTaken) { try await RemoteAuthService(client: client).register(form) }

        StubURLProtocol.handler = { _ in (401, #"{"success":false,"data":null,"error":{"code":"invalid_credentials","message":null}}"#) }
        await #expect(throws: AuthError.invalidCredentials) { try await RemoteAuthService(client: client).login(email: "a@b.cc", password: "x") }
    }

    @Test func expiredTokenIsRefreshedOnce() async throws {
        let client = makeClient()
        client.tokens.tokens = AuthTokens(accessToken: "old", refreshToken: "R1")
        StubURLProtocol.handler = { request in
            switch (request.url?.path, request.value(forHTTPHeaderField: "Authorization")) {
            case ("/v1/auth/refresh", _):
                return (200, #"{"success":true,"data":{"access_token":"new","refresh_token":"R2"},"error":null}"#)
            case ("/v1/me", "Bearer new"):
                return (200, #"{"success":true,"data":\#(userJSON),"error":null}"#)
            default:
                return (401, #"{"success":false,"data":null,"error":{"code":"token_expired","message":null}}"#)
            }
        }
        let me: RemoteAuthService.UserDTO = try await client.send(.get, "me")
        #expect(me.nickname == "Nova")
        #expect(client.tokens.tokens?.accessToken == "new")
        #expect(StubURLProtocol.requests.map { $0.url?.path ?? "" } == ["/v1/me", "/v1/auth/refresh", "/v1/me"])
    }

    @Test func notConfiguredMeansNoConnection() async {
        let client = APIClient(baseURL: nil, tokens: TokenStore(secrets: MemorySecrets()))
        await #expect(throws: AuthError.noConnection) { try await RemoteAuthService(client: client).login(email: "a@b.cc", password: "x") }
        #expect(AppConfig.backend == .local)
    }
}
