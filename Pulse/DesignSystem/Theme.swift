import SwiftUI

/// Design tokens extracted from the Pulse mockups (dark navy + blue/cyan accent).
enum Theme {
    enum Palette {
        static let background = Color(hex: 0x050A16)
        static let backgroundGlow = Color(hex: 0x0D2550)
        static let card = Color(hex: 0x0D1628)
        static let cardHighlight = Color(hex: 0x121E35)
        static let tile = Color(hex: 0x0F1A2F)
        static let stroke = Color.white.opacity(0.08)
        static let divider = Color.white.opacity(0.07)
        static let track = Color(hex: 0x1A2640)

        static let textPrimary = Color.white
        static let textSecondary = Color(hex: 0x8B98B2)
        static let textTertiary = Color(hex: 0x5D6A84)
        static let section = Color(hex: 0x8695B0)

        static let blue = Color(hex: 0x1E7CFF)
        static let cyan = Color(hex: 0x00D4FF)
        static let lightBlue = Color(hex: 0x4F7DF6)
        static let navyBar = Color(hex: 0x1D4BA8)
        static let green = Color(hex: 0x2CD67A)
        static let orange = Color(hex: 0xF8AC2A)
        static let yellow = Color(hex: 0xF5C443)
        static let purple = Color(hex: 0xB27DFF)
        static let red = Color(hex: 0xFF5A5F)
        static let pink = Color(hex: 0xFF4F8B)
        static let restZone = Color(hex: 0x2A3E70)
    }

    enum Gradients {
        static let accent = LinearGradient(
            colors: [Palette.blue, Palette.cyan], startPoint: .leading, endPoint: .trailing
        )
        static let accentDiagonal = LinearGradient(
            colors: [Color(hex: 0x1C6BFF), Palette.cyan], startPoint: .topLeading, endPoint: .bottomTrailing
        )
        static let barVertical = LinearGradient(
            colors: [Color(hex: 0x16B4FF), Palette.blue], startPoint: .top, endPoint: .bottom
        )
        static let barHighlight = LinearGradient(
            colors: [Palette.cyan, Color(hex: 0x12C8F5)], startPoint: .top, endPoint: .bottom
        )
        static let card = LinearGradient(
            colors: [Palette.cardHighlight, Palette.card], startPoint: .top, endPoint: .bottom
        )
    }

    enum Metrics {
        static let screenPadding: CGFloat = 16
        static let cardRadius: CGFloat = 22
        static let tileRadius: CGFloat = 18
        static let buttonRadius: CGFloat = 14
        static let cardPadding: CGFloat = 18
        static let sectionSpacing: CGFloat = 26
        static let headerButtonSize: CGFloat = 46
        static let tabBarContentInset: CGFloat = 24
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255,
            opacity: opacity
        )
    }
}
