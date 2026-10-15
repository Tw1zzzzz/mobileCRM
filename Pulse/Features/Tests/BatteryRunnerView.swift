import SwiftUI

/// Проход батареи: описание → обратный отсчёт → тест → результат → следующий тест.
struct BatteryRunnerView: View {
    enum Step: Equatable {
        case intro
        case countdown(Int)
        case running
        case result(CognitiveOutcome)
        case finished
    }

    let onResult: (CognitiveTestKind, CognitiveOutcome) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var index = 0
    @State private var step: Step = .intro
    @State private var outcomes: [CognitiveTestKind: CognitiveOutcome] = [:]

    private let kinds = CognitiveTestKind.allCases
    private var kind: CognitiveTestKind { kinds[min(index, kinds.count - 1)] }

    var body: some View {
        VStack(spacing: 0) {
            topBar
            Group {
                switch step {
                case .intro: intro
                case .countdown(let value): countdown(value)
                case .running: runningTest
                case .result(let outcome): result(outcome)
                case .finished: summary
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(.horizontal, 20)
            .padding(.bottom, 20)
        }
        .background(ScreenBackground())
    }

    private var topBar: some View {
        VStack(spacing: 14) {
            HStack {
                HeaderIconButton(systemName: "xmark") { dismiss() }
                Spacer()
                Text(step == .finished ? "Готово" : "Тест \(index + 1) из \(kinds.count)")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                Color.clear.frame(width: Theme.Metrics.headerButtonSize, height: 1)
            }
            HStack(spacing: 6) {
                ForEach(kinds.indices, id: \.self) { i in
                    Capsule()
                        .fill(i < index || step == .finished ? AnyShapeStyle(Theme.Gradients.accent)
                              : (i == index ? AnyShapeStyle(Theme.Palette.cyan.opacity(0.5)) : AnyShapeStyle(Theme.Palette.track)))
                        .frame(height: 5)
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 20)
    }

    private var intro: some View {
        VStack(alignment: .leading, spacing: 20) {
            IconBadge(systemName: kind.icon, tint: kind.tint, size: 76, iconSize: 34)
            Text(kind.title).font(.system(size: 32, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            infoBlock(title: "Что измеряет", text: kind.measures)
            infoBlock(title: "Что нужно делать", text: kind.instructions)
            Spacer()
            GradientButton(title: "Старт", systemImage: "play.fill") { startCountdown() }
        }
    }

    private func infoBlock(title: String, text: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title.uppercased()).font(.system(size: 13, weight: .semibold)).tracking(2).foregroundStyle(Theme.Palette.section)
            Text(text).font(.system(size: 17)).foregroundStyle(Theme.Palette.textPrimary)
        }
        .pulseCard()
    }

    private func countdown(_ value: Int) -> some View {
        Text("\(value)")
            .font(.system(size: 140, weight: .bold, design: .rounded))
            .foregroundStyle(Theme.Gradients.accent)
            .contentTransition(.numericText(countsDown: true))
            .id(value)
            .transition(.scale.combined(with: .opacity))
    }

    @ViewBuilder
    private var runningTest: some View {
        let finish: (CognitiveOutcome) -> Void = { outcome in
            onResult(kind, outcome)
            outcomes = outcomes.merging([kind: outcome]) { _, new in new }
            withAnimation { step = .result(outcome) }
        }
        switch kind {
        case .reaction: ReactionTestView(onFinish: finish)
        case .goNoGo: GoNoGoTestView(onFinish: finish)
        case .stroop: StroopTestView(onFinish: finish)
        case .memory: MemoryTestView(onFinish: finish)
        case .aim: AimTestView(onFinish: finish)
        }
    }

    private func result(_ outcome: CognitiveOutcome) -> some View {
        VStack(spacing: 24) {
            Spacer()
            ScoreRing(progress: Double(outcome.score) / 100, value: "\(outcome.score)", caption: "БАЛЛОВ", size: 190, valueSize: 60)
            Text(kind.title).font(.system(size: 24, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
            Text(outcome.metric).font(.system(size: 18)).foregroundStyle(Theme.Palette.textSecondary)
            Spacer()
            GradientButton(title: index < kinds.count - 1 ? "Следующий тест" : "Завершить батарею", systemImage: "arrow.right") {
                withAnimation {
                    if index < kinds.count - 1 {
                        index += 1
                        step = .intro
                    } else {
                        step = .finished
                    }
                }
            }
        }
    }

    private var summary: some View {
        let average = StatisticsMath.mean(outcomes.values.map { Double($0.score) }) ?? 0
        return VStack(spacing: 20) {
            ScoreRing(progress: average / 100, value: "\(Int(average))", caption: "КОГН. ФОРМА", size: 170, valueSize: 54)
                .padding(.top, 10)
            VStack(spacing: 0) {
                ForEach(kinds) { kind in
                    HStack {
                        IconBadge(systemName: kind.icon, tint: kind.tint, size: 36, iconSize: 15)
                        Text(kind.title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary)
                        Spacer()
                        Text(outcomes[kind].map { "\($0.score)" } ?? "—")
                            .font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                    }
                    .padding(.vertical, 8)
                }
            }
            .pulseCard()
            Spacer()
            GradientButton(title: "Вернуться к тестам") { dismiss() }
        }
    }

    private func startCountdown() {
        Task { @MainActor in
            for value in stride(from: 3, through: 1, by: -1) {
                withAnimation(.spring(duration: 0.35)) { step = .countdown(value) }
                try? await Task.sleep(for: .seconds(1))
            }
            withAnimation { step = .running }
        }
    }
}
