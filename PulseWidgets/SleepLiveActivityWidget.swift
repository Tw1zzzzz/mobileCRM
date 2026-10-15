import ActivityKit
import SwiftUI
import WidgetKit

@main
struct PulseWidgetsBundle: WidgetBundle {
    var body: some Widget {
        SleepLiveActivityWidget()
    }
}

/// The extension never runs the intent itself (LiveActivityIntent executes in the app process),
/// but the shared intent needs the symbol to compile here too.
enum SleepTrackingBridge {
    static func stopSleep() async {}
}

private enum Palette {
    static let background = Color(red: 0.03, green: 0.06, blue: 0.13)
    static let purple = Color(red: 0.66, green: 0.55, blue: 1.0)
    static let cyan = Color(red: 0.2, green: 0.83, blue: 1.0)
    static let blue = Color(red: 0.16, green: 0.45, blue: 1.0)
    static let secondary = Color.white.opacity(0.62)
}

struct SleepLiveActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: SleepActivityAttributes.self) { context in
            LockScreenSleepView(context: context)
                .activityBackgroundTint(Palette.background)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label("Сон", systemImage: "moon.zzz.fill")
                        .font(.headline)
                        .foregroundStyle(Palette.purple)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    SleepTimerText(start: context.attributes.startedAt)
                        .font(.headline)
                        .multilineTextAlignment(.trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Text("с \(context.attributes.startedAt.formatted(date: .omitted, time: .shortened)) · будильник \(context.state.alarmText)")
                            .font(.subheadline)
                            .foregroundStyle(Palette.secondary)
                        Spacer()
                        WakeButton()
                    }
                }
            } compactLeading: {
                Image(systemName: "moon.zzz.fill").foregroundStyle(Palette.purple)
            } compactTrailing: {
                SleepTimerText(start: context.attributes.startedAt)
                    .frame(maxWidth: 56)
                    .foregroundStyle(Palette.purple)
            } minimal: {
                Image(systemName: "moon.zzz.fill").foregroundStyle(Palette.purple)
            }
            .keylineTint(Palette.purple)
        }
    }
}

private struct LockScreenSleepView: View {
    let context: ActivityViewContext<SleepActivityAttributes>

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: "moon.zzz.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(Palette.purple)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Palette.purple.opacity(0.18)))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Сон отслеживается")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                    Text("с \(context.attributes.startedAt.formatted(date: .omitted, time: .shortened)) · будильник \(context.state.alarmText)")
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                Spacer(minLength: 8)
                SleepTimerText(start: context.attributes.startedAt)
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(1)
                    .frame(width: 104, alignment: .trailing)
            }
            HStack {
                Text("Pulse")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(Palette.cyan)
                Spacer()
                WakeButton()
            }
        }
        .padding(16)
    }
}

/// Counts up from the moment the user went to bed.
private struct SleepTimerText: View {
    let start: Date

    var body: some View {
        // A closed range keeps the system clock-style counter ("1:23:45") instead of "< 1 минута".
        Text(timerInterval: start...start.addingTimeInterval(24 * 3600), countsDown: false, showsHours: true)
            .monospacedDigit()
    }
}

private struct WakeButton: View {
    var body: some View {
        Button(intent: StopSleepActivityIntent()) {
            Label("Проснулся", systemImage: "sun.max.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(LinearGradient(colors: [Palette.blue, Palette.cyan], startPoint: .leading, endPoint: .trailing))
                )
        }
        .buttonStyle(.plain)
    }
}
