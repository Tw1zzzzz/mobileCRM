import Foundation
import Testing
@testable import Pulse

private final class MemorySecretStore: SecretStore {
    var values: [String: String] = [:]
    func read(_ key: String) -> String? { values[key] }
    func write(_ value: String?, for key: String) { values[key] = value }
}

private func tempFolder() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("pulse-tests-\(UUID().uuidString)")
}

struct LocalAuthTests {
    private func service(_ folder: URL, _ secrets: MemorySecretStore) -> LocalAuthService {
        LocalAuthService(storage: JSONFileStorage(filename: "accounts.json", directory: folder), secrets: secrets)
    }

    private func form(email: String = "nova@team.gg", password: String = "secret123", access: AccessType = .solo,
                      role: TeamRole = .player) -> RegistrationForm {
        var form = RegistrationForm()
        form.nickname = "Nova"
        form.email = email
        form.password = password
        form.confirmPassword = password
        form.accessType = access
        form.teamRole = role
        form.teamCode = "abc12"
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        return form
    }

    @Test func registerThenLogin() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let secrets = MemorySecretStore()
        let auth = service(folder, secrets)
        let user = try await auth.register(form(email: "  Nova@Team.GG "))
        #expect(user.email == "nova@team.gg")
        #expect(!user.isDemo)
        // The password itself is never stored, only salt + hash.
        #expect(!(secrets.values.values.contains { $0.contains("secret123") }))
        let again = try await service(folder, secrets).login(email: "NOVA@team.gg", password: "secret123")
        #expect(again.id == user.id)
    }

    @Test func wrongPasswordAndUnknownEmailFail() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let auth = service(folder, MemorySecretStore())
        _ = try await auth.register(form())
        await #expect(throws: AuthError.invalidCredentials) { try await auth.login(email: "nova@team.gg", password: "wrong1234") }
        await #expect(throws: AuthError.invalidCredentials) { try await auth.login(email: "ghost@team.gg", password: "secret123") }
    }

    @Test func duplicateDemoAndStaffAreRejected() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let auth = service(folder, MemorySecretStore())
        _ = try await auth.register(form())
        await #expect(throws: AuthError.emailTaken) { try await auth.register(form()) }
        await #expect(throws: AuthError.emailTaken) { try await auth.register(form(email: "player@pulse.app")) }
        await #expect(throws: AuthError.staffNeedsServer) {
            try await auth.register(form(email: "coach@team.gg", access: .team, role: .manager))
        }
    }

    @Test func teamPlayerKeepsTheCode() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let user = try await service(folder, MemorySecretStore()).register(form(access: .team))
        #expect(user.role == .teamPlayer)
        #expect(user.team == "Команда ABC12")
    }

    @Test func demoLoginStillWorks() async throws {
        let auth = service(tempFolder(), MemorySecretStore())
        let demo = try await auth.login(email: "solo@pulse.app", password: MockAuthService.demoPassword)
        #expect(demo.isDemo)
    }

    @Test func deleteAccountForgetsCredentials() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let secrets = MemorySecretStore()
        let auth = service(folder, secrets)
        let user = try await auth.register(form())
        auth.deleteAccount(user)
        #expect(secrets.values.isEmpty)
        await #expect(throws: AuthError.invalidCredentials) { try await auth.login(email: "nova@team.gg", password: "secret123") }
        // The e-mail is free again.
        _ = try await auth.register(form())
    }

    @Test func profileEditsAreKept() async throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let secrets = MemorySecretStore()
        let auth = service(folder, secrets)
        let user = try await auth.register(form())
        auth.updateProfile(user.with(nickname: "Nova2"))
        #expect(try await auth.login(email: "nova@team.gg", password: "secret123").nickname == "Nova2")
    }
}

struct DataScopeTests {
    @Test func demoAndRealAccountsUseDifferentFolders() {
        let real = User(id: UUID(), nickname: "A", email: "a@b.cc", role: .soloPlayer, tariff: .free, team: nil, streakDays: 0)
        let demo = MockAuthService.demoAccounts[0].user
        #expect(DataScope.id(for: real) == real.id.uuidString)
        #expect(DataScope.id(for: demo) == "demo-teamPlayer")
        #expect(DataScope.folder(for: "x") != DataScope.folder(for: "y"))
    }

    @Test func realAccountsStartEmptyDemoAccountsSeeded() {
        let sleep = SleepStore(storage: nil, seed: [])
        sleep.reload(isDemo: false)
        #expect(sleep.sessions.isEmpty)
        #expect(sleep.displayNight == nil)
        sleep.reload(isDemo: true)
        #expect(!sleep.sessions.isEmpty)
        #expect(sleep.displayNight != nil)

        let fitness = FitnessStore(storage: nil, healthService: nil, isDemo: false)
        #expect(fitness.steps == 0)
        #expect(fitness.todayWorkouts.isEmpty)
        #expect(fitness.heartRateZones == nil)
        #expect(fitness.loadWorkouts.isEmpty)

        let game = GameStore(storage: nil, secrets: MemorySecretStore())
        game.reload(isDemo: false)
        #expect(!game.hasData)
        #expect(game.matches(for: nil).isEmpty)
        #expect(game.eloHistory(for: nil, days: 7).isEmpty)

        let nutrition = NutritionStore(storage: nil)
        nutrition.reload(isDemo: false)
        #expect(nutrition.meals.isEmpty)
        #expect(!nutrition.canAnalyzePhotos)

        let goals = GameGoalsStore(storage: nil)
        goals.reload(isDemo: false)
        #expect(goals.goals.isEmpty)
    }

    @Test func screenTimeManualEntries() {
        let store = ScreenTimeStore(storage: nil, isDemo: false)
        #expect(store.todayMinutes == 0)
        #expect(!store.hasDetailedData)
        #expect(store.showsContent)
        store.setMinutes(185, on: .now)
        store.setMinutes(200, on: .now) // replaces, doesn't duplicate
        store.setMinutes(240, on: Date.now.adding(days: -1))
        #expect(store.todayMinutes == 200)
        #expect(store.entries.count == 2)
        #expect(store.week.last?.minutes == 200)
        #expect(store.daily(lastDays: 7).count == 2)
        #expect(store.apps.isEmpty && store.hourly.isEmpty)
    }
}

struct SyncLedgerTests {
    @Test func changesStayPendingUntilSynced() throws {
        let folder = tempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let start = Date(timeIntervalSince1970: 1_000_000)
        SyncLedger.markChanged("sleep.json", at: start, in: folder)
        SyncLedger.markChanged("nutrition.json", at: start, in: folder)
        #expect(SyncLedger.pending(in: folder) == ["nutrition.json", "sleep.json"])
        SyncLedger.markSynced("sleep.json", at: start.addingTimeInterval(10), in: folder)
        #expect(SyncLedger.pending(in: folder) == ["nutrition.json"])
        SyncLedger.markChanged("sleep.json", at: start.addingTimeInterval(20), in: folder)
        #expect(SyncLedger.pending(in: folder) == ["nutrition.json", "sleep.json"])
    }
}

struct InboxAndStreakTests {
    @Test func derivedRemindersFollowTheDay() {
        let morning = Date.now.startOfDay.setting(hour: 10, minute: 0)
        let event = CalendarEvent(id: UUID(), title: "Тренировка", details: "", start: morning.adding(minutes: 90),
                                  end: morning.adding(minutes: 180), links: [], tag: .personal, ownerId: nil)
        let items = NotificationInbox.derived(.init(isDemo: false, now: morning, events: [event], batteryDone: false,
                                                    moodSlotsFilled: 0, sleptLastNight: false, isSleeping: false))
        #expect(items.map(\.title).contains("Тренировка"))
        #expect(items.contains { $0.id.hasPrefix("battery-") })
        #expect(items.contains { $0.id.hasPrefix("mood-") })
        #expect(items.contains { $0.id.hasPrefix("sleep-") })
        let done = NotificationInbox.derived(.init(isDemo: false, now: morning, events: [], batteryDone: true,
                                                   moodSlotsFilled: 1, sleptLastNight: true, isSleeping: false))
        #expect(done.isEmpty)
        #expect(NotificationInbox.items(for: .init(isDemo: true)) == NotificationInbox.items)
    }

    @Test func streakCountsConsecutiveDays() {
        let today = Date.now.startOfDay
        let days: Set<Date> = [today, today.adding(days: -1), today.adding(days: -2), today.adding(days: -4)]
        #expect(ActivityStreak.days(activeDays: days, today: today) == 3)
        // Today not logged yet: yesterday's streak still counts.
        #expect(ActivityStreak.days(activeDays: [today.adding(days: -1), today.adding(days: -2)], today: today) == 2)
        #expect(ActivityStreak.days(activeDays: [], today: today) == 0)
    }
}
