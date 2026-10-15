import SwiftUI

/// Circular progress with the cyan→blue sweep used on Сон / Фитнес / Питание.
struct ProgressRing: View {
    let progress: Double
    var lineWidth: CGFloat = 14

    var body: some View {
        let clamped = min(max(progress, 0), 1)
        ZStack {
            Circle().stroke(Theme.Palette.track, lineWidth: lineWidth)
            if clamped > 0 {
                Circle()
                    .trim(from: 0, to: clamped)
                    .stroke(
                        AngularGradient(
                            colors: [Theme.Palette.cyan, Color(hex: 0x1593FF), Theme.Palette.blue],
                            center: .center,
                            startAngle: .degrees(0),
                            endAngle: .degrees(360 * clamped)
                        ),
                        style: StrokeStyle(lineWidth: lineWidth, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                    .shadow(color: Theme.Palette.cyan.opacity(0.35), radius: 8)
            }
        }
        .animation(.easeOut(duration: 0.6), value: clamped)
    }
}

/// Ring with big centered value and caption ("84 / ОЦЕНКА СНА").
struct ScoreRing: View {
    let progress: Double
    let value: String
    let caption: String
    var size: CGFloat = 150
    var lineWidth: CGFloat = 14
    var valueSize: CGFloat = 52

    var body: some View {
        ZStack {
            ProgressRing(progress: progress, lineWidth: lineWidth)
            VStack(spacing: 2) {
                Text(value)
                    .font(.system(size: valueSize, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .monospacedDigit()
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Text(caption)
                    .font(.system(size: 13, weight: .medium))
                    .tracking(1.2)
                    .foregroundStyle(Theme.Palette.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .padding(lineWidth + 6)
        }
        .frame(width: size, height: size)
    }
}

/// Horizontal capsule progress bar.
struct GradientBar: View {
    let progress: Double
    var height: CGFloat = 10
    var fill: AnyShapeStyle = AnyShapeStyle(Theme.Gradients.accent)
    var track: Color = Theme.Palette.track

    var body: some View {
        GeometryReader { geo in
            let clamped = min(max(progress, 0), 1)
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                if clamped > 0 {
                    Capsule()
                        .fill(fill)
                        .frame(width: max(height, geo.size.width * clamped))
                }
            }
        }
        .frame(height: height)
        .animation(.easeOut(duration: 0.5), value: progress)
    }
}
