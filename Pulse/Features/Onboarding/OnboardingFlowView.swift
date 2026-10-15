import SwiftUI

/// First-login questionnaire: goals → player type → «Игровой характер» test → result.
/// `testOnly` re-runs just the test (Профиль → Игровой профиль → «Пройти заново»).
struct OnboardingFlowView: View {
    enum Step: Int, CaseIterable {
        case welcome, goals, player, testIntro, test, result
    }

    var testOnly = false

    @Environment(SessionStore.self) private var session
    @Environment(PlayerSurveyStore.self) private var store
    @Environment(GameGoalsStore.self) private var gameGoals
    @Environment(GameStore.self) private var game
    @Environment(SleepStore.self) private var sleep
    @Environment(\.dismiss) private var dismiss

    @State private var step: Step = .welcome
    @State private var draft = PlayerSurvey()
    @State private var eloTarget = ""
    @State private var sleepHours = 8.0
    @State private var answers: [String: Int] = [:]
    @State private var result: MindsetProfile?

    var body: some View {
        VStack(spacing: 0) {
            topBar
            ScrollView {
                VStack(alignment: .leading, spacing: 20) { content }
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                    .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            .id(step) // every step starts at the top
            .transition(.opacity)
            if let action = bottomAction {
                VStack(spacing: 10) {
                    GradientButton(title: action.title, action: action.run)
                        .disabled(!action.enabled)
                        .opacity(action.enabled ? 1 : 0.5)
                    if let secondary = secondaryAction {
                        Button(secondary.title, action: secondary.run)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Theme.Palette.textSecondary)
                            .frame(height: 36)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
            }
        }
        .background(ScreenBackground())
        .animation(.easeInOut(duration: 0.25), value: step)
        .onAppear(perform: prepare)
    }

    // MARK: Layout

    private var topBar: some View {
        HStack {
            if canGoBack {
                HeaderIconButton(systemName: "chevron.left") { goBack() }
                    .accessibilityLabel("Назад")
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
            Spacer()
            if let progress = stepTitle {
                Text(progress)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer()
            HeaderIconButton(systemName: "xmark") { close() }
                .accessibilityLabel("Закрыть")
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome: welcome
        case .goals:
            title("Твои цели", "Выбери до \(FocusGoal.maxSelected) главных — Pulse будет делать на них акцент в советах и сводках.")
            SurveyGoalsPicker(goals: $draft.goals)
            if draft.goals.contains(.rankUp) {
                PulseTextField(title: "Цель по ELO (необязательно)", text: $eloTarget, placeholder: "Например, 2500", keyboard: .numberPad)
            }
            if draft.goals.contains(.sleep) {
                sleepGoal
            }
        case .player:
            title("Что ты за игрок", "Роль, уровень и режим игры — тренер и Pulse будут учитывать это в нагрузке и рекомендациях.")
            SurveyPlayerForm(survey: $draft)
        case .testIntro: testIntro
        case .test:
            MindsetTestRunner(answers: $answers) { finishTest() }
        case .result:
            if let result {
                title("Твой игровой характер", nil)
                MindsetProfileCard(profile: result)
                MindsetAdviceCard(profile: result, audience: .player)
                Text(sharingNote).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                Text(MindsetCopy.disclaimer).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
            }
        }
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 20) {
            IconBadge(systemName: "person.text.rectangle", tint: Theme.Palette.cyan, size: 64, iconSize: 28)
            title("Давай познакомимся", "Три коротких шага — около 5 минут. Ответы можно поменять в профиле.")
            VStack(alignment: .leading, spacing: 14) {
                bullet("target", "Цели", "Что хочешь улучшить: игру, сон, тильт, форму")
                bullet("gamecontroller", "Ты как игрок", "Роль, уровень, сколько и когда играешь")
                bullet("brain.head.profile", "Игровой характер", "24 утверждения: хладнокровие, тильт, фокус, командность, лидерство, дисциплина")
            }
            .pulseCard(padding: 16)
            Text(sharingNote).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }

    private var testIntro: some View {
        VStack(alignment: .leading, spacing: 18) {
            IconBadge(systemName: "brain.head.profile", tint: Theme.Palette.purple, size: 64, iconSize: 28)
            title("Игровой характер", "24 утверждения о том, как ты ведёшь себя в игре. Отвечай честно и быстро — правильных ответов нет. Около 3 минут.")
            VStack(alignment: .leading, spacing: 10) {
                ForEach(MindsetTrait.allCases) { trait in
                    HStack(spacing: 10) {
                        Image(systemName: trait.icon).foregroundStyle(trait.tint).frame(width: 22)
                        Text(trait.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .pulseCard(padding: 16)
            Text(MindsetCopy.disclaimer).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
        }
    }

    private var sleepGoal: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Хочу спать").font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text(sleepHoursText).font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.Palette.purple)
            }
            Slider(value: $sleepHours, in: 6...10, step: 0.5).tint(Theme.Palette.purple)
            Text("Отбой в \(sleep.window.bedtimeText) — подъём сдвинется под эту цель.")
                .font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 16)
    }

    private var sleepHoursText: String {
        let minutes = Int(sleepHours * 60)
        return minutes % 60 == 0 ? "\(minutes / 60) ч" : "\(minutes / 60) ч \(minutes % 60) мин"
    }

    private func title(_ text: String, _ subtitle: String?) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text).font(.system(size: 28, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            if let subtitle {
                Text(subtitle).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func bullet(_ icon: String, _ title: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            IconBadge(systemName: icon, tint: Theme.Palette.cyan, size: 36, iconSize: 15)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text(text).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var sharingNote: String {
        session.user?.role == .teamPlayer
            ? "Профиль увидишь ты и тренер команды — так ему проще понять, как с тобой работать."
            : "Профиль видишь только ты."
    }

    // MARK: Navigation

    private struct Action {
        let title: String
        var enabled = true
        let run: () -> Void
    }

    private var stepTitle: String? {
        switch step {
        case .goals: "Шаг 1 из 3"
        case .player: "Шаг 2 из 3"
        case .testIntro, .test: testOnly ? nil : "Шаг 3 из 3"
        default: nil
        }
    }

    private var canGoBack: Bool {
        switch step {
        case .goals, .player: true
        case .testIntro: !testOnly
        default: false
        }
    }

    private var bottomAction: Action? {
        switch step {
        case .welcome: Action(title: "Начать") { step = .goals }
        case .goals: Action(title: "Дальше", enabled: !draft.goals.isEmpty) { step = .player }
        case .player: Action(title: "Дальше") { saveSurvey(); step = .testIntro }
        case .testIntro: Action(title: "Начать тест") { step = .test }
        case .test: nil
        case .result: Action(title: "Готово") { dismiss() }
        }
    }

    private var secondaryAction: Action? {
        switch step {
        case .welcome: Action(title: "Позже") { close() }
        case .testIntro where !testOnly: Action(title: "Пройти позже") { dismiss() }
        default: nil
        }
    }

    private func goBack() {
        switch step {
        case .player: step = .goals
        case .testIntro: step = .player
        default: step = .welcome
        }
    }

    private func close() {
        if store.needsOnboarding { store.dismissOnboarding() }
        dismiss()
    }

    private func prepare() {
        if let saved = store.survey { draft = saved }
        if let elo = gameGoals.goals.elo { eloTarget = String(elo) }
        sleepHours = Double(sleep.window.durationMinutes) / 60
        if testOnly { step = .testIntro }
    }

    private func saveSurvey() {
        store.saveSurvey(draft)
        if draft.goals.contains(.rankUp), let elo = Int(eloTarget.filter(\.isNumber)), elo > 0 {
            var goals = gameGoals.goals
            goals.elo = elo
            gameGoals.update(goals, currentElo: game.profile(for: session.user).elo)
        }
        if draft.goals.contains(.sleep) {
            let minutes = Int(sleepHours * 60)
            if minutes != sleep.window.durationMinutes {
                var window = sleep.window
                let wake = (window.bedtimeHour * 60 + window.bedtimeMinute + minutes) % (24 * 60)
                window.wakeHour = wake / 60
                window.wakeMinute = wake % 60
                sleep.updateWindow(window)
            }
        }
    }

    private func finishTest() {
        guard store.addMindset(answers) else { return }
        result = store.profile
        step = .result
    }
}

/// Grid of focus goals, up to `FocusGoal.maxSelected`.
struct SurveyGoalsPicker: View {
    @Binding var goals: [FocusGoal]
    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 10) {
            ForEach(FocusGoal.allCases) { goal in
                let isSelected = goals.contains(goal)
                let isLocked = !isSelected && goals.count >= FocusGoal.maxSelected
                Button {
                    goals = isSelected ? goals.filter { $0 != goal } : goals + [goal]
                } label: {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: goal.icon).font(.system(size: 18)).foregroundStyle(goal.tint)
                        Text(goal.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, minHeight: 76, alignment: .topLeading)
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(isSelected ? goal.tint.opacity(0.16) : Theme.Palette.tile))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .strokeBorder(isSelected ? goal.tint : Theme.Palette.stroke, lineWidth: isSelected ? 1.5 : 1)
                    )
                    .opacity(isLocked ? 0.45 : 1)
                }
                .buttonStyle(.pressable)
                .disabled(isLocked)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
    }
}

/// Role, level, experience, schedule and motivation.
struct SurveyPlayerForm: View {
    @Binding var survey: PlayerSurvey

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            field("Роль") {
                WrapLayout {
                    ForEach(PlayerGameRole.allCases) { role in
                        ChipButton(title: role.title, isSelected: survey.role == role) { survey.role = role }
                    }
                }
                Text(survey.role.subtitle).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
            }
            field("Уровень") {
                VStack(spacing: 8) {
                    ForEach(SkillLevel.allCases) { level in
                        levelRow(level)
                    }
                }
            }
            field("Опыт") {
                VStack(spacing: 14) {
                    stepperRow("Играю в CS", value: Formatters.years(survey.yearsPlaying),
                               onMinus: { survey.yearsPlaying = max(survey.yearsPlaying - 1, 0) },
                               onPlus: { survey.yearsPlaying = min(survey.yearsPlaying + 1, 25) })
                    PulseSlider(title: "Часов в неделю", value: Binding(
                        get: { Double(survey.hoursPerWeek) }, set: { survey.hoursPerWeek = Int($0) }
                    ), range: 1...70)
                }
                .pulseCard(padding: 16)
            }
            field("Когда обычно играешь") {
                PulseSegmented(options: PlayTime.allCases, selection: $survey.playTime) { $0.title }
            }
            field("Что мотивирует больше всего") {
                WrapLayout {
                    ForEach(PlayerMotivation.allCases) { motivation in
                        ChipButton(title: motivation.title, isSelected: survey.motivation == motivation) { survey.motivation = motivation }
                    }
                }
            }
        }
    }

    private func field<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title.uppercased())
                .font(.system(size: 13, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(Theme.Palette.section)
            content()
        }
    }

    private func levelRow(_ level: SkillLevel) -> some View {
        let isSelected = survey.level == level
        return Button { survey.level = level } label: {
            HStack(spacing: 12) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .font(.system(size: 20))
                    .foregroundStyle(isSelected ? Theme.Palette.cyan : Theme.Palette.textTertiary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(level.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(level.subtitle).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer()
            }
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(isSelected ? Theme.Palette.cyan : Theme.Palette.stroke, lineWidth: isSelected ? 1.5 : 1)
            )
        }
        .buttonStyle(.pressable)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func stepperRow(_ title: String, value: String, onMinus: @escaping () -> Void, onPlus: @escaping () -> Void) -> some View {
        HStack {
            Text(title).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
            Spacer()
            roundButton("minus", action: onMinus).accessibilityLabel("Меньше")
            Text(value).font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.Palette.cyan)
                .frame(minWidth: 64)
            roundButton("plus", action: onPlus).accessibilityLabel("Больше")
        }
    }

    private func roundButton(_ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .font(.system(size: 14, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .frame(width: 34, height: 34)
                .background(Circle().fill(Theme.Palette.track))
        }
        .buttonStyle(.pressable)
    }
}
