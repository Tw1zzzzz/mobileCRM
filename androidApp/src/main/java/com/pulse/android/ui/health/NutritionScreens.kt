package com.pulse.android.ui.health

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.Callout
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientBar
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.ProgressRing
import com.pulse.android.ui.components.PulseBarChart
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.ValueUnitText
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.FoodAnalysis
import com.pulse.core.model.Meal
import com.pulse.core.model.MealSource
import com.pulse.core.store.FoodAnalysisException
import com.pulse.core.util.Formatters
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDateTime
import com.pulse.core.util.startOfDay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.time.Clock

/** Mockup 7 — «Питание»: КБЖУ, вода, фото-CTA, быстрые действия, дневник за сегодня. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NutritionScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.nutrition
    val now = Clock.System.now()
    val totals = store.totals(now)
    val goals = store.goals
    var capture by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(router.pendingFoodCapture) {
        if (router.pendingFoodCapture) {
            router.pendingFoodCapture = false
            capture = true
        }
    }
    ScreenScaffold(header = {
        NavHeader("Питание", "Калории, белки, жиры, углеводы", onBack = { router.pop() }) {
            HeaderIconButton("clock.arrow.circlepath", "История питания") { router.open(Route.NutritionHistory) }
        }
    }) {
        Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(totals.kcal.toDouble() / goals.kcal, Modifier.fillMaxSize())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(Formatters.grouped(totals.kcal), color = Palette.textPrimary, autoSize = fit(34.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("/ ${goals.kcal} ккал", color = Palette.textSecondary, autoSize = fit(13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.width(1.dp).height(112.dp).background(Palette.divider))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MacroRow("Белки", totals.protein, goals.protein, Palette.cyan)
                    MacroRow("Жиры", totals.fat, goals.fat, Palette.orange)
                    MacroRow("Углеводы", totals.carbs, goals.carbs, Palette.purple)
                }
            }
            DividerLine()
            val water = store.water(now)
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
                    Icon(sfIcon("drop"), null, tint = Palette.cyan, modifier = Modifier.size(22.dp))
                    Text("Вода", color = Palette.textPrimary, fontSize = 18.sp, modifier = Modifier.weight(1f))
                    Text("${Formatters.liters(water)} / ${Formatters.liters(goals.waterMilliliters)} л", color = Palette.textPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                }
                GradientBar(water.toDouble() / goals.waterMilliliters, height = 12.dp)
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Metrics.cardRadius))
                .background(Brush.horizontalGradient(listOf(Color(0xFF1462F5), Color(0xFF00CCFF)))).pressable { capture = true }
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(sfIcon("camera"), null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Сфотографируй еду", color = Color.White, autoSize = fit(20.sp), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (store.canAnalyzePhotos) "AI оценит состав за 5 секунд" else "Фото и калории — в дневник питания", color = Color.White.copy(alpha = 0.85f), autoSize = fit(15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(sfIcon("arrow.right"), null, tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuickTile("Стакан воды", "drop", Modifier.weight(1f)) { store.addWater(250) }
            QuickTile("Кофе", "cup.and.saucer", Modifier.weight(1f)) {
                store.addMeal("Кофе", 60, 3, 3, 5, source = MealSource.QUICK, author = core.session.user.value?.nickname)
            }
            QuickTile("Перекус", "leaf", Modifier.weight(1f)) { manual = "Перекус" }
        }
        PulseSection("Сегодня", "История", { router.open(Route.NutritionHistory) }) {
            val meals = store.meals(now)
            if (meals.isEmpty()) {
                EmptyStateView("fork.knife", "Приёмов пищи пока нет", "Добавь первое блюдо, чтобы начать учёт")
            } else {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    meals.forEachIndexed { index, meal ->
                        MealRowWithMenu(meal) { store.deleteMeal(meal.id) }
                        if (index < meals.size - 1) DividerLine()
                    }
                }
            }
        }
    }
    if (capture) PulseSheet({ capture = false }) {
        FoodCaptureSheet({ capture = false }) { name -> capture = false; manual = name }
    }
    manual?.let { name ->
        PulseSheet({ manual = null }) {
            ManualMealSheet(name) { manual = null }
        }
    }
}

@Composable
private fun MacroRow(title: String, value: Int, goal: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row {
            Text(title, color = Palette.textPrimary, autoSize = fit(15.sp), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$value / $goal г", color = Palette.textSecondary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GradientBar(value.toDouble() / goal, height = 10.dp, fill = Brush.horizontalGradient(listOf(color.copy(alpha = 0.8f), color)))
    }
}

@Composable
private fun QuickTile(title: String, icon: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(Metrics.tileRadius)).background(Palette.tile).border(1.dp, Palette.stroke, RoundedCornerShape(Metrics.tileRadius))
            .pressable(onClick = onClick).padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(sfIcon(icon), null, tint = Palette.textSecondary, modifier = Modifier.size(28.dp))
        Text(title, color = Palette.textPrimary, autoSize = fit(15.sp), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MealRowWithMenu(meal: Meal, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.pressable { menu = true }) { MealRow(meal) }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Удалить", color = Palette.red) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
fun MealRow(meal: Meal) {
    val bitmap = remember(meal.id) { meal.photo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)))
        } else {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Palette.track), contentAlignment = Alignment.Center) {
                Icon(sfIcon(if (meal.source == MealSource.QUICK) "cup.and.saucer" else "fork.knife"), null, tint = Palette.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(meal.name, color = Palette.textPrimary, autoSize = fit(17.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Б ${meal.protein} · Ж ${meal.fat} · У ${meal.carbs}  ·  ${Formatters.time(meal.date)}", color = Palette.textSecondary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ValueUnitText("${meal.kcal}", "ккал", valueSize = 18.sp, unitSize = 12.sp)
    }
}

/** Manual entry (snack, or correcting a failed recognition). */
@Composable
private fun ManualMealSheet(defaultName: String, onClose: () -> Unit) {
    val core = observeCore()
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SheetScaffold("Добавить вручную", onClose) {
        PulseTextField("Название", name, defaultName) { name = it }
        PulseTextField("Калории, ккал *", kcal, "0", KeyboardType.Number) { kcal = it.filter(Char::isDigit) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { PulseTextField("Белки, г", protein, "0", KeyboardType.Number) { protein = it.filter(Char::isDigit) } }
            Box(Modifier.weight(1f)) { PulseTextField("Жиры, г", fat, "0", KeyboardType.Number) { fat = it.filter(Char::isDigit) } }
            Box(Modifier.weight(1f)) { PulseTextField("Углев., г", carbs, "0", KeyboardType.Number) { carbs = it.filter(Char::isDigit) } }
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Сохранить") {
            val calories = kcal.toIntOrNull()
            if (calories == null || calories !in 1..4999) {
                error = "Укажите калорийность от 1 до 5000 ккал"
                return@GradientButton
            }
            core.nutrition.addMeal(
                name.trim().ifEmpty { defaultName.ifEmpty { "Приём пищи" } }, calories,
                protein.toIntOrNull() ?: 0, fat.toIntOrNull() ?: 0, carbs.toIntOrNull() ?: 0,
                source = MealSource.MANUAL, author = core.session.user.value?.nickname
            )
            onClose()
        }
    }
}

private sealed interface CapturePhase {
    data object Choosing : CapturePhase
    data object Analyzing : CapturePhase
    data class Result(val analysis: FoodAnalysis) : CapturePhase
    data class Failed(val message: String) : CapturePhase
    data object ManualEntry : CapturePhase
}

/** Downscales and JPEG-compresses a photo so it can be stored with the meal. */
private fun compress(bitmap: Bitmap, maxDimension: Int = 640): ByteArray {
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()) else bitmap
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
}

@Composable
private fun FoodCaptureSheet(onClose: () -> Unit, onManual: (String) -> Unit) {
    val core = observeCore()
    val store = core.nutrition
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<CapturePhase>(CapturePhase.Choosing) }
    var image by remember { mutableStateOf<ByteArray?>(null) }
    var editedName by remember { mutableStateOf("") }
    var editedKcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    val author = core.session.user.value?.nickname

    fun start(bytes: ByteArray) {
        image = bytes
        if (!store.canAnalyzePhotos) {
            phase = CapturePhase.ManualEntry
            return
        }
        phase = CapturePhase.Analyzing
        scope.launch {
            phase = try {
                val analysis = store.analyzer.analyze(bytes)
                editedName = analysis.dishName
                editedKcal = "${analysis.kcal}"
                CapturePhase.Result(analysis)
            } catch (error: FoodAnalysisException) {
                CapturePhase.Failed(FoodAnalysisException.MESSAGE)
            }
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> bitmap?.let { start(compress(it)) } }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val bitmap = runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
        if (bitmap == null) phase = CapturePhase.Failed("Не удалось загрузить изображение") else start(compress(bitmap))
    }

    SheetScaffold(if (store.canAnalyzePhotos) "Анализ блюда" else "Фото блюда", onClose) {
        val bitmap = remember(image) { image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
        if (bitmap != null) {
            Box(contentAlignment = Alignment.Center) {
                Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(Metrics.cardRadius)))
                if (phase == CapturePhase.Analyzing) {
                    Box(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(Metrics.cardRadius)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(Metrics.cardRadius)).background(Palette.tile).border(1.5.dp, Palette.stroke, RoundedCornerShape(Metrics.cardRadius)),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(sfIcon("camera.viewfinder"), null, tint = Palette.textSecondary, modifier = Modifier.size(44.dp))
                Text("Сфотографируй тарелку сверху при хорошем освещении", color = Palette.textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 30.dp, vertical = 12.dp))
            }
        }
        when (val current = phase) {
            CapturePhase.Choosing -> {
                GradientButton("Сделать фото", icon = sfIcon("camera")) { camera.launch(null) }
                SecondaryButton("Выбрать из галереи", icon = sfIcon("photo.on.rectangle")) {
                    gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SecondaryButton("Ввести вручную", icon = sfIcon("pencil"), tint = Palette.textSecondary) { onManual("") }
            }
            CapturePhase.Analyzing -> Row(Modifier.pulseCard(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = Palette.cyan, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Column {
                    Text("AI анализирует фото…", color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Определяем блюдо, калорийность и БЖУ", color = Palette.textSecondary, fontSize = 14.sp)
                }
            }
            is CapturePhase.Result -> {
                val analysis = current.analysis
                Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row {
                        Text("РЕЗУЛЬТАТ", color = Palette.cyan, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                        Text("уверенность ${(analysis.confidence * 100).toInt()}%", color = Palette.textSecondary, fontSize = 13.sp)
                    }
                    PulseTextField("Название", editedName) { editedName = it }
                    PulseTextField("Калории, ккал", editedKcal, keyboard = KeyboardType.Number) { editedKcal = it.filter(Char::isDigit) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Macro("Белки", analysis.protein, Palette.cyan, Modifier.weight(1f))
                        Macro("Жиры", analysis.fat, Palette.orange, Modifier.weight(1f))
                        Macro("Углеводы", analysis.carbs, Palette.purple, Modifier.weight(1f))
                    }
                }
                GradientButton("Сохранить", icon = sfIcon("checkmark")) {
                    store.addMeal(editedName.trim().ifEmpty { analysis.dishName }, (editedKcal.toIntOrNull() ?: analysis.kcal).coerceIn(0, 5000),
                        analysis.protein, analysis.fat, analysis.carbs, image, MealSource.PHOTO, author = author)
                    onClose()
                }
                SecondaryButton("Повторить анализ", icon = sfIcon("arrow.counterclockwise")) { image?.let(::start) }
            }
            is CapturePhase.Failed -> {
                ErrorBanner(current.message)
                GradientButton("Повторить анализ", icon = sfIcon("arrow.counterclockwise")) { image?.let(::start) }
                SecondaryButton("Ввести вручную", icon = sfIcon("pencil")) { onManual(editedName) }
                SecondaryButton("Выбрать другое фото", icon = sfIcon("photo.on.rectangle")) {
                    image = null
                    phase = CapturePhase.Choosing
                }
            }
            CapturePhase.ManualEntry -> {
                Text("Автоматическое распознавание калорий появится с сервером Pulse. Пока введи данные — фото сохранится в дневнике.", color = Palette.textSecondary, fontSize = 13.sp)
                PulseTextField("Блюдо *", editedName, "Курица с рисом") { editedName = it }
                PulseTextField("Калории, ккал *", editedKcal, "500", KeyboardType.Number) { editedKcal = it.filter(Char::isDigit) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { PulseTextField("Белки, г", protein, "0", KeyboardType.Number) { protein = it.filter(Char::isDigit) } }
                    Box(Modifier.weight(1f)) { PulseTextField("Жиры, г", fat, "0", KeyboardType.Number) { fat = it.filter(Char::isDigit) } }
                    Box(Modifier.weight(1f)) { PulseTextField("Углев., г", carbs, "0", KeyboardType.Number) { carbs = it.filter(Char::isDigit) } }
                }
                GradientButton("Сохранить", icon = sfIcon("checkmark"), enabled = editedName.isNotBlank() && editedKcal.toIntOrNull() != null) {
                    store.addMeal(editedName.trim(), (editedKcal.toIntOrNull() ?: 0).coerceIn(0, 5000), (protein.toIntOrNull() ?: 0).coerceIn(0, 500),
                        (fat.toIntOrNull() ?: 0).coerceIn(0, 500), (carbs.toIntOrNull() ?: 0).coerceIn(0, 1000), image, MealSource.PHOTO, author = author)
                    onClose()
                }
            }
        }
    }
}

@Composable
private fun Macro(title: String, grams: Int, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.background.copy(alpha = 0.4f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 13.sp)
        Text("$grams г", color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/** «Статистика питания»: average, 7/30-day dynamics, frequency, parts of the day, diary. */
@Composable
fun NutritionHistoryScreen() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.nutrition
    var period by remember { mutableIntStateOf(7) }
    val now = Clock.System.now()
    val series = store.dailyCalories(period, now)
    ScreenScaffold(header = { NavHeader("Питание", "Статистика и дневник", onBack = { router.pop() }) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("7 дней", period == 7) { period = 7 }
            ChipButton("30 дней", period == 30) { period = 30 }
        }
        if (series.isEmpty()) {
            EmptyStateView("fork.knife", "Нет данных", "Добавьте первое блюдо, чтобы начать отслеживание питания.")
            return@ScreenScaffold
        }
        val average = (StatisticsMath.mean(series.map { it.value }) ?: 0.0).toInt()
        val count = store.mealCount(period, now)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HistoryTile("Среднее в день", Formatters.grouped(average), "ккал", Modifier.weight(1f))
            HistoryTile("Приёмов пищи", "$count", "≈ ${Formatters.decimal(count.toDouble() / maxOf(series.size, 1))} / день", Modifier.weight(1f))
        }
        PulseSection("Динамика калорийности") {
            Column(Modifier.pulseCard(padding = 20.dp)) {
                PulseBarChart(
                    series.map { it.value },
                    labels = if (series.size <= 7) series.map { Formatters.weekdayShort(it.date) } else series.mapIndexed { i, day -> if (i % 5 == 0) Formatters.dayMonthShort(day.date) else "" },
                    calloutFor = { i ->
                        val delta = series[i].value.toInt() - store.goals.kcal
                        Callout(Formatters.dayMonthWeekday(series[i].date), listOf(
                            "${Formatters.grouped(series[i].value.toInt())} ккал" to Palette.cyan,
                            "${if (delta > 0) "+" else ""}${Formatters.grouped(delta)} к цели" to (if (delta > 0) Palette.orange else Palette.green)
                        ))
                    },
                    height = 180.dp,
                    goal = store.goals.kcal.toDouble()
                )
            }
        }
        PulseSection("По времени суток") {
            val from = now.startOfDay().addingDays(-period)
            val meals = store.meals.filter { it.date >= from }
            val buckets = listOf("Утро" to 5..10, "День" to 11..15, "Вечер" to 16..20, "Ночь" to 21..23)
            val counts = buckets.map { (name, hours) -> name to meals.count { it.date.localDateTime().hour in hours } }
            val maxCount = (counts.maxOfOrNull { it.second } ?: 1).toDouble()
            Column(Modifier.pulseCard(padding = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                counts.forEach { (name, value) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = Palette.textPrimary, fontSize = 16.sp, modifier = Modifier.width(60.dp))
                        GradientBar(if (maxCount > 0) value / maxCount else 0.0, Modifier.weight(1f), height = 10.dp)
                        Text("$value", color = Palette.textSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
                    }
                }
            }
        }
        PulseSection("Дневник") {
            val meals = store.meals.take(30)
            Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                meals.forEachIndexed { index, meal ->
                    if (index == 0 || !meals[index - 1].date.isSameDay(meal.date)) {
                        Text(Formatters.dayMonthWeekday(meal.date), color = Palette.cyan, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    }
                    MealRow(meal)
                    if (index < meals.size - 1) DividerLine()
                }
            }
        }
    }
}

@Composable
private fun HistoryTile(title: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier.pulseCard(16.dp, Metrics.tileRadius), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 15.sp)
        Text(value, color = Palette.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(unit, color = Palette.textTertiary, fontSize = 14.sp)
    }
}
