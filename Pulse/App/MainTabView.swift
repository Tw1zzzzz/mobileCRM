import SwiftUI

struct MainTabView: View {
    @State private var router = AppRouter()
    @State private var showQuickAdd = false
    @Environment(DeepLinkCenter.self) private var deepLinks
    @Environment(SessionStore.self) private var session
    @Environment(CalendarStore.self) private var calendar
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(SleepStore.self) private var sleep
    @Environment(FitnessStore.self) private var fitness
    @Environment(GameStore.self) private var game
    @Environment(PlayerSurveyStore.self) private var survey
    @Environment(BodyProfileStore.self) private var bodyProfile
    @Environment(\.scenePhase) private var scenePhase
    @State private var showScreenTimeReminder = false
    /// Questionnaire cover; its mode is fixed when it opens (saving the survey must not change the flow).
    @State private var showSurvey = false
    @State private var surveyMode: OnboardingFlowView.Mode = .full

    private var tabs: [AppTab] { AppTab.tabs(for: session.user?.role) }

    var body: some View {
        @Bindable var router = router
        ZStack {
            ScreenBackground()
            ForEach(tabs) { tab in
                stack(for: tab)
                    .opacity(router.selectedTab == tab ? 1 : 0)
                    .allowsHitTesting(router.selectedTab == tab)
                    .accessibilityHidden(router.selectedTab != tab)
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            PulseTabBar(tabs: tabs, selection: $router.selectedTab,
                        onPlus: AppTab.showsQuickAdd(for: session.user?.role) ? { showQuickAdd = true } : nil,
                        onReselect: { router.popToRoot($0) })
        }
        .sheet(isPresented: $showQuickAdd) {
            QuickAddSheet()
                .presentationDetents([.height(430)])
                .presentationDragIndicator(.visible)
        }
        .environment(router)
        #if DEBUG
        .onAppear {
            DebugLaunchRoute.apply(to: router, role: session.user?.role)
            ensureValidTab()
        }
        #endif
        .sheet(item: Binding(get: { deepLinks.pendingGameTest }, set: { deepLinks.pendingGameTest = $0 })) { phase in
            GameTestQuickSheet(phase: phase) {
                router.show([.tests, .gameTests], in: .state)
            }
            .presentationDetents([.large])
        }
        // First sign-in of a player: questionnaire + «Игровой характер»; later from the reminder card.
        .fullScreenCover(isPresented: $showSurvey, onDismiss: {
            if survey.needsOnboarding { survey.dismissOnboarding() }
        }) {
            OnboardingFlowView(mode: surveyMode)
        }
        .onChange(of: survey.needsOnboarding, initial: true) { _, needs in
            if needs { presentSurvey() }
        }
        // Players who answered the questionnaire before «О тебе» existed get that step alone, once.
        .onChange(of: AboutYou.needsStep(user: session.user, profile: bodyProfile.profile), initial: true) { _, needs in
            if needs && !survey.needsOnboarding { presentAboutYou() }
        }
        .onChange(of: showSurvey) { _, shown in
            if !shown && AboutYou.needsStep(user: session.user, profile: bodyProfile.profile) { presentAboutYou() }
        }
        .onChange(of: router.showsSurvey) { _, requested in
            guard requested else { return }
            router.showsSurvey = false
            presentSurvey()
        }
        .task { await onLaunch() }
        // Apple Health keeps counting while the app is closed — refresh when it comes back.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task {
                    await fitness.refreshHealth()
                    await game.refresh()
                    // No-op until the backend is configured (AppConfig.backend).
                    await AppDependencies.shared.sync.pushPending()
                }
            }
        }
        .onChange(of: session.user?.role) { _, _ in ensureValidTab() }
        .alert("Нет доступа к экранному времени", isPresented: $showScreenTimeReminder) {
            Button("Дать доступ") { router.show([.screenTime], in: .state) }
            Button("Позже", role: .cancel) {}
        } message: {
            Text("Разрешите доступ к данным устройства, чтобы Pulse показывал реальное экранное время.")
        }
        .onChange(of: deepLinks.pendingSleep) { _, pending in
            guard pending else { return }
            if !session.isStaff { router.show([.sleep], in: .state) }
            deepLinks.pendingSleep = false
        }
        .onChange(of: deepLinks.pendingWeekly) { _, pending in
            guard pending else { return }
            router.show([.weeklyReport], in: .today)
            deepLinks.pendingWeekly = false
        }
        .onChange(of: deepLinks.pendingEventID) { _, id in
            guard let id else { return }
            router.show([.calendar(createNew: false), .event(id)], in: AppTab.hub(for: session.user?.role))
            deepLinks.pendingEventID = nil
        }
    }

    private func presentSurvey() {
        guard session.user?.role.isStaff == false, !showSurvey else { return }
        surveyMode = survey.survey != nil && survey.latestResult == nil ? .testOnly : .full
        showSurvey = true
    }

    private func presentAboutYou() {
        guard session.user?.role.isStaff == false, !showSurvey else { return }
        surveyMode = .aboutOnly
        showSurvey = true
    }

    /// After a role switch (demo mode) the selected tab may not exist for the new role.
    private func ensureValidTab() {
        if !tabs.contains(router.selectedTab) { router.selectedTab = .today }
    }

    /// Players: ask for notifications once, schedule game tests around trainings,
    /// and remind about Screen Time access on every launch while it is missing (реестр рисков ТЗ).
    private func onLaunch() async {
        guard let role = session.user?.role, !role.isStaff else { return }
        SleepTrackingCoordinator.sync(store: sleep)
        await fitness.refreshHealth()
        await game.refresh()
        if await NotificationScheduler.requestAuthorization() {
            await NotificationScheduler.scheduleGameTests(for: calendar.events)
            await SleepNotifications.scheduleBedtimeReminder(window: sleep.window)
            await WeeklyNotifications.schedule()
        }
        if screenTime.isDemo && !screenTime.isAuthorized { showScreenTimeReminder = true }
    }

    private func stack(for tab: AppTab) -> some View {
        NavigationStack(path: Binding(get: { router.path(for: tab) }, set: { router.setPath($0, for: tab) })) {
            root(for: tab).withRoutes()
        }
    }

    @ViewBuilder
    private func root(for tab: AppTab) -> some View {
        let isStaff = session.isStaff
        switch tab {
        case .today: if isStaff { StaffTodayView() } else { TodayView() }
        case .state: StateHubView()
        case .diary: LineupsView(showsBack: false)
        case .profile: ProfileView()
        case .players: StaffPlayersTab()
        case .analytics: StaffAnalyticsTab()
        case .lineups: LineupsView(showsBack: false)
        }
    }
}

private extension View {
    func withRoutes() -> some View {
        navigationDestination(for: Route.self) { RouteDestination(route: $0) }
    }
}

/// Custom bottom bar from the mockups: 4 tabs + elevated gradient "+" sitting in a raised hump.
/// Without `onPlus` (staff) it is a flat bar with equal tabs.
struct PulseTabBar: View {
    let tabs: [AppTab]
    @Binding var selection: AppTab
    let onPlus: (() -> Void)?
    /// Tapping the tab that is already open returns it to its first screen (standard iOS behaviour).
    var onReselect: (AppTab) -> Void = { _ in }

    private var humpHeight: CGFloat { onPlus == nil ? 0 : 14 }

    var body: some View {
        HStack(alignment: .bottom, spacing: 0) {
            if let onPlus {
                ForEach(tabs.prefix(2)) { item($0) }
                plusButton(onPlus)
                ForEach(tabs.dropFirst(2)) { item($0) }
            } else {
                ForEach(tabs) { item($0) }
            }
        }
        .padding(.horizontal, 6)
        .padding(.top, 12)
        .padding(.bottom, 2)
        .background(alignment: .top) {
            ZStack(alignment: .top) {
                TabBarShape(humpHeight: humpHeight).fill(Color(hex: 0x060B18))
                TabBarShape(humpHeight: humpHeight, closed: false)
                    .stroke(Theme.Palette.divider, lineWidth: 1)
            }
            .padding(.top, -humpHeight)
            .ignoresSafeArea(edges: .bottom)
        }
    }

    private func item(_ tab: AppTab) -> some View {
        let isSelected = selection == tab
        return Button {
            if isSelected { onReselect(tab) } else { selection = tab }
        } label: {
            VStack(spacing: 6) {
                Image(systemName: tab.icon)
                    .font(.system(size: 24, weight: isSelected ? .semibold : .regular))
                    .symbolRenderingMode(.monochrome)
                    .frame(height: 30)
                Text(tab.title)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(isSelected ? Theme.Palette.cyan : Theme.Palette.textSecondary)
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func plusButton(_ onPlus: @escaping () -> Void) -> some View {
        Button(action: onPlus) {
            Image(systemName: "plus")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 62, height: 62)
                .background(Circle().fill(Theme.Gradients.accentDiagonal))
                .shadow(color: Theme.Palette.cyan.opacity(0.45), radius: 14, y: 4)
        }
        .buttonStyle(.pressable)
        .frame(maxWidth: .infinity)
        .offset(y: -8)
        .accessibilityLabel("Быстрое добавление")
    }
}

/// Bar outline with a smooth raised hump in the middle (behind the "+" button).
struct TabBarShape: Shape {
    var humpHeight: CGFloat
    var humpHalfWidth: CGFloat = 62
    var closed = true

    func path(in rect: CGRect) -> Path {
        let midX = rect.midX
        let base = rect.minY + humpHeight
        var path = Path()
        path.move(to: CGPoint(x: rect.minX, y: base))
        path.addLine(to: CGPoint(x: midX - humpHalfWidth, y: base))
        path.addCurve(
            to: CGPoint(x: midX, y: rect.minY),
            control1: CGPoint(x: midX - humpHalfWidth * 0.55, y: base),
            control2: CGPoint(x: midX - humpHalfWidth * 0.5, y: rect.minY)
        )
        path.addCurve(
            to: CGPoint(x: midX + humpHalfWidth, y: base),
            control1: CGPoint(x: midX + humpHalfWidth * 0.5, y: rect.minY),
            control2: CGPoint(x: midX + humpHalfWidth * 0.55, y: base)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: base))
        if closed {
            path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            path.closeSubpath()
        }
        return path
    }
}

#if DEBUG
/// `-PulseRoute <name>` launch argument opens a screen directly (used for UI review).
enum DebugLaunchRoute {
    static func apply(to router: AppRouter, role: UserRole?) {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-PulseRoute"), index + 1 < args.count else { return }
        let routes: [String: (AppTab, [Route])] = [
            "sleep": (.state, [.sleep]), "recovery": (.state, [.sleepRecovery]),
            "fitness": (.state, [.fitness]), "fitnessWeek": (.state, [.fitnessWeek]),
            "screen": (.state, [.screenTime]), "screenApps": (.state, [.screenTimeApps]),
            "nutrition": (.state, [.nutrition]), "tests": (.state, [.tests]), "cognitive": (.state, [.cognitive]),
            "mood": (.state, [.mood]), "balance": (.state, [.balance]), "stats": (.state, [.statistics]),
            "calendar": (.state, [.calendar(createNew: false)]),
            "profile": (.profile, []), "gameTests": (.state, [.gameTests]), "nutritionHistory": (.state, [.nutrition, .nutritionHistory]),
            "lineups": (.diary, []), "teamAnalytics": (.analytics, []),
            "teamCorrelations": (.analytics, []), "gameStats": (.state, [.gameStats]), "teamFood": (.players, []),
            "players": (.players, []), "analytics": (.analytics, []), "today": (.today, []),
            "player": (.players, [.player(DemoData.players[2].id)]), "state": (.state, []),
            "weekly": (.today, [.weeklyReport]),
            "portrait": (.profile, [.playerPortrait])
        ]
        guard let target = routes[args[index + 1]] else { return }
        let tab = target.0 == .state ? AppTab.hub(for: role) : target.0
        router.show(target.1, in: tab)
    }
}
#endif
