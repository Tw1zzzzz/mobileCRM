import Foundation

enum UserRole: String, Codable, CaseIterable, Identifiable {
    case soloPlayer
    case teamPlayer
    case staff

    var id: String { rawValue }

    var title: String {
        switch self {
        case .soloPlayer: "Solo игрок"
        case .teamPlayer: "Командный игрок"
        case .staff: "Staff"
        }
    }

    var isStaff: Bool { self == .staff }

    /// Короткий статус профиля для бейджа: «Игрок» / «Solo» / «Стафф».
    var badgeTitle: String {
        switch self {
        case .soloPlayer: "Solo"
        case .teamPlayer: "Игрок"
        case .staff: "Стафф"
        }
    }

    var badgeIcon: String {
        switch self {
        case .soloPlayer: "person"
        case .teamPlayer: "gamecontroller"
        case .staff: "person.badge.shield.checkmark"
        }
    }
}

enum Tariff: String, Codable {
    case free
    case pro

    var title: String {
        switch self {
        case .free: "FREE"
        case .pro: "PRO"
        }
    }

    var hasSubscription: Bool { self == .pro }
}

struct User: Codable, Equatable, Identifiable {
    let id: UUID
    let nickname: String
    let email: String
    let role: UserRole
    let tariff: Tariff
    let team: String?
    let streakDays: Int
    var faceitURL: String? = nil

    var initials: String {
        String(nickname.prefix(2)).uppercased()
    }

    /// Demo accounts (button «Посмотреть демо») get seeded data in their own folder.
    var isDemo: Bool { DemoAccounts.emails.contains(email) }

    func with(nickname: String? = nil, faceitURL: String?? = nil, streakDays: Int? = nil) -> User {
        User(id: id, nickname: nickname ?? self.nickname, email: email, role: role, tariff: tariff, team: team,
             streakDays: streakDays ?? self.streakDays, faceitURL: faceitURL ?? self.faceitURL)
    }
}

enum DemoAccounts {
    static let emails: Set<String> = ["player@pulse.app", "solo@pulse.app", "staff@pulse.app"]
}

enum AccessType: String, CaseIterable, Identifiable {
    case solo
    case team

    var id: String { rawValue }

    var title: String {
        switch self {
        case .solo: "Соло"
        case .team: "Командный доступ"
        }
    }
}

enum TeamRole: String, CaseIterable, Identifiable {
    case player
    case manager

    var id: String { rawValue }

    var title: String {
        switch self {
        case .player: "Я игрок"
        case .manager: "Я стафф"
        }
    }
}

struct RegistrationForm: Equatable {
    var nickname = ""
    var email = ""
    var password = ""
    var confirmPassword = ""
    var faceitURL = ""
    var teamCode = ""
    var teamName = ""
    var accessType: AccessType = .solo
    var teamRole: TeamRole = .player
    var acceptedTerms = false
    var acceptedPrivacy = false

    var resolvedRole: UserRole {
        switch accessType {
        case .solo: .soloPlayer
        case .team: teamRole == .manager ? .staff : .teamPlayer
        }
    }
}

extension RegistrationForm {
    var isTeamPlayer: Bool { accessType == .team && teamRole == .player }
    var isTeamStaff: Bool { accessType == .team && teamRole == .manager }

    /// The visible fields in screen order: the keyboard's «Далее» walks them, the last one closes the keyboard.
    var fields: [AuthFieldID] {
        [.name, .email, .password, .confirm, .faceit] + (isTeamPlayer ? [.teamCode] : []) + (isTeamStaff ? [.teamName] : [])
    }

    /// The field «Далее» moves to after `field`; nil after the last one.
    func field(after field: AuthFieldID) -> AuthFieldID? {
        guard let index = fields.firstIndex(of: field), index + 1 < fields.count else { return nil }
        return fields[index + 1]
    }
}

enum AuthFieldID: Hashable { case name, email, password, confirm, faceit, teamCode, teamName }
