import SwiftUI

/// Deterministic demo content so every screen of the MVP looks like the mockups
/// before the backend is connected.
enum DemoData {
    // MARK: Sleep

    static func lastNight(now: Date = .now) -> SleepNight {
        let start = now.startOfDay.adding(days: -1).setting(hour: 23, minute: 12)
        let plan: [(SleepStage, Int)] = [
            (.awake, 8), (.light, 30), (.deep, 57), (.light, 10), (.rem, 20), (.light, 15),
            (.deep, 46), (.light, 4), (.rem, 48), (.light, 32), (.awake, 2), (.light, 26),
            (.rem, 40), (.light, 40), (.rem, 14), (.light, 56), (.awake, 14)
        ]
        var cursor = start
        let segments = plan.map { stage, minutes -> SleepStageSegment in
            let segment = SleepStageSegment(stage: stage, start: cursor, end: cursor.adding(minutes: minutes))
            cursor = segment.end
            return segment
        }
        return SleepNight(
            start: start,
            end: cursor,
            score: 84,
            baselineDelta: 2,
            segments: segments,
            cycles: 5,
            efficiency: 92,
            latencyMinutes: 14,
            awakenings: 2,
            heartRate: [62, 59, 56, 55, 53, 52, 54, 56, 57, 58, 59, 60, 58, 57, 56, 55, 54, 55, 57, 58],
            restingHeartRate: 56,
            minHeartRate: 51,
            heartRateBaselineDelta: -3,
            respiratoryRate: 14,
            hrv: 58,
            spo2: 97
        )
    }

    static func sleepHistory(now: Date = .now) -> [SleepSession] {
        var rng = SeededGenerator(seed: 42)
        let night = lastNight(now: now)
        let previous = (1..<30).map { offset -> SleepSession in
            let bed = now.startOfDay.adding(days: -offset - 1).setting(hour: 22, minute: 40)
                .adding(minutes: rng.int(in: 0...70))
            return SleepSession(id: UUID(), start: bed, end: bed.adding(minutes: rng.int(in: 370...500)))
        }
        return [SleepSession(id: UUID(), start: night.start, end: night.end)] + previous
    }

    /// Sleep sessions of a team player. The first demo player shares the device's demo history,
    /// so the staff view of «Nova» matches what Nova sees in her own app.
    static func sleepSessions(for player: PlayerProfile, now: Date = .now) -> [SleepSession] {
        if player.id == players[0].id { return sleepHistory(now: now) }
        var rng = SeededGenerator(seed: player.seed &+ 11)
        return (0..<30).map { offset -> SleepSession in
            let bed = now.startOfDay.adding(days: -offset - 1).setting(hour: 22, minute: 30)
                .adding(minutes: rng.int(in: 0...100))
            return SleepSession(id: UUID(), start: bed, end: bed.adding(minutes: rng.int(in: 330...510)))
        }
    }

    /// Demo meals of a team player (no photos — photos come from players using the app).
    static func playerMeals(for player: PlayerProfile, now: Date = .now) -> [Meal] {
        var rng = SeededGenerator(seed: player.seed &+ 13)
        typealias Dish = (name: String, kcal: ClosedRange<Int>, protein: Int, fat: Int, carbs: Int)
        let lunches: [Dish] = [("Курица с рисом", 480...620, 42, 12, 60), ("Паста болоньезе", 550...700, 30, 20, 75),
                               ("Боул с лососем", 520...650, 34, 20, 58), ("Суп и сэндвич", 450...580, 24, 16, 55)]
        let dinners: [Dish] = [("Стейк с овощами", 520...680, 45, 28, 20), ("Салат Цезарь", 380...480, 26, 24, 18),
                               ("Рыба с картофелем", 450...600, 36, 14, 48), ("Омлет с тостом", 350...450, 24, 20, 28)]
        return (0..<10).map { index in
            let isLunch = index % 2 == 0
            let menu = isLunch ? lunches : dinners
            let dish = menu[rng.int(in: 0...(menu.count - 1))]
            return Meal(id: UUID(), date: now.startOfDay.adding(days: -(index / 2)).setting(hour: isLunch ? 13 : 19, minute: rng.int(in: 0...50)),
                        name: dish.name, kcal: rng.int(in: dish.kcal), protein: dish.protein,
                        fat: dish.fat, carbs: dish.carbs, photo: nil, source: .manual, author: player.nickname)
        }
        .filter { $0.date <= now }
    }

    static func sleepWeek(now: Date = .now) -> [SleepWeekDay] {
        let values: [(Int, Int, Int)] = [
            (82, 70, 268), (95, 100, 298), (90, 92, 255), (60, 75, 250),
            (78, 95, 262), (60, 80, 266), (103, 122, 237)
        ]
        return values.enumerated().map { index, value in
            SleepWeekDay(date: now.startOfDay.adding(days: index - 6), deep: value.0, rem: value.1, light: value.2)
        }
    }

    // MARK: Fitness

    static let seededWorkoutIDs: Set<UUID> = [
        UUID(uuidString: "11111111-1111-1111-1111-111111111111")!,
        UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
    ]

    static func workouts(now: Date = .now) -> [Workout] {
        let ids = seededWorkoutIDs.sorted { $0.uuidString < $1.uuidString }
        return [
            Workout(id: ids[0], kind: .strength, detail: "верх", date: now.setting(hour: 7, minute: 20),
                    minutes: 52, kcal: 280, averageHeartRate: 132),
            Workout(id: ids[1], kind: .hiit, detail: "табата", date: now.setting(hour: 12, minute: 40),
                    minutes: 18, kcal: 132, averageHeartRate: 156)
        ]
    }

    /// Device activity outside logged workouts while Apple Health is not connected.
    static let baselineActiveMinutes = 48
    static let baselineActiveKcal = 412
    static let baselineSteps = 6840
    static let baselineDistanceKm = 4.2
    static let averageHeartRate = 118

    /// Four weeks of rated workouts so «Нагрузка» has a chronic base before real data arrives.
    static func loadHistory(now: Date = .now) -> [Workout] {
        var rng = SeededGenerator(seed: 2026)
        let kinds: [WorkoutKind] = [.strength, .cardio, .hiit, .strength, .yoga]
        return (1..<28).compactMap { offset -> Workout? in
            guard rng.int(in: 0...9) < 6 else { return nil }
            let kind = kinds[rng.int(in: 0...(kinds.count - 1))]
            // The last week is a bit heavier, so the demo shows a rising load.
            let extra = offset < 7 ? 10 : 0
            return Workout(id: UUID(), kind: kind, detail: "", date: now.startOfDay.adding(days: -offset).setting(hour: 18, minute: 0),
                           minutes: rng.int(in: 30...60) + extra, kcal: rng.int(in: 180...420),
                           averageHeartRate: rng.int(in: 115...160), rpe: rng.int(in: 4...8))
        }
    }

    static var weeklyActiveMinutes: [DailyValue] {
        [42, 64, 38, 51, 46, 60, 48].enumerated().map { index, value in
            DailyValue(date: Date.now.startOfDay.adding(days: index - 6), value: Double(value))
        }
    }

    static let heartRateZones: [HeartRateZone] = [
        HeartRateZone(name: "Покой", range: "< 60%", percent: 35, color: Theme.Palette.restZone),
        HeartRateZone(name: "Жиросжигание", range: "60–70%", percent: 25, color: Theme.Palette.cyan),
        HeartRateZone(name: "Кардио", range: "70–80%", percent: 22, color: Theme.Palette.green),
        HeartRateZone(name: "Пик", range: "80–90%", percent: 14, color: Theme.Palette.orange),
        HeartRateZone(name: "Максимум", range: "> 90%", percent: 4, color: Theme.Palette.red)
    ]

    static let bodyMetrics: [BodyMetric] = [
        BodyMetric(title: "Вес", value: 74.2, unit: "кг", delta: -0.3),
        BodyMetric(title: "Жир", value: 16.4, unit: "%", delta: -0.2),
        BodyMetric(title: "Мышцы", value: 34.8, unit: "кг", delta: 0.1)
    ]

    // MARK: Screen time

    static let screenTimeHourly: [Int] = [
        7, 6, 4, 3, 4, 5, 12, 18, 26, 18, 13, 18, 24, 27, 28, 42, 56, 27, 52, 54, 40, 24, 14, 10
    ]

    static let screenTimeApps: [AppUsage] = [
        AppUsage(name: "YouTube", category: .video, minutes: 78, icon: "play.rectangle.fill", tint: Theme.Palette.red),
        AppUsage(name: "Telegram", category: .social, minutes: 64, icon: "paperplane.fill", tint: Color(hex: 0x2AABEE)),
        AppUsage(name: "Instagram", category: .social, minutes: 42, icon: "camera", tint: Theme.Palette.pink),
        AppUsage(name: "Safari", category: .browser, minutes: 38, icon: "safari", tint: Theme.Palette.blue),
        AppUsage(name: "Pulse", category: .health, minutes: 16, icon: "waveform.path.ecg", tint: Theme.Palette.green),
        AppUsage(name: "Slack", category: .work, minutes: 24, icon: "number", tint: Theme.Palette.purple)
    ]

    static func screenTimeWeek(now: Date = .now) -> [ScreenTimeDay] {
        let minutes = [252, 321, 278, 245, 296, 251, 263]
        return minutes.enumerated().map { index, value in
            ScreenTimeDay(
                date: now.startOfDay.adding(days: index - 6),
                minutes: value,
                categories: [
                    .video: value * 30 / 100, .social: value * 40 / 100, .browser: value * 14 / 100,
                    .work: value * 9 / 100, .other: value * 7 / 100
                ]
            )
        }
    }

    static func screenTimeSeries(days: Int, now: Date = .now) -> [DailyValue] {
        (0..<days).reversed().map { offset in
            let day = now.startOfDay.adding(days: -offset)
            var rng = SeededGenerator(seed: UInt64(abs(Int(day.timeIntervalSince1970 / 86_400))) + 7)
            return DailyValue(date: day, value: Double(rng.int(in: 190...340)))
        }
    }

    // MARK: Nutrition

    static func mealHistory(now: Date = .now) -> [Meal] {
        var rng = SeededGenerator(seed: 7)
        let menu: [(String, Int, Int, Int, Int, Int)] = [
            ("Овсянка с бананом", 8, 380, 12, 9, 62),
            ("Курица с гречкой", 13, 610, 48, 16, 64),
            ("Творог с ягодами", 16, 260, 24, 6, 22),
            ("Лосось с рисом", 19, 680, 38, 24, 70)
        ]
        return (1...21).flatMap { offset -> [Meal] in
            let day = now.startOfDay.adding(days: -offset)
            return menu.compactMap { item in
                guard rng.int(in: 0...9) > 1 else { return nil }
                let factor = rng.double(in: 0.85...1.2)
                return Meal(
                    id: UUID(), date: day.setting(hour: item.1, minute: rng.int(in: 0...50)), name: item.0,
                    kcal: Int(Double(item.2) * factor), protein: item.3, fat: item.4, carbs: item.5,
                    photo: nil, source: .photo
                )
            }
        }
    }

    // MARK: Mood, balance, cognitive

    static func moodHistory(now: Date = .now) -> [MoodEntry] {
        var rng = SeededGenerator(seed: 11)
        return (1...45).flatMap { offset -> [MoodEntry] in
            let day = now.startOfDay.adding(days: -offset)
            let base = rng.int(in: 5...8)
            return DaySlot.allCases.compactMap { slot in
                guard rng.int(in: 0...9) > 2 else { return nil }
                return MoodEntry(
                    id: UUID(), day: day, slot: slot,
                    mood: min(10, max(1, base + rng.int(in: -1...2))),
                    energy: min(10, max(1, base + rng.int(in: -2...1)))
                )
            }
        } + [MoodEntry(id: UUID(), day: now.startOfDay, slot: .morning, mood: 7, energy: 6)]
    }

    static func balanceHistory(now: Date = .now) -> [BalanceEntry] {
        var rng = SeededGenerator(seed: 23)
        return (1...10).map { week in
            let values = Dictionary(uniqueKeysWithValues: BalanceCategory.allCases.map {
                ($0.rawValue, Double(rng.int(in: 4...9)))
            })
            return BalanceEntry(id: UUID(), day: now.startOfDay.adding(days: -week * 7 + 3), values: values)
        }
    }

    static func cognitiveHistory(now: Date = .now) -> [CognitiveResult] {
        var rng = SeededGenerator(seed: 31)
        return (1...30).flatMap { offset -> [CognitiveResult] in
            let date = now.startOfDay.adding(days: -offset).setting(hour: 10, minute: 15)
            guard rng.int(in: 0...9) > 1 else { return [] }
            return CognitiveTestKind.allCases.map { kind in
                let score = rng.int(in: 55...92)
                return CognitiveResult(id: UUID(), kind: kind, date: date, score: score, metric: demoMetric(kind: kind, score: score))
            }
        }
    }

    private static func demoMetric(kind: CognitiveTestKind, score: Int) -> String {
        switch kind {
        case .reaction: "\(500 - score * 3) мс"
        case .goNoGo, .stroop: "\(score)% точность"
        case .memory: "\(3 + score * 6 / 100) цифр"
        case .aim: "\(900 - score * 5) мс"
        }
    }

    static func gameTestHistory(now: Date = .now) -> [GameTestResult] {
        var rng = SeededGenerator(seed: 5)
        return (0..<6).flatMap { offset -> [GameTestResult] in
            let day = now.startOfDay.adding(days: -offset)
            return [
                GameTestResult(id: UUID(), date: day.setting(hour: 11, minute: 50), phase: .beforeTraining,
                               focus: rng.int(in: 6...9), fatigue: rng.int(in: 2...4), readiness: rng.int(in: 6...9)),
                GameTestResult(id: UUID(), date: day.setting(hour: 14, minute: 10), phase: .afterTraining,
                               focus: rng.int(in: 4...8), fatigue: rng.int(in: 4...8), readiness: rng.int(in: 4...7))
            ]
        }
    }

    // MARK: Calendar

    static func calendarEvents(now: Date = .now) -> [CalendarEvent] {
        let today = now.startOfDay
        func event(_ title: String, _ details: String, day: Int, _ h: Int, _ m: Int, minutes: Int, tag: EventTag, links: [String] = []) -> CalendarEvent {
            let start = today.adding(days: day).setting(hour: h, minute: m)
            return CalendarEvent(id: UUID(), title: title, details: details, start: start,
                                 end: start.adding(minutes: minutes), links: links, tag: tag, ownerId: nil)
        }
        return [
            event("Тренировка", "Командная практика: дефолты на Mirage", day: 0, 12, 0, minutes: 120, tag: .team,
                  links: ["https://faceit.com"]),
            event("Разбор игры", "Демо вчерашнего матча, раунды 12–18", day: 0, 18, 0, minutes: 60, tag: .team),
            event("Пробежка", "Лёгкий темп, 5 км", day: 0, 8, 0, minutes: 40, tag: .personal),
            event("Матч ESL", "Групповой этап, BO3", day: 1, 19, 0, minutes: 180, tag: .team,
                  links: ["https://www.twitch.tv"]),
            event("Индивидуальная AWP-практика", "Aim botz, 45 минут", day: 1, 10, 0, minutes: 60, tag: .player("Nova")),
            event("Психолог", "Сессия по стресс-менеджменту", day: 2, 15, 0, minutes: 50, tag: .personal),
            event("Буткемп", "Выезд команды", day: 4, 9, 0, minutes: 600, tag: .team),
            event("Стрим", "Благотворительный стрим", day: -1, 20, 0, minutes: 120, tag: .player("Blitz"))
        ]
    }

    // MARK: Players (staff)

    static let players: [PlayerProfile] = [
        makePlayer("Nova", "Алексей Морозов", "AWPer", seed: 101, elo: 2840, hoursAgo: 1),
        makePlayer("Blitz", "Иван Котов", "Entry", seed: 202, elo: 2710, hoursAgo: 3),
        makePlayer("Echo", "Дмитрий Лебедев", "IGL", seed: 303, elo: 2590, hoursAgo: 20, readiness: 40...68),
        makePlayer("Frost", "Максим Орлов", "Support", seed: 404, elo: 2655, hoursAgo: 6),
        makePlayer("Ghost", "Никита Волков", "Lurker", seed: 505, elo: 2760, hoursAgo: 48)
    ]

    private static func makePlayer(
        _ nick: String, _ name: String, _ role: String, seed: UInt64, elo: Int, hoursAgo: Int,
        readiness: ClosedRange<Int> = 50...85
    ) -> PlayerProfile {
        var rng = SeededGenerator(seed: seed)
        let matches = rng.int(in: 38...60)
        let wins = rng.int(in: matches / 3...matches * 2 / 3)
        let draws = rng.int(in: 0...3)
        let rounds = matches * rng.int(in: 21...25)
        let pistol = matches * 2
        let stats = GameStats(
            kills: rng.int(in: 650...980), deaths: rng.int(in: 600...820), assists: rng.int(in: 150...300),
            adr: rng.double(in: 68...92), kpr: rng.double(in: 0.62...0.86), deathsPerRound: rng.double(in: 0.58...0.72),
            avgKR: rng.double(in: 0.65...0.85), avgKD: rng.double(in: 0.95...1.35), kast: rng.double(in: 66...78),
            firstKills: rng.int(in: 60...140), firstDeaths: rng.int(in: 50...120), udr: rng.double(in: 4...12),
            openDuelDiff: rng.int(in: -15...30), avgMultikills: rng.double(in: 1.2...2.4), clutchesWon: rng.int(in: 6...24),
            avgFlashTime: rng.double(in: 2.1...4.2), matches: matches, wins: wins, losses: matches - wins - draws, draws: draws,
            rounds: rounds, roundsWon: rounds / 2 + rng.int(in: -40...60), pistolRounds: pistol, pistolWon: rng.int(in: pistol / 3...pistol * 2 / 3)
        )
        return PlayerProfile(
            id: UUID(), nickname: nick, realName: name, gameRole: role,
            lastActive: Date.now.addingTimeInterval(TimeInterval(-hoursAgo * 3600)),
            stats: stats, elo: elo, seed: seed, readinessRange: readiness
        )
    }

    static func playerSeries(_ player: PlayerProfile, days: Int, range: ClosedRange<Int>, salt: UInt64) -> [DailyValue] {
        var rng = SeededGenerator(seed: player.seed &+ salt)
        return (0..<days).reversed().map { offset in
            DailyValue(date: Date.now.startOfDay.adding(days: -offset), value: Double(rng.int(in: range)))
        }
    }

}
