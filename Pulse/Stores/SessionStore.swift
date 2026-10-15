import Foundation
import Observation

@Observable
final class SessionStore {
    private(set) var user: User?
    private(set) var isLoading = false
    var errorMessage: String?

    private let authService: AuthService
    private let storage: JSONFileStorage<User>?

    /// Login, logout, demo switch, account deletion — the app reloads every store for the new account.
    @ObservationIgnored var onUserChange: ((User?) -> Void)?

    init(authService: AuthService, storage: JSONFileStorage<User>? = JSONFileStorage(filename: "session.json", global: true)) {
        self.authService = authService
        self.storage = storage
        self.user = storage?.load()
    }

    var isStaff: Bool { user?.role.isStaff ?? false }
    var hasSubscription: Bool { user?.tariff.hasSubscription ?? false }

    @MainActor
    func login(email: String, password: String) async {
        errorMessage = nil
        if let error = AuthValidator.validateLogin(email: email, password: password) {
            errorMessage = error.message
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let user = try await authService.login(email: email, password: password)
            setUser(user)
        } catch let error as AuthError {
            errorMessage = error.message
        } catch is URLError {
            errorMessage = AuthError.noConnection.message
        } catch {
            errorMessage = AuthError.server.message
        }
    }

    @MainActor
    func register(_ form: RegistrationForm) async {
        errorMessage = nil
        if let error = AuthValidator.validateRegistration(form) {
            errorMessage = error.message
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            let user = try await authService.register(form)
            setUser(user)
        } catch let error as AuthError {
            errorMessage = error.message
        } catch is URLError {
            errorMessage = AuthError.noConnection.message
        } catch {
            errorMessage = AuthError.registrationServer.message
        }
    }

    /// Profile editing (п. 15 ТЗ). Validated locally; will be sent to the API once it exists.
    func updateProfile(nickname: String, faceitURL: String) -> String? {
        guard let user else { return nil }
        let name = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        let faceit = faceitURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { return AuthError.emptyFields.message }
        if !faceit.isEmpty && !AuthValidator.isValidFaceitURL(faceit) { return AuthError.invalidFaceit.message }
        let updated = user.with(nickname: name, faceitURL: .some(faceit.isEmpty ? nil : faceit))
        self.user = updated
        storage?.save(updated)
        authService.updateProfile(updated)
        return nil
    }

    /// Demo mode: instant switch between seeded accounts without typing credentials.
    @MainActor
    func switchToDemo(role: UserRole) async {
        guard let account = MockAuthService.demoAccounts.first(where: { $0.user.role == role }) else { return }
        await login(email: account.email, password: account.password)
    }

    func logout() {
        user = nil
        storage?.clear()
        onUserChange?(nil)
    }

    /// App Store requirement for apps with sign-up: the account and all its data on this phone are removed.
    func deleteAccount() {
        guard let user, !user.isDemo else { return }
        authService.deleteAccount(user)
        DataScope.erase(DataScope.id(for: user))
        logout()
    }

    private func setUser(_ user: User) {
        let changed = self.user?.id != user.id || self.user?.email != user.email
        self.user = user
        storage?.save(user)
        if changed { onUserChange?(user) }
    }
}
