import SwiftUI

/// Top of the game screens: connect Faceit, or show which account the numbers come from.
struct FaceitSourceCard: View {
    @Environment(GameStore.self) private var game
    @State private var showConnect = false

    var body: some View {
        Group {
            if game.isConnected {
                connected
            } else {
                prompt
            }
        }
        .sheet(isPresented: $showConnect) { FaceitConnectSheet().presentationDetents([.large]) }
    }

    private var prompt: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                FaceitLogo()
                VStack(alignment: .leading, spacing: 4) {
                    Text("Подключи Faceit")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text(game.isDemo
                         ? "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам. Сейчас показаны демо-данные."
                         : "ELO, уровень, матчи, K/D, ADR, хедшоты, клатчи и статистика по картам подтянутся автоматически.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            GradientButton(title: "Подключить", systemImage: "link") { showConnect = true }
        }
        .pulseCard(padding: 16)
    }

    private var connected: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 12) {
                FaceitLogo()
                VStack(alignment: .leading, spacing: 2) {
                    Text("Faceit · \(game.faceit?.nickname ?? game.nickname ?? "")")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .lineLimit(1)
                    Text(status)
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer(minLength: 8)
                if game.isSyncing { ProgressView().tint(Theme.Palette.orange) }
                Menu {
                    Button("Обновить", systemImage: "arrow.clockwise") { Task { await game.refresh() } }
                    Button("Сменить аккаунт или ключ", systemImage: "key") { showConnect = true }
                    Button("Отключить", systemImage: "xmark.circle", role: .destructive) { game.disconnect() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 36, height: 36)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Настройки Faceit")
            }
            if let error = game.error { ErrorBanner(message: error) }
        }
        .pulseCard(padding: 14)
    }

    private var status: String {
        if game.isSyncing && game.faceit == nil { return "Загружаем данные…" }
        guard let profile = game.faceit else { return "Подключено" }
        return "Обновлено в \(Formatters.time(profile.fetchedAt)) · \(profile.matches.count) матчей"
    }
}

private struct FaceitLogo: View {
    var body: some View {
        Image(systemName: "flame.fill")
            .font(.system(size: 18, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: 40, height: 40)
            .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(Color(hex: 0xFF5500)))
    }
}

struct FaceitConnectSheet: View {
    @Environment(GameStore.self) private var game
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var nickname = ""
    @State private var apiKey = ""

    var body: some View {
        SheetScaffold(title: "Faceit") {
            PulseTextField(title: "Ник или ссылка на профиль", text: $nickname, placeholder: "https://www.faceit.com/ru/players/…")
            // The app's own key (from the build) is enough: only builds without it ask the player for one.
            if game.hasBuiltInKey {
                Text("ELO, уровень, матчи и статистика по картам подтянутся по нику.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            } else {
                keyFields
            }
            if let error = game.error { ErrorBanner(message: error) }
            GradientButton(title: game.isSyncing ? "Подключаем…" : "Подключить", systemImage: "link") {
                Task {
                    await game.connect(nickname: nickname, apiKey: apiKey)
                    if game.error == nil { dismiss() }
                }
            }
            .disabled(game.isSyncing)
        }
        .onAppear {
            nickname = game.nickname ?? session.user?.faceitURL ?? ""
        }
    }

    @ViewBuilder private var keyFields: some View {
        PulseTextField(title: game.hasApiKey ? "API-ключ (сохранён, можно заменить)" : "API-ключ *", text: $apiKey,
                       placeholder: "xxxxxxxx-xxxx-…", isSecure: true)
        VStack(alignment: .leading, spacing: 8) {
            Text("Где взять ключ")
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(Theme.Palette.textPrimary)
            Text("developers.faceit.com → App Studio → создать приложение → API Keys → ключ типа Client side. Ключ хранится только на этом iPhone в Keychain; когда появится сервер Pulse, он будет обращаться к Faceit сам.")
                .font(.system(size: 13))
                .foregroundStyle(Theme.Palette.textSecondary)
            if let url = URL(string: "https://developers.faceit.com") {
                Link("Открыть портал разработчиков", destination: url)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.Palette.cyan)
            }
        }
        .pulseCard(padding: 14)
    }
}

/// Real Faceit numbers: lifetime, the selected period from match stats, and per-map results.
struct FaceitDetailsSection: View {
    let profile: FaceitProfile
    let matches: [GameMatch]
    let period: Int

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            accountCard
            if let lifetime = profile.lifetime {
                PulseSection(title: "За всё время") { lifetimeCard(lifetime) }
            }
            PulseSection(title: "Подробно · \(period) дней") { periodCard }
            if !profile.maps.isEmpty {
                PulseSection(title: "Карты") { mapsCard }
            }
            Text("Данные Faceit Data API: последние \(FaceitClient.matchLimit) матчей CS2. История ELO копится с момента подключения — Faceit отдаёт только текущее значение.")
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textTertiary)
        }
    }

    private var accountCard: some View {
        HStack(spacing: 14) {
            if let level = profile.skillLevel {
                Text("\(level)")
                    .font(.system(size: 22, weight: .heavy))
                    .foregroundStyle(.white)
                    .frame(width: 48, height: 48)
                    .background(Circle().fill(levelColor(level)))
                    .accessibilityLabel("Уровень \(level)")
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(profile.nickname).font(.system(size: 18, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text([profile.country?.uppercased(), profile.region, profile.regionRank.map { "#\(Formatters.grouped($0)) в регионе" }]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer(minLength: 0)
            VStack(alignment: .trailing, spacing: 2) {
                Text(Formatters.grouped(profile.elo)).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.orange)
                Text("ELO").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .pulseCard(padding: 16)
    }

    private func lifetimeCard(_ lifetime: FaceitProfile.Lifetime) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
                cell("Матчей", Formatters.grouped(lifetime.matches))
                cell("Победы", "\(Int(lifetime.winRate.rounded()))%", tint: Theme.Palette.cyan)
                cell("K/D", Formatters.decimal(lifetime.kd, fractionDigits: 2))
                cell("Хедшоты", "\(Int(lifetime.headshots.rounded()))%")
                cell("Серия побед", "\(lifetime.longestWinStreak)")
                cell("Текущая серия", "\(lifetime.currentWinStreak)")
                if let adr = lifetime.adr { cell("ADR", Formatters.decimal(adr)) }
                if let entry = lifetime.entrySuccessRate { cell("Успех входа", "\(Int(entry.rounded()))%") }
                if let utility = lifetime.utilityDamagePerRound { cell("Урон гранатами/раунд", Formatters.decimal(utility)) }
                if let clutch = lifetime.clutch1v1WinRate { cell("Клатчи 1v1", "\(Int(clutch.rounded()))%") }
            }
            if !lifetime.recentResults.isEmpty {
                HStack(spacing: 6) {
                    Text("Последние:").font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                    ForEach(Array(lifetime.recentResults.enumerated()), id: \.offset) { _, won in
                        Text(won ? "W" : "L")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 22, height: 22)
                            .background(RoundedRectangle(cornerRadius: 6).fill(won ? Theme.Palette.green : Theme.Palette.red))
                    }
                }
            }
        }
        .pulseCard(padding: 16)
    }

    private var periodCard: some View {
        let details = matches.compactMap(\.details)
        let count = Double(max(details.count, 1))
        let rounds = Double(max(details.reduce(0) { $0 + $1.rounds }, 1))
        let entries = details.reduce(0) { $0 + $1.entryCount }
        let entryWins = details.reduce(0) { $0 + $1.entryWins }
        let headshots = StatisticsMath.mean(details.compactMap(\.headshotsPercent))
        return Group {
            if details.isEmpty {
                Text("За этот период матчей на Faceit нет")
                    .font(.system(size: 15))
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .pulseCard(padding: 16)
            } else {
                LazyVGrid(columns: columns, alignment: .leading, spacing: 14) {
                    cell("Хедшоты", headshots.map { "\(Int($0.rounded()))%" } ?? "—")
                    cell("Первые убийства", Formatters.decimal(Double(details.compactMap(\.firstKills).reduce(0, +)) / count), caption: "за матч")
                    cell("Успех входа", entries > 0 ? "\(Int((Double(entryWins) / Double(entries) * 100).rounded()))%" : "—")
                    cell("Клатчи", "\(details.reduce(0) { $0 + $1.clutchesWon })", caption: "1v1 и 1v2")
                    cell("Мультикиллы", Formatters.decimal(Double(details.reduce(0) { $0 + $1.multiKills }) / count), caption: "за матч")
                    cell("MVP", Formatters.decimal(Double(details.compactMap(\.mvps).reduce(0, +)) / count), caption: "за матч")
                    cell("Урон гранатами", Formatters.decimal(Double(details.compactMap(\.utilityDamage).reduce(0, +)) / rounds), caption: "за раунд")
                    cell("Ослеплено", Formatters.decimal(Double(details.compactMap(\.enemiesFlashed).reduce(0, +)) / count), caption: "врагов за матч")
                    cell("Со снайперки", "\(details.compactMap(\.sniperKills).reduce(0, +))", caption: "убийств")
                }
                .pulseCard(padding: 16)
            }
        }
    }

    private var mapsCard: some View {
        VStack(spacing: 0) {
            ForEach(Array(profile.maps.enumerated()), id: \.element.id) { index, map in
                HStack(spacing: 12) {
                    Text(map.map.replacingOccurrences(of: "de_", with: "").capitalized)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .frame(width: 90, alignment: .leading)
                    Text("\(map.matches) матчей")
                        .font(.system(size: 13))
                        .foregroundStyle(Theme.Palette.textSecondary)
                    Spacer(minLength: 4)
                    Text("\(Int(map.winRate.rounded()))%")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(map.winRate >= 50 ? Theme.Palette.green : Theme.Palette.red)
                        .frame(width: 48, alignment: .trailing)
                    Text("K/D \(Formatters.decimal(map.kd, fractionDigits: 2))")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 70, alignment: .trailing)
                }
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.vertical, 12)
                if index < profile.maps.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 16)
        .pulseCard(padding: 0)
    }

    private func cell(_ title: String, _ value: String, caption: String? = nil, tint: Color = Theme.Palette.textPrimary) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value).font(.system(size: 19, weight: .bold)).foregroundStyle(tint).monospacedDigit()
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(caption.map { "\(title), \($0)" } ?? title)
                .font(.system(size: 12))
                .foregroundStyle(Theme.Palette.textSecondary)
                .lineLimit(2)
                .minimumScaleFactor(0.85)
        }
    }

    /// Faceit level colours: grey 1, green 2–3, yellow 4–7, orange 8–9, red 10.
    private func levelColor(_ level: Int) -> Color {
        switch level {
        case ..<2: Color(hex: 0x9E9E9E)
        case 2...3: Theme.Palette.green
        case 4...7: Theme.Palette.yellow
        case 8...9: Theme.Palette.orange
        default: Theme.Palette.red
        }
    }
}
