import SwiftUI
import UserNotifications

@main
struct PulseApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            RootView()
                .injectDependencies(AppDependencies.shared)
                .preferredColorScheme(.dark)
                .tint(Theme.Palette.cyan)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        SleepNotifications.registerCategories()
        return true
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    /// Tapping an event reminder opens Календарь → экран события; sleep pushes open «Сон».
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo
        // «Начать сон» / «Я проснулся» right from the notification, without opening the app.
        switch response.actionIdentifier {
        case SleepNotifications.startAction:
            await MainActor.run { AppDependencies.shared.sleep.startSleep() }
            return
        case SleepNotifications.stopAction:
            await MainActor.run { _ = AppDependencies.shared.sleep.stopSleep() }
            return
        default:
            break
        }
        if info[SleepNotifications.routeKey] as? Bool == true {
            await MainActor.run { AppDependencies.shared.deepLinks.pendingSleep = true }
            return
        }
        if info[WeeklyNotifications.routeKey] as? Bool == true {
            await MainActor.run { AppDependencies.shared.deepLinks.pendingWeekly = true }
            return
        }
        if let rawPhase = info[NotificationScheduler.gameTestPhaseKey] as? String,
           let phase = GameTestPhase(rawValue: rawPhase) {
            await MainActor.run { AppDependencies.shared.deepLinks.pendingGameTest = phase }
            return
        }
        guard let raw = info[NotificationScheduler.eventIDKey] as? String, let id = UUID(uuidString: raw) else { return }
        await MainActor.run {
            AppDependencies.shared.deepLinks.pendingEventID = id
        }
    }
}

struct RootView: View {
    @Environment(SessionStore.self) private var session

    var body: some View {
        Group {
            if session.user != nil {
                MainTabView()
                    .transition(.opacity)
            } else {
                AuthFlowView()
                    .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: session.user?.id)
        #if DEBUG
        .task { await DebugLaunchRole.apply(to: session) }
        #endif
    }
}

#if DEBUG
/// `-PulseDemoRole staff|player|solo` signs in the matching demo account on launch (UI review only).
enum DebugLaunchRole {
    @MainActor
    static func apply(to session: SessionStore) async {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-PulseDemoRole"), index + 1 < args.count else { return }
        if args[index + 1] == "fresh" {
            await signInFreshLocalAccount(session)
            return
        }
        let roles: [String: UserRole] = ["staff": .staff, "player": .teamPlayer, "solo": .soloPlayer]
        guard let role = roles[args[index + 1]], session.user?.role != role else { return }
        await session.switchToDemo(role: role)
    }

    /// `-PulseDemoRole fresh`: a brand-new local account (random credentials) to review a first launch.
    @MainActor
    private static func signInFreshLocalAccount(_ session: SessionStore) async {
        if session.user?.isDemo == false, session.user?.email.hasSuffix("@pulse.local") == true { return }
        var form = RegistrationForm()
        form.nickname = "Тестер"
        form.email = "review-\(UUID().uuidString.prefix(8).lowercased())@pulse.local"
        form.password = "A1" + UUID().uuidString
        form.confirmPassword = form.password
        form.acceptedTerms = true
        form.acceptedPrivacy = true
        if session.user != nil { session.logout() }
        await session.register(form)
    }
}
#endif
