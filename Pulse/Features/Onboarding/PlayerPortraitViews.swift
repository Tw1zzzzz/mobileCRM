import SwiftUI

/// Профиль → «Игровой профиль»: questionnaire answers, mindset radar, traits and advice.
struct PlayerPortraitView: View {
    @Environment(PlayerSurveyStore.self) private var store
    @Environment(SessionStore.self) private var session
    @State private var showEdit = false
    @State private var showTest = false

    var body: some View {
        ScreenScaffold(spacing: 22) {
            NavHeader(title: "Игровой профиль", subtitle: session.user?.role == .teamPlayer ? "Видишь ты и тренер команды" : "Видишь только ты") {
                HeaderIconButton(systemName: "pencil") { showEdit = true }
                    .accessibilityLabel("Изменить анкету")
            }
        } content: {
            if let survey = store.survey {
                PulseSection(title: "Ты как игрок") { SurveySummaryCard(survey: survey) }
            } else {
                SurveyReminderCard()
            }
            if let profile = store.profile, let result = store.latestResult {
                PulseSection(title: "Игровой характер") {
                    MindsetProfileCard(profile: profile, caption: "Тест от \(Formatters.dayMonth(result.date))")
                }
                PulseSection(title: "Черты") { MindsetTraitsCard(profile: profile, previous: store.previousProfile) }
                PulseSection(title: "Что делать") { MindsetAdviceCard(profile: profile, audience: .player) }
                SecondaryButton(title: "Пройти тест заново", systemImage: "arrow.clockwise") { showTest = true }
                if let next = store.nextRetakeDate(), next > .now {
                    Text("Рекомендуем перепроходить раз в месяц — следующий раз после \(Formatters.dayMonth(next)).")
                        .font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                }
            } else if store.survey != nil {
                SurveyReminderCard()
            }
            Text(MindsetCopy.disclaimer).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
        }
        .sheet(isPresented: $showEdit) { SurveyEditSheet().presentationDetents([.large]) }
        .fullScreenCover(isPresented: $showTest) { OnboardingFlowView(testOnly: true) }
    }
}

/// Compact block in «Профиль».
struct PlayerPortraitSection: View {
    @Environment(PlayerSurveyStore.self) private var store
    @Environment(AppRouter.self) private var router

    var body: some View {
        PulseSection(title: "Игровой профиль", actionTitle: store.isComplete ? "Подробнее" : nil) {
            router.open(.playerPortrait, in: .profile)
        } content: {
            if store.isComplete, let profile = store.profile, let survey = store.survey {
                Button { router.open(.playerPortrait, in: .profile) } label: {
                    HStack(spacing: 14) {
                        IconBadge(systemName: profile.archetype.icon, tint: profile.archetype.tint, size: 48, iconSize: 21)
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(profile.archetype.caption): \(profile.archetype.title.lowercased())")
                                .font(.system(size: 17, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                                .lineLimit(1).minimumScaleFactor(0.8)
                            Text(survey.summary)
                                .font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                                .lineLimit(2)
                            Text(profile.growthZones.isEmpty ? "Без явных зон роста"
                                 : "Зона роста: \(profile.growthZones.map { $0.title.lowercased() }.joined(separator: ", "))")
                                .font(.system(size: 13, weight: .medium)).foregroundStyle(Theme.Palette.orange)
                                .lineLimit(1).minimumScaleFactor(0.8)
                        }
                        Spacer(minLength: 4)
                        Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
                    }
                    .pulseCard(padding: 14)
                }
                .buttonStyle(.pressable)
            } else {
                SurveyReminderCard()
            }
        }
    }
}

/// «Заполни анкету» / «Пройди тест» — opens the questionnaire full-screen.
struct SurveyReminderCard: View {
    @Environment(PlayerSurveyStore.self) private var store
    @Environment(AppRouter.self) private var router

    var body: some View {
        let needsSurvey = store.survey == nil
        Button { router.showsSurvey = true } label: {
            HStack(spacing: 14) {
                IconBadge(systemName: needsSurvey ? "person.text.rectangle" : "brain.head.profile",
                          tint: needsSurvey ? Theme.Palette.cyan : Theme.Palette.purple, size: 46, iconSize: 20)
                VStack(alignment: .leading, spacing: 3) {
                    Text(needsSurvey ? "Заполни анкету игрока" : "Пройди тест «Игровой характер»")
                        .font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(needsSurvey ? "Цели, роль и игровой характер — 5 минут" : "24 утверждения, около 3 минут")
                        .font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer(minLength: 4)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .semibold)).foregroundStyle(Theme.Palette.textTertiary)
            }
            .pulseCard(padding: 14)
        }
        .buttonStyle(.pressable)
    }
}

/// Edit goals and player type later.
struct SurveyEditSheet: View {
    @Environment(PlayerSurveyStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var draft = PlayerSurvey()

    var body: some View {
        SheetScaffold(title: "Анкета игрока") {
            Text("ЦЕЛИ · ДО \(FocusGoal.maxSelected)")
                .font(.system(size: 13, weight: .semibold)).tracking(1.2).foregroundStyle(Theme.Palette.section)
            SurveyGoalsPicker(goals: $draft.goals)
            SurveyPlayerForm(survey: $draft)
            GradientButton(title: "Сохранить") {
                store.saveSurvey(draft)
                dismiss()
            }
            .disabled(draft.goals.isEmpty)
            .opacity(draft.goals.isEmpty ? 0.5 : 1)
        }
        .onAppear { if let survey = store.survey { draft = survey } }
    }
}

/// Staff → player card: questionnaire, mindset radar and how to work with the player.
struct StaffPlayerPortraitSection: View {
    let nickname: String

    var body: some View {
        let state = PlayerSurveyDemo.state(for: nickname)
        if let survey = state.survey, let result = state.mindset.last {
            let profile = MindsetProfile(scores: result.scores)
            PulseSection(title: "Профиль игрока") {
                VStack(spacing: 12) {
                    SurveySummaryCard(survey: survey)
                    MindsetProfileCard(profile: profile, caption: "Тест «Игровой характер» от \(Formatters.dayMonth(result.date)) · самооценка игрока")
                }
            }
            PulseSection(title: "Как работать с игроком") { MindsetAdviceCard(profile: profile, audience: .coach) }
        } else {
            PulseSection(title: "Профиль игрока") {
                Text("Игрок ещё не заполнил анкету.")
                    .font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .pulseCard(padding: 16)
            }
        }
    }
}
