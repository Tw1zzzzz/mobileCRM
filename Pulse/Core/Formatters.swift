import Foundation

/// Russian-locale formatting helpers ("6 840", "74,2", "7 ч 42 м", "23:12").
enum Formatters {
    static let locale = Locale(identifier: "ru_RU")

    private static func dateFormatter(_ format: String) -> DateFormatter {
        let formatter = DateFormatter()
        formatter.locale = locale
        formatter.calendar = .pulse
        formatter.dateFormat = format
        return formatter
    }

    private static let timeFormatter = dateFormatter("HH:mm")
    private static let dayMonthFormatter = dateFormatter("d MMMM")
    private static let dayMonthShortFormatter = dateFormatter("d MMM")
    private static let weekdayShortFormatter = dateFormatter("EEEEEE")
    private static let weekdayFullFormatter = dateFormatter("EEEE")
    private static let monthYearFormatter = dateFormatter("LLLL yyyy")
    private static let dayMonthWeekdayFormatter = dateFormatter("d MMMM, EEEE")
    private static let dayMonthTimeFormatter = dateFormatter("d MMMM, HH:mm")

    private static let integerFormatter: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = " "
        formatter.usesGroupingSeparator = true
        formatter.maximumFractionDigits = 0
        return formatter
    }()

    static func time(_ date: Date) -> String { timeFormatter.string(from: date) }
    /// Stable day id like "2026-10-14" (not for display).
    static func dayKey(_ date: Date) -> String {
        let parts = Calendar.pulse.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
    static func dayMonth(_ date: Date) -> String { dayMonthFormatter.string(from: date) }
    static func dayMonthShort(_ date: Date) -> String { dayMonthShortFormatter.string(from: date) }
    static func dayMonthTime(_ date: Date) -> String { dayMonthTimeFormatter.string(from: date) }
    static func weekdayShort(_ date: Date) -> String { weekdayShortFormatter.string(from: date).capitalizedFirst }
    static func weekdayFull(_ date: Date) -> String { weekdayFullFormatter.string(from: date).capitalizedFirst }
    static func monthYear(_ date: Date) -> String { monthYearFormatter.string(from: date).capitalizedFirst }
    static func dayMonthWeekday(_ date: Date) -> String { dayMonthWeekdayFormatter.string(from: date).capitalizedFirst }

    static func durationParts(minutes: Int) -> (hours: Int, minutes: Int) {
        let safe = max(minutes, 0)
        return (safe / 60, safe % 60)
    }

    /// "3 ч 33 м" or "24 мин" when under an hour.
    static func duration(minutes: Int) -> String {
        let parts = durationParts(minutes: minutes)
        if parts.hours == 0 { return "\(parts.minutes) мин" }
        return "\(parts.hours) ч \(parts.minutes) м"
    }

    /// Live timer "07:42:05".
    static func timer(_ interval: TimeInterval) -> String {
        let total = max(Int(interval), 0)
        return String(format: "%02d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    static func grouped(_ value: Int) -> String {
        integerFormatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    static func decimal(_ value: Double, fractionDigits: Int = 1) -> String {
        String(format: "%.\(fractionDigits)f", value).replacingOccurrences(of: ".", with: ",")
    }

    /// «+0,3», «−1,2», «0,0» (no sign when the rounded value is zero).
    static func signedDecimal(_ value: Double, fractionDigits: Int = 1) -> String {
        let text = decimal(abs(value), fractionDigits: fractionDigits)
        guard Double(text.replacingOccurrences(of: ",", with: ".")) != 0 else { return text }
        return (value > 0 ? "+" : "−") + text
    }

    /// «+12», «−3», «0».
    static func signed(_ value: Int) -> String {
        value == 0 ? "0" : (value > 0 ? "+\(value)" : "−\(abs(value))")
    }

    static func liters(milliliters: Int) -> String {
        decimal(Double(milliliters) / 1000, fractionDigits: milliliters % 1000 == 0 ? 0 : 1)
    }
}

extension String {
    var capitalizedFirst: String {
        guard let first else { return self }
        return first.uppercased() + dropFirst()
    }
}

extension Calendar {
    /// Gregorian calendar, Russian locale, week starts on Monday.
    static let pulse: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = Locale(identifier: "ru_RU")
        calendar.firstWeekday = 2
        return calendar
    }()
}

extension Date {
    var startOfDay: Date { Calendar.pulse.startOfDay(for: self) }

    func adding(days: Int) -> Date {
        Calendar.pulse.date(byAdding: .day, value: days, to: self) ?? self
    }

    func adding(minutes: Int) -> Date {
        addingTimeInterval(TimeInterval(minutes * 60))
    }

    func isSameDay(as other: Date) -> Bool {
        Calendar.pulse.isDate(self, inSameDayAs: other)
    }

    /// Monday of the week containing this date.
    var startOfWeek: Date {
        Calendar.pulse.dateInterval(of: .weekOfYear, for: self)?.start ?? startOfDay
    }

    func setting(hour: Int, minute: Int) -> Date {
        Calendar.pulse.date(bySettingHour: hour, minute: minute, second: 0, of: self) ?? self
    }
}
