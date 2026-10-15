import Foundation
import Testing
@testable import Pulse

struct AuthValidatorTests {
    private func validForm() -> RegistrationForm {
        var form = RegistrationForm()
        form.nickname = "Nova"
        form.email = "nova@team.gg"
        form.password = "secret123"
        form.confirmPassword = "secret123"
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        return form
    }

    @Test func loginRequiresBothFields() {
        #expect(AuthValidator.validateLogin(email: "", password: "x") == .emptyFields)
        #expect(AuthValidator.validateLogin(email: "a@b.cc", password: "") == .emptyFields)
        #expect(AuthValidator.validateLogin(email: "   ", password: "x") == .emptyFields)
        #expect(AuthValidator.validateLogin(email: "a@b.cc", password: "x") == nil)
    }

    @Test func validRegistrationPasses() {
        #expect(AuthValidator.validateRegistration(validForm()) == nil)
    }

    @Test func registrationErrorsMatchSpec() {
        var empty = validForm()
        empty.nickname = " "
        #expect(AuthValidator.validateRegistration(empty) == .emptyFields)

        var badEmail = validForm()
        badEmail.email = "nova@"
        #expect(AuthValidator.validateRegistration(badEmail) == .invalidEmail)

        var weak = validForm()
        weak.password = "short1"
        weak.confirmPassword = "short1"
        #expect(AuthValidator.validateRegistration(weak) == .weakPassword)

        var mismatch = validForm()
        mismatch.confirmPassword = "secret124"
        #expect(AuthValidator.validateRegistration(mismatch) == .passwordMismatch)

        var noTerms = validForm()
        noTerms.acceptedTerms = false
        #expect(AuthValidator.validateRegistration(noTerms) == .termsNotAccepted)

        var noPrivacy = validForm()
        noPrivacy.acceptedPrivacy = false
        #expect(AuthValidator.validateRegistration(noPrivacy) == .privacyNotAccepted)
    }

    @Test(arguments: [
        ("password", false), ("12345678", false), ("abc123", false), ("abcd1234", true), ("Пароль2026", true)
    ])
    func passwordStrength(password: String, expected: Bool) {
        #expect(AuthValidator.isStrongPassword(password) == expected)
    }

    @Test func messagesMatchSpecWording() {
        #expect(AuthError.invalidCredentials.message == "Неверный логин или пароль")
        #expect(AuthError.emptyFields.message == "Заполните обязательные поля")
        #expect(AuthError.noConnection.message == "Не удалось установить соединение с сервером")
    }

    @Test func teamFieldsAreRequiredPerRole() {
        var player = validForm()
        player.accessType = .team
        player.teamRole = .player
        #expect(AuthValidator.validateRegistration(player) == .emptyFields)
        player.teamCode = "PULSE"
        #expect(AuthValidator.validateRegistration(player) == nil)

        var staff = validForm()
        staff.accessType = .team
        staff.teamRole = .manager
        #expect(AuthValidator.validateRegistration(staff) == .emptyFields)
        staff.teamName = "Pulse Academy"
        #expect(AuthValidator.validateRegistration(staff) == nil)
    }

    @Test(arguments: [
        ("https://www.faceit.com/ru/players/nova", true), ("https://faceit.com/en/players/x", true),
        ("https://evil.com/faceit.com", false), ("faceit.com/players/x", false), ("ftp://faceit.com/x", false)
    ])
    func faceitLinks(link: String, expected: Bool) {
        #expect(AuthValidator.isValidFaceitURL(link) == expected)
        var form = validForm()
        form.faceitURL = link
        #expect((AuthValidator.validateRegistration(form) == nil) == expected)
    }

    @Test func registrationRoleResolution() {
        var form = validForm()
        #expect(form.resolvedRole == .soloPlayer)
        form.accessType = .team
        #expect(form.resolvedRole == .teamPlayer)
        form.teamRole = .manager
        #expect(form.resolvedRole == .staff)
    }
}

struct EventValidatorTests {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)

    @Test func requiresTitle() {
        let result = EventValidator.validate(title: "  ", start: start, end: start.adding(minutes: 60), links: [])
        #expect(result == .emptyRequiredFields)
    }

    @Test func rejectsEndBeforeStart() {
        let result = EventValidator.validate(title: "Тренировка", start: start, end: start.adding(minutes: -5), links: [])
        #expect(result == .endBeforeStart)
        #expect(EventValidator.validate(title: "Тренировка", start: start, end: start, links: []) == .endBeforeStart)
    }

    @Test func rejectsInvalidLinks() {
        let result = EventValidator.validate(title: "Матч", start: start, end: start.adding(minutes: 60), links: ["not a link"])
        #expect(result == .invalidLink)
        let ftp = EventValidator.validate(title: "Матч", start: start, end: start.adding(minutes: 60), links: ["ftp://x.com"])
        #expect(ftp == .invalidLink)
    }

    @Test func acceptsValidEvent() {
        let result = EventValidator.validate(
            title: "Матч", start: start, end: start.adding(minutes: 90), links: ["https://faceit.com/match/1"]
        )
        #expect(result == nil)
    }

    @Test func parsesLinksFromFreeText() {
        let links = EventValidator.parseLinks("https://a.com, https://b.com\n\n https://c.com ")
        #expect(links == ["https://a.com", "https://b.com", "https://c.com"])
    }
}

@MainActor
struct SessionStoreTests {
    private func makeStore() -> SessionStore {
        SessionStore(authService: MockAuthService(latency: .zero), storage: nil)
    }

    @Test func loginWithDemoAccountSetsUser() async {
        let store = makeStore()
        await store.login(email: "PLAYER@pulse.app ", password: MockAuthService.demoPassword)
        #expect(store.user?.role == .teamPlayer)
        #expect(store.errorMessage == nil)
    }

    @Test func wrongPasswordShowsSpecMessage() async {
        let store = makeStore()
        await store.login(email: "player@pulse.app", password: "wrong")
        #expect(store.user == nil)
        #expect(store.errorMessage == "Неверный логин или пароль")
    }

    @Test func emptyFieldsAreRejectedBeforeNetwork() async {
        let store = makeStore()
        await store.login(email: "", password: "")
        #expect(store.errorMessage == "Заполните обязательные поля")
    }

    @Test func registeringExistingEmailFails() async {
        let store = makeStore()
        var form = RegistrationForm()
        form.nickname = "Copy"
        form.email = "staff@pulse.app"
        form.password = "abcd1234"
        form.confirmPassword = "abcd1234"
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        await store.register(form)
        #expect(store.errorMessage == AuthError.emailTaken.message)
    }

    @Test func unknownTeamCodeIsRejected() async {
        let store = makeStore()
        var form = RegistrationForm()
        form.nickname = "Newbie"
        form.email = "newbie@team.gg"
        form.password = "abcd1234"
        form.confirmPassword = "abcd1234"
        form.accessType = .team
        form.teamRole = .player
        form.teamCode = "NOPE"
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        await store.register(form)
        #expect(store.user == nil)
        #expect(store.errorMessage == "Команда с таким кодом не найдена")

        form.teamCode = "pulse"
        await store.register(form)
        #expect(store.user?.role == .teamPlayer)
        #expect(store.user?.team == "Pulse Esports")
    }

    @Test func registrationCreatesFreeTierAccount() async {
        let store = makeStore()
        var form = RegistrationForm()
        form.nickname = "Rookie"
        form.email = "rookie@team.gg"
        form.password = "abcd1234"
        form.confirmPassword = "abcd1234"
        form.accessType = .team
        form.teamRole = .manager
        form.teamName = "Rookies"
        form.faceitURL = "https://www.faceit.com/ru/players/rookie"
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        await store.register(form)
        #expect(store.user?.role == .staff)
        #expect(store.user?.team == "Rookies")
        #expect(store.user?.faceitURL == "https://www.faceit.com/ru/players/rookie")
        #expect(store.user?.tariff == .free)
        store.logout()
        #expect(store.user == nil)
    }
}
