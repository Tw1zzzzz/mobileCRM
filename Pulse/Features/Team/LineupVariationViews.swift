import SwiftUI

/// «Вариации» на экране раскидки: одна и та же граната с разных позиций / разной техникой.
struct LineupVariationsSection: View {
    let lineup: Lineup
    let canEdit: Bool

    @Environment(LineupStore.self) private var store
    @State private var showAdd = false

    var body: some View {
        PulseSection(title: "Вариации · \(lineup.variations.count)", actionTitle: canEdit ? "Добавить" : nil) {
            showAdd = true
        } content: {
            if lineup.variations.isEmpty {
                emptyHint
            } else {
                VStack(spacing: 10) {
                    ForEach(Array(lineup.variations.enumerated()), id: \.element.id) { index, variation in
                        VariationCard(index: index + 1, variation: variation)
                            .contextMenu {
                                if canEdit {
                                    Button(role: .destructive) { remove(variation.id) } label: {
                                        Label("Удалить вариацию", systemImage: "trash")
                                    }
                                }
                            }
                    }
                    if canEdit {
                        Text("Удерживай карточку, чтобы удалить вариацию")
                            .font(.system(size: 12))
                            .foregroundStyle(Theme.Palette.textTertiary)
                    }
                }
            }
        }
        .sheet(isPresented: $showAdd) {
            AddVariationSheet { variation in
                store.upsert(lineup.addingVariation(variation))
            }
            .presentationDetents([.large])
        }
    }

    private var emptyHint: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "square.stack.3d.up").font(.system(size: 20)).foregroundStyle(Theme.Palette.cyan)
            Text(canEdit
                 ? "Добавь вариации: та же граната с другой позиции или другой техникой броска — jump-throw, run-throw, ПКМ."
                 : "У этой раскидки пока одна вариация — описание выше.")
                .font(.system(size: 14))
                .foregroundStyle(Theme.Palette.textSecondary)
        }
        .pulseCard(padding: 14)
    }

    private func remove(_ id: UUID) {
        store.upsert(lineup.removingVariation(id: id))
    }
}

struct VariationCard: View {
    let index: Int
    let variation: LineupVariation

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Text("\(index)")
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background(Circle().fill(Theme.Gradients.accentDiagonal))
            VStack(alignment: .leading, spacing: 6) {
                Text(variation.position.isEmpty ? "Позиция не указана" : variation.position)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Text(variation.technique.title)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.Palette.cyan)
                    .padding(.horizontal, 8).padding(.vertical, 3)
                    .background(Capsule().fill(Theme.Palette.cyan.opacity(0.14)))
                if !variation.aim.isEmpty {
                    Label(variation.aim, systemImage: "scope")
                        .font(.system(size: 14))
                        .foregroundStyle(Theme.Palette.textSecondary)
                }
            }
            Spacer(minLength: 0)
            if let link = variation.videoURL, let url = URL(string: link) {
                Link(destination: url) {
                    Image(systemName: "play.rectangle.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(Theme.Palette.cyan)
                }
                .accessibilityLabel("Видео вариации \(index)")
            }
        }
        .pulseCard(padding: 14)
    }
}

/// Inline editor used by the lineup form and the «Добавить вариацию» sheet.
struct VariationEditor: View {
    let index: Int
    @Binding var variation: LineupVariation
    var onDelete: (() -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Вариация \(index)")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                Spacer()
                if let onDelete {
                    Button(action: onDelete) {
                        Image(systemName: "trash").foregroundStyle(Theme.Palette.red)
                    }
                    .accessibilityLabel("Удалить вариацию \(index)")
                }
            }
            PulseTextField(title: "Откуда бросать", text: $variation.position, placeholder: "T-спавн, угол у ящика")
            VStack(alignment: .leading, spacing: 8) {
                Text("Техника").font(.system(size: 14, weight: .medium)).foregroundStyle(Theme.Palette.textSecondary)
                ScrollView(.horizontal) {
                    HStack(spacing: 8) {
                        ForEach(ThrowTechnique.allCases) { technique in
                            ChipButton(title: technique.title, isSelected: variation.technique == technique) {
                                variation.technique = technique
                            }
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            PulseTextField(title: "Куда целиться", text: $variation.aim, placeholder: "Прицел на край антенны")
            PulseTextField(title: "Видео (необязательно)", text: Binding(
                get: { variation.videoURL ?? "" },
                set: { variation.videoURL = $0.isEmpty ? nil : $0 }
            ), placeholder: "https://…", keyboard: .URL)
        }
        .pulseCard(padding: 14)
    }
}

struct AddVariationSheet: View {
    let onSave: (LineupVariation) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var variation = LineupVariation(position: "", technique: .standard, aim: "")
    @State private var error: String?

    var body: some View {
        SheetScaffold(title: "Новая вариация") {
            VariationEditor(index: 1, variation: $variation)
            if let error { ErrorBanner(message: error) }
            GradientButton(title: "Добавить", systemImage: "plus") {
                if let message = LineupValidator.validate(variation: variation) {
                    withAnimation { error = message }
                    return
                }
                onSave(variation)
                dismiss()
            }
        }
    }
}

extension Lineup {
    func addingVariation(_ variation: LineupVariation) -> Lineup {
        var copy = self
        copy.variations = variations + [variation]
        return copy
    }

    func removingVariation(id: UUID) -> Lineup {
        var copy = self
        copy.variations = variations.filter { $0.id != id }
        return copy
    }
}

/// «2 вариации», «5 вариаций» — Russian plural forms.
enum VariationCount {
    static func text(_ count: Int) -> String {
        let mod10 = count % 10
        let mod100 = count % 100
        let word: String
        if mod10 == 1 && mod100 != 11 {
            word = "вариация"
        } else if (2...4).contains(mod10) && !(12...14).contains(mod100) {
            word = "вариации"
        } else {
            word = "вариаций"
        }
        return "\(count) \(word)"
    }
}
