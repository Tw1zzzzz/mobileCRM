import SwiftUI

/// «Питание игроков» для стаффа: что едят игроки — фото блюд, калории и БЖУ за неделю.
struct TeamFoodContent: View {
    @Environment(NutritionStore.self) private var nutrition
    @State private var player: String?
    @State private var onlyPhotos = false
    @State private var opened: TeamFood.Entry?

    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        let all = TeamFood.entries(deviceMeals: nutrition.meals)
        let entries = all
            .filter { player == nil || $0.player == player }
            .filter { !onlyPhotos || $0.meal.photo != nil }
        let days = Dictionary(grouping: entries) { $0.meal.date.startOfDay }.keys.sorted(by: >)
        VStack(alignment: .leading, spacing: 18) {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ChipButton(title: "Все", isSelected: player == nil) { player = nil }
                    ForEach(DemoData.players) { item in
                        ChipButton(title: item.nickname, isSelected: player == item.nickname) { player = item.nickname }
                    }
                }
            }
            .scrollIndicators(.hidden)
            HStack {
                ChipButton(title: "Только с фото", isSelected: onlyPhotos) { onlyPhotos.toggle() }
                Spacer()
                Text("\(entries.count) записей · \(entries.filter { $0.meal.photo != nil }.count) с фото")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            if !all.contains(where: { $0.meal.photo != nil }) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "camera.badge.ellipsis").font(.system(size: 20)).foregroundStyle(Theme.Palette.cyan)
                    Text("Фото появятся здесь, когда игроки сфотографируют еду во вкладке «Питание». Ниже — записи без фото.")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
                .pulseCard(padding: 14)
            }
            if entries.isEmpty {
                EmptyStateView(systemName: "camera", title: "Фото пока нет",
                               message: "Когда игрок сфотографирует еду в приложении, снимок появится здесь")
            }
            ForEach(days, id: \.self) { day in
                PulseSection(title: Formatters.dayMonthWeekday(day)) {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(entries.filter { $0.meal.date.isSameDay(as: day) }) { entry in
                            Button { opened = entry } label: { FoodCard(entry: entry) }
                                .buttonStyle(.pressable)
                        }
                    }
                }
            }
        }
        .sheet(item: $opened) { entry in
            FoodEntrySheet(entry: entry).presentationDetents([.large])
        }
    }
}

private struct FoodCard: View {
    let entry: TeamFood.Entry

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            MealPhoto(meal: entry.meal)
                .frame(height: 118)
                .clipped()
                .overlay(alignment: .topLeading) {
                    Text(entry.player)
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 4)
                        .background(Capsule().fill(.black.opacity(0.55)))
                        .padding(8)
                }
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.meal.name)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                    .lineLimit(1)
                Text("\(entry.meal.kcal) ккал · \(Formatters.time(entry.meal.date))")
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.Palette.textSecondary)
            }
            .padding(12)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).fill(Theme.Palette.tile))
        .clipShape(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: Theme.Metrics.tileRadius, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
    }
}

/// Real photo when the player took one, otherwise a neutral placeholder.
struct MealPhoto: View {
    let meal: Meal

    var body: some View {
        if let data = meal.photo, let image = UIImage(data: data) {
            Image(uiImage: image).resizable().scaledToFill()
        } else {
            ZStack {
                LinearGradient(colors: [Theme.Palette.track, Theme.Palette.card], startPoint: .topLeading, endPoint: .bottomTrailing)
                VStack(spacing: 6) {
                    Image(systemName: "fork.knife").font(.system(size: 26, weight: .light))
                    Text("без фото").font(.system(size: 11))
                }
                .foregroundStyle(Theme.Palette.textTertiary)
            }
        }
    }
}

private struct FoodEntrySheet: View {
    let entry: TeamFood.Entry

    var body: some View {
        SheetScaffold(title: entry.player) {
            MealPhoto(meal: entry.meal)
                .frame(height: 280)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous))
            VStack(alignment: .leading, spacing: 12) {
                Text(entry.meal.name).font(.system(size: 22, weight: .bold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("\(Formatters.dayMonthTime(entry.meal.date)) · \(entry.meal.source.title)")
                    .font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
                HStack(spacing: 10) {
                    macro("Ккал", entry.meal.kcal, Theme.Palette.textPrimary)
                    macro("Белки", entry.meal.protein, Theme.Palette.cyan)
                    macro("Жиры", entry.meal.fat, Theme.Palette.orange)
                    macro("Углев.", entry.meal.carbs, Theme.Palette.purple)
                }
            }
            .pulseCard()
        }
    }

    private func macro(_ title: String, _ value: Int, _ tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("\(value)").font(.system(size: 18, weight: .bold)).foregroundStyle(tint)
            Text(title).font(.system(size: 12)).foregroundStyle(Theme.Palette.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Отдельный экран «Питание игроков».
struct TeamFoodView: View {
    var body: some View {
        ScreenScaffold(spacing: 18) {
            NavHeader(title: "Питание игроков", subtitle: "Фото еды за 7 дней")
        } content: {
            TeamFoodContent()
        }
    }
}
