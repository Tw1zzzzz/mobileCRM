import CommonCrypto
import Foundation

/// On-device accounts for the release without a backend. Profiles live in the shared `accounts.json`,
/// password hashes (PBKDF2-SHA256, random salt) in the Keychain — passwords themselves are never stored.
/// When the server is ready, `RemoteAuthService` replaces this class behind the same `AuthService` protocol.
final class LocalAuthService: AuthService {
    struct Account: Codable, Equatable {
        var user: User
        let createdAt: Date
    }

    struct Credential: Codable {
        let salt: Data
        let hash: Data
        let rounds: UInt32
    }

    static let rounds: UInt32 = 120_000

    private let storage: JSONFileStorage<[Account]>?
    private let secrets: SecretStore

    init(storage: JSONFileStorage<[Account]>? = JSONFileStorage(filename: "accounts.json", global: true),
         secrets: SecretStore = KeychainStore()) {
        self.storage = storage
        self.secrets = secrets
    }

    private var accounts: [Account] { storage?.load() ?? [] }

    func login(email: String, password: String) async throws -> User {
        let normalized = Self.normalize(email)
        if let demo = MockAuthService.demoAccounts.first(where: { $0.email == normalized }) {
            guard demo.password == password else { throw AuthError.invalidCredentials }
            return demo.user
        }
        guard let account = accounts.first(where: { $0.user.email == normalized }),
              let credential = credential(for: normalized),
              Self.verify(password, against: credential) else {
            throw AuthError.invalidCredentials
        }
        return account.user
    }

    func register(_ form: RegistrationForm) async throws -> User {
        let email = Self.normalize(form.email)
        if DemoAccounts.emails.contains(email) || accounts.contains(where: { $0.user.email == email }) {
            throw AuthError.emailTaken
        }
        // A coach needs the players' data, which only a server can share between phones.
        if form.resolvedRole == .staff { throw AuthError.staffNeedsServer }
        let faceit = form.faceitURL.trimmingCharacters(in: .whitespaces)
        let code = form.teamCode.trimmingCharacters(in: .whitespaces).uppercased()
        let user = User(
            id: UUID(),
            nickname: form.nickname.trimmingCharacters(in: .whitespacesAndNewlines),
            email: email,
            role: form.resolvedRole,
            tariff: .free,
            // The invite code is checked by the server later; until then it is kept as the team label.
            team: form.isTeamPlayer ? "Команда \(code)" : nil,
            streakDays: 0,
            faceitURL: faceit.isEmpty ? nil : faceit
        )
        let salt = Self.randomSalt()
        let credential = Credential(salt: salt, hash: Self.hash(form.password, salt: salt, rounds: Self.rounds), rounds: Self.rounds)
        guard let encoded = try? JSONEncoder().encode(credential) else { throw AuthError.registrationServer }
        secrets.write(encoded.base64EncodedString(), for: Self.credentialKey(email))
        storage?.save(accounts + [Account(user: user, createdAt: .now)])
        return user
    }

    /// Profile edits are kept with the account so the next login returns them.
    func updateProfile(_ user: User) {
        guard !user.isDemo else { return }
        storage?.save(accounts.map { $0.user.id == user.id ? Account(user: user, createdAt: $0.createdAt) : $0 })
    }

    /// «Удалить аккаунт»: profile and password hash are removed (the data folder is erased by the session).
    func deleteAccount(_ user: User) {
        guard !user.isDemo else { return }
        storage?.save(accounts.filter { $0.user.id != user.id })
        secrets.write(nil, for: Self.credentialKey(user.email))
    }

    // MARK: Hashing

    static func normalize(_ email: String) -> String {
        email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private static func credentialKey(_ email: String) -> String { "auth.\(email)" }

    private func credential(for email: String) -> Credential? {
        guard let raw = secrets.read(Self.credentialKey(email)), let data = Data(base64Encoded: raw) else { return nil }
        return try? JSONDecoder().decode(Credential.self, from: data)
    }

    static func verify(_ password: String, against credential: Credential) -> Bool {
        let candidate = hash(password, salt: credential.salt, rounds: credential.rounds)
        // Constant-time comparison.
        guard candidate.count == credential.hash.count else { return false }
        return zip(candidate, credential.hash).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }

    static func hash(_ password: String, salt: Data, rounds: UInt32) -> Data {
        let passwordBytes = Array(password.utf8).map { Int8(bitPattern: $0) }
        var derived = [UInt8](repeating: 0, count: 32)
        let status = salt.withUnsafeBytes { saltBuffer in
            CCKeyDerivationPBKDF(
                CCPBKDFAlgorithm(kCCPBKDF2), passwordBytes, passwordBytes.count,
                saltBuffer.bindMemory(to: UInt8.self).baseAddress, salt.count,
                CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds, &derived, derived.count
            )
        }
        return status == kCCSuccess ? Data(derived) : Data()
    }

    static func randomSalt() -> Data {
        var bytes = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return Data(bytes)
    }
}
