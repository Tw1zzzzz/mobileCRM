import SwiftUI

/// One statement per screen, five answers, auto-advance.
struct MindsetTestRunner: View {
    @Binding var answers: [String: Int]
    let onFinish: () -> Void

    @State private var index = 0
    private let questions = MindsetTest.questions

    var body: some View {
        let question = questions[index]
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Утверждение \(index + 1) из \(questions.count)")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                if index > 0 {
                    Button("Назад") { withAnimation { index -= 1 } }
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.Palette.cyan)
                }
            }
            GradientBar(progress: Double(index) / Double(questions.count), height: 6)
            Text(question.text)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 96, alignment: .topLeading)
                .transaction { $0.animation = nil } // swap statements instantly, no overlapping crossfade
            Text("Насколько это про тебя?")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
            VStack(spacing: 10) {
                ForEach(Array(MindsetTest.answerTitles.enumerated()), id: \.offset) { offset, title in
                    answerButton(title, value: offset + 1, question: question)
                }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: index)
    }

    private func answerButton(_ title: String, value: Int, question: MindsetQuestion) -> some View {
        let isSelected = answers[question.id] == value
        return Button {
            answers = answers.merging([question.id: value]) { _, new in new }
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(220))
                guard questions[index].id == question.id else { return }
                if index < questions.count - 1 { index += 1 } else { onFinish() }
            }
        } label: {
            HStack(spacing: 12) {
                Text("\(value)")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(isSelected ? .white : Theme.Palette.textSecondary)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(isSelected ? AnyShapeStyle(Theme.Gradients.accent) : AnyShapeStyle(Theme.Palette.track)))
                Text(title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 14)
            .frame(height: 54)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(isSelected ? Theme.Palette.cyan : Theme.Palette.stroke, lineWidth: isSelected ? 1.5 : 1)
            )
        }
        .buttonStyle(.pressable)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Archetype + radar of the six traits.
struct MindsetProfileCard: View {
    let profile: MindsetProfile
    var caption: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 14) {
                IconBadge(systemName: profile.archetype.icon, tint: profile.archetype.tint, size: 50, iconSize: 22)
                VStack(alignment: .leading, spacing: 3) {
                    Text(profile.archetype.caption.uppercased())
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.2)
                        .foregroundStyle(Theme.Palette.textTertiary)
                    Text(profile.archetype.title)
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text(profile.archetype.summary)
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            RadarChart(labels: MindsetTrait.allCases.map(\.shortTitle),
                       values: profile.orderedScores.map(Double.init), maxValue: 100)
                .frame(height: 250)
            if let caption {
                Text(caption)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.Palette.textTertiary)
            }
        }
        .pulseCard(padding: 16)
    }
}

/// Trait list with score bars and the change since the previous test.
struct MindsetTraitsCard: View {
    let profile: MindsetProfile
    var previous: MindsetProfile?
    var showsMeaning = true

    var body: some View {
        VStack(spacing: 16) {
            ForEach(MindsetTrait.allCases) { trait in
                let score = profile.scores[trait] ?? 0
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 8) {
                        Image(systemName: trait.icon).font(.system(size: 13)).foregroundStyle(trait.tint).frame(width: 18)
                        Text(trait.title).font(.system(size: 15, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                        Spacer()
                        if let before = previous?.scores[trait], before != score {
                            Text(Formatters.signed(score - before))
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(score > before ? Theme.Palette.green : Theme.Palette.orange)
                        }
                        Text("\(score)").font(.system(size: 16, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                            .monospacedDigit()
                    }
                    GeometryReader { geo in
                        ZStack(alignment: .leading) {
                            Capsule().fill(Theme.Palette.track)
                            Capsule().fill(trait.tint).frame(width: geo.size.width * CGFloat(score) / 100)
                        }
                    }
                    .frame(height: 6)
                    if showsMeaning {
                        Text(trait.meaning).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .accessibilityElement(children: .combine)
            }
        }
        .pulseCard(padding: 16)
    }
}

/// Strengths and growth zones with advice for the player or for the coach.
struct MindsetAdviceCard: View {
    enum Audience { case player, coach }

    let profile: MindsetProfile
    let audience: Audience

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            group("Сильные стороны", icon: "checkmark.seal.fill", tint: Theme.Palette.green, traits: profile.strengths) { trait in
                audience == .coach ? trait.coachStrength : trait.meaning
            }
            if !profile.growthZones.isEmpty {
                DividerLine()
                group("Зоны роста", icon: "arrow.up.forward.circle.fill", tint: Theme.Palette.orange, traits: profile.growthZones) { trait in
                    audience == .coach ? trait.coachTip : trait.playerTip
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 16)
    }

    private func group(_ title: String, icon: String, tint: Color, traits: [MindsetTrait], text: @escaping (MindsetTrait) -> String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: icon)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(tint)
            ForEach(traits) { trait in
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(trait.title) · \(profile.scores[trait] ?? 0)")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text(text(trait))
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }
}

/// Goals + player type in a compact card.
struct SurveySummaryCard: View {
    let survey: PlayerSurvey

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                tag(survey.role.title, tint: Theme.Palette.cyan)
                tag(survey.level.title, tint: Theme.Palette.purple)
                tag(survey.playTime.title, tint: Theme.Palette.blue)
            }
            Text("\(Formatters.years(survey.yearsPlaying)) в CS · \(survey.hoursPerWeek) ч в неделю · мотивация: \(survey.motivation.title.lowercased())")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if !survey.goals.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(survey.goals) { goal in
                        HStack(spacing: 10) {
                            Image(systemName: goal.icon).font(.system(size: 13)).foregroundStyle(goal.tint).frame(width: 20)
                            Text(goal.title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textPrimary)
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 16)
    }

    private func tag(_ title: String, tint: Color) -> some View {
        Text(title)
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .padding(.horizontal, 10).padding(.vertical, 5)
            .background(Capsule().fill(tint.opacity(0.14)))
    }
}

enum MindsetCopy {
    static let disclaimer = "Это самооценка игровых качеств, а не психологическая или медицинская диагностика. Результат меняется — проходи тест раз в месяц."
}
