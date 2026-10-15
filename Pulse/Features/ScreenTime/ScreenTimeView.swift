import SwiftUI
import Charts

/// Mockup 5 — «Экранное время» (сегодня, по часам, топ приложений).
struct ScreenTimeView: View {
    @Environment(ScreenTimeStore.self) private var store
    @Environment(AppRouter.self) private var router
    @State private var showSettings = false
    @State private var showEntry = false
    @State private var scrubHour: Double?
    private var selectedHour: Int? { scrubHour.map { Int($0) } }

    static let peakThreshold = 28

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Экранное время") {
                HeaderIconButton(systemName: "gearshape") { showSettings = true }
                    .accessibilityLabel("Настройки экранного времени")
            }
        } content: {
            if store.showsContent {
                todayCard
                if store.hasDetailedData {
                    tilesRow
                    PulseSection(title: "По часам") { hourlyCard }
                    PulseSection(title: "Топ приложений", actionTitle: "Все") {
                        router.open(.screenTimeApps, in: router.selectedTab)
                    } content: {
                        AppUsageList(apps: Array(store.apps.prefix(2)), style: .filled)
                    }
                } else {
                    ManualScreenTimeInfo()
                    HubRow(icon: "chart.bar", tint: Theme.Palette.cyan, title: "Неделя и история",
                           subtitle: "Динамика по введённым дням", route: .screenTimeApps)
                }
            } else {
                ScreenTimeEmptyState { showSettings = true }
            }
        }
        .sheet(isPresented: $showSettings) {
            ScreenTimeSettingsSheet().presentationDetents([.medium])
        }
        .sheet(isPresented: $showEntry) {
            ScreenTimeEntrySheet().presentationDetents([.medium, .large])
        }
    }

    private var todayCard: some View {
        let withinLimit = store.remainingMinutes >= 0
        return VStack(alignment: .leading, spacing: 14) {
            Text("СЕГОДНЯ")
                .font(.system(size: 15, weight: .semibold))
                .tracking(2.4)
                .foregroundStyle(Theme.Palette.cyan)
            DurationText(minutes: store.todayMinutes, valueSize: 76, unitSize: 46)
            HStack(spacing: 4) {
                Text("Лимит дня:").foregroundStyle(Theme.Palette.textSecondary)
                Text(limitText).foregroundStyle(Theme.Palette.textPrimary).monospacedDigit()
            }
            .font(.system(size: 18))
            HStack(spacing: 16) {
                GradientBar(progress: store.limitProgress, height: 14)
                Text("\(Int((store.limitProgress * 100).rounded()))%")
                    .font(.system(size: 16))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            Text(withinLimit
                 ? "В пределах лимита · осталось \(store.remainingMinutes) мин"
                 : "Лимит превышен на \(abs(store.remainingMinutes)) мин")
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(withinLimit ? Theme.Palette.green : Theme.Palette.red)
            if !store.isDemo {
                GradientButton(title: store.minutes(on: .now) == nil ? "Ввести время за сегодня" : "Изменить",
                               systemImage: "square.and.pencil") { showEntry = true }
            }
        }
        .pulseCard(padding: 22)
    }

    private var limitText: String {
        let parts = Formatters.durationParts(minutes: store.dailyLimitMinutes)
        return "\(parts.hours) ч  \(parts.minutes) м"
    }

    private var tilesRow: some View {
        let change = store.changeVsYesterday
        let diff = abs(store.yesterdayMinutes - store.todayMinutes)
        return HStack(spacing: 12) {
            infoTile(icon: "bell", tint: Theme.Palette.yellow, value: "\(store.pickups)", title: "Поднятий",
                     caption: "≈ \(store.pickups / 17) / час")
            infoTile(icon: "sun.max", tint: Theme.Palette.purple, value: store.firstPickup, title: "Первый экран",
                     caption: "время")
            // As in the mockup: green "trend" icon means the day got better (less screen time).
            infoTile(icon: change <= 0 ? "chart.line.uptrend.xyaxis" : "chart.line.downtrend.xyaxis",
                     tint: change <= 0 ? Theme.Palette.green : Theme.Palette.red,
                     value: "\(change > 0 ? "+" : "")\(Int(change.rounded()))%", title: "Ко вчера",
                     caption: "\(diff / 60) ч \(diff % 60) м \(change <= 0 ? "меньше" : "больше")")
        }
    }

    private func infoTile(icon: String, tint: Color, value: String, title: String, caption: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: icon).font(.system(size: 20)).foregroundStyle(tint)
                .padding(.bottom, 4)
            Text(value)
                .font(.system(size: 28, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                .lineLimit(1).minimumScaleFactor(0.75)
            Text(caption).font(.system(size: 14)).foregroundStyle(Theme.Palette.textTertiary)
                .lineLimit(1).minimumScaleFactor(0.7)
        }
        .padding(.horizontal, -2)
        .pulseCard(padding: 14, radius: Theme.Metrics.tileRadius)
    }

    private var hourlyCard: some View {
        VStack(alignment: .leading, spacing: 18) {
            Chart {
                ForEach(Array(store.hourly.enumerated()), id: \.offset) { hour, minutes in
                    BarMark(x: .value("Час", hour), y: .value("Мин", minutes), width: .fixed(10))
                        .foregroundStyle(minutes > Self.peakThreshold ? AnyShapeStyle(Theme.Palette.red) : AnyShapeStyle(Theme.Gradients.barVertical))
                        .clipShape(RoundedRectangle(cornerRadius: 3, style: .continuous))
                        .opacity(selectedHour == nil || selectedHour == hour ? 1 : 0.35)
                }
                if let hour = selectedHour, store.hourly.indices.contains(hour) {
                    RuleMark(x: .value("Час", hour))
                        .foregroundStyle(.clear)
                        .annotation(position: .top, spacing: 0, overflowResolution: .init(x: .fit(to: .chart), y: .fit(to: .chart))) {
                            ChartCallout(title: String(format: "%02d:00–%02d:00", hour, (hour + 1) % 24),
                                         value: "\(store.hourly[hour]) мин", tint: Theme.Palette.cyan)
                        }
                }
            }
            .chartScrub($scrubHour) { (0...23).contains(Int($0.rounded())) ? $0.rounded() : nil }
            .chartYScale(domain: 0...60)
            .chartXScale(domain: -0.8...24.2)
            .chartYAxis {
                AxisMarks(position: .leading, values: [0, 30, 60]) { value in
                    AxisGridLine(stroke: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                        .foregroundStyle(Theme.Palette.divider)
                    AxisValueLabel {
                        if let minutes = value.as(Int.self) {
                            Text(minutes == 0 ? "0\nмин" : "\(minutes)")
                                .font(.system(size: 14))
                                .foregroundStyle(Theme.Palette.textSecondary)
                        }
                    }
                }
            }
            .chartXAxis {
                AxisMarks(values: [0, 6, 12, 18, 23]) { value in
                    AxisValueLabel {
                        if let hour = value.as(Int.self) {
                            Text(String(format: "%02d", hour))
                                .font(.system(size: 15))
                                .foregroundStyle(Theme.Palette.textSecondary)
                        }
                    }
                }
            }
            .frame(height: 210)
            .padding(.trailing, 6)
            HStack(spacing: 28) {
                legend(color: Theme.Palette.cyan, title: "Обычно")
                legend(color: Theme.Palette.red, title: "Пик (> \(Self.peakThreshold) мин)")
            }
        }
        .pulseCard(padding: 20)
    }

    private func legend(color: Color, title: String) -> some View {
        HStack(spacing: 10) {
            RoundedRectangle(cornerRadius: 4).fill(color).frame(width: 18, height: 18)
            Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textSecondary)
        }
    }
}

enum AppIconStyle {
    /// Saturated brand-colored tile (mockup 5).
    case filled
    /// Dark tile with colored outline glyph (mockup 6).
    case outlined
}

struct AppUsageList: View {
    let apps: [AppUsage]
    var style: AppIconStyle = .outlined

    var body: some View {
        let maxMinutes = Double(apps.map(\.minutes).max() ?? 1)
        VStack(spacing: 0) {
            ForEach(Array(apps.enumerated()), id: \.element.id) { index, app in
                HStack(spacing: 16) {
                    icon(for: app)
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .firstTextBaseline) {
                            Text(app.name).font(.system(size: 19, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                            Spacer()
                            Text(Formatters.duration(minutes: app.minutes))
                                .font(.system(size: 19, weight: .semibold))
                                .foregroundStyle(Theme.Palette.textPrimary)
                                .monospacedDigit()
                        }
                        Text(app.category.title).font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                        GradientBar(progress: Double(app.minutes) / maxMinutes * 0.72, height: 7)
                            .padding(.top, 4)
                    }
                }
                .padding(.vertical, 14)
                if index < apps.count - 1 { DividerLine() }
            }
        }
        .padding(.horizontal, 18)
        .pulseCard(padding: 0)
    }

    @ViewBuilder
    private func icon(for app: AppUsage) -> some View {
        switch style {
        case .filled:
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(app.tint.opacity(0.22))
                .frame(width: 56, height: 56)
                .overlay(
                    Image(systemName: app.icon)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(app.tint)
                )
        case .outlined:
            IconBadge(systemName: app.icon, tint: app.tint, size: 56, iconSize: 22, background: Theme.Palette.track)
        }
    }
}

struct ScreenTimeEmptyState: View {
    let onOpenSettings: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            EmptyStateView(
                systemName: "hourglass",
                title: "Нет данных",
                message: "Нет данных об экранном времени. Проверьте разрешения устройства."
            )
            GradientButton(title: "Дать доступ", systemImage: "lock.open") { onOpenSettings() }
        }
    }
}

struct ScreenTimeSettingsSheet: View {
    @Environment(ScreenTimeStore.self) private var store

    private let limits = [180, 240, 300, 360, 420]

    var body: some View {
        @Bindable var store = store
        SheetScaffold(title: "Экранное время") {
            Toggle(isOn: $store.isAuthorized) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Доступ к Screen Time").font(.system(size: 17, weight: .semibold))
                    Text("Нужен системный доступ FamilyControls").font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .tint(Theme.Palette.blue)
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard()

            Text("ЛИМИТ ДНЯ")
                .font(.system(size: 14, weight: .semibold))
                .tracking(2)
                .foregroundStyle(Theme.Palette.section)
            HStack(spacing: 8) {
                ForEach(limits, id: \.self) { limit in
                    ChipButton(title: "\(limit / 60) ч", isSelected: store.dailyLimitMinutes == limit) {
                        store.dailyLimitMinutes = limit
                    }
                }
            }
        }
    }
}

/// Where to find the number while automatic collection isn't available.
struct ManualScreenTimeInfo: View {
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: "hourglass")
                .font(.system(size: 22))
                .foregroundStyle(Theme.Palette.cyan)
            VStack(alignment: .leading, spacing: 4) {
                Text("Время берётся из настроек iPhone")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text("Открой Настройки → Экранное время → «Все действия» и перенеси сюда время за день. Автоматический сбор, разбивка по часам и приложениям появятся, когда Apple выдаст Pulse доступ к Экранному времени.")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .pulseCard(padding: 16)
    }
}

/// Manual entry of a day's screen time (today or an earlier day).
struct ScreenTimeEntrySheet: View {
    @Environment(ScreenTimeStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @State private var day = Date.now
    @State private var hours = 3
    @State private var minutes = 0

    var body: some View {
        SheetScaffold(title: "Экранное время") {
            VStack(spacing: 16) {
                DatePicker("День", selection: $day, in: ...Date.now, displayedComponents: .date)
                DividerLine()
                Stepper("Часы: \(hours)", value: $hours, in: 0...20)
                Stepper("Минуты: \(minutes)", value: $minutes, in: 0...55, step: 5)
            }
            .font(.system(size: 17))
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard()
            GradientButton(title: "Сохранить", systemImage: "checkmark") {
                store.setMinutes(hours * 60 + minutes, on: day)
                dismiss()
            }
        }
        .onChange(of: day) { _, newDay in load(newDay) }
        .onAppear { load(day) }
    }

    private func load(_ day: Date) {
        guard let saved = store.minutes(on: day) else { return }
        hours = saved / 60
        minutes = saved % 60 / 5 * 5
    }
}
