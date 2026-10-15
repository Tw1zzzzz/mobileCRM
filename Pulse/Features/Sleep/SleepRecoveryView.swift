import SwiftUI
import Charts

/// Mockup 2 — «Сон · Восстановление и режим».
struct SleepRecoveryView: View {
    @Environment(SleepStore.self) private var store
    @State private var showWindowEditor = false

    private var night: SleepNight { store.displayNight ?? .placeholder }
    @State private var selectedSample: Double?
    private var week: [SleepWeekDay] { store.week }

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Сон", subtitle: "Восстановление и режим") {
                HeaderIconButton(systemName: "gearshape") { showWindowEditor = true }
                    .accessibilityLabel("Настройки сна")
            }
        } content: {
            PulseSection(title: "Готовность к нагрузке") { RecoveryCard() }
            if store.displayNight != nil {
                PulseSection(title: "Тело ночью") { bodyCard }
            } else {
                NoSleepDataCard()
            }
            PulseSection(title: "Неделя") { weekCard }
            PulseSection(title: "Режим сна") { regimeCard }
        }
        .sheet(isPresented: $showWindowEditor) {
            SleepWindowEditor(window: store.window) { store.updateWindow($0) }
                .presentationDetents([.medium])
        }
    }

    // MARK: Body at night

    private var bodyCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack {
                Text("Пульс").font(.system(size: 18)).foregroundStyle(Theme.Palette.textSecondary)
                Spacer()
                if let delta = night.heartRateBaselineDelta {
                    // A lower resting pulse than usual means better recovery.
                    HStack(spacing: 6) {
                        Image(systemName: delta <= 0 ? "arrow.down" : "arrow.up")
                        Text("\(abs(delta)) к базовому")
                    }
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(delta <= 0 ? Theme.Palette.green : Theme.Palette.orange)
                }
            }
            if let resting = night.restingHeartRate {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("\(resting)")
                        .font(.system(size: 56, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                    Text(night.minHeartRate.map { "уд/мин · мин. \($0)" } ?? "уд/мин")
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            if night.heartRate.isEmpty {
                Label("Пульс ночью, HRV, дыхание и кислород записывают Apple Watch и другие трекеры, подключённые к Apple Health.",
                      systemImage: "applewatch")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            } else {
                heartRateChart.frame(height: 84)
            }
            DividerLine()
            vitalsRow
        }
        .pulseCard(padding: 20)
    }

    private var vitalsRow: some View {
        let accent = AnyShapeStyle(Theme.Gradients.accent)
        let purple = AnyShapeStyle(Theme.Palette.purple)
        return HStack(alignment: .top, spacing: 0) {
            // Bars show where the value sits in its typical range (breathing 8–22, HRV up to 120 ms, SpO₂ 90–100 %).
            vital("ДЫХАНИЕ", value: night.respiratoryRate.map(String.init) ?? "—", unit: "вд/мин",
                  progress: night.respiratoryRate.map { Double($0 - 8) / 14 } ?? 0, tint: accent)
            DividerLine(vertical: true).frame(height: 96)
            vital("HRV", value: night.hrv.map(String.init) ?? "—", unit: "мс",
                  progress: night.hrv.map { Double($0) / 120 } ?? 0, tint: accent)
                .padding(.leading, 16)
            DividerLine(vertical: true).frame(height: 96)
            vital("SpO₂", value: night.spo2.map(String.init) ?? "—", unit: "%",
                  progress: night.spo2.map { Double($0 - 90) / 10 } ?? 0, tint: purple)
                .padding(.leading, 16)
        }
    }

    private struct HeartPoint: Identifiable {
        let id: Int
        let bpm: Double
    }

    private var heartRateChart: some View {
        let points = night.heartRate.enumerated().map { HeartPoint(id: $0.offset, bpm: Double($0.element)) }
        let lower = Double((night.heartRate.min() ?? 50) - 6)
        let upper = Double((night.heartRate.max() ?? 70) + 2)
        let fill = LinearGradient(
            colors: [Theme.Palette.cyan.opacity(0.35), Theme.Palette.blue.opacity(0.02)],
            startPoint: .top, endPoint: .bottom
        )
        return Chart {
            ForEach(points) { point in
                AreaMark(
                    x: .value("t", point.id),
                    yStart: .value("min", lower),
                    yEnd: .value("bpm", point.bpm)
                )
                .foregroundStyle(fill)
                LineMark(x: .value("t", point.id), y: .value("bpm", point.bpm))
                    .foregroundStyle(Theme.Palette.cyan)
                    .lineStyle(StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
            }
            if let sample = selectedSample, points.indices.contains(Int(sample)) {
                let point = points[Int(sample)]
                RuleMark(x: .value("t", point.id))
                    .foregroundStyle(Theme.Palette.textSecondary.opacity(0.55))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .annotation(position: .top, spacing: 4, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                        ChartCallout(title: Formatters.time(sampleTime(point.id, count: points.count)),
                                     value: "\(Int(point.bpm)) уд/мин", tint: Theme.Palette.cyan)
                    }
                ScrubPoint(x: .value("t", point.id), y: point.bpm, tint: Theme.Palette.cyan)
            } else if let last = points.last {
                PointMark(x: .value("t", last.id), y: .value("bpm", last.bpm))
                    .foregroundStyle(Theme.Palette.cyan)
                    .symbolSize(90)
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartYScale(domain: lower...upper)
        .chartXScale(domain: 0...max(points.count - 1, 1))
        .chartScrub($selectedSample) { min(max($0.rounded(), 0), Double(points.count - 1)) }
    }

    /// Samples are spread evenly across the night.
    private func sampleTime(_ index: Int, count: Int) -> Date {
        let step = night.end.timeIntervalSince(night.start) / Double(max(count - 1, 1))
        return night.start.addingTimeInterval(step * Double(index))
    }

    private func vital(_ title: String, value: String, unit: String, progress: Double, tint: AnyShapeStyle) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .tracking(1)
                .foregroundStyle(Theme.Palette.textSecondary)
            ValueUnitText(value: value, unit: unit, valueSize: 34, unitSize: 15)
            GradientBar(progress: progress, height: 7, fill: tint)
                .padding(.trailing, 14)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: Week

    private var weekCard: some View {
        // Average over nights that have data; max(…, 1) keeps empty weeks from dividing by zero.
        let nights = week.filter { $0.total > 0 }
        let average = nights.reduce(0) { $0 + $1.total } / max(nights.count, 1)
        let maxTotal = CGFloat(max(week.map(\.total).max() ?? 0, 1))
        return VStack(spacing: 22) {
            HStack {
                Text("Среднее за 7 ночей").font(.system(size: 18)).foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                DurationText(minutes: average, valueSize: 28, unitSize: 20, unitColor: Theme.Palette.textPrimary)
            }
            HStack(alignment: .bottom, spacing: 18) {
                ForEach(week) { day in
                    VStack(spacing: 12) {
                        StackedSleepBar(day: day, height: 100 * CGFloat(day.total) / maxTotal)
                        Text(Formatters.weekdayShort(day.date))
                            .font(.system(size: 16, weight: day.date.isSameDay(as: .now) ? .bold : .regular))
                            .foregroundStyle(day.date.isSameDay(as: .now) ? Theme.Palette.textPrimary : Theme.Palette.textSecondary)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .frame(height: 136, alignment: .bottom)
        }
        .pulseCard(padding: 20)
    }

    // MARK: Regime

    private var regimeCard: some View {
        @Bindable var store = store
        return VStack(spacing: 0) {
            HStack(spacing: 14) {
                IconBadge(systemName: "moon", tint: Theme.Palette.purple, size: 54, iconSize: 24, background: Color(hex: 0x1E2B4D))
                VStack(alignment: .leading, spacing: 6) {
                    Text("Окно сна").font(.system(size: 18, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                    Text("\(store.window.bedtimeText) → \(store.window.wakeText) · \(store.window.durationMinutes / 60) ч")
                        .font(.system(size: 15, design: .monospaced))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                Spacer()
                Button("Править") { showWindowEditor = true }
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Theme.Palette.cyan)
            }
            .padding(.vertical, 18)
            DividerLine()
            HStack(spacing: 14) {
                IconBadge(systemName: "bell", tint: Theme.Palette.orange, size: 54, iconSize: 22, background: Color(hex: 0x2E2A26))
                VStack(alignment: .leading, spacing: 6) {
                    Text("Умный будильник").font(.system(size: 18, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary).lineLimit(1)
                    Text("В лёгкой фазе ±20 мин").font(.system(size: 15)).lineLimit(1).minimumScaleFactor(0.8).foregroundStyle(Theme.Palette.textSecondary)
                }
                Spacer()
                Toggle("", isOn: Binding(
                    get: { store.window.smartAlarmEnabled },
                    set: { enabled in
                        var updated = store.window
                        updated.smartAlarmEnabled = enabled
                        store.updateWindow(updated)
                    }
                ))
                .labelsHidden()
                .tint(Theme.Palette.blue)
            }
            .padding(.vertical, 18)
        }
        .padding(.horizontal, 18)
        .pulseCard(padding: 0)
    }
}

struct StackedSleepBar: View {
    let day: SleepWeekDay
    let height: CGFloat

    var body: some View {
        let total = CGFloat(max(day.total, 1))
        VStack(spacing: 0) {
            Rectangle().fill(Theme.Palette.navyBar).frame(height: height * CGFloat(day.light) / total)
            Rectangle().fill(Color(hex: 0x2A86F7)).frame(height: height * CGFloat(day.rem) / total)
            Rectangle().fill(Theme.Palette.cyan).frame(height: height * CGFloat(day.deep) / total)
        }
        .frame(maxWidth: .infinity)
        .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
    }
}

struct SleepWindowEditor: View {
    let onSave: (SleepWindow) -> Void
    @State private var bedtime: Date
    @State private var wake: Date
    private let original: SleepWindow
    @Environment(\.dismiss) private var dismiss

    init(window: SleepWindow, onSave: @escaping (SleepWindow) -> Void) {
        self.onSave = onSave
        self.original = window
        _bedtime = State(initialValue: Date.now.setting(hour: window.bedtimeHour, minute: window.bedtimeMinute))
        _wake = State(initialValue: Date.now.setting(hour: window.wakeHour, minute: window.wakeMinute))
    }

    var body: some View {
        SheetScaffold(title: "Окно сна") {
            VStack(spacing: 14) {
                DatePicker("Отбой", selection: $bedtime, displayedComponents: .hourAndMinute)
                DividerLine()
                DatePicker("Подъём", selection: $wake, displayedComponents: .hourAndMinute)
            }
            .font(.system(size: 18))
            .foregroundStyle(Theme.Palette.textPrimary)
            .pulseCard()
            GradientButton(title: "Сохранить") {
                let calendar = Calendar.pulse
                var updated = original
                updated.bedtimeHour = calendar.component(.hour, from: bedtime)
                updated.bedtimeMinute = calendar.component(.minute, from: bedtime)
                updated.wakeHour = calendar.component(.hour, from: wake)
                updated.wakeMinute = calendar.component(.minute, from: wake)
                onSave(updated)
                dismiss()
            }
        }
    }
}
