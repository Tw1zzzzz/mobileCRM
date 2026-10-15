import SwiftUI

enum HeaderStyle {
    /// Centered title (Сон, Фитнес, Экранное время).
    case centered
    /// Large leading title (Питание).
    case leading
}

/// Top bar from the mockups: square back button, title + optional subtitle, square trailing button.
struct NavHeader<Trailing: View>: View {
    let title: String
    var subtitle: String?
    var style: HeaderStyle = .centered
    var showsBack = true
    var onBack: (() -> Void)?
    @ViewBuilder var trailing: Trailing

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        switch style {
        case .centered: centered
        case .leading: leading
        }
    }

    private var centered: some View {
        ZStack {
            VStack(spacing: 4) {
                Text(title)
                    .font(.system(size: 26, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 16))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, Theme.Metrics.headerButtonSize + 8)

            HStack {
                backButton
                Spacer()
                trailing
            }
        }
        .frame(minHeight: Theme.Metrics.headerButtonSize)
    }

    private var leading: some View {
        HStack(alignment: .center, spacing: 16) {
            backButton
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.system(size: 15))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            Spacer(minLength: 0)
            trailing
        }
    }

    @ViewBuilder private var backButton: some View {
        if showsBack {
            HeaderIconButton(systemName: "chevron.left") {
                if let onBack { onBack() } else { dismiss() }
            }
            .accessibilityLabel("Назад")
        } else {
            Color.clear.frame(width: Theme.Metrics.headerButtonSize, height: Theme.Metrics.headerButtonSize)
        }
    }
}

extension NavHeader where Trailing == EmptyView {
    init(
        title: String,
        subtitle: String? = nil,
        style: HeaderStyle = .centered,
        showsBack: Bool = true,
        onBack: (() -> Void)? = nil
    ) {
        self.init(title: title, subtitle: subtitle, style: style, showsBack: showsBack, onBack: onBack) {
            EmptyView()
        }
    }
}

/// Standard screen: fixed header + scrolling content over the glowing background.
struct ScreenScaffold<Header: View, Content: View>: View {
    var spacing: CGFloat = Theme.Metrics.sectionSpacing
    @ViewBuilder let header: Header
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, Theme.Metrics.screenPadding)
                .padding(.top, 4)
                .padding(.bottom, 10)
            ScrollView {
                VStack(alignment: .leading, spacing: spacing) {
                    content
                }
                .padding(.horizontal, Theme.Metrics.screenPadding)
                .padding(.top, 10)
                .padding(.bottom, Theme.Metrics.tabBarContentInset)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
            #if DEBUG
            .defaultScrollAnchor(DebugScrollAnchor.value)
            #endif
        }
        .background(ScreenBackground())
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// Sheet chrome: grabber-less title row with a close button, on the app background.
struct SheetScaffold<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(title)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Theme.Palette.textSecondary)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(Theme.Palette.track))
                }
                .accessibilityLabel("Закрыть")
            }
            .padding(.horizontal, 20)
            .padding(.top, 22)
            .padding(.bottom, 12)

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    content
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 30)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
        .background(ScreenBackground())
        .presentationBackground(Theme.Palette.background)
    }
}

/// Re-enables the swipe-back gesture while the system navigation bar is hidden.
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }

    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        viewControllers.count > 1
    }
}

#if DEBUG
/// `-PulseScrollAnchor center|bottom` opens screens pre-scrolled (UI review screenshots on small phones).
enum DebugScrollAnchor {
    static let value: UnitPoint? = {
        let args = ProcessInfo.processInfo.arguments
        guard let index = args.firstIndex(of: "-PulseScrollAnchor"), index + 1 < args.count else { return nil }
        switch args[index + 1] {
        case "center": return .center
        case "bottom": return .bottom
        default: return nil
        }
    }()
}
#endif
