import SwiftUI
import PhotosUI

/// Блоки 1–2 ТЗ «Питание»: выбор фото → анализ (loading) → результат / ошибка → сохранение.
struct FoodCaptureView: View {
    enum Phase: Equatable {
        case choosing
        case analyzing
        case result(FoodAnalysis)
        case failed(String)
        /// No AI on this account yet: the photo is kept and the player enters the numbers.
        case manualEntry
    }

    @Environment(NutritionStore.self) private var store
    @Environment(SessionStore.self) private var session
    @Environment(\.dismiss) private var dismiss

    @State private var phase: Phase = .choosing
    @State private var imageData: Data?
    @State private var pickerItem: PhotosPickerItem?
    @State private var showCamera = false
    @State private var editedKcal = ""
    @State private var editedName = ""
    @State private var showManual = false
    @State private var manualProtein = ""
    @State private var manualFat = ""
    @State private var manualCarbs = ""

    var body: some View {
        SheetScaffold(title: store.canAnalyzePhotos ? "Анализ блюда" : "Фото блюда") {
            preview
            switch phase {
            case .choosing: sourceButtons
            case .analyzing: analyzingState
            case .result(let analysis): resultState(analysis)
            case .failed(let message): failedState(message)
            case .manualEntry: manualEntryState
            }
        }
        .onChange(of: pickerItem) { _, item in
            guard let item else { return }
            Task { await loadPicked(item) }
        }
        .fullScreenCover(isPresented: $showCamera) {
            CameraPicker { data in
                showCamera = false
                if let data { startAnalysis(with: data) }
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showManual) {
            ManualMealSheet(defaultName: editedName).presentationDetents([.large])
        }
    }

    @ViewBuilder
    private var preview: some View {
        if let imageData, let image = UIImage(data: imageData) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(height: 240)
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous))
                .overlay(alignment: .center) {
                    if phase == .analyzing {
                        RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                            .fill(.black.opacity(0.45))
                            .overlay(ProgressView().controlSize(.large).tint(.white))
                    }
                }
        } else {
            RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                .fill(Theme.Palette.tile)
                .frame(height: 200)
                .overlay(
                    VStack(spacing: 12) {
                        Image(systemName: "camera.viewfinder").font(.system(size: 44, weight: .light))
                        Text("Сфотографируй тарелку сверху при хорошем освещении")
                            .font(.system(size: 15))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 30)
                    }
                    .foregroundStyle(Theme.Palette.textSecondary)
                )
                .overlay(RoundedRectangle(cornerRadius: Theme.Metrics.cardRadius, style: .continuous)
                    .strokeBorder(Theme.Palette.stroke, style: StrokeStyle(lineWidth: 1.5, dash: [6, 5])))
        }
    }

    private var sourceButtons: some View {
        VStack(spacing: 12) {
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                GradientButton(title: "Сделать фото", systemImage: "camera") { showCamera = true }
            }
            PhotosPicker(selection: $pickerItem, matching: .images) {
                HStack(spacing: 10) {
                    Image(systemName: "photo.on.rectangle")
                    Text("Выбрать из галереи")
                }
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.Palette.textPrimary)
                .frame(maxWidth: .infinity)
                .frame(height: 52)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Theme.Palette.tile))
                .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.Palette.stroke, lineWidth: 1))
            }
            SecondaryButton(title: "Ввести вручную", systemImage: "square.and.pencil", tint: Theme.Palette.textSecondary) {
                showManual = true
            }
        }
    }

    private var analyzingState: some View {
        HStack(spacing: 14) {
            ProgressView().tint(Theme.Palette.cyan)
            VStack(alignment: .leading, spacing: 4) {
                Text("AI анализирует фото…").font(.system(size: 17, weight: .semibold)).foregroundStyle(Theme.Palette.textPrimary)
                Text("Определяем блюдо, калорийность и БЖУ").font(.system(size: 14)).foregroundStyle(Theme.Palette.textSecondary)
            }
        }
        .pulseCard()
    }

    private func resultState(_ analysis: FoodAnalysis) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Text("РЕЗУЛЬТАТ").font(.system(size: 14, weight: .semibold)).tracking(2).foregroundStyle(Theme.Palette.cyan)
                    Spacer()
                    Text("уверенность \(Int(analysis.confidence * 100))%").font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
                }
                TextField("Название", text: $editedName)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(Theme.Palette.textPrimary)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    TextField("0", text: $editedKcal)
                        .keyboardType(.numberPad)
                        .font(.system(size: 44, weight: .bold))
                        .foregroundStyle(Theme.Palette.textPrimary)
                        .fixedSize()
                    Text("ккал").font(.system(size: 20)).foregroundStyle(Theme.Palette.textSecondary)
                    Spacer()
                    Label("изменить", systemImage: "pencil").font(.system(size: 14)).foregroundStyle(Theme.Palette.textTertiary)
                }
                HStack(spacing: 10) {
                    macro("Белки", analysis.protein, Theme.Palette.cyan)
                    macro("Жиры", analysis.fat, Theme.Palette.orange)
                    macro("Углеводы", analysis.carbs, Theme.Palette.purple)
                }
            }
            .pulseCard()
            GradientButton(title: "Сохранить", systemImage: "checkmark") { save(analysis) }
            SecondaryButton(title: "Повторить анализ", systemImage: "arrow.clockwise") {
                if let imageData { startAnalysis(with: imageData) }
            }
        }
    }

    private func macro(_ title: String, _ grams: Int, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 13)).foregroundStyle(Theme.Palette.textSecondary)
            Text("\(grams) г").font(.system(size: 18, weight: .bold)).foregroundStyle(color)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Theme.Palette.background.opacity(0.4)))
    }

    private func failedState(_ message: String) -> some View {
        VStack(spacing: 12) {
            ErrorBanner(message: message)
            GradientButton(title: "Повторить анализ", systemImage: "arrow.clockwise") {
                if let imageData { startAnalysis(with: imageData) }
            }
            SecondaryButton(title: "Ввести вручную", systemImage: "square.and.pencil") { showManual = true }
            SecondaryButton(title: "Выбрать другое фото", systemImage: "photo") {
                imageData = nil
                phase = .choosing
            }
        }
    }

    private func loadPicked(_ item: PhotosPickerItem) async {
        do {
            guard let data = try await item.loadTransferable(type: Data.self) else {
                phase = .failed("Не удалось загрузить изображение")
                return
            }
            startAnalysis(with: data)
        } catch {
            phase = .failed("Не удалось загрузить изображение. Проверьте доступ к фото.")
        }
    }

    private var manualEntryState: some View {
        VStack(alignment: .leading, spacing: 14) {
            Label("Автоматическое распознавание калорий появится с сервером Pulse. Пока введи данные — фото сохранится в дневнике.",
                  systemImage: "sparkles")
                .font(.system(size: 13))
                .foregroundStyle(Theme.Palette.textSecondary)
            PulseTextField(title: "Блюдо *", text: $editedName, placeholder: "Курица с рисом")
            PulseTextField(title: "Калории, ккал *", text: $editedKcal, placeholder: "500", keyboard: .numberPad)
            HStack(spacing: 10) {
                PulseTextField(title: "Белки, г", text: $manualProtein, placeholder: "0", keyboard: .numberPad)
                PulseTextField(title: "Жиры, г", text: $manualFat, placeholder: "0", keyboard: .numberPad)
                PulseTextField(title: "Углев., г", text: $manualCarbs, placeholder: "0", keyboard: .numberPad)
            }
            GradientButton(title: "Сохранить", systemImage: "checkmark") { saveManual() }
                .disabled(editedName.trimmingCharacters(in: .whitespaces).isEmpty || Int(editedKcal) == nil)
        }
    }

    private func saveManual() {
        let name = editedName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty, let kcal = Int(editedKcal) else { return }
        store.addMeal(
            name: name,
            kcal: min(max(kcal, 0), 5000),
            protein: min(max(Int(manualProtein) ?? 0, 0), 500),
            fat: min(max(Int(manualFat) ?? 0, 0), 500),
            carbs: min(max(Int(manualCarbs) ?? 0, 0), 1000),
            photo: imageData, source: .photo,
            author: session.user?.nickname
        )
        dismiss()
    }

    private func startAnalysis(with data: Data) {
        let compressed = ImageCompressor.thumbnailData(from: data) ?? data
        imageData = compressed
        guard store.canAnalyzePhotos else {
            phase = .manualEntry
            return
        }
        phase = .analyzing
        Task {
            do {
                let analysis = try await store.analyzer.analyze(imageData: compressed)
                editedName = analysis.dishName
                editedKcal = "\(analysis.kcal)"
                phase = .result(analysis)
            } catch let error as FoodAnalysisError {
                phase = .failed(error.message)
            } catch {
                phase = .failed(FoodAnalysisError.unrecognized.message)
            }
        }
    }

    private func save(_ analysis: FoodAnalysis) {
        let kcal = Int(editedKcal) ?? analysis.kcal
        let name = editedName.trimmingCharacters(in: .whitespaces)
        store.addMeal(
            name: name.isEmpty ? analysis.dishName : name,
            kcal: min(max(kcal, 0), 5000),
            protein: analysis.protein, fat: analysis.fat, carbs: analysis.carbs,
            photo: imageData, source: .photo,
            author: session.user?.nickname
        )
        dismiss()
    }
}

/// UIKit camera bridge (PhotosPicker has no camera mode).
struct CameraPicker: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onFinish: (Data?) -> Void

        init(onFinish: @escaping (Data?) -> Void) {
            self.onFinish = onFinish
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let image = info[.originalImage] as? UIImage
            onFinish(image?.jpegData(compressionQuality: 0.85))
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onFinish(nil)
        }
    }
}
