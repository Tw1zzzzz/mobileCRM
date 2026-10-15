import SwiftUI
import Charts

extension View {
    /// «Как на бирже»: удерживай палец на графике и веди — выбирается ближайшее значение по оси X.
    /// `snap` maps the raw X under the finger to an X that has data (nil = ignore).
    func chartScrub<X: Plottable & Equatable>(_ selection: Binding<X?>, snap: @escaping (X) -> X? = { $0 }) -> some View {
        modifier(ChartScrubModifier(selection: selection, snap: snap))
    }
}

private struct ChartScrubModifier<X: Plottable & Equatable>: ViewModifier {
    @Binding var selection: X?
    let snap: (X) -> X?

    func body(content: Content) -> some View {
        content
            .chartOverlay { proxy in
                GeometryReader { geo in
                    LongPressScrubView(
                        onChange: { location in select(at: location, proxy: proxy, geo: geo) },
                        onEnd: finish
                    )
                }
            }
            .sensoryFeedback(.selection, trigger: selection) { _, new in new != nil }
    }

    private func select(at location: CGPoint, proxy: ChartProxy, geo: GeometryProxy) {
        guard let plot = proxy.plotFrame else { return }
        let x = location.x - geo[plot].origin.x
        guard let raw: X = proxy.value(atX: x), let snapped = snap(raw), snapped != selection else { return }
        selection = snapped
    }

    private func finish() {
        if selection != nil { selection = nil }
    }
}

/// Hold-then-slide gesture backed by UIKit's long press. Unlike a SwiftUI gesture inside a ScrollView,
/// it negotiates with the scroll view's pan: a quick swipe scrolls the page, a held finger inspects the chart,
/// and the page cannot scroll while inspecting. Cancellation always reports `onEnd`.
struct LongPressScrubView: UIViewRepresentable {
    var minimumDuration: TimeInterval = 0.25
    let onChange: (CGPoint) -> Void
    let onEnd: () -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.backgroundColor = .clear
        let recognizer = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handle(_:)))
        recognizer.minimumPressDuration = minimumDuration
        recognizer.allowableMovement = 10
        view.addGestureRecognizer(recognizer)
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.parent = self
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(parent: self)
    }

    final class Coordinator: NSObject {
        var parent: LongPressScrubView

        init(parent: LongPressScrubView) {
            self.parent = parent
        }

        @objc func handle(_ recognizer: UILongPressGestureRecognizer) {
            switch recognizer.state {
            case .began, .changed:
                parent.onChange(recognizer.location(in: recognizer.view))
            case .ended, .cancelled, .failed:
                parent.onEnd()
            default:
                break
            }
        }
    }
}

/// Floating label above the selected point: value(s) on top, date below.
struct ChartCallout: View {
    struct Line: Identifiable {
        let text: String
        let tint: Color
        var id: String { text }
    }

    let title: String
    let lines: [Line]

    init(title: String, value: String, tint: Color) {
        self.title = title
        self.lines = [Line(text: value, tint: tint)]
    }

    init(title: String, lines: [Line]) {
        self.title = title
        self.lines = lines
    }

    var body: some View {
        VStack(spacing: 2) {
            ForEach(lines) { line in
                Text(line.text)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(line.tint)
            }
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(Theme.Palette.textSecondary)
        }
        .lineLimit(1)
        .fixedSize()
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color(hex: 0x0E1730))
                .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
                .shadow(color: .black.opacity(0.35), radius: 8, y: 3)
        )
    }
}

/// Vertical guide + callout for the selected day.
struct DayScrubRule: ChartContent {
    let date: Date
    let callout: ChartCallout

    var body: some ChartContent {
        RuleMark(x: .value("Выбор", date, unit: .day))
            .foregroundStyle(Theme.Palette.textSecondary.opacity(0.55))
            .lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
            .annotation(position: .top, spacing: 4, overflowResolution: .init(x: .fit(to: .chart), y: .disabled)) {
                callout
            }
    }
}

/// Highlighted dot on a line at the selected X.
struct ScrubPoint<X: Plottable>: ChartContent {
    let x: PlottableValue<X>
    let y: Double
    let tint: Color

    var body: some ChartContent {
        PointMark(x: x, y: .value("Значение", y))
            .symbol {
                Circle()
                    .fill(tint)
                    .frame(width: 11, height: 11)
                    .overlay(Circle().stroke(.white, lineWidth: 2))
            }
    }
}

extension Array where Element == DailyValue {
    /// Nearest point to a raw date picked on the chart.
    func nearest(to date: Date) -> DailyValue? {
        self.min { abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date)) }
    }

    func value(on day: Date?) -> DailyValue? {
        guard let day else { return nil }
        return first { $0.date.isSameDay(as: day) }
    }
}
