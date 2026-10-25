import Foundation
import HealthKit

/// Source of real activity data. The store depends on this protocol, so tests and the demo work without HealthKit.
protocol HealthDataProviding {
    var isAvailable: Bool { get }
    func requestAuthorization() async throws
    /// `body` («О тебе») gives the age for heart-rate zones and the height/weight for «Тело» and the kcal estimate.
    func snapshot(now: Date, body: BodyProfile?) async throws -> HealthSnapshot
}

/// Read-only Apple Health access: steps, distance, energy, exercise minutes, heart rate, workouts, body.
final class HealthKitService: HealthDataProviding {
    private let store = HKHealthStore()
    private let days = 7

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    private var readTypes: Set<HKObjectType> {
        let quantities: [HKQuantityTypeIdentifier] = [
            .stepCount, .distanceWalkingRunning, .activeEnergyBurned, .appleExerciseTime,
            .heartRate, .restingHeartRate, .heartRateVariabilitySDNN, .respiratoryRate, .oxygenSaturation,
            .bodyMass, .bodyFatPercentage, .leanBodyMass
        ]
        var types: Set<HKObjectType> = Set(quantities.map { HKQuantityType($0) })
        types.insert(HKObjectType.workoutType())
        types.insert(HKCategoryType(.sleepAnalysis))
        types.insert(HKCharacteristicType(.dateOfBirth))
        return types
    }

    func requestAuthorization() async throws {
        try await store.requestAuthorization(toShare: [], read: readTypes)
    }

    func snapshot(now: Date = .now, body: BodyProfile? = nil) async throws -> HealthSnapshot {
        let start = now.startOfDay.adding(days: -(days - 1))
        async let steps = dailySums(.stepCount, unit: .count(), from: start, to: now)
        async let distance = dailySums(.distanceWalkingRunning, unit: .meterUnit(with: .kilo), from: start, to: now)
        async let energy = dailySums(.activeEnergyBurned, unit: .kilocalorie(), from: start, to: now)
        async let exercise = dailySums(.appleExerciseTime, unit: .minute(), from: start, to: now)
        async let brisk = briskMinutesPerDay(from: start, to: now)
        async let heartRates = samples(.heartRate, unit: HKUnit.count().unitDivided(by: .minute()), from: now.startOfDay, to: now)
        async let workouts = workouts(from: now.startOfDay.adding(days: -27), to: now)
        async let sleep = sleepIntervals(from: now.startOfDay.adding(days: -30), to: now)
        async let restingByDay = dailyAverages(.restingHeartRate, unit: HKUnit.count().unitDivided(by: .minute()),
                                               from: now.startOfDay.adding(days: -29), to: now)
        async let hrvByDay = dailyAverages(.heartRateVariabilitySDNN, unit: .secondUnit(with: .milli),
                                           from: now.startOfDay.adding(days: -29), to: now)
        async let resting = latest(.restingHeartRate, unit: HKUnit.count().unitDivided(by: .minute()))
        async let hrv = latest(.heartRateVariabilitySDNN, unit: .secondUnit(with: .milli))
        async let weight = latestSample(.bodyMass, unit: .gramUnit(with: .kilo))
        async let fat = latest(.bodyFatPercentage, unit: .percent())
        async let lean = latest(.leanBodyMass, unit: .gramUnit(with: .kilo))

        let (stepsByDay, distanceByDay, energyByDay, exerciseByDay, briskByDay) =
            try await (steps, distance, energy, exercise, brisk)
        let healthWeight = try await weight
        let weightKg = BodyMath.currentWeight(profile: body, healthKg: healthWeight?.value, healthAt: healthWeight?.date)
        let dayList = (0..<days).map { start.adding(days: $0) }
        let activeMinutes = dayList.map { day in
            DailyValue(date: day, value: max(exerciseByDay[day] ?? 0, Double(briskByDay[day] ?? 0)))
        }
        let today = now.startOfDay
        let todaySteps = Int(stepsByDay[today] ?? 0)
        let recordedKcal = Int((energyByDay[today] ?? 0).rounded())
        let summary = HealthDaySummary(
            steps: todaySteps,
            distanceKm: distanceByDay[today] ?? 0,
            activeKcal: recordedKcal > 0 ? recordedKcal : FitnessMath.estimatedActiveKcal(steps: todaySteps, weightKg: weightKg),
            activeMinutes: Int(activeMinutes.last?.value ?? 0)
        )
        let bpm = try await heartRates
        // «О тебе» first; Apple Health's date of birth only for accounts that have not answered it yet.
        let age = FitnessMath.age(birthDate: body?.birthDay ?? (try? store.dateOfBirthComponents()), now: now)
        let average = bpm.isEmpty ? nil : Int((bpm.reduce(0, +) / Double(bpm.count)).rounded())

        let allWorkouts = try await workouts
        let intervals = try await sleep
        let vitals = try await nightVitals(for: SleepMath.nights(from: intervals).last)

        return HealthSnapshot(
            today: summary,
            weeklyActiveMinutes: activeMinutes,
            workouts: allWorkouts.filter { $0.date >= start },
            heartRateZones: FitnessMath.zones(samples: bpm, maxHeartRate: FitnessMath.maxHeartRate(age: age)),
            averageHeartRate: average,
            restingHeartRate: try await resting.map { Int($0.rounded()) },
            hrv: try await hrv.map { Int($0.rounded()) },
            fetchedAt: now,
            // Apple Health stores body fat as a fraction.
            bodyReadings: BodyReadings(weightKg: healthWeight?.value, weightAt: healthWeight?.date,
                                       bodyFatPercent: try await fat.map { $0 * 100 }, leanMassKg: try await lean),
            sleepIntervals: intervals,
            nightVitals: vitals,
            restingHeartRateByDay: try await restingByDay,
            hrvByDay: try await hrvByDay,
            loadWorkouts: allWorkouts
        )
    }

    // MARK: Sleep

    private func sleepIntervals(from start: Date, to end: Date) async throws -> [SleepInterval] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.categorySample(type: HKCategoryType(.sleepAnalysis), predicate: predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)]
        )
        return try await descriptor.result(for: store).compactMap { sample in
            guard let value = HKCategoryValueSleepAnalysis(rawValue: sample.value) else { return nil }
            return SleepInterval(stage: Self.stage(for: value), start: sample.startDate, end: sample.endDate)
        }
    }

    /// «В кровати» has no stage; unspecified sleep (phone or older trackers) counts as light sleep.
    private static func stage(for value: HKCategoryValueSleepAnalysis) -> SleepStage? {
        switch value {
        case .inBed: nil
        case .awake: .awake
        case .asleepDeep: .deep
        case .asleepREM: .rem
        default: .light
        }
    }

    private func nightVitals(for night: [SleepInterval]?) async throws -> SleepMath.NightVitals? {
        guard let night, let start = night.map(\.start).min(), let end = night.map(\.end).max() else { return nil }
        let bpmUnit = HKUnit.count().unitDivided(by: .minute())
        async let heart = timedSamples(.heartRate, unit: bpmUnit, from: start, to: end)
        async let hrv = samples(.heartRateVariabilitySDNN, unit: .secondUnit(with: .milli), from: start, to: end)
        async let breathing = samples(.respiratoryRate, unit: bpmUnit, from: start, to: end)
        async let oxygen = samples(.oxygenSaturation, unit: .percent(), from: start, to: end)
        async let resting = latest(.restingHeartRate, unit: bpmUnit)
        let baseline = try await dailyAverages(.restingHeartRate, unit: bpmUnit, from: end.startOfDay.adding(days: -30), to: end.startOfDay)
        func average(_ values: [Double], scale: Double = 1) -> Int? {
            values.isEmpty ? nil : Int((values.reduce(0, +) / Double(values.count) * scale).rounded())
        }
        return SleepMath.NightVitals(
            heartRate: try await heart,
            restingHeartRate: try await resting.map { Int($0.rounded()) },
            restingBaseline: StatisticsMath.mean(baseline.map(\.value)),
            hrv: average(try await hrv),
            respiratoryRate: average(try await breathing),
            spo2: average(try await oxygen, scale: 100)
        )
    }

    private func timedSamples(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [(date: Date, bpm: Double)] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(id), predicate: predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)]
        )
        return try await descriptor.result(for: store).map { ($0.startDate, $0.quantity.doubleValue(for: unit)) }
    }

    /// Daily average of a discrete metric (resting pulse, HRV), only days with data, oldest first.
    private func dailyAverages(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [DailyValue] {
        let type = HKQuantityType(id)
        let descriptor = HKStatisticsCollectionQueryDescriptor(
            predicate: HKSamplePredicate.quantitySample(type: type, predicate: HKQuery.predicateForSamples(withStart: start, end: end)),
            options: .discreteAverage,
            anchorDate: start.startOfDay,
            intervalComponents: DateComponents(day: 1)
        )
        let collection = try await descriptor.result(for: store)
        var result: [DailyValue] = []
        collection.enumerateStatistics(from: start, to: end) { stats, _ in
            if let value = stats.averageQuantity()?.doubleValue(for: unit) {
                result.append(DailyValue(date: stats.startDate.startOfDay, value: value))
            }
        }
        return result
    }

    // MARK: Queries

    private func dailySums(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [Date: Double] {
        let collection = try await statistics(id, interval: DateComponents(day: 1), from: start, to: end)
        var result: [Date: Double] = [:]
        collection.enumerateStatistics(from: start, to: end) { stats, _ in
            result[stats.startDate.startOfDay] = stats.sumQuantity()?.doubleValue(for: unit) ?? 0
        }
        return result
    }

    /// Without a watch there is no exercise time: count minutes with ≥ 100 steps instead.
    private func briskMinutesPerDay(from start: Date, to end: Date) async throws -> [Date: Int] {
        let collection = try await statistics(.stepCount, interval: DateComponents(minute: 1), from: start, to: end)
        var perDay: [Date: [Double]] = [:]
        collection.enumerateStatistics(from: start, to: end) { stats, _ in
            guard let steps = stats.sumQuantity()?.doubleValue(for: .count()) else { return }
            perDay[stats.startDate.startOfDay, default: []].append(steps)
        }
        return perDay.mapValues { FitnessMath.briskMinutes(stepsPerMinute: $0) }
    }

    private func statistics(_ id: HKQuantityTypeIdentifier, interval: DateComponents,
                            from start: Date, to end: Date) async throws -> HKStatisticsCollection {
        let type = HKQuantityType(id)
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKStatisticsCollectionQueryDescriptor(
            predicate: HKSamplePredicate.quantitySample(type: type, predicate: predicate),
            options: .cumulativeSum,
            anchorDate: start,
            intervalComponents: interval
        )
        return try await descriptor.result(for: store)
    }

    private func samples(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [Double] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(id), predicate: predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)]
        )
        return try await descriptor.result(for: store).map { $0.quantity.doubleValue(for: unit) }
    }

    /// The newest sample with its end date (decides whether it beats the weight from «О тебе»).
    private func latestSample(_ id: HKQuantityTypeIdentifier, unit: HKUnit) async throws -> (value: Double, date: Date)? {
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(id))],
            sortDescriptors: [SortDescriptor(\.endDate, order: .reverse)],
            limit: 1
        )
        return try await descriptor.result(for: store).first.map { ($0.quantity.doubleValue(for: unit), $0.endDate) }
    }

    private func latest(_ id: HKQuantityTypeIdentifier, unit: HKUnit) async throws -> Double? {
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: HKQuantityType(id))],
            sortDescriptors: [SortDescriptor(\.endDate, order: .reverse)],
            limit: 1
        )
        return try await descriptor.result(for: store).first?.quantity.doubleValue(for: unit)
    }

    private func workouts(from start: Date, to end: Date) async throws -> [Workout] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.workout(predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)],
            limit: 100
        )
        return try await descriptor.result(for: store).map(Self.workout)
    }

    private static func workout(_ item: HKWorkout) -> Workout {
        let kcal = item.statistics(for: HKQuantityType(.activeEnergyBurned))?.sumQuantity()?.doubleValue(for: .kilocalorie()) ?? 0
        let bpm = item.statistics(for: HKQuantityType(.heartRate))?
            .averageQuantity()?.doubleValue(for: HKUnit.count().unitDivided(by: .minute())) ?? 0
        return Workout(
            id: item.uuid,
            kind: kind(for: item.workoutActivityType),
            detail: "Apple Health",
            date: item.startDate,
            minutes: max(Int((item.duration / 60).rounded()), 1),
            kcal: Int(kcal.rounded()),
            averageHeartRate: Int(bpm.rounded())
        )
    }

    private static func kind(for type: HKWorkoutActivityType) -> WorkoutKind {
        switch type {
        case .traditionalStrengthTraining, .functionalStrengthTraining, .coreTraining, .crossTraining: .strength
        case .highIntensityIntervalTraining, .mixedCardio: .hiit
        case .yoga, .mindAndBody, .pilates: .yoga
        case .flexibility, .cooldown, .preparationAndRecovery: .stretch
        default: .cardio
        }
    }
}
