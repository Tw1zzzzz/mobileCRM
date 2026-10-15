import Foundation

/// Backend contract for authentication. Replace `MockAuthService` with an HTTP implementation
/// that talks to the existing web backend — the views and `SessionStore` stay unchanged.
protocol AuthService {
    func login(email: String, password: String) async throws -> User
    func register(_ form: RegistrationForm) async throws -> User
    func updateProfile(_ user: User)
    func deleteAccount(_ user: User)
}

extension AuthService {
    func updateProfile(_ user: User) {}
    func deleteAccount(_ user: User) {}
}

/// In-memory auth used by the MVP. Demo accounts are documented in README.md.
final class MockAuthService: AuthService {
    struct DemoAccount {
        let email: String
        let password: String
        let user: User
    }

    static let demoPassword = "pulse2026"
    /// Invite codes of existing teams (demo). Real codes come from the backend.
    static let teamCodes = ["PULSE": "Pulse Esports"]

    static let demoAccounts: [DemoAccount] = [
        DemoAccount(
            email: "player@pulse.app",
            password: demoPassword,
            user: User(id: UUID(), nickname: "Nova", email: "player@pulse.app", role: .teamPlayer, tariff: .pro, team: "Pulse Esports", streakDays: 12)
        ),
        DemoAccount(
            email: "solo@pulse.app",
            password: demoPassword,
            user: User(id: UUID(), nickname: "Raven", email: "solo@pulse.app", role: .soloPlayer, tariff: .free, team: nil, streakDays: 3)
        ),
        DemoAccount(
            email: "staff@pulse.app",
            password: demoPassword,
            user: User(id: UUID(), nickname: "Coach", email: "staff@pulse.app", role: .staff, tariff: .pro, team: "Pulse Esports", streakDays: 27)
        )
    ]

    private var registered: [DemoAccount] = []
    private let latency: Duration

    init(latency: Duration = .milliseconds(700)) {
        self.latency = latency
    }

    func login(email: String, password: String) async throws -> User {
        try await Task.sleep(for: latency)
        let normalized = email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let accounts = Self.demoAccounts + registered
        guard let account = accounts.first(where: { $0.email == normalized && $0.password == password }) else {
            throw AuthError.invalidCredentials
        }
        return account.user
    }

    func register(_ form: RegistrationForm) async throws -> User {
        try await Task.sleep(for: latency)
        let email = form.email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let accounts = Self.demoAccounts + registered
        if accounts.contains(where: { $0.email == email }) {
            throw AuthError.emailTaken
        }
        let teamName: String?
        switch form.accessType {
        case .solo:
            teamName = nil
        case .team where form.teamRole == .player:
            let code = form.teamCode.trimmingCharacters(in: .whitespaces).uppercased()
            guard let name = Self.teamCodes[code] else { throw AuthError.teamNotFound }
            teamName = name
        case .team:
            teamName = form.teamName.trimmingCharacters(in: .whitespaces)
        }
        let faceit = form.faceitURL.trimmingCharacters(in: .whitespaces)
        let user = User(
            id: UUID(),
            nickname: form.nickname.trimmingCharacters(in: .whitespacesAndNewlines),
            email: email,
            role: form.resolvedRole,
            tariff: .free,
            team: teamName,
            streakDays: 0,
            faceitURL: faceit.isEmpty ? nil : faceit
        )
        registered = registered + [DemoAccount(email: email, password: form.password, user: user)]
        return user
    }
}
