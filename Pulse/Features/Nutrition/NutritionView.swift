import SwiftUI

/// Mockup 7 — «Питание»: opened from the «Питание» card in «Состояние → Здоровье».
struct NutritionView: View {
    @Environment(AppRouter.self) private var router

    var body: some View {
        ScreenScaffold {
            NavHeader(title: "Питание", subtitle: "Калории, белки, жиры, углеводы", style: .leading) {
                HeaderIconButton(systemName: "clock.arrow.circlepath") { router.open(.nutritionHistory, in: router.selectedTab) }
                    .accessibilityLabel("История питания")
            }
        } content: {
            NutritionContent()
        }
    }
}

/// КБЖУ, вода, фото-CTA, быстрые действия, дневник за сегодня.
struct NutritionContent: View {
    @Environment(NutritionStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(AppRouter.self) private var router
    @State private var showCapture = false
    @State private var showManualSnack = false

    var body: some View {
        let totals = store.totals(on: .now)
        VStack(alignment: .leading, spacing: Theme.Metrics.sectionSpacing) {
            summaryCard(totals: totals)
            photoCTA
            quickActions
            PulseSection(title: "Сегодня", actionTitle: "История") {
                router.open(.nutritionHistory, in: router.selectedTab)
            } content: {
                todayMeals
            }
        }
        .sheet(isPresented: $showCapture) {
            FoodCaptureView().presentationDetents([.large])
        }
        .sheet(isPresented: $showManualSnack) {
            ManualMealSheet(defaultName: "Перекус").presentationDetents([.medium, .large])
        }
        // «+ → Фото еды» may switch to this section and set the flag before it appears.
        .onAppear(perform: consumePendingCapture)
        .onChange(of: router.pendingFoodCapture) { _, _ in consumePendingCapture() }
    }

    private func consumePendingCapture() {
        guard router.pendingFoodCapture else { return }
        router.pendingFoodCapture = false
        showCapture = true
    }

    private func summaryCard(totals: NutritionTotals) -> some View {
        let goals = store.goals
        let water = store.water(on: .now)
        return VStack(spacing: 22) {
            HStack(spacing: 16) {
                ZStack {
                    ProgressRing(progress: Double(totals.kcal) / Double(goals.kcal), lineWidth: 14)
                    VStack(spacing: 4) {
                        Text(Formatters.grouped(totals.kcal))
                            .font(.system(size: 42, weight: .bold))
                            .foregroundStyle(Theme.Palette.textPrimary)
                            .minimumScaleFactor(0.6)
                            .lineLimit(1)
                        Text("/ \(String(goals.kcal)) ккал")
                            .font(.system(size: 14))
                            .foregroundStyle(Theme.Palette.textSecondary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                    }
                    .padding(18)
                }
                .frame(width: 112, height: 112)
                DividerLine(vertical: true).frame(height: 112)
                VStack(spacing: 14) {
                    macroRow("Белки", value: totals.protein, goal: goals.protein, color: Theme.Palette.cyan)
                    macroRow("Жиры", value: totals.fat, goal: goals.fat, color: Theme.Palette.orange)
                    macroRow("Углеводы", value: totals.carbs, goal: goals.carbs, color: Theme.Palette.purple)
                }
            }
            DividerLine()
            VStack(spacing: 14) {
                HStack(spacing: 14) {
                    Image(systemName: "drop").font(.system(size: 22)).foregroundStyle(Theme.Palette.cyan)
                    Text("Вода").font(.system(size: 18)).foregroundStyle(Theme.Palette.textPrimary)
                    Spacer()
                    Text("\(Formatters.liters(milliliters: water)) / \(Formatters.liters(milliliters: goals.waterMilliliters)) л")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                }
                .padding(.horizontal, 6)
                GradientBar(progress: Double(water) / Double(goals.waterMilliliters), height: 12)
            }
        }
        .pulseCard(padding: 20)
    }

    private func macroRow(_ title: String, value: Int, goal: Int, color: Color) -> some View {
        VStack(spacing: 10) {
            // One line on narrow phones: the title shrinks before it breaks («Углевод-ы»).
            HStack(spacing: 6) {
                Text(title).font(.system(size: 16)).foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.75)
                Spacer(minLength: 4)
                Text("\(value) / \(goal) г").font(.system(size: 15)).foregroundStyle(Theme.Palette.textSecondary)
                    .lineLimit(1).minimumScaleFactor(0.75)
            }
            GradientBar(progress: Double(value) / Double(goal), height: 10,
                        fill: AnyShapeStyle(LinearGradient(colors: [color.opacity(0.8), color], startPoint: .leading, endPoint: .trailing)))
                .overlay(Capsule().strokeBorder(Color(hex: 0x24345A), lineWidth: 1.5))
        }
    }

    private var photoCTA: some View {
        Button {
            showCapture = true
        } label: {
            HStack(spacing: 16) {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(.white.opacity(0.16))
                    .frame(width: 54, height: 54)
                    .overlay(Image(systemName: "camera").font(.system(size: 26, weight: .medium)).foregroundStyle(.white))
                VStack(alignment: .leading, spacing: 6) {
                    Text("Сфотографируй еду")
                        .font(.system(size: 21, weight: .bold))
                    Text(store.canAnalyzePhotos ? "AI оценит состав за 5 секунд" : "Фото и калории — в дневник питания")
                        .font(.system(size: 15))
                        .opacity(0.85)
                }
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                Spacer(minLength: 0)
                Image(systemName: "arrow.right").font(.system(size: 24, weight: .medium)).foregroundStyle(.white)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
            .background(
                RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                    .fill(LinearGradient(colors: [Color(hex: 0x1462F5), Color(hex: 0x00CCFF)], startPoint: .leading, endPoint: .trailing))
            )
            .shadow(color: Theme.Palette.blue.opacity(0.35), radius: 18, y: 8)
        }
        .buttonStyle(.pressable)
    }

    private var quickActions: some View {
        HStack(spacing: 12) {
            quickTile("Стакан воды", icon: "drop") { store.addWater(milliliters: 250) }
            quickTile("Кофе", icon: "cup.and.saucer") {
                store.addMeal(name: "Кофе", kcal: 60, protein: 3, fat: 3, carbs: 5, source: .quick, author: session.user?.nickname)
            }
            quickTile("Перекус", icon: "leaf") { showManualSnack = true }
        }
    }

    private func quickTile(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 12) {
                Image(systemName: icon).font(.system(size: 26, weight: .light)).foregroundStyle(Theme.Palette.textSecondary)
                Text(title).font(.system(size: 16, weight: .medium)).foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 22)
            .background(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).fill(Theme.Palette.tile))
            .overlay(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
        }
        .buttonStyle(.pressable)
        .sensoryFeedback(.impact(weight: .light), trigger: store.meals.count + store.water(on: .now))
    }

    @ViewBuilder
    private var todayMeals: some View {
        let meals = store.meals(on: .now)
        if meals.isEmpty {
            EmptyStateView(systemName: "fork.knife", title: "Приёмов пищи пока нет",
                           message: "Добавь первое блюдо, чтобы начать учёт")
        } else {
            VStack(spacing: 0) {
                ForEach(Array(meals.enumerated()), id: \.element.id) { index, meal in
                    MealRow(meal: meal)
                        .contextMenu {
                            Button("Удалить", systemImage: "trash", role: .destructive) { store.deleteMeal(id: meal.id) }
                        }
                    if index < meals.count - 1 { DividerLine() }
                }
            }
            .padding(.horizontal, 16)
            .pulseCard(padding: 0)
        }
    }
}

struct MealRow: View {
    let meal: Meal

    var body: some View {
        HStack(spacing: 14) {
            thumbnail
            VStack(alignment: .leading, spacing: 4) {
                Text(meal.name).font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("Б \(meal.protein) · Ж \(meal.fat) · У \(meal.carbs)  ·  \(Formatters.time(meal.date))")
                    .font(.system(size: 14))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            Spacer()
            ValueUnitText(value: "\(meal.kcal)", unit: "ккал", valueSize: 19, unitSize: 13)
        }
        .padding(.vertical, 12)
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let data = meal.photo, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        } else {
            IconBadge(systemName: meal.source == .quick ? "cup.and.saucer" : "fork.knife",
                      tint: Theme.Palette.textSecondary, size: 52, iconSize: 20, background: Theme.Palette.track)
        }
    }
}

/// Manual entry (snack or correcting a failed recognition).
struct ManualMealSheet: View {
    var defaultName = ""
    @Environment(NutritionStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var kcal = ""
    @State private var protein = ""
    @State private var fat = ""
    @State private var carbs = ""
    @State private var error: String?

    var body: some View {
        SheetScaffold(title: "Добавить вручную") {
            PulseTextField(title: "Название", text: $name, placeholder: defaultName)
            PulseTextField(title: "Калории, ккал *", text: $kcal, placeholder: "0", keyboard: .numberPad)
            HStack(spacing: 10) {
                PulseTextField(title: "Белки, г", text: $protein, placeholder: "0", keyboard: .numberPad)
                PulseTextField(title: "Жиры, г", text: $fat, placeholder: "0", keyboard: .numberPad)
                PulseTextField(title: "Углев., г", text: $carbs, placeholder: "0", keyboard: .numberPad)
            }
            if let error { ErrorBanner(message: error) }
            GradientButton(title: "Сохранить") { save() }
        }
    }

    private func save() {
        guard let calories = Int(kcal), calories > 0, calories < 5000 else {
            error = "Укажите калорийность от 1 до 5000 ккал"
            return
        }
        let title = name.trimmingCharacters(in: .whitespaces)
        store.addMeal(
            name: title.isEmpty ? (defaultName.isEmpty ? "Приём пищи" : defaultName) : title,
            kcal: calories, protein: Int(protein) ?? 0, fat: Int(fat) ?? 0, carbs: Int(carbs) ?? 0,
            source: .manual,
            author: session.user?.nickname
        )
        dismiss()
    }
}
