import SwiftUI

/// Tokens of the auth screens (desktop registration design adapted to mobile).
enum AuthStyle {
    static let card = Color(hex: 0x0B1222)
    static let field = Color(hex: 0x1C2333)
    static let fieldText = Color.white
    static let placeholder = Color(hex: 0x7B8599)
    static let roleBorder = Color(hex: 0x2A3346)
    static let buttonGradient = LinearGradient(
        colors: [Color(hex: 0x0A6CFF), Color(hex: 0x00C8FF)], startPoint: .leading, endPoint: .trailing
    )
}

/// Placeholder-only filled field; password fields get the eye toggle from the design.
/// The name sits inside the empty field and above the text once something is typed, so two filled password fields still
/// say which is which. With `focus` and `id` the keyboard's return key («Далее» / «Готово») runs `onSubmit`.
struct AuthField: View {
    let placeholder: String
    @Binding var text: String
    var isPassword = false
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var focus: FocusState<AuthFieldID?>.Binding?
    var id: AuthFieldID?
    var submitLabel: SubmitLabel = .next
    var onSubmit: () -> Void = {}

    @State private var isRevealed = false

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                if !text.isEmpty {
                    Text(placeholder)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(AuthStyle.placeholder)
                        .accessibilityHidden(true)
                }
                Group {
                    if isPassword && !isRevealed {
                        SecureField(placeholder, text: $text, prompt: prompt)
                    } else {
                        TextField(placeholder, text: $text, prompt: prompt)
                            .keyboardType(keyboard)
                    }
                }
                .focusedIfGiven(focus, id: id)
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
            }
            .textContentType(contentType)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .font(.system(size: 17))
            .foregroundStyle(AuthStyle.fieldText)

            if isPassword {
                Button {
                    isRevealed.toggle()
                } label: {
                    Image(systemName: isRevealed ? "eye" : "eye.slash")
                        .font(.system(size: 15))
                        .foregroundStyle(AuthStyle.placeholder)
                }
                .accessibilityLabel(isRevealed ? "Скрыть пароль" : "Показать пароль")
            }
        }
        .padding(.horizontal, 18)
        .frame(height: 54)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(AuthStyle.field))
    }

    private var prompt: Text {
        Text(placeholder).foregroundStyle(AuthStyle.placeholder)
    }
}

/// Outlined role option («Я игрок» / «Я стафф»), selected = cyan border.
struct AuthRoleOption: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(AuthStyle.card))
                .overlay(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(isSelected ? Theme.Palette.blue : AuthStyle.roleBorder, lineWidth: isSelected ? 2 : 1.5)
                )
        }
        .buttonStyle(.pressable)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

struct AuthPrimaryButton: View {
    let title: String
    let isLoading: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if isLoading {
                    ProgressView().tint(.white)
                } else {
                    Text(title).font(.system(size: 17, weight: .semibold))
                }
            }
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(AuthStyle.buttonGradient))
            .shadow(color: Theme.Palette.blue.opacity(0.35), radius: 14, y: 6)
        }
        .buttonStyle(.pressable)
        .disabled(isLoading)
    }
}

enum LegalDocument: String, Identifiable {
    case terms, privacy

    var id: String { rawValue }

    var title: String {
        switch self {
        case .terms: "Пользовательское соглашение"
        case .privacy: "Политика конфиденциальности"
        }
    }

    /// `pulse://legal/<kind>` links inside consent texts.
    var url: URL { URL(string: "pulse://legal/\(rawValue)")! }

    init?(url: URL) {
        guard url.scheme == "pulse", url.host == "legal" else { return nil }
        self.init(rawValue: url.lastPathComponent)
    }
}

/// Checkbox + text with an inline cyan link, as in the design.
struct AuthConsentRow: View {
    let prefix: String
    let document: LegalDocument
    let linkText: String
    @Binding var isOn: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Button {
                isOn.toggle()
            } label: {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(isOn ? Theme.Palette.cyan : AuthStyle.placeholder, lineWidth: 1.5)
                    .background(RoundedRectangle(cornerRadius: 4).fill(isOn ? Theme.Palette.cyan : .clear))
                    .overlay(
                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(AuthStyle.card)
                            .opacity(isOn ? 1 : 0)
                    )
                    .frame(width: 18, height: 18)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(prefix) \(linkText)")
            .accessibilityAddTraits(isOn ? .isSelected : [])

            Text(.init("\(prefix) [\(linkText)](\(document.url.absoluteString))"))
                .font(.system(size: 13))
                .foregroundStyle(.white)
                .tint(Theme.Palette.cyan)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

struct LegalDocumentSheet: View {
    let document: LegalDocument

    var body: some View {
        SheetScaffold(title: document.title) {
            Text(LegalTexts.text(for: document))
                .font(.system(size: 15))
                .foregroundStyle(Theme.Palette.textSecondary)
                .textSelection(.enabled)
        }
    }
}

private extension View {
    @ViewBuilder
    func focusedIfGiven(_ focus: FocusState<AuthFieldID?>.Binding?, id: AuthFieldID?) -> some View {
        if let focus, let id { focused(focus, equals: id) } else { self }
    }
}
