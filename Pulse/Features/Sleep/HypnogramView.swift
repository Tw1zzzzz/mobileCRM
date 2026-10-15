import SwiftUI

/// Sleep phases chart ("ФАЗЫ СНА"): stage blocks on four levels with dashed hour guides.
struct HypnogramView: View {
    let night: SleepNight
    var chartHeight: CGFloat = 175

    private var ticks: [Date] {
        let calendar = Calendar.pulse
        var result = [night.start]
        var cursor = calendar.nextDate(after: night.start, matching: DateComponents(minute: 0), matchingPolicy: .nextTime) ?? night.end
        while cursor < night.end.adding(minutes: -45) {
            let hour = calendar.component(.hour, from: cursor)
            if hour % 2 == 1 { result.append(cursor) }
            cursor = cursor.adding(minutes: 60)
        }
        return result + [night.end]
    }

    var body: some View {
        VStack(spacing: 10) {
            Canvas { context, size in
                draw(in: &context, size: size)
            }
            .frame(height: chartHeight)

            GeometryReader { geo in
                ForEach(Array(ticks.enumerated()), id: \.offset) { index, tick in
                    Text(Formatters.time(tick))
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .monospacedDigit()
                        .fixedSize()
                        .position(x: labelX(for: tick, index: index, width: geo.size.width), y: 10)
                }
            }
            .frame(height: 20)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Фазы сна с \(Formatters.time(night.start)) до \(Formatters.time(night.end))")
    }

    private func x(for date: Date, width: CGFloat) -> CGFloat {
        let total = night.end.timeIntervalSince(night.start)
        guard total > 0 else { return 0 }
        return CGFloat(date.timeIntervalSince(night.start) / total) * width
    }

    private func labelX(for date: Date, index: Int, width: CGFloat) -> CGFloat {
        let raw = x(for: date, width: width)
        if index == 0 { return raw + 22 }
        if index == ticks.count - 1 { return raw - 22 }
        return raw
    }

    private func draw(in context: inout GraphicsContext, size: CGSize) {
        let barHeight: CGFloat = 17

        for tick in ticks {
            var guide = Path()
            let gx = x(for: tick, width: size.width)
            guide.move(to: CGPoint(x: gx, y: 0))
            guide.addLine(to: CGPoint(x: gx, y: size.height))
            context.stroke(guide, with: .color(.white.opacity(0.13)), style: StrokeStyle(lineWidth: 1, dash: [3, 4]))
        }

        let segments = night.segments
        for (index, segment) in segments.enumerated() where index > 0 {
            let previous = segments[index - 1]
            let bx = x(for: segment.start, width: size.width)
            var connector = Path()
            connector.move(to: CGPoint(x: bx, y: previous.stage.level * size.height))
            connector.addLine(to: CGPoint(x: bx, y: segment.stage.level * size.height))
            let tint = segment.stage == .awake || previous.stage == .awake ? Theme.Palette.orange : Color.white
            context.stroke(connector, with: .color(tint.opacity(0.45)), lineWidth: 1.2)
        }

        for segment in segments {
            let startX = x(for: segment.start, width: size.width)
            let endX = x(for: segment.end, width: size.width)
            let isAwake = segment.stage == .awake
            let width = max(endX - startX, isAwake ? 5 : 3)
            let height = isAwake && width < 10 ? barHeight + 8 : barHeight
            let rect = CGRect(
                x: startX,
                y: segment.stage.level * size.height - height / 2,
                width: width,
                height: height
            )
            let shape = Path(roundedRect: rect, cornerRadius: min(5, width / 2), style: .continuous)
            let color = segment.stage.color
            context.drawLayer { layer in
                layer.addFilter(.shadow(color: color.opacity(0.45), radius: 6))
                layer.fill(
                    shape,
                    with: .linearGradient(
                        Gradient(colors: [color.opacity(0.95), color.opacity(0.75)]),
                        startPoint: CGPoint(x: rect.midX, y: rect.minY),
                        endPoint: CGPoint(x: rect.midX, y: rect.maxY)
                    )
                )
            }
        }
    }
}

/// 2x2 legend under the hypnogram with a vertical divider.
struct SleepStageLegend: View {
    let night: SleepNight

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 22) {
                item(.awake)
                item(.light)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            DividerLine(vertical: true).frame(height: 130)
            VStack(alignment: .leading, spacing: 22) {
                item(.rem)
                item(.deep)
            }
            .padding(.leading, 28)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func item(_ stage: SleepStage) -> some View {
        HStack(alignment: .top, spacing: 16) {
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(stage.color)
                .frame(width: 22, height: 22)
                .padding(.top, 4)
            VStack(alignment: .leading, spacing: 4) {
                Text(stage.title)
                    .font(.system(size: 17))
                    .foregroundStyle(Theme.Palette.textSecondary)
                Text(Formatters.duration(minutes: night.minutes(in: stage)))
                    .font(.system(size: 21, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .monospacedDigit()
            }
        }
    }
}
