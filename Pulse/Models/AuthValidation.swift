import Foundation

/// Errors and user-facing messages exactly as defined in the spec (раздел «Авторизация»).
enum AuthError: Error, Equatable {
    case emptyFields
    case invalidCredentials
    case server
    case noConnection
    case invalidEmail
    case emailTaken
    case passwordMismatch
    case weakPassword
    case termsNotAccepted
    case privacyNotAccepted
    case registrationServer
    case invalidFaceit
    case teamNotFound
    case staffNeedsServer

    var message: String {
        switch self {
        case .emptyFields: "Заполните обязательные поля"
        case .invalidCredentials: "Неверный логин или пароль"
        case .server: "Не удалось выполнить вход. Повторите попытку позже"
        case .noConnection: "Не удалось установить соединение с сервером"
        case .invalidEmail: "Укажите корректный адрес электронной почты"
        case .emailTaken: "Пользователь с таким адресом электронной почты уже существует"
        case .passwordMismatch: "Введенные пароли не совпадают"
        case .weakPassword: "Пароль должен содержать не менее 8 символов, включая буквы и цифры"
        case .termsNotAccepted: "Для продолжения необходимо принять условия использования"
        case .privacyNotAccepted: "Для продолжения необходимо принять политику конфиденциальности"
        case .registrationServer: "Не удалось выполнить регистрацию. Повторите попытку позже"
        case .invalidFaceit: "Укажите корректную ссылку на профиль Faceit"
        case .teamNotFound: "Команда с таким кодом не найдена"
        case .staffNeedsServer: "Доступ стаффа откроется вместе с сервером команды. Сейчас его можно посмотреть в демо."
        }
    }
}

enum AuthValidator {
    static func validateLogin(email: String, password: String) -> AuthError? {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || password.isEmpty { return .emptyFields }
        return nil
    }

    static func validateRegistration(_ form: RegistrationForm) -> AuthError? {
        let nickname = form.nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        let email = form.email.trimmingCharacters(in: .whitespacesAndNewlines)
        if nickname.isEmpty || email.isEmpty || form.password.isEmpty || form.confirmPassword.isEmpty {
            return .emptyFields
        }
        if form.isTeamPlayer && form.teamCode.trimmingCharacters(in: .whitespaces).isEmpty { return .emptyFields }
        if form.isTeamStaff && form.teamName.trimmingCharacters(in: .whitespaces).isEmpty { return .emptyFields }
        if !isValidEmail(email) { return .invalidEmail }
        if !isStrongPassword(form.password) { return .weakPassword }
        if form.password != form.confirmPassword { return .passwordMismatch }
        let faceit = form.faceitURL.trimmingCharacters(in: .whitespaces)
        if !faceit.isEmpty && !isValidFaceitURL(faceit) { return .invalidFaceit }
        if !form.acceptedTerms { return .termsNotAccepted }
        if !form.acceptedPrivacy { return .privacyNotAccepted }
        return nil
    }

    static func isValidEmail(_ email: String) -> Bool {
        let pattern = #"^[A-Z0-9a-z._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$"#
        return email.range(of: pattern, options: .regularExpression) != nil
    }

    /// Profile link on faceit.com (http/https), e.g. https://www.faceit.com/ru/players/nick.
    static func isValidFaceitURL(_ text: String) -> Bool {
        guard let url = URL(string: text), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https", let host = url.host?.lowercased() else { return false }
        return host == "faceit.com" || host.hasSuffix(".faceit.com")
    }

    /// At least 8 characters with both letters and digits.
    static func isStrongPassword(_ password: String) -> Bool {
        guard password.count >= 8 else { return false }
        let hasLetter = password.contains { $0.isLetter }
        let hasDigit = password.contains { $0.isNumber }
        return hasLetter && hasDigit
    }
}
