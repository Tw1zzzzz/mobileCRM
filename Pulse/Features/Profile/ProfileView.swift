import SwiftUI
import UserNotifications

struct ProfileView: View {
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @Environment(SleepStore.self) private var sleep
    @Environment(CognitiveStore.self) private var cognitive
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(LineupStore.self) private var lineups
    @Environment(CalendarStore.self) private var calendar

    @State private var notificationsEnabled = false
    @State private var confirmLogout = false
    @State private var showEdit = false
    @State private var showGameGoals = false
    @State private var showsAboutYou = false
    @State private var confirmDelete = false
    var streak = StreakSource()
    @State private var legalDocument: LegalDocument?

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Профиль", showsBack: false) {
                HeaderIconButton(systemName: "pencil") { showEdit = true }
                    .accessibilityLabel("Редактировать профиль")
            }
        } content: {
            if let user = session.user {
                header(user)
                statusCard(user)
                statsRow(user)
                if !user.role.isStaff {
                    PlayerPortraitSection()
                    PulseSection(title: "Игровые цели", actionTitle: "Изменить") {
                        showGameGoals = true
                    } content: {
                        GameGoalsCard { showGameGoals = true }
                    }
                    GoalsSection()
                }
                PulseSection(title: "Настройки") { settingsCard }
                if user.isDemo {
                    DemoModeSection()
                } else {
                    PulseSection(title: "Данные и конфиденциальность") { privacyCard }
                }
                SecondaryButton(title: "Выйти из аккаунта", systemImage: "rectangle.portrait.and.arrow.right", tint: Theme.Palette.red) {
                    confirmLogout = true
                }
                Text("Pulse MVP · версия \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "")")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.textTertiary)
                    .frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: $showEdit) { EditProfileSheet().presentationDetents([.large]) }
        .sheet(isPresented: $showGameGoals) { GameGoalsSheet().presentationDetents([.large]) }
        .sheet(isPresented: $showsAboutYou) { AboutYouSheet().presentationDetents([.large]) }
        .task {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            notificationsEnabled = settings.authorizationStatus == .authorized
        }
        .confirmationDialog("Выйти из аккаунта?", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("Выйти", role: .destructive) { session.logout() }
        }
        .confirmationDialog("Удалить аккаунт и все данные?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Удалить безвозвратно", role: .destructive) { session.deleteAccount() }
        } message: {
            Text("Сон, питание, тесты, тренировки, дневник и цели будут удалены с этого iPhone. Отменить нельзя.")
        }
        .sheet(item: $legalDocument) { LegalDocumentSheet(document: $0).presentationDetents([.large]) }
    }

    private func header(_ user: User) -> some View {
        HStack(spacing: 18) {
            Text(user.initials)
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 80, height: 80)
                .background(Circle().fill(Theme.Gradients.accentDiagonal))
                .shadow(color: Theme.Palette.cyan.opacity(0.4), radius: 14)
            VStack(alignment: .leading, spacing: 6) {
                Text(user.nickname).font(.system(size: 26, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(user.email).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                HStack(spacing: 8) {
                    RoleBadge(role: user.role, compact: true)
                    tag(user.tariff.title, tint: user.tariff.hasSubscription ? Theme.Palette.green : Theme.Palette.textSecondary)
                }
            }
            Spacer(minLength: 0)
        }
        .pulseCard(padding: 20)
    }

    private func tag(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(tint)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Capsule().fill(tint.opacity(0.14)))
    }

    /// Статус профиля: тип аккаунта, команда, доступные разделы, Faceit.
    private func statusCard(_ user: User) -> some View {
        VStack(spacing: 0) {
            infoRow("Статус профиля", value: user.role.title, icon: user.role.badgeIcon)
            DividerLine()
            infoRow("Команда", value: user.team ?? "Без команды (Solo)", icon: "person.3")
            DividerLine()
            infoRow("Версия приложения", value: user.role.isStaff ? "Staff: команда и аналитика" : "Игрок: трекинг и тесты",
                    icon: "square.grid.2x2")
            if let faceit = user.faceitURL, let url = URL(string: faceit) {
                DividerLine()
                Link(destination: url) {
                    HStack {
                        infoRow("Faceit", value: "Открыть профиль", icon: "link")
                        Image(systemName: "arrow.up.right").foregroundStyle(Theme.Palette.cyan)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func infoRow(_ title: String, value: String, icon: String) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon).frame(width: 22).foregroundStyle(Theme.Palette.cyan)
            Text(title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
            Spacer(minLength: 8)
            Text(value).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .minimumScaleFactor(0.8)
        }
        .padding(.vertical, 14)
    }

    @ViewBuilder
    private func statsRow(_ user: User) -> some View {
        HStack(spacing: 12) {
            if user.role.isStaff {
                stat(value: "\(DemoData.players.count)", title: "игроков", icon: "person.3.fill", tint: Theme.Palette.cyan)
                stat(value: "\(calendar.events.count)", title: "событий", icon: "calendar", tint: Theme.Palette.orange)
                stat(value: "\(LineupStore.visible(lineups.lineups, for: user).count)", title: "раскидок", icon: "scope", tint: Theme.Palette.green)
            } else {
                stat(value: "\(streak.days)", title: "дней подряд", icon: "flame.fill", tint: Theme.Palette.orange)
                stat(value: "\(cognitive.results.count)", title: "тестов", icon: "brain.head.profile", tint: Theme.Palette.green)
                stat(value: "\(sleep.sessions.count)", title: "ночей", icon: "moon.fill", tint: Theme.Palette.purple)
            }
        }
    }

    private func stat(value: String, title: String, icon: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).foregroundStyle(tint)
            Text(value).font(.system(size: 26, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            Text(title).font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }

    private var settingsCard: some View {
        @Bindable var screenTime = screenTime
        return VStack(spacing: 0) {
            Toggle(isOn: $notificationsEnabled) { settingLabel("Напоминания о событиях", icon: "bell", tint: Theme.Palette.orange) }
                .tint(Theme.Palette.blue)
                .padding(.vertical, 12)
                .onChange(of: notificationsEnabled) { _, enabled in
                    guard enabled else { return }
                    Task { notificationsEnabled = await NotificationScheduler.requestAuthorization() }
                }
            DividerLine()
            if !session.isStaff && screenTime.isDemo {
                Toggle(isOn: $screenTime.isAuthorized) { settingLabel("Доступ к экранному времени", icon: "hourglass", tint: Theme.Palette.cyan) }
                    .tint(Theme.Palette.blue)
                    .padding(.vertical, 12)
                DividerLine()
            }
            if !session.isStaff {
                Button { showsAboutYou = true } label: {
                    HStack {
                        settingLabel(AboutYouCopy.title, icon: "person.text.rectangle", tint: Theme.Palette.cyan)
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(Theme.Palette.textTertiary)
                    }
                    .contentShape(Rectangle()) // the whole row is tappable, not only its text
                }
                .buttonStyle(.plain)
                .padding(.vertical, 14)
                DividerLine()
            }
            Button { router.open(.calendar(createNew: false), in: .profile) } label: {
                HStack {
                    settingLabel("Календарь", icon: "calendar", tint: Theme.Palette.purple)
                    Spacer()
                    Image(systemName: "chevron.right").foregroundStyle(Theme.Palette.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.vertical, 14)
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private var privacyCard: some View {
        VStack(spacing: 0) {
            Label("Все данные хранятся только на этом iPhone", systemImage: "lock.iphone")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
            DividerLine()
            legalRow(.privacy)
            DividerLine()
            legalRow(.terms)
            DividerLine()
            Button { confirmDelete = true } label: {
                HStack {
                    settingLabel("Удалить аккаунт", icon: "trash", tint: Theme.Palette.red)
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            .padding(.vertical, 14)
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func legalRow(_ document: LegalDocument) -> some View {
        Button { legalDocument = document } label: {
            HStack {
                settingLabel(document.title, icon: document == .privacy ? "hand.raised" : "doc.text", tint: Theme.Palette.blue)
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .buttonStyle(.plain)
        .padding(.vertical, 14)
    }

    private func settingLabel(_ title: String, icon: String, tint: Color) -> some View {
        HStack(spacing: 14) {
            IconBadge(systemName: icon, tint: tint, size: 36, iconSize: 16)
            Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary)
        }
    }
}
