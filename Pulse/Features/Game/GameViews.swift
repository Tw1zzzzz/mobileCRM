import SwiftUI
import Charts

/// Compact game card: ELO + 7-day delta + sparkline, and K/D · ADR · Win-rate over the last 10 matches.
struct GameSummaryCard: View {
    let player: PlayerProfile
    let action: () -> Void
    @Environment(GameGoalsStore.self) private var goals
    @Environment(GameStore.self) private var game
    @Environment(SessionStore.self) private var session

    var body: some View {
        let history = game.eloHistory(for: session.user, days: 14)
        // With real data the history is our own daily snapshots, so it may be shorter than a week.
        let weekAgo = history.first { $0.date >= Date.now.startOfDay.adding(days: -7) }?.value ?? Double(player.elo)
        let delta = player.elo - Int(weekAgo)
        let summary = GameSummary(matches: game.recentMatches(for: session.user, count: 10))
        Button(action: action) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("ELO FACEIT")
                            .font(.system(size: 13, weight: .semibold))
                            .tracking(1.6)
                            .foregroundStyle(Theme.Palette.textSecondary)
                        // Number and delta stacked: side by side «2 840» wrapped onto two lines on narrow phones.
                        Text("\(player.elo)")
                            .font(.system(size: 36, weight: .bold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                            .monospacedDigit()
                            .lineLimit(1)
                            .fixedSize()
                        DeltaLabel(value: delta, suffix: "за 7 дн.")
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Sparkline(values: history, color: delta >= 0 ? Theme.Palette.cyan : Theme.Palette.orange)
                        .frame(width: 120, height: 52)
                }
                if let eloGoal = GoalMath.progress(goals: goals.goals, elo: player.elo, recent: summary)
                    .first(where: { $0.metric == .elo }) {
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Label("Цель \(eloGoal.target)", systemImage: "target")
                                .font(.system(size: 13, weight: .semibold))
                                .foregroundStyle(Theme.Palette.cyan)
                            Spacer()
                            Text(eloGoal.note)
                                .font(.system(size: 13))
                                .foregroundStyle(eloGoal.isReached ? Theme.Palette.green : Theme.Palette.textSecondary)
                        }
                        GradientBar(progress: eloGoal.progress, height: 6)
                    }
                }
                DividerLine()
                Text("Последние 10 матчей")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(Theme.Palette.textTertiary)
                HStack {
                    stat("K/D", Formatters.decimal(summary.kd, fractionDigits: 2))
                    Spacer()
                    stat("ADR", Formatters.decimal(summary.adr))
                    Spacer()
                    stat("Win-rate", "\(Int(summary.winRate.rounded()))%")
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textTertiary)
                }
            }
            .pulseCard()
        }
        .buttonStyle(.pressable)
        .accessibilityLabel("ELO \(player.elo), K/D \(Formatters.decimal(summary.kd, fractionDigits: 2))")
    }

    private func stat(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: 19, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary).monospacedDigit()
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }
}

struct DeltaLabel: View {
    let value: Int
    var suffix = ""

    var body: some View {
        let tint = value >= 0 ? Theme.Palette.green : Theme.Palette.red
        HStack(spacing: 3) {
            Image(systemName: value >= 0 ? "arrow.up.right" : "arrow.down.right")
            Text("\(value >= 0 ? "+" : "")\(value) \(suffix)")
        }
        .font(.system(size: 13, weight: .semibold))
        .foregroundStyle(tint)
        .lineLimit(1)
    }
}

struct MatchRow: View {
    let match: GameMatch

    var body: some View {
        HStack(spacing: 12) {
            RoundedRectangle(cornerRadius: 2)
                .fill(match.won ? Theme.Palette.green : Theme.Palette.red)
                .frame(width: 4, height: 40)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 8) {
                    Text(match.map.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                    Text(match.score)
                        .font(.system(size: 14, weight: .bold))
                        .foregroundStyle(match.won ? Theme.Palette.green : Theme.Palette.red)
                }
                Text(Formatters.dayMonthTime(match.date)).font(.system(size: 12)).foregroundStyle(Theme.Palette.textTertiary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 3) {
                Text("\(match.kills)/\(match.deaths)/\(match.assists)")
                    .font(.system(size: 15, weight: .semibold, design: .monospaced))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("ADR \(Int(match.adr)) · \(match.eloDelta >= 0 ? "+" : "")\(match.eloDelta) ELO")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .padding(.vertical, 10)
    }
}

/// «Игровая статистика» для игрока: ELO, средние, последние матчи и подробные CS2-метрики.
struct GameStatsView: View {
    @Environment(SessionStore.self) private var session
    @Environment(GameStore.self) private var game
    @State private var period = 14
    @State private var eloDay: Date?

    var body: some View {
        let player = game.profile(for: session.user)
        let matches = game.matches(for: session.user, days: period)
        let summary = GameSummary(matches: matches)
        ScreenScaffold {
            NavHeader(title: "Игровая статистика", subtitle: "CS2 · Faceit")
        } content: {
            FaceitSourceCard()
            if game.hasData {
                stats(player: player, matches: matches, summary: summary)
            } else {
                EmptyStateView(systemName: "gamecontroller", title: "Нет игровых данных",
                               message: "Подключи Faceit — матчи, ELO и статистика подтянутся автоматически.")
            }
        }
        .task { await game.refresh() }
        .refreshable { await game.refresh() }
    }

    @ViewBuilder
    private func stats(player: PlayerProfile, matches: [GameMatch], summary: GameSummary) -> some View {
            HStack(spacing: 8) {
                ForEach([7, 14, 30], id: \.self) { days in
                    ChipButton(title: "\(days) дней", isSelected: period == days) { withAnimation { period = days } }
                }
            }
            eloCard(player)
            HStack(spacing: 10) {
                tile("Матчей", "\(summary.matches)")
                tile("Win-rate", "\(Int(summary.winRate.rounded()))%")
                tile("K/D", Formatters.decimal(summary.kd, fractionDigits: 2))
                tile("ADR", "\(Int(summary.adr))")
            }
            PulseSection(title: "Последние матчи") {
                if matches.isEmpty {
                    EmptyStateView(systemName: "gamecontroller", title: "Матчей нет", message: "За выбранный период матчей не найдено")
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(matches.prefix(8).enumerated()), id: \.element.id) { index, match in
                            MatchRow(match: match)
                            if index < min(matches.count, 8) - 1 { DividerLine() }
                        }
                    }
                    .padding(.horizontal, 16)
                    .pulseCard(padding: 0)
                }
            }
            if let faceit = game.faceit {
                FaceitDetailsSection(profile: faceit, matches: matches, period: period)
            } else {
                PulseSection(title: "Подробная статистика · \(period) дней") { PlayerGameStatsView(stats: GameData.stats(for: player, days: period)) }
            }
    }

    private func eloCard(_ player: PlayerProfile) -> some View {
        let history = game.eloHistory(for: session.user, days: period)
        let first = Int(history.first?.value ?? Double(player.elo))
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text("\(player.elo)").font(.system(size: 34, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary).lineLimit(1)
                Text("ELO").font(.system(size: 16, weight: .semibold)).foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                DeltaLabel(value: player.elo - first, suffix: "за период")
            }
            Chart {
                ForEach(history) { item in
                    LineMark(x: .value("День", item.date, unit: .day), y: .value("ELO", item.value))
                        .foregroundStyle(Theme.Palette.cyan)
                        .lineStyle(StrokeStyle(lineWidth: 2.5))
                        .interpolationMethod(.monotone)
                    PointMark(x: .value("День", item.date, unit: .day), y: .value("ELO", item.value))
                        .foregroundStyle(Theme.Palette.cyan)
                        .symbolSize(18)
                }
                if let selected = history.value(on: eloDay) {
                    DayScrubRule(date: selected.date, callout: ChartCallout(
                        title: Formatters.dayMonthShort(selected.date),
                        value: "\(Int(selected.value)) ELO", tint: Theme.Palette.cyan))
                    ScrubPoint(x: .value("День", selected.date, unit: .day), y: selected.value, tint: Theme.Palette.cyan)
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .pulseChartAxes()
            .chartScrub($eloDay) { history.nearest(to: $0)?.date }
            .frame(height: 160)
        }
        .pulseCard(padding: 18)
    }

    private func tile(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value).font(.system(size: 20, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).fill(Theme.Palette.tile))
        .overlay(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
    }
}
