import SwiftUI

/// Dark navy background with the soft blue glow at the top of every screen.
struct ScreenBackground: View {
    var body: some View {
        ZStack(alignment: .top) {
            Theme.Palette.background
            RadialGradient(
                colors: [Theme.Palette.backgroundGlow.opacity(0.85), .clear],
                center: .top,
                startRadius: 0,
                endRadius: 380
            )
            .frame(height: 420)
        }
        .ignoresSafeArea()
    }
}

struct CardModifier: ViewModifier {
    var padding: CGFloat
    var radius: CGFloat
    var fill: AnyShapeStyle

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(Theme.Palette.stroke, lineWidth: 1)
            )
    }
}

extension View {
    func pulseCard(
        padding: CGFloat = Theme.Metrics.cardPadding,
        radius: CGFloat = Theme.Metrics.cardRadius
    ) -> some View {
        modifier(CardModifier(padding: padding, radius: radius, fill: AnyShapeStyle(Theme.Gradients.card)))
    }

    func pulseTile(padding: CGFloat = 16, radius: CGFloat = Theme.Metrics.tileRadius) -> some View {
        modifier(CardModifier(padding: padding, radius: radius, fill: AnyShapeStyle(Theme.Palette.tile)))
    }
}

/// Uppercase, letter-spaced section title ("ФАЗЫ СНА") with an optional trailing link ("Детали →").
struct SectionHeader: View {
    let title: String
    var actionTitle: String?
    var action: (() -> Void)?

    var body: some View {
        HStack {
            Text(title.uppercased())
                .font(.system(size: 14, weight: .semibold))
                .tracking(2.2)
                .foregroundStyle(Theme.Palette.section)
            Spacer()
            if let actionTitle, let action {
                Button(action: action) {
                    HStack(spacing: 6) {
                        Text(actionTitle)
                        Image(systemName: "arrow.right")
                    }
                    .font(.system(size: 17, weight: .medium))
                    .foregroundStyle(Theme.Palette.cyan)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 4)
    }
}

/// Section header + content stacked with the standard spacing.
struct PulseSection<Content: View>: View {
    let title: String
    var actionTitle: String?
    var action: (() -> Void)?
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            SectionHeader(title: title, actionTitle: actionTitle, action: action)
            content
        }
    }
}

/// Rounded-square tinted icon used in list rows and tiles.
struct IconBadge: View {
    let systemName: String
    let tint: Color
    var size: CGFloat = 46
    var iconSize: CGFloat = 20
    var background: Color?

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
            .fill(background ?? tint.opacity(0.14))
            .frame(width: size, height: size)
            .overlay(
                Image(systemName: systemName)
                    .font(.system(size: iconSize, weight: .medium))
                    .foregroundStyle(tint)
            )
    }
}

struct DividerLine: View {
    var vertical = false

    var body: some View {
        Rectangle()
            .fill(Theme.Palette.divider)
            .frame(width: vertical ? 1 : nil, height: vertical ? nil : 1)
    }
}

/// Number + small unit on a shared baseline: "56 уд/мин".
struct ValueUnitText: View {
    let value: String
    var unit: String?
    var valueSize: CGFloat = 34
    var unitSize: CGFloat = 17
    var monospaced = false
    var unitColor: Color = Theme.Palette.textSecondary
    var valueColor: Color = Theme.Palette.textPrimary
    var minimumScale: CGFloat = 0.7

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 5) {
            Text(value)
                .font(.system(size: valueSize, weight: .bold, design: monospaced ? .monospaced : .default))
                .foregroundStyle(valueColor)
                .monospacedDigit()
            if let unit {
                Text(unit)
                    .font(.system(size: unitSize, weight: .medium))
                    .foregroundStyle(unitColor)
            }
        }
        .lineLimit(1)
        .minimumScaleFactor(minimumScale)
    }
}

/// "7ч 42м" style duration with large numbers and small units.
struct DurationText: View {
    let minutes: Int
    var valueSize: CGFloat = 40
    var unitSize: CGFloat = 24
    var unitColor: Color = Theme.Palette.textSecondary

    var body: some View {
        let parts = Formatters.durationParts(minutes: minutes)
        HStack(alignment: .firstTextBaseline, spacing: 2) {
            Text("\(parts.hours)").font(.system(size: valueSize, weight: .bold))
            Text("ч").font(.system(size: unitSize, weight: .semibold)).foregroundStyle(unitColor)
                .padding(.trailing, valueSize * 0.18)
            Text("\(parts.minutes)").font(.system(size: valueSize, weight: .bold))
            Text("м").font(.system(size: unitSize, weight: .semibold)).foregroundStyle(unitColor)
        }
        .foregroundStyle(Theme.Palette.textPrimary)
        .monospacedDigit()
        .lineLimit(1)
        .minimumScaleFactor(0.6)
    }
}

/// Small colored pill ("● Хорошо · +2 к базовому").
struct StatusPill: View {
    let text: String
    var tint: Color = Theme.Palette.green
    var showsDot = true

    var body: some View {
        HStack(spacing: 8) {
            if showsDot {
                Circle().fill(tint).frame(width: 9, height: 9)
            }
            Text(text)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(Capsule().fill(tint.opacity(0.12)))
        .overlay(Capsule().strokeBorder(tint.opacity(0.35), lineWidth: 1))
    }
}

/// Empty-state placeholder shared by nutrition, calendar, statistics, screen time.
struct EmptyStateView: View {
    let systemName: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 14) {
            IconBadge(
                systemName: systemName,
                tint: Theme.Palette.textSecondary,
                size: 72,
                iconSize: 30,
                background: Theme.Palette.track
            )
            Text(title)
                .font(.system(size: 22, weight: .bold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .multilineTextAlignment(.center)
            Text(message)
                .font(.system(size: 16))
                .foregroundStyle(Theme.Palette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
        .pulseCard()
    }
}
