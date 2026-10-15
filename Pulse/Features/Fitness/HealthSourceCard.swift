import SwiftUI

/// Top of «Фитнес»: connect Apple Health, or show where the numbers come from and when they were updated.
struct HealthSourceCard: View {
    @Environment(FitnessStore.self) private var store

    var body: some View {
        if store.isHealthConnected {
            connected
        } else {
            connectPrompt
        }
    }

    private var connectPrompt: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                healthIcon
                VStack(alignment: .leading, spacing: 4) {
                    Text("Подключи Apple Health")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text(store.isDemo
                         ? "Шаги, калории, активные минуты, пульс и тренировки подтянутся автоматически. Сейчас показаны демо-данные."
                         : "Шаги, калории, активные минуты, пульс, сон и тренировки подтянутся автоматически.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            if let error = store.healthError {
                ErrorBanner(message: error)
            }
            GradientButton(title: "Подключить", systemImage: "heart.fill") {
                Task { await store.connectHealth() }
            }
            .disabled(!store.isHealthAvailable)
            Text("Pulse только читает данные и не меняет их. Доступ можно отозвать в Настройки → Здоровье → Доступ к данным.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
        .pulseCard(padding: 16)
    }

    private var connected: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                healthIcon
                VStack(alignment: .leading, spacing: 2) {
                    Text("Apple Health")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text(statusText)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer(minLength: 8)
                if store.isSyncing {
                    ProgressView().tint(Theme.Palette.cyan)
                }
                Menu {
                    Button("Обновить", systemImage: "arrow.clockwise") {
                        Task { await store.refreshHealth() }
                    }
                    Button("Показывать демо-данные", systemImage: "eye.slash") { store.disconnectHealth() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Настройки Apple Health")
            }
            if let error = store.healthError {
                ErrorBanner(message: error)
            } else if store.healthLooksEmpty {
                Label("За сегодня в Apple Health пусто. Если в «Здоровье» шаги есть — проверь доступ: Настройки → Здоровье → Доступ к данным → Pulse.",
                      systemImage: "exclamationmark.circle")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.orange)
            }
        }
        .pulseCard(padding: 14)
    }

    private var statusText: String {
        if store.isSyncing && store.health == nil { return "Загружаем данные…" }
        guard let health = store.health else { return "Подключено" }
        return "Обновлено в \(Formatters.time(health.fetchedAt))"
    }

    private var healthIcon: some View {
        Image(systemName: "heart.fill")
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(Color(hex: 0xFF375F))
            .frame(width: 40, height: 40)
            .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(.white))
    }
}

/// Connected without a watch / tracker: there are no heart-rate samples to split into zones.
struct NoHeartRateCard: View {
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "applewatch")
                .font(.system(size: 22))
                .foregroundStyle(Theme.Palette.red)
            VStack(alignment: .leading, spacing: 4) {
                Text("Сегодня нет данных о пульсе")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("Зоны пульса появятся с Apple Watch или другим трекером, который пишет пульс в Apple Health.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 16)
    }
}
