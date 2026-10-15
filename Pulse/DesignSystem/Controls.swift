import SwiftUI

struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.85 : 1)
            .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
    }
}

extension ButtonStyle where Self == PressableButtonStyle {
    static var pressable: PressableButtonStyle { PressableButtonStyle() }
}

/// Rounded-square header button (back, share, settings, calendar, plus).
struct HeaderIconButton: View {
    let systemName: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HeaderIconLabel(systemName: systemName)
        }
        .buttonStyle(.pressable)
    }
}

struct HeaderIconLabel: View {
    let systemName: String
    var size: CGFloat = Theme.Metrics.headerButtonSize

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: size * 0.41, weight: .medium))
            .foregroundStyle(Theme.Palette.textPrimary)
            .frame(width: size, height: size)
            .background(
                RoundedRectangle(cornerRadius: Theme.Metrics.buttonRadius, style: .continuous)
                    .fill(Theme.Palette.card)
            )
            .overlay(
                RoundedRectangle(cornerRadius: Theme.Metrics.buttonRadius, style: .continuous)
                    .strokeBorder(Theme.Palette.stroke, lineWidth: 1)
            )
    }
}

/// Full-width blue→cyan gradient button ("+ Записать тренировку").
struct GradientButton: View {
    let title: String
    var systemImage: String?
    var isLoading = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if isLoading {
                    ProgressView().tint(.white)
                } else if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 20, weight: .medium))
                }
                Text(title).font(.system(size: 18, weight: .semibold))
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Gradients.accent)
            )
            .shadow(color: Theme.Palette.blue.opacity(0.35), radius: 14, y: 6)
        }
        .buttonStyle(.pressable)
        .disabled(isLoading)
    }
}

/// Secondary outlined button on a dark card.
struct SecondaryButton: View {
    let title: String
    var systemImage: String?
    var tint: Color = Theme.Palette.textPrimary
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title)
            }
            .font(.system(size: 17, weight: .semibold))
            .foregroundStyle(tint)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Theme.Palette.stroke, lineWidth: 1)
            )
        }
        .buttonStyle(.pressable)
    }
}

/// Selectable chip used for period filters and tags.
struct ChipButton: View {
    let title: String
    let isSelected: Bool
    var tint: Color = Theme.Palette.cyan
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(isSelected ? .white : Theme.Palette.textSecondary)
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(
                    Capsule().fill(isSelected ? AnyShapeStyle(Theme.Gradients.accent) : AnyShapeStyle(Theme.Palette.tile))
                )
                .overlay(Capsule().strokeBorder(isSelected ? .clear : Theme.Palette.stroke, lineWidth: 1))
        }
        .buttonStyle(.pressable)
    }
}

/// Text field styled for the dark cards (auth, forms).
struct PulseTextField: View {
    let title: String
    @Binding var text: String
    var placeholder = ""
    var isSecure = false
    var keyboard: UIKeyboardType = .default
    var axis: Axis = .horizontal

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Theme.Palette.textSecondary)
            Group {
                if isSecure {
                    SecureField("", text: $text, prompt: prompt)
                } else {
                    TextField("", text: $text, prompt: prompt, axis: axis)
                        .keyboardType(keyboard)
                }
            }
            .font(.system(size: 17))
            .foregroundStyle(Theme.Palette.textPrimary)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile))
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Theme.Palette.stroke, lineWidth: 1)
            )
        }
    }

    private var prompt: Text {
        Text(placeholder).foregroundStyle(Theme.Palette.textTertiary)
    }
}

/// Inline error banner with the exact messages from the spec.
struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
            Text(message).frame(maxWidth: .infinity, alignment: .leading)
        }
        .font(.system(size: 15, weight: .medium))
        .foregroundStyle(Theme.Palette.red)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.red.opacity(0.12)))
    }
}

/// Labeled slider (mood, energy, balance wheel).
struct PulseSlider: View {
    let title: String
    @Binding var value: Double
    var range: ClosedRange<Double> = 1...10
    var tint: Color = Theme.Palette.cyan

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Text("\(Int(value.rounded()))")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(tint)
                    .monospacedDigit()
            }
            Slider(value: $value, in: range, step: 1).tint(tint)
        }
    }
}
