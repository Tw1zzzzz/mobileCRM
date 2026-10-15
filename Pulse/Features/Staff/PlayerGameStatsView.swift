import SwiftUI

/// K/D, аналитические метрики, матчи, раунды и пистолетки (CS2).
struct PlayerGameStatsView: View {
    let stats: GameStats

    private let columns = [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]

    var body: some View {
        VStack(spacing: 14) {
            group("K/D статистика") {
                cell("Убийства", "\(stats.kills)")
                cell("Смерти", "\(stats.deaths)")
                cell("Ассисты", "\(stats.assists)")
            }
            group("Аналитические метрики") {
                cell("ADR", Formatters.decimal(stats.adr))
                cell("KPR", Formatters.decimal(stats.kpr, fractionDigits: 2))
                cell("Death/round", Formatters.decimal(stats.deathsPerRound, fractionDigits: 2))
                cell("AVG KR", Formatters.decimal(stats.avgKR, fractionDigits: 2))
                cell("AVG KD", Formatters.decimal(stats.avgKD, fractionDigits: 2))
                cell("KAST", "\(Formatters.decimal(stats.kast))%")
                cell("First kills", "\(stats.firstKills)")
                cell("First deaths", "\(stats.firstDeaths)")
                cell("UDR", Formatters.decimal(stats.udr))
                cell("Open duels ±", "\(stats.openDuelDiff > 0 ? "+" : "")\(stats.openDuelDiff)")
                cell("Мультикиллы", Formatters.decimal(stats.avgMultikills))
                cell("Клатчи", "\(stats.clutchesWon)")
                cell("Ослепление", "\(Formatters.decimal(stats.avgFlashTime)) с")
            }
            group("Матчи") {
                cell("Всего", "\(stats.matches)")
                cell("Победы", "\(stats.wins)", tint: Theme.Palette.green)
                cell("Поражения", "\(stats.losses)", tint: Theme.Palette.red)
                cell("Ничьи", "\(stats.draws)")
                cell("Win-Rate", "\(Int(stats.winRate))%", tint: Theme.Palette.cyan)
            }
            group("Раунды") {
                cell("Всего", "\(stats.rounds)")
                cell("Выиграно", "\(stats.roundsWon)", tint: Theme.Palette.green)
                cell("Проиграно", "\(stats.roundsLost)", tint: Theme.Palette.red)
                cell("Round WR", "\(Int(stats.roundWinRate))%", tint: Theme.Palette.cyan)
                cell("AVG Won", Formatters.decimal(stats.avgRoundsWon))
                cell("AVG Lost", Formatters.decimal(stats.avgRoundsLost))
            }
            group("Пистолетные раунды") {
                cell("Всего", "\(stats.pistolRounds)")
                cell("Выиграно", "\(stats.pistolWon)", tint: Theme.Palette.green)
                cell("Pistol WR", "\(Int(stats.pistolWinRate))%", tint: Theme.Palette.cyan)
            }
        }
    }

    private func group<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
            LazyVGrid(columns: columns, alignment: .leading, spacing: 14) { content() }
        }
        .pulseCard(padding: 16)
    }

    private func cell(_ title: String, _ value: String, tint: Color = Theme.Palette.textPrimary) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value).font(.system(size: 19, weight: .bold)).foregroundStyle(tint).monospacedDigit()
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
                .lineLimit(1).minimumScaleFactor(0.8)
        }
    }
}
