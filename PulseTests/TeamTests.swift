import Foundation
import Testing
@testable import Pulse

struct LineupStoreTests {
    private let staff = User(id: UUID(), nickname: "Coach", email: "c@x.gg", role: .staff, tariff: .pro, team: "T", streakDays: 0)
    private let player = User(id: UUID(), nickname: "Nova", email: "n@x.gg", role: .teamPlayer, tariff: .pro, team: "T", streakDays: 0)

    private func lineup(_ title: String, map: CSMap, type: LineupType, author: String) -> Lineup {
        Lineup(id: UUID(), title: title, map: map, side: .t, type: type, details: "", videoURL: nil, author: author, createdAt: .now)
    }

    @Test func filtersByMapAndType() {
        let store = LineupStore(storage: nil, seed: [
            lineup("A", map: .mirage, type: .smoke, author: "Coach"),
            lineup("B", map: .mirage, type: .flash, author: "Nova"),
            lineup("C", map: .inferno, type: .smoke, author: "Coach")
        ])
        #expect(store.filtered(map: nil, type: nil).count == 3)
        #expect(store.filtered(map: .mirage, type: nil).count == 2)
        #expect(store.filtered(map: .mirage, type: .smoke).map(\.title) == ["A"])
    }

    @Test func upsertAndDelete() throws {
        let item = lineup("A", map: .nuke, type: .script, author: "Coach")
        let store = LineupStore(storage: nil, seed: [item])
        store.upsert(Lineup(id: item.id, title: "A2", map: .nuke, side: .ct, type: .script, details: "1. …",
                            videoURL: nil, author: "Coach", createdAt: item.createdAt))
        #expect(store.lineups.count == 1)
        #expect(store.lineup(id: item.id)?.title == "A2")
        store.delete(id: item.id)
        #expect(store.lineups.isEmpty)
    }

    @Test func editPermissions() {
        let own = lineup("Mine", map: .mirage, type: .smoke, author: "Nova")
        let foreign = lineup("Coach's", map: .mirage, type: .smoke, author: "Coach")
        #expect(LineupStore.canEdit(own, user: player))
        #expect(!LineupStore.canEdit(foreign, user: player))
        #expect(LineupStore.canEdit(foreign, user: staff))
        #expect(!LineupStore.canEdit(own, user: nil))
    }

    @Test func validation() {
        #expect(LineupValidator.validate(title: " ", videoURL: "") == "Заполните обязательные поля")
        #expect(LineupValidator.validate(title: "Смок", videoURL: "not a link") != nil)
        #expect(LineupValidator.validate(title: "Смок", videoURL: "https://youtu.be/x") == nil)
        #expect(LineupValidator.validate(title: "Смок", videoURL: "") == nil)
    }
}

struct RoleSeparationTests {
    @Test func staffAndPlayersGetDifferentTabs() {
        #expect(AppTab.tabs(for: .staff) == [.today, .players, .analytics, .lineups, .profile])
        #expect(AppTab.tabs(for: .teamPlayer) == [.today, .state, .diary, .profile])
        #expect(AppTab.tabs(for: .soloPlayer) == [.today, .state, .diary, .profile])
        #expect(AppTab.hub(for: .staff) == .today)
        #expect(AppTab.hub(for: .soloPlayer) == .state)
        #expect(!AppTab.showsQuickAdd(for: .staff))
        #expect(AppTab.showsQuickAdd(for: .teamPlayer))
        #expect(AppTab.showsQuickAdd(for: .soloPlayer))
    }

    @Test func roleBadges() {
        #expect(UserRole.staff.badgeTitle == "Стафф")
        #expect(UserRole.teamPlayer.badgeTitle == "Игрок")
        #expect(UserRole.soloPlayer.badgeTitle == "Solo")
    }

    @Test func teamAnalyticsCoverEveryPlayer() {
        let snapshots = TeamAnalytics.snapshots()
        #expect(snapshots.count == DemoData.players.count)
        #expect(snapshots.allSatisfy { (0...10).contains($0.readiness) })
        #expect(TeamAnalytics.teamCorrelations().allSatisfy { (-1...1).contains($0.value) })
    }

    @Test func routerKeepsSeparateStacksPerTab() {
        let router = AppRouter()
        router.open(.players, in: .players)
        router.open(.lineups, in: .lineups)
        router.show([.teamAnalytics], in: .players)
        #expect(router.path(for: .players) == [.teamAnalytics])
        #expect(router.path(for: .lineups) == [.lineups])
        #expect(router.selectedTab == .players)
    }
}

struct TabReselectTests {
    @Test func secondTapReturnsTabToItsRoot() {
        let router = AppRouter()
        router.show([.sleep, .sleepRecovery], in: .state)
        router.popToRoot(.state)
        #expect(router.path(for: .state).isEmpty)
        #expect(router.selectedTab == .state)
        router.popToRoot(.today)
        #expect(router.path(for: .today).isEmpty)
    }
}
