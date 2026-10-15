import SwiftUI

/// Открывается из пуша «Игровой тест до / после тренировки»: три ползунка — и результат в «Игровых тестах».
struct GameTestQuickSheet: View {
    let phase: GameTestPhase
    let onSaved: () -> Void

    @Environment(CognitiveStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var focus = 7.0
    @State private var fatigue = 3.0
    @State private var readiness = 7.0

    var body: some View {
        SheetScaffold(title: phase.title) {
            Text(phase == .beforeTraining
                 ? "Как ты себя чувствуешь перед тренировкой? Ответ займёт 20 секунд."
                 : "Как прошла тренировка? Отметь состояние сразу после неё.")
                .font(.system(size: 16))
                .foregroundStyle(Theme.Palette.textSecondary)
            VStack(spacing: 22) {
                PulseSlider(title: "Фокус", value: $focus)
                PulseSlider(title: "Усталость", value: $fatigue, tint: Theme.Palette.orange)
                PulseSlider(title: "Готовность", value: $readiness, tint: Theme.Palette.green)
            }
            .pulseCard(padding: 20)
            GradientButton(title: "Сохранить результат", systemImage: "checkmark") {
                store.addGameResult(phase: phase, focus: Int(focus), fatigue: Int(fatigue), readiness: Int(readiness))
                dismiss()
                onSaved()
            }
        }
        .sensoryFeedback(.selection, trigger: Int(focus) + Int(fatigue) + Int(readiness))
    }
}
