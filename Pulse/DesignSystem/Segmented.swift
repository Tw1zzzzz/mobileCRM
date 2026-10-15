import SwiftUI
import Charts

/// Pill segmented control (auth «Соло | Командный доступ», «Состояние» sections).
struct PulseSegmented<Option: Hashable & Identifiable>: View {
    let options: [Option]
    @Binding var selection: Option
    let title: (Option) -> String
    var height: CGFloat = 42
    var unselectedColor: Color = Theme.Palette.textSecondary
    var fontSize: CGFloat = 15

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options) { option in
                let isSelected = option == selection
                Button {
                    withAnimation(.easeInOut(duration: 0.2)) { selection = option }
                } label: {
                    Text(title(option))
                        .font(.system(size: fontSize, weight: .semibold))
                        .foregroundStyle(isSelected ? .white : unselectedColor)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .frame(maxWidth: .infinity)
                        .frame(height: height)
                        .background(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .fill(isSelected ? AnyShapeStyle(AuthStyle.buttonGradient) : AnyShapeStyle(Color.clear))
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(5)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(AuthStyle.field))
    }
}

/// Scrollable tab strip: each tab takes its natural width, so long titles («Статистика», «Здоровье»)
/// never get squeezed; the selected tab scrolls into view.
struct PulseScrollTabs<Option: Hashable & Identifiable>: View {
    let options: [Option]
    @Binding var selection: Option
    let title: (Option) -> String
    var height: CGFloat = 38

    var body: some View {
        ScrollViewReader { reader in
            ScrollView(.horizontal) {
                HStack(spacing: 4) {
                    ForEach(options) { option in
                        tab(option)
                            .id(option.id)
                    }
                }
                .padding(5)
            }
            .scrollIndicators(.hidden)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(AuthStyle.field))
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            .onAppear { reader.scrollTo(selection.id, anchor: .center) }
            .onChange(of: selection) { _, new in
                withAnimation(.easeInOut(duration: 0.25)) { reader.scrollTo(new.id, anchor: .center) }
            }
        }
    }

    private func tab(_ option: Option) -> some View {
        let isSelected = option == selection
        return Button {
            withAnimation(.easeInOut(duration: 0.2)) { selection = option }
        } label: {
            Text(title(option))
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(isSelected ? .white : Theme.Palette.textSecondary)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 16)
                .frame(height: height)
                .background(
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(isSelected ? AnyShapeStyle(AuthStyle.buttonGradient) : AnyShapeStyle(Color.clear))
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Tiny axis-less trend line for dashboard cards.
struct Sparkline: View {
    let values: [DailyValue]
    var color: Color = Theme.Palette.cyan
    var showsArea = true

    var body: some View {
        let numbers = values.map(\.value)
        let low = numbers.min() ?? 0
        let high = numbers.max() ?? 1
        let pad = max((high - low) * 0.15, 0.5)
        Chart(values) { item in
            if showsArea {
                AreaMark(
                    x: .value("День", item.date),
                    yStart: .value("Мин", low - pad),
                    yEnd: .value("Значение", item.value)
                )
                .foregroundStyle(LinearGradient(colors: [color.opacity(0.3), .clear], startPoint: .top, endPoint: .bottom))
                .interpolationMethod(.catmullRom)
            }
            LineMark(x: .value("День", item.date), y: .value("Значение", item.value))
                .foregroundStyle(color)
                .lineStyle(StrokeStyle(lineWidth: 2.2, lineCap: .round))
                .interpolationMethod(.catmullRom)
        }
        .chartXAxis(.hidden)
        .chartYAxis(.hidden)
        .chartYScale(domain: (low - pad)...(high + pad))
        .accessibilityHidden(true)
    }
}
