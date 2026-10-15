import SwiftUI

/// Actions behind the central "+" button (players only — staff have no «+»).
struct QuickAddSheet: View {
    @Environment(AppRouter.self) private var router
    @Environment(SleepStore.self) private var sleep
    @Environment(\.dismiss) private var dismiss

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("Добавить")
                .font(.system(size: 24, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .padding(.top, 26)

            LazyVGrid(columns: columns, spacing: 12) {
                playerActions
            }
            Spacer()
        }
        .padding(.horizontal, 20)
        .background(ScreenBackground())
        .presentationBackground(Theme.Palette.background)
    }

    @ViewBuilder
    private var playerActions: some View {
        action(sleep.isSleeping ? "Завершить сон" : "Начать сон", icon: "moon.zzz", tint: Theme.Palette.purple) {
            sleep.toggleSleep()
            router.show([.sleep], in: .state)
        }
        action("Фото еды", icon: "camera", tint: Theme.Palette.cyan) {
            router.pendingFoodCapture = true
            router.show([.nutrition], in: .state)
        }
        action("Настроение", icon: "face.smiling", tint: Theme.Palette.yellow) {
            router.show([.tests, .mood], in: .state)
        }
        action("Когн. тест", icon: "brain.head.profile", tint: Theme.Palette.green) {
            router.show([.tests, .cognitive], in: .state)
        }
        action("Событие", icon: "calendar.badge.plus", tint: Theme.Palette.orange) {
            router.show([.calendar(createNew: true)], in: .state)
        }
        action("Тренировка", icon: "dumbbell", tint: Theme.Palette.pink) {
            router.show([.fitness], in: .state)
        }
    }

    private func action(_ title: String, icon: String, tint: Color, perform: @escaping () -> Void) -> some View {
        Button {
            dismiss()
            perform()
        } label: {
            HStack(spacing: 12) {
                IconBadge(systemName: icon, tint: tint, size: 40, iconSize: 18)
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                Spacer(minLength: 0)
            }
            .pulseTile(padding: 14)
        }
        .buttonStyle(.pressable)
    }
}
