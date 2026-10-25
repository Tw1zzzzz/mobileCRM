import SwiftUI

enum AboutYouCopy {
    static let title = "О тебе"
    static let subtitle = "Возраст, рост и вес нужны для пульсовых зон и калорий, режим — для напоминаний о сне. Всё можно поменять в профиле."
}

/// «Тело», «Режим сна», «Цели на день» of the questionnaire step «О тебе»; the profile sheet shows the body only.
/// Errors of typed fields appear once the player leaves the field, not while typing «18» on the way to «182».
struct AboutYouFields: View {
    @Binding var form: AboutYouForm
    var schedule = true

    private enum Field: Hashable { case height, weight }
    @FocusState private var focus: Field?
    @State private var touched: Set<AboutYouField> = []

    private var errors: [AboutYouField: String] {
        AboutYou.errors(form, now: .now).filter { [.wake].contains($0.key) || touched.contains($0.key) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: "Тело")
            birthDateRow
            field("Рост, см", text: $form.heightText, placeholder: "Например, 182", keyboard: .numberPad, focus: .height, error: errors[.height])
            field("Вес, кг", text: Binding(get: { form.weightText }, set: { form = form.withWeightText($0) }),
                  placeholder: "Например, 74", keyboard: .decimalPad, focus: .weight, error: errors[.weight])
            if schedule {
                SectionHeader(title: "Режим сна")
                VStack(spacing: 14) {
                    DatePicker("Отбой", selection: time(\.bedtimeHour, \.bedtimeMinute), displayedComponents: .hourAndMinute)
                    DividerLine()
                    DatePicker("Подъём", selection: time(\.wakeHour, \.wakeMinute), displayedComponents: .hourAndMinute)
                    if let error = errors[.wake] { errorText(error) }
                }
                .font(.system(size: 17))
                .foregroundStyle(Theme.Palette.textPrimary)
                .pulseCard()
                SectionHeader(title: "Цели на день")
                VStack(spacing: 18) {
                    PulseSlider(title: "Активные минуты в день", value: number(\.activeMinutes), range: range(AboutYou.activeMinutes), tint: Theme.Palette.orange)
                    DecimalSlider(title: "Шаги в день", value: number(\.steps), range: range(AboutYou.steps), step: Double(AboutYou.stepsStep),
                                  digits: 0, tint: Theme.Palette.green)
                    DecimalSlider(title: "Активные ккал в день", value: number(\.activeKcal), range: range(AboutYou.activeKcal), step: 50,
                                  digits: 0, tint: Theme.Palette.orange)
                    DecimalSlider(title: "Калории в день", value: Binding(get: { Double(form.foodKcal) }, set: { form = form.withFoodKcal(Int($0)) }),
                                  range: range(AboutYou.foodKcal), step: 50, digits: 0)
                    DecimalSlider(title: "Вода в день, л", value: Binding(get: { Double(form.waterMl) / 1000 }, set: { form = form.withWater(Int(($0 * 1000).rounded())) }),
                                  range: Double(AboutYou.waterMl.lowerBound) / 1000...Double(AboutYou.waterMl.upperBound) / 1000,
                                  step: Double(AboutYou.waterStepMl) / 1000, digits: 2, suffix: " л", tint: Theme.Palette.blue)
                    PulseSlider(title: "Лимит экранного времени, ч", value: number(\.screenLimitHours), range: range(AboutYou.screenLimitHours),
                                tint: Theme.Palette.purple)
                }
                .pulseCard(padding: 18)
            }
        }
        .onChange(of: focus) { old, _ in
            if old == .height, !form.heightText.isEmpty { touched.insert(.height) }
            if old == .weight, !form.weightText.isEmpty { touched.insert(.weight) }
        }
    }

    // MARK: Rows

    @ViewBuilder private var birthDateRow: some View {
        let lower = Calendar.pulse.date(byAdding: .year, value: -AboutYou.ageYears.upperBound, to: .now) ?? .now
        let upper = Calendar.pulse.date(byAdding: .year, value: -AboutYou.ageYears.lowerBound, to: .now) ?? .now
        HStack {
            Text("Дата рождения").font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary)
            Spacer()
            if form.birthDate == nil {
                Button("Указать") {
                    form.birthDate = Calendar.pulse.date(byAdding: .year, value: -20, to: .now)
                    touched.insert(.birthDate)
                }
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(Theme.Palette.cyan)
            } else {
                DatePicker("Дата рождения", selection: Binding(get: { form.birthDate ?? upper }, set: { form.birthDate = $0 }),
                           in: lower...upper, displayedComponents: .date)
                    .labelsHidden()
            }
        }
        .frame(minHeight: 44)
        .pulseCard(padding: 14)
    }

    private func field(_ title: String, text: Binding<String>, placeholder: String, keyboard: UIKeyboardType, focus field: Field,
                       error: String?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            PulseTextField(title: title, text: text, placeholder: placeholder, keyboard: keyboard)
                .focused($focus, equals: field)
            if let error { errorText(error) }
        }
    }

    private func errorText(_ text: String) -> some View {
        Text(text).font(.system(size: 13)).foregroundStyle(Theme.Palette.red)
    }

    // MARK: Bindings

    private func range(_ range: ClosedRange<Int>) -> ClosedRange<Double> { Double(range.lowerBound)...Double(range.upperBound) }

    private func number(_ keyPath: WritableKeyPath<AboutYouForm, Int>) -> Binding<Double> {
        Binding(get: { Double(form[keyPath: keyPath]) }, set: { form[keyPath: keyPath] = Int($0.rounded()) })
    }

    private func time(_ hour: WritableKeyPath<AboutYouForm, Int>, _ minute: WritableKeyPath<AboutYouForm, Int>) -> Binding<Date> {
        Binding(
            get: { Date.now.setting(hour: form[keyPath: hour], minute: form[keyPath: minute]) },
            set: { date in
                form[keyPath: hour] = Calendar.pulse.component(.hour, from: date)
                form[keyPath: minute] = Calendar.pulse.component(.minute, from: date)
            }
        )
    }
}

/// Профиль → «О тебе»: birth date, height and weight; goals and the sleep window keep their own editors.
struct AboutYouSheet: View {
    @Environment(BodyProfileStore.self) private var bodyProfile
    @Environment(FitnessStore.self) private var fitness
    @Environment(SleepStore.self) private var sleep
    @Environment(NutritionStore.self) private var nutrition
    @Environment(ScreenTimeStore.self) private var screenTime
    @Environment(\.dismiss) private var dismiss
    @State private var form: AboutYouForm?

    var body: some View {
        SheetScaffold(title: AboutYouCopy.title) {
            if let current = form {
                AboutYouFields(form: Binding(get: { form ?? current }, set: { form = $0 }), schedule: false)
                GradientButton(title: "Сохранить") {
                    guard let form, AboutYou.updateBody(form, body: bodyProfile, fitness: fitness) else { return }
                    Task { await fitness.refreshHealth() }
                    dismiss()
                }
                .disabled(!AboutYou.errors(current, now: .now).isEmpty)
                .opacity(AboutYou.errors(current, now: .now).isEmpty ? 1 : 0.5)
            }
        }
        .onAppear {
            form = AboutYou.initial(window: sleep.window, fitness: fitness.goals, nutrition: nutrition.goals,
                                    screenLimitMinutes: screenTime.dailyLimitMinutes, profile: bodyProfile.profile, isNewPlayer: false)
        }
    }
}
