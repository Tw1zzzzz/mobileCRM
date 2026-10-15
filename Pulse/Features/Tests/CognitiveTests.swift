import SwiftUI

/// Shared header for a running test: instruction + progress.
private struct TestChrome<Content: View>: View {
    let instruction: String
    let progress: Double
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 18) {
            Text(instruction)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(Theme.Palette.textSecondary)
                .multilineTextAlignment(.center)
            GradientBar(progress: progress, height: 6)
            content.frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

// MARK: - 1. Reaction time

struct ReactionTestView: View {
    enum Phase { case waiting, go, early }

    let onFinish: (CognitiveOutcome) -> Void
    private let trials = 5

    @State private var phase: Phase = .waiting
    @State private var times: [Double] = []
    @State private var goDate = Date.now
    @State private var token = 0

    var body: some View {
        TestChrome(instruction: "Нажми, как только экран станет зелёным", progress: Double(times.count) / Double(trials)) {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(color)
                .overlay(Text(label).font(.system(size: 30, weight: .bold)).foregroundStyle(.white))
                .onTapGesture { tap() }
        }
        .task { scheduleGo() }
    }

    private var color: Color {
        switch phase {
        case .waiting: Theme.Palette.track
        case .go: Theme.Palette.green
        case .early: Theme.Palette.red
        }
    }

    private var label: String {
        switch phase {
        case .waiting: "Жди…"
        case .go: "Жми!"
        case .early: "Рано! Ещё раз"
        }
    }

    private func scheduleGo() {
        token += 1
        let current = token
        phase = .waiting
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(Int.random(in: 1300...3200)))
            guard current == token else { return }
            goDate = .now
            phase = .go
        }
    }

    private func tap() {
        switch phase {
        case .waiting:
            token += 1
            phase = .early
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(900))
                scheduleGo()
            }
        case .go:
            let updated = times + [Date.now.timeIntervalSince(goDate) * 1000]
            times = updated
            if updated.count >= trials {
                let average = StatisticsMath.mean(updated) ?? 500
                onFinish(CognitiveOutcome(score: CognitiveScoring.reactionScore(averageMs: average), metric: "\(Int(average)) мс в среднем"))
            } else {
                scheduleGo()
            }
        case .early:
            break
        }
    }
}

// MARK: - 2. Go / No-Go

struct GoNoGoTestView: View {
    let onFinish: (CognitiveOutcome) -> Void
    private let total = 20

    @State private var index = 0
    @State private var isGo = true
    @State private var visible = false
    @State private var tapped = false
    @State private var correct = 0

    var body: some View {
        TestChrome(instruction: "Зелёный — жми. Красный — не трогай", progress: Double(index) / Double(total)) {
            ZStack {
                RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Theme.Palette.tile)
                if visible {
                    Circle()
                        .fill(isGo ? Theme.Palette.green : Theme.Palette.red)
                        .frame(width: 150, height: 150)
                        .shadow(color: (isGo ? Theme.Palette.green : Theme.Palette.red).opacity(0.6), radius: 24)
                        .transition(.scale)
                }
            }
            .contentShape(Rectangle())
            .onTapGesture {
                guard visible, !tapped else { return }
                tapped = true
            }
        }
        .task { await run() }
    }

    @MainActor
    private func run() async {
        for i in 0..<total {
            index = i
            isGo = Int.random(in: 0...9) < 7
            tapped = false
            withAnimation(.easeOut(duration: 0.12)) { visible = true }
            try? await Task.sleep(for: .milliseconds(750))
            if tapped == isGo { correct += 1 }
            withAnimation(.easeIn(duration: 0.1)) { visible = false }
            try? await Task.sleep(for: .milliseconds(Int.random(in: 350...700)))
        }
        index = total
        onFinish(CognitiveOutcome(
            score: CognitiveScoring.accuracyScore(correct: correct, total: total),
            metric: "\(correct) из \(total) верно"
        ))
    }
}

// MARK: - 3. Stroop

struct StroopTestView: View {
    private struct InkColor: Identifiable, Equatable {
        let id: String
        let color: Color
    }

    let onFinish: (CognitiveOutcome) -> Void
    private let total = 12
    private let palette = [
        InkColor(id: "КРАСНЫЙ", color: Theme.Palette.red),
        InkColor(id: "ЗЕЛЁНЫЙ", color: Theme.Palette.green),
        InkColor(id: "СИНИЙ", color: Theme.Palette.blue),
        InkColor(id: "ЖЁЛТЫЙ", color: Theme.Palette.yellow)
    ]

    @State private var index = 0
    @State private var word = "КРАСНЫЙ"
    @State private var inkIndex = 0
    @State private var shownAt = Date.now
    @State private var correct = 0
    @State private var times: [Double] = []

    var body: some View {
        TestChrome(instruction: "Выбери цвет, которым написано слово", progress: Double(index) / Double(total)) {
            VStack(spacing: 30) {
                Spacer()
                Text(word)
                    .font(.system(size: 46, weight: .heavy))
                    .foregroundStyle(palette[inkIndex].color)
                Spacer()
                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                    ForEach(palette.indices, id: \.self) { i in
                        Button { answer(i) } label: {
                            Text(palette[i].id.capitalized)
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 58)
                                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(palette[i].color.opacity(0.85)))
                        }
                        .buttonStyle(.pressable)
                    }
                }
            }
        }
        .onAppear { next() }
    }

    private func next() {
        word = palette.randomElement()?.id ?? "КРАСНЫЙ"
        let options = palette.indices.filter { palette[$0].id != word || Int.random(in: 0...3) == 0 }
        inkIndex = options.randomElement() ?? 0
        shownAt = .now
    }

    private func answer(_ choice: Int) {
        times = times + [Date.now.timeIntervalSince(shownAt) * 1000]
        if choice == inkIndex { correct += 1 }
        index += 1
        if index >= total {
            let average = StatisticsMath.mean(times) ?? 1000
            onFinish(CognitiveOutcome(
                score: CognitiveScoring.speedAccuracyScore(correct: correct, total: total, averageMs: average),
                metric: "\(correct)/\(total) · \(Int(average)) мс"
            ))
        } else {
            next()
        }
    }
}

// MARK: - 4. Digit span

struct MemoryTestView: View {
    enum Phase { case showing, input, feedback(Bool) }

    let onFinish: (CognitiveOutcome) -> Void

    @State private var length = 3
    @State private var sequence: [Int] = []
    @State private var visibleIndex: Int?
    @State private var input: [Int] = []
    @State private var phase: Phase = .showing
    @State private var bestSpan = 2
    @State private var mistakes = 0

    var body: some View {
        TestChrome(instruction: instruction, progress: Double(length - 3) / 7) {
            VStack(spacing: 26) {
                Spacer()
                display
                Spacer()
                keypad
            }
        }
        .task { await showSequence() }
    }

    private var instruction: String {
        switch phase {
        case .showing: "Запоминай цифры: \(length) шт."
        case .input: "Введи последовательность"
        case .feedback(let ok): ok ? "Верно! Длина растёт" : "Ошибка"
        }
    }

    @ViewBuilder
    private var display: some View {
        switch phase {
        case .showing:
            Text(visibleIndex.map { "\(sequence[$0])" } ?? " ")
                .font(.system(size: 110, weight: .bold, design: .rounded))
                .foregroundStyle(Theme.Palette.textPrimary)
                .frame(height: 140)
        case .input, .feedback:
            HStack(spacing: 10) {
                ForEach(0..<length, id: \.self) { i in
                    Text(i < input.count ? "\(input[i])" : "")
                        .font(.system(size: 26, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .frame(width: 34, height: 48)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.Palette.track))
                }
            }
            .frame(height: 140)
        }
    }

    private var keypad: some View {
        let isInput: Bool = { if case .input = phase { return true } else { return false } }()
        return LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 3), spacing: 12) {
            ForEach([1, 2, 3, 4, 5, 6, 7, 8, 9, -1, 0, -2], id: \.self) { key in
                Button { press(key) } label: {
                    Group {
                        switch key {
                        case -1: Image(systemName: "delete.left")
                        case -2: Image(systemName: "checkmark")
                        default: Text("\(key)")
                        }
                    }
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity)
                    .frame(height: 56)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(key == -2 ? AnyShapeStyle(Theme.Gradients.accent) : AnyShapeStyle(Theme.Palette.tile)))
                }
                .buttonStyle(.pressable)
                .disabled(!isInput)
                .opacity(isInput ? 1 : 0.4)
            }
        }
    }

    @MainActor
    private func showSequence() async {
        sequence = (0..<length).map { _ in Int.random(in: 0...9) }
        input = []
        phase = .showing
        try? await Task.sleep(for: .milliseconds(400))
        for i in sequence.indices {
            visibleIndex = i
            try? await Task.sleep(for: .milliseconds(750))
            visibleIndex = nil
            try? await Task.sleep(for: .milliseconds(200))
        }
        phase = .input
    }

    private func press(_ key: Int) {
        switch key {
        case -1: input = Array(input.dropLast())
        case -2: submit()
        default: if input.count < length { input = input + [key] }
        }
    }

    private func submit() {
        let ok = input == sequence
        phase = .feedback(ok)
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(700))
            if ok {
                bestSpan = length
                if length >= 9 { return finish() }
                length += 1
            } else {
                mistakes += 1
                if mistakes >= 2 { return finish() }
            }
            await showSequence()
        }
    }

    private func finish() {
        onFinish(CognitiveOutcome(score: CognitiveScoring.memoryScore(span: bestSpan), metric: "\(bestSpan) цифр"))
    }
}

// MARK: - 5. Aim

struct AimTestView: View {
    let onFinish: (CognitiveOutcome) -> Void
    private let total = 15

    @State private var hits = 0
    @State private var target = CGPoint(x: 0.5, y: 0.5)
    @State private var shownAt = Date.now
    @State private var times: [Double] = []
    @State private var misses = 0

    var body: some View {
        TestChrome(instruction: "Попадай по мишеням как можно быстрее", progress: Double(hits) / Double(total)) {
            GeometryReader { geo in
                ZStack {
                    RoundedRectangle(cornerRadius: 28, style: .continuous)
                        .fill(Theme.Palette.tile)
                        .onTapGesture { misses += 1 }
                    Button { hit() } label: {
                        ZStack {
                            Circle().fill(Theme.Palette.red).frame(width: 62, height: 62)
                            Circle().fill(.white).frame(width: 38, height: 38)
                            Circle().fill(Theme.Palette.red).frame(width: 16, height: 16)
                        }
                    }
                    .buttonStyle(.plain)
                    .position(x: 40 + target.x * (geo.size.width - 80), y: 40 + target.y * (geo.size.height - 80))
                }
            }
        }
        .onAppear { moveTarget() }
    }

    private func moveTarget() {
        target = CGPoint(x: .random(in: 0...1), y: .random(in: 0...1))
        shownAt = .now
    }

    private func hit() {
        times = times + [Date.now.timeIntervalSince(shownAt) * 1000]
        hits += 1
        if hits >= total {
            let average = StatisticsMath.mean(times) ?? 1000
            onFinish(CognitiveOutcome(
                score: CognitiveScoring.speedAccuracyScore(correct: total, total: total + misses, averageMs: average),
                metric: "\(Int(average)) мс · промахов \(misses)"
            ))
        } else {
            moveTarget()
        }
    }
}
