import SwiftUI

/// Spider / radar chart for the balance wheel (8 life areas, values 0...10).
/// With labels it is interactive: hold a finger and slide around to read each area.
struct RadarChart: View {
    let labels: [String]
    let values: [Double]
    var maxValue: Double = 10
    var showsLabels = true

    @State private var selected: Int?

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let center = CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
            let radius = size / 2 - (showsLabels ? 46 : 8)

            ZStack {
                ForEach(1...4, id: \.self) { level in
                    RadarPolygon(values: Array(repeating: Double(level) / 4, count: labels.count))
                        .stroke(Theme.Palette.divider, lineWidth: 1)
                        .frame(width: radius * 2, height: radius * 2)
                        .position(center)
                }
                ForEach(labels.indices, id: \.self) { index in
                    Path { path in
                        path.move(to: center)
                        path.addLine(to: point(index: index, scale: 1, center: center, radius: radius))
                    }
                    .stroke(Theme.Palette.divider, lineWidth: 1)
                }
                RadarPolygon(values: normalized)
                    .fill(
                        LinearGradient(
                            colors: [Theme.Palette.cyan.opacity(0.45), Theme.Palette.blue.opacity(0.25)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .frame(width: radius * 2, height: radius * 2)
                    .position(center)
                RadarPolygon(values: normalized)
                    .stroke(Theme.Palette.cyan, style: StrokeStyle(lineWidth: 2.5, lineJoin: .round))
                    .frame(width: radius * 2, height: radius * 2)
                    .position(center)
                    .shadow(color: Theme.Palette.cyan.opacity(0.4), radius: 6)

                if showsLabels {
                    ForEach(labels.indices, id: \.self) { index in
                        Group {
                            if selected == index {
                                selectedLabel(index)
                            } else {
                                Text(labels[index])
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(Theme.Palette.textSecondary)
                                    .multilineTextAlignment(.center)
                                    .frame(width: 76)
                            }
                        }
                        .position(point(index: index, scale: 1, center: center, radius: radius + 30))
                    }
                }
                if let index = selected, values.indices.contains(index) {
                    selectionMarks(index: index, center: center, radius: radius)
                }
            }
            .animation(.easeOut(duration: 0.3), value: values)
            .overlay {
                if showsLabels {
                    LongPressScrubView(
                        onChange: { location in
                            let index = axisIndex(for: location, center: center)
                            if index != selected { selected = index }
                        },
                        onEnd: { selected = nil }
                    )
                }
            }
            .sensoryFeedback(.selection, trigger: selected) { _, new in new != nil }
        }
    }

    @ViewBuilder
    private func selectionMarks(index: Int, center: CGPoint, radius: CGFloat) -> some View {
        let vertex = point(index: index, scale: normalized[index], center: center, radius: radius)
        Path { path in
            path.move(to: center)
            path.addLine(to: point(index: index, scale: 1, center: center, radius: radius))
        }
        .stroke(Theme.Palette.cyan.opacity(0.8), style: StrokeStyle(lineWidth: 1.5, dash: [3, 3]))
        Circle()
            .fill(Theme.Palette.cyan)
            .frame(width: 12, height: 12)
            .overlay(Circle().stroke(.white, lineWidth: 2))
            .position(vertex)
    }

    /// The axis label turns into a pill with the score while the finger is on that area.
    private func selectedLabel(_ index: Int) -> some View {
        let value = values[index]
        return VStack(spacing: 0) {
            Text("\(Formatters.decimal(value, fractionDigits: value == value.rounded() ? 0 : 1)) / \(Int(maxValue))")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
            Text(labels[index])
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Theme.Palette.cyan)
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(
            Capsule()
                .fill(Color(hex: 0x0E1730))
                .overlay(Capsule().strokeBorder(Theme.Palette.cyan.opacity(0.6), lineWidth: 1))
        )
    }

    /// The axis closest to the finger's angle is selected while the finger slides around the wheel.
    private func axisIndex(for location: CGPoint, center: CGPoint) -> Int {
        // Axis 0 points straight up; angles grow clockwise.
        let angle = atan2(Double(location.y - center.y), Double(location.x - center.x)) + .pi / 2
        let turn = (angle < 0 ? angle + 2 * .pi : angle) / (2 * .pi)
        return Int((turn * Double(labels.count)).rounded()) % labels.count
    }

    private var normalized: [Double] {
        values.map { min(max($0 / maxValue, 0), 1) }
    }

    private func point(index: Int, scale: Double, center: CGPoint, radius: CGFloat) -> CGPoint {
        let angle = (Double(index) / Double(labels.count)) * 2 * .pi - .pi / 2
        return CGPoint(
            x: center.x + CGFloat(cos(angle)) * radius * scale,
            y: center.y + CGFloat(sin(angle)) * radius * scale
        )
    }
}

struct RadarPolygon: Shape {
    var values: [Double]

    var animatableData: AnimatableVector {
        get { AnimatableVector(values: values) }
        set { values = newValue.values }
    }

    func path(in rect: CGRect) -> Path {
        guard values.count > 2 else { return Path() }
        let center = CGPoint(x: rect.midX, y: rect.midY)
        let radius = min(rect.width, rect.height) / 2
        var path = Path()
        for (index, value) in values.enumerated() {
            let angle = (Double(index) / Double(values.count)) * 2 * .pi - .pi / 2
            let point = CGPoint(
                x: center.x + CGFloat(cos(angle) * value) * radius,
                y: center.y + CGFloat(sin(angle) * value) * radius
            )
            if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        return path
    }
}

/// Minimal VectorArithmetic so the polygon animates between value sets.
struct AnimatableVector: VectorArithmetic {
    var values: [Double]

    static var zero: AnimatableVector { AnimatableVector(values: []) }

    static func + (lhs: AnimatableVector, rhs: AnimatableVector) -> AnimatableVector {
        combine(lhs, rhs, +)
    }

    static func - (lhs: AnimatableVector, rhs: AnimatableVector) -> AnimatableVector {
        combine(lhs, rhs, -)
    }

    mutating func scale(by rhs: Double) {
        values = values.map { $0 * rhs }
    }

    var magnitudeSquared: Double {
        values.reduce(0) { $0 + $1 * $1 }
    }

    private static func combine(
        _ lhs: AnimatableVector,
        _ rhs: AnimatableVector,
        _ op: (Double, Double) -> Double
    ) -> AnimatableVector {
        let count = max(lhs.values.count, rhs.values.count)
        let result = (0..<count).map { index in
            op(
                index < lhs.values.count ? lhs.values[index] : 0,
                index < rhs.values.count ? rhs.values[index] : 0
            )
        }
        return AnimatableVector(values: result)
    }
}
