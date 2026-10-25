import SwiftUI

/// Вход и регистрация. Дизайн — десктоп-макеты «Регистрация» (Соло / Командный доступ → Я игрок / Я стафф),
/// адаптированные под телефон: градиентный хиро-блок сверху и карточка формы.
struct AuthFlowView: View {
    enum Mode { case login, register }

    @State private var mode: Mode = .login
    @State private var legalDocument: LegalDocument?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 26) {
                hero
                Group {
                    switch mode {
                    case .login: LoginCard { switchMode(.register) }
                    case .register: RegistrationCard { switchMode(.login) }
                    }
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 28)
                .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(AuthStyle.card))
                .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
            }
            .padding(.horizontal, 16)
            .padding(.top, 24)
            .padding(.bottom, 40)
        }
        .scrollDismissesKeyboard(.interactively)
        .clipped()
        .background(AuthBackground())
        .environment(\.openURL, OpenURLAction { url in
            guard let document = LegalDocument(url: url) else { return .systemAction }
            legalDocument = document
            return .handled
        })
        .sheet(item: $legalDocument) { LegalDocumentSheet(document: $0).presentationDetents([.large]) }
    }

    private func switchMode(_ newMode: Mode) {
        withAnimation(.easeInOut(duration: 0.25)) { mode = newMode }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 10) {
                Circle()
                    .fill(.white.opacity(0.18))
                    .frame(width: 38, height: 38)
                    .overlay(Image(systemName: "waveform.path.ecg").font(.system(size: 18, weight: .bold)).foregroundStyle(.white))
                Text("Pulse").font(.system(size: 22, weight: .bold)).foregroundStyle(.white)
            }
            Text("ТВОЯ ФОРМА —\nТВОЯ ПОБЕДА")
                .font(.system(size: 38, weight: .bold))
                .foregroundStyle(.white)
                .lineSpacing(2)
                .minimumScaleFactor(0.7)
            Text("Сон, восстановление и статистика\nкиберспортсмена в одном приложении")
                .font(.system(size: 17))
                .foregroundStyle(.white.opacity(0.85))
        }
        .padding(.horizontal, 8)
        .padding(.top, 20)
    }
}

/// Blue gradient glow from the desktop hero, placed behind the top of the screen.
struct AuthBackground: View {
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color(hex: 0x070C1A)
            RadialGradient(
                colors: [Color(hex: 0x00BFFF), Color(hex: 0x0A6CFF).opacity(0.9), Color(hex: 0x0A2C7A).opacity(0.6), .clear],
                center: UnitPoint(x: 0.1, y: 0.05),
                startRadius: 10,
                endRadius: 520
            )
        }
        .ignoresSafeArea()
    }
}

private struct LoginCard: View {
    let onRegister: () -> Void
    @Environment(SessionStore.self) private var session
    @State private var email = ""
    @State private var password = ""
    @FocusState private var focus: AuthFieldID?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Вход")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
                .padding(.bottom, 6)
            AuthField(placeholder: "Email", text: $email, keyboard: .emailAddress, contentType: .username, focus: $focus, id: .email) {
                focus = .password
            }
            AuthField(placeholder: "Пароль", text: $password, isPassword: true, contentType: .password, focus: $focus, id: .password,
                      submitLabel: .go) {
                focus = nil
                Task { await session.login(email: email, password: password) }
            }
            if let error = session.errorMessage { ErrorBanner(message: error) }
            AuthPrimaryButton(title: "Войти", isLoading: session.isLoading) {
                Task { await session.login(email: email, password: password) }
            }
            .padding(.top, 8)
            switchLink(prompt: "Нет аккаунта?", action: "Зарегистрироваться", perform: onRegister)
            demoAccounts
        }
        .onAppear { session.errorMessage = nil }
    }

    /// «Посмотреть демо»: a separate account with sample data — never mixed with the user's own records.
    private var demoAccounts: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("БЕЗ РЕГИСТРАЦИИ")
                .font(.system(size: 12, weight: .semibold))
                .tracking(2)
                .foregroundStyle(AuthStyle.placeholder)
            Menu {
                ForEach([UserRole.teamPlayer, .soloPlayer, .staff]) { role in
                    Button(role.title) { Task { await session.switchToDemo(role: role) } }
                }
            } label: {
                Label("Посмотреть демо", systemImage: "play.rectangle")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Theme.Palette.cyan)
                    .frame(maxWidth: .infinity)
                    .frame(height: 44)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.Palette.cyan.opacity(0.5), lineWidth: 1))
            }
            Text("Демо-аккаунт с примерами данных. Твои записи хранятся отдельно.")
                .font(.system(size: 12))
                .foregroundStyle(AuthStyle.placeholder)
        }
        .padding(.top, 8)
    }
}

private struct RegistrationCard: View {
    let onLogin: () -> Void
    @Environment(SessionStore.self) private var session
    @State private var form = RegistrationForm()
    @FocusState private var focus: AuthFieldID?

    /// «Далее» on every field but the last, which closes the keyboard.
    private func field(_ placeholder: String, _ text: Binding<String>, _ id: AuthFieldID, isPassword: Bool = false,
                       keyboard: UIKeyboardType = .default, contentType: UITextContentType? = nil) -> AuthField {
        AuthField(placeholder: placeholder, text: text, isPassword: isPassword, keyboard: keyboard, contentType: contentType,
                  focus: $focus, id: id, submitLabel: form.field(after: id) == nil ? .done : .next) { focus = form.field(after: id) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Регистрация")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(.white)
                .padding(.bottom, 6)

            PulseSegmented(options: AccessType.allCases, selection: $form.accessType, title: { $0.title }, unselectedColor: .white)

            if form.accessType == .team {
                HStack(spacing: 12) {
                    ForEach(TeamRole.allCases) { role in
                        AuthRoleOption(title: role.title, isSelected: form.teamRole == role) {
                            withAnimation(.easeInOut(duration: 0.2)) { form.teamRole = role }
                        }
                    }
                }
                .transition(.opacity.combined(with: .move(edge: .top)))
                Text(form.isTeamStaff
                     ? "Кабинет стаффа с данными игроков откроется вместе с сервером команды. Сейчас его можно посмотреть в демо на экране входа."
                     : "Код команды сохранится в профиле — привяжем тебя к команде, когда подключится сервер.")
                    .font(.system(size: 13))
                    .foregroundStyle(form.isTeamStaff ? Theme.Palette.orange : AuthStyle.placeholder)
                    .fixedSize(horizontal: false, vertical: true)
            }

            VStack(spacing: 12) {
                field("Имя", $form.nickname, .name, contentType: .nickname)
                field("Email", $form.email, .email, keyboard: .emailAddress, contentType: .emailAddress)
                field("Пароль", $form.password, .password, isPassword: true, contentType: .newPassword)
                field("Повторите пароль", $form.confirmPassword, .confirm, isPassword: true, contentType: .newPassword)
                field("Ссылка Faceit", $form.faceitURL, .faceit, keyboard: .URL, contentType: .URL)
                if form.isTeamPlayer {
                    field("Код команды", $form.teamCode, .teamCode)
                }
                if form.isTeamStaff {
                    field("Название команды", $form.teamName, .teamName, contentType: .organizationName)
                }
            }
            .padding(.top, 6)

            // Consents before the button: «Создать аккаунт» is the last thing to press, not the first error to read.
            VStack(alignment: .leading, spacing: 10) {
                AuthConsentRow(prefix: "Я принимаю условия", document: .terms,
                               linkText: "Пользовательского соглашения", isOn: $form.acceptedTerms)
                AuthConsentRow(prefix: "Я принимаю", document: .privacy,
                               linkText: "политику конфиденциальности", isOn: $form.acceptedPrivacy)
            }
            .padding(.top, 6)

            if let error = session.errorMessage { ErrorBanner(message: error) }

            AuthPrimaryButton(title: "Создать аккаунт", isLoading: session.isLoading) {
                focus = nil
                Task { await session.register(form) }
            }
            .padding(.top, 10)

            switchLink(prompt: "Уже есть аккаунт?", action: "Войти", perform: onLogin)
        }
        .onAppear { session.errorMessage = nil }
        .onChange(of: form.accessType) { _, _ in session.errorMessage = nil }
    }
}

private func switchLink(prompt: String, action: String, perform: @escaping () -> Void) -> some View {
    HStack(spacing: 6) {
        Text(prompt).foregroundStyle(AuthStyle.placeholder)
        Button(action, action: perform)
            .foregroundStyle(Theme.Palette.cyan)
            .fontWeight(.semibold)
    }
    .font(.system(size: 15))
    .frame(maxWidth: .infinity)
    .padding(.top, 10)
}
