package com.pulse.android.ui.diary

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.IconBadge
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSegmented
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.components.pulseTile
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.CSMap
import com.pulse.core.model.DiaryScope
import com.pulse.core.model.Lineup
import com.pulse.core.model.LineupSide
import com.pulse.core.model.LineupType
import com.pulse.core.model.LineupValidator
import com.pulse.core.model.LineupVariation
import com.pulse.core.model.LineupVisibility
import com.pulse.core.model.ThrowTechnique
import com.pulse.core.model.UserRole
import com.pulse.core.model.WebLinks
import com.pulse.core.store.LineupStore
import com.pulse.core.util.Formatters
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** «База знаний» for staff / «Дневник CS2» for players (tab root or a pushed route). */
@Composable
fun DiaryScreen(showsBack: Boolean) {
    val core = observeCore()
    val router = LocalRouter.current
    val isStaff = core.session.isStaff
    ScreenScaffold(spacing = 18.dp, header = {
        NavHeader(
            if (isStaff) "База знаний" else "Дневник CS2",
            if (isStaff) "Раскидки, скрипты и заметки команды" else "Раскидки, скрипты и заметки",
            onBack = if (showsBack) ({ router.pop() }) else null
        )
    }) { DiaryContent() }
}

/** Solo: personal diary; team player: personal + team entries; staff: the team's scripts and lineups. */
@Composable
private fun DiaryContent() {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.lineups
    val user = core.session.user.value
    val role = user?.role
    var scope by remember { mutableStateOf(DiaryScope.ALL) }
    var map by remember { mutableStateOf<CSMap?>(null) }
    var type by remember { mutableStateOf<LineupType?>(null) }
    var query by remember { mutableStateOf("") }
    var newEntry by remember { mutableStateOf<LineupType?>(null) }
    LaunchedEffect(role) { scope = DiaryScope.ALL }
    val entries = store.diary(user, scope, map, type, query)
    val all = store.diary(user)
    val isFiltered = map != null || type != null || query.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val maps = all.map { it.map }.toSet().size
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SummaryTile("${all.size}", "записей", Palette.cyan, Modifier.weight(1f))
            SummaryTile("${all.count { it.isFavorite }}", "в избранном", Palette.yellow, Modifier.weight(1f))
            SummaryTile("$maps", if (maps == 1) "карта" else "карт", Palette.purple, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAdd("Раскидка", Modifier.weight(1f)) { newEntry = LineupType.SMOKE }
            QuickAdd("Скрипт", Modifier.weight(1f)) { newEntry = LineupType.SCRIPT }
            if (role?.isStaff != true) QuickAdd("Заметка", Modifier.weight(1f)) { newEntry = LineupType.NOTE }
        }
        PulseSegmented(DiaryScope.options(role), scope, { if (it == DiaryScope.FAVORITES) "★ ${it.title}" else it.title }, height = 38.dp, fontSize = 13.sp) { scope = it }
        SearchField(query) { query = it }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ChipRow(map == null, { map = null }) {
                CSMap.entries.forEach { item -> ChipButton(item.title, map == item) { map = if (map == item) null else item } }
            }
            ChipRow(type == null, { type = null }) {
                LineupType.entries.forEach { item -> ChipButton(item.title, type == item) { type = if (type == item) null else item } }
            }
        }
        when {
            entries.isNotEmpty() -> PulseSection(
                when {
                    isFiltered -> "Найдено: ${entries.size}"
                    scope == DiaryScope.FAVORITES -> "Избранное · ${entries.size}"
                    else -> "Все записи"
                }
            ) {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    entries.forEach { lineup ->
                        Box(Modifier.pressable { router.open(Route.Lineup(lineup.id)) }) {
                            LineupRow(lineup, showsVisibility = role == UserRole.TEAM_PLAYER) { store.toggleFavorite(lineup.id) }
                        }
                    }
                }
            }
            scope == DiaryScope.FAVORITES && !isFiltered ->
                EmptyStateView("star", "В избранном пусто", "Нажми ☆ у раскидки, которую используешь чаще всего, — она появится здесь")
            else -> EmptyStateView("book.closed", if (isFiltered) "Ничего не найдено" else "Дневник пуст",
                if (isFiltered) "Измени фильтры или поиск" else "Добавь первую раскидку или заметку после матча")
        }
    }
    newEntry?.let { entryType ->
        PulseSheet({ newEntry = null }) { LineupFormSheet(null, map ?: CSMap.MIRAGE, entryType) { newEntry = null } }
    }
}

@Composable
private fun SummaryTile(value: String, title: String, tint: Color, modifier: Modifier) {
    Column(modifier.pulseTile(12.dp, 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, color = tint, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun QuickAdd(title: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(Gradients.accent).pressable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(sfIcon("plus"), "Добавить: $title", tint = Color.White, modifier = Modifier.size(15.dp))
        Text(" $title", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(Modifier.pulseTile(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(sfIcon("magnifyingglass"), null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Поиск по дневнику", color = Palette.textTertiary, fontSize = 16.sp)
            BasicTextField(query, onChange, singleLine = true, textStyle = TextStyle(color = Palette.textPrimary, fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.cyan), modifier = Modifier.fillMaxWidth())
        }
        if (query.isNotEmpty()) {
            Icon(sfIcon("xmark.circle.fill"), "Очистить", tint = Palette.textTertiary, modifier = Modifier.size(18.dp).pressable { onChange("") })
        }
    }
}

@Composable
private fun ChipRow(isAll: Boolean, onAll: () -> Unit, content: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChipButton("Все", isAll, onClick = onAll)
        content()
    }
}

/** Entry in the diary list; the star toggles «Избранное» right from the list. */
@Composable
fun LineupRow(lineup: Lineup, showsVisibility: Boolean = false, onToggleFavorite: (() -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    Row(Modifier.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(sfIcon(lineup.type.icon), lineup.type.tint.color, 48.dp, 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(lineup.title, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(lineup.subtitle, color = Palette.textSecondary, fontSize = 13.sp)
            Text(listOfNotNull(if (showsVisibility) lineup.visibility.title else null, "Автор: ${lineup.author}").joinToString(" · "),
                color = Palette.textTertiary, fontSize = 12.sp, maxLines = 1)
            if (lineup.variations.size > 1) {
                Text(Formatters.plural(lineup.variations.size, "вариация", "вариации", "вариаций"), color = Palette.cyan, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).background(Palette.cyan.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
        }
        if (lineup.videoURL != null) Icon(sfIcon("play.rectangle"), "Есть видео", tint = Palette.cyan, modifier = Modifier.size(20.dp))
        if (onToggleFavorite != null) {
            Box(
                Modifier.size(36.dp, 44.dp).pressable { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onToggleFavorite() },
                contentAlignment = Alignment.Center
            ) {
                Icon(sfIcon(if (lineup.isFavorite) "star.fill" else "star"), if (lineup.isFavorite) "Убрать из избранного" else "В избранное",
                    tint = if (lineup.isFavorite) Palette.yellow else Palette.textTertiary, modifier = Modifier.size(20.dp))
            }
        } else {
            if (lineup.isFavorite) Icon(sfIcon("star.fill"), null, tint = Palette.yellow, modifier = Modifier.size(14.dp))
            Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun LineupDetailScreen(id: Uuid) {
    val core = observeCore()
    val router = LocalRouter.current
    val uri = LocalUriHandler.current
    val store = core.lineups
    val lineup = store.lineup(id)
    val canEdit = lineup?.let { LineupStore.canEdit(it, core.session.user.value) } ?: false
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(header = {
        NavHeader(if (lineup?.type == LineupType.NOTE) "Заметка" else "Раскидка", onBack = { router.pop() }) {
            if (lineup != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderIconButton(if (lineup.isFavorite) "star.fill" else "star", if (lineup.isFavorite) "Убрать из избранного" else "В избранное") {
                    store.toggleFavorite(lineup.id)
                }
                if (canEdit) HeaderIconButton("pencil", "Редактировать") { edit = true }
            }
        }
    }) {
        if (lineup == null) {
            EmptyStateView("scope", "Запись не найдена", "Возможно, она была удалена")
            return@ScreenScaffold
        }
        Column(Modifier.pulseCard(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(lineup.map.title, Palette.cyan)
                if (lineup.type != LineupType.NOTE) Tag(lineup.side.title, if (lineup.side == LineupSide.T) Palette.orange else Palette.blue)
                Tag(lineup.type.title, lineup.type.tint.color)
                Tag(lineup.visibility.title, Palette.textSecondary)
            }
            Text(lineup.title, color = Palette.textPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (lineup.details.isNotEmpty()) Text(lineup.details, color = Palette.textSecondary, fontSize = 17.sp)
            Text("Автор: ${lineup.author} · ${Formatters.dayMonth(lineup.createdAt)}", color = Palette.textTertiary, fontSize = 13.sp)
        }
        // Links are validated on save; only web links are opened.
        lineup.videoURL?.takeIf { WebLinks.isValid(it) }?.let { link ->
            Row(Modifier.pressable { runCatching { uri.openUri(link) } }.pulseCard(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(sfIcon("play.rectangle.fill"), null, tint = Palette.cyan, modifier = Modifier.size(20.dp))
                Text("  Смотреть видео", color = Palette.cyan, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(sfIcon("arrow.up.right"), null, tint = Palette.cyan, modifier = Modifier.size(16.dp))
            }
        }
        if (lineup.supportsVariations) VariationsSection(lineup, canEdit)
        if (canEdit) SecondaryButton("Удалить", icon = sfIcon("trash"), tint = Palette.red) { confirmDelete = true }
    }
    if (edit && lineup != null) PulseSheet({ edit = false }) { LineupFormSheet(lineup, lineup.map, lineup.type) { edit = false } }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Palette.tile,
        title = { Text("Удалить запись?", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        confirmButton = {
            TextButton({
                confirmDelete = false
                store.delete(id)
                router.pop()
            }) { Text("Удалить", color = Palette.red, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена", color = Palette.textSecondary) } }
    )
}

@Composable
private fun Tag(text: String, tint: Color) {
    Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(tint.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
}

/** «Вариации»: the same grenade from other spots or with another throw; long-press a card to delete it. */
@Composable
private fun VariationsSection(lineup: Lineup, canEdit: Boolean) {
    val store = observeCore().lineups
    var add by remember { mutableStateOf(false) }
    PulseSection("Вариации · ${lineup.variations.size}", if (canEdit) "Добавить" else null, { add = true }) {
        if (lineup.variations.isEmpty()) {
            Row(Modifier.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(sfIcon("square.stack.3d.up"), null, tint = Palette.cyan, modifier = Modifier.size(22.dp))
                Text(
                    if (canEdit) "Добавь вариации: та же граната с другой позиции или другой техникой броска — jump-throw, run-throw, ПКМ."
                    else "У этой раскидки пока одна вариация — описание выше.",
                    color = Palette.textSecondary, fontSize = 14.sp
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                lineup.variations.forEachIndexed { index, variation ->
                    VariationCard(index + 1, variation, if (canEdit) ({ store.upsert(lineup.copy(variations = lineup.variations.filter { it.id != variation.id })) }) else null)
                }
                if (canEdit) Text("Удерживай карточку, чтобы удалить вариацию", color = Palette.textTertiary, fontSize = 12.sp)
            }
        }
    }
    if (add) PulseSheet({ add = false }) {
        AddVariationSheet({ add = false }) { store.upsert(lineup.copy(variations = lineup.variations + it)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VariationCard(index: Int, variation: LineupVariation, onDelete: (() -> Unit)?) {
    val uri = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).combinedClickable(
                remember { MutableInteractionSource() }, indication = null, onClick = {},
                onLongClick = onDelete?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true } }
            ).pulseCard(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Gradients.accentDiagonal), contentAlignment = Alignment.Center) {
                Text("$index", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(variation.position.ifEmpty { "Позиция не указана" }, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(variation.technique.title, color = Palette.cyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(Palette.cyan.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp))
                if (variation.aim.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(sfIcon("scope"), null, tint = Palette.textSecondary, modifier = Modifier.size(14.dp))
                    Text(variation.aim, color = Palette.textSecondary, fontSize = 14.sp)
                }
            }
            variation.videoURL?.takeIf { WebLinks.isValid(it) }?.let { link ->
                Icon(sfIcon("play.rectangle.fill"), "Видео вариации $index", tint = Palette.cyan,
                    modifier = Modifier.size(24.dp).pressable { runCatching { uri.openUri(link) } })
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Удалить вариацию", color = Palette.red) }, onClick = { menu = false; onDelete?.invoke() })
        }
    }
}

/** Inline editor used by the entry form and the «Новая вариация» sheet. */
@Composable
private fun VariationEditor(index: Int, variation: LineupVariation, onChange: (LineupVariation) -> Unit, onDelete: (() -> Unit)? = null) {
    Column(Modifier.pulseCard(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Вариация $index", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onDelete != null) Icon(sfIcon("trash"), "Удалить вариацию $index", tint = Palette.red, modifier = Modifier.size(20.dp).pressable(onClick = onDelete))
        }
        PulseTextField("Откуда бросать", variation.position, "T-спавн, угол у ящика") { onChange(variation.copy(position = it)) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Техника", color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThrowTechnique.entries.forEach { technique -> ChipButton(technique.title, variation.technique == technique) { onChange(variation.copy(technique = technique)) } }
            }
        }
        PulseTextField("Куда целиться", variation.aim, "Прицел на край антенны") { onChange(variation.copy(aim = it)) }
        PulseTextField("Видео (необязательно)", variation.videoURL ?: "", "https://…", KeyboardType.Uri) {
            onChange(variation.copy(videoURL = it.ifEmpty { null }))
        }
    }
}

@Composable
private fun AddVariationSheet(onClose: () -> Unit, onSave: (LineupVariation) -> Unit) {
    var variation by remember { mutableStateOf(LineupVariation(position = "", technique = ThrowTechnique.STANDARD, aim = "")) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetScaffold("Новая вариация", onClose) {
        VariationEditor(1, variation, { variation = it })
        error?.let { ErrorBanner(it) }
        GradientButton("Добавить", icon = sfIcon("plus")) {
            error = LineupValidator.validate(variation)
            if (error == null) {
                onSave(variation)
                onClose()
            }
        }
    }
}

private fun LineupType.formTitle(): String = when (this) {
    LineupType.NOTE -> "Новая заметка"
    LineupType.SCRIPT -> "Новый скрипт"
    else -> "Новая раскидка"
}

/** Create or edit an entry. Staff entries are always for the team; solo diaries are always personal. */
@Composable
private fun LineupFormSheet(existing: Lineup?, defaultMap: CSMap, defaultType: LineupType, onClose: () -> Unit) {
    val core = observeCore()
    val user = core.session.user.value
    val role = user?.role
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    var videoURL by remember { mutableStateOf(existing?.videoURL ?: "") }
    var map by remember { mutableStateOf(existing?.map ?: defaultMap) }
    var side by remember { mutableStateOf(existing?.side ?: LineupSide.T) }
    var type by remember { mutableStateOf(existing?.type ?: defaultType) }
    var sharedWithTeam by remember { mutableStateOf(existing?.let { it.visibility == LineupVisibility.TEAM } ?: (defaultType == LineupType.SCRIPT)) }
    var variations by remember { mutableStateOf(existing?.variations ?: emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetScaffold(if (existing == null) type.formTitle() else "Редактирование", onClose) {
        PulseTextField("Название *", title, if (type == LineupType.NOTE) "Разбор матча на Mirage" else "Смок в окно") { title = it }
        ChipPicker("Тип", LineupType.entries.filter { role != UserRole.STAFF || it != LineupType.NOTE }, type, { it.title }) { type = it }
        ChipPicker("Карта", CSMap.entries, map, { it.title }) { map = it }
        if (type != LineupType.NOTE) ChipPicker("Сторона", LineupSide.entries, side, { it.title }) { side = it }
        PulseTextField(if (type == LineupType.NOTE) "Что получилось, что улучшить" else "Описание / шаги скрипта", details,
            if (type == LineupType.NOTE) "Мысли после матча" else "Откуда и как бросать", singleLine = false) { details = it }
        PulseTextField("Ссылка на видео", videoURL, "https://…", KeyboardType.Uri) { videoURL = it }
        if (type != LineupType.NOTE && type != LineupType.SCRIPT) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Вариации", color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text("Та же граната с другой позиции или другой техникой броска", color = Palette.textTertiary, fontSize = 13.sp)
                variations.forEachIndexed { index, variation ->
                    VariationEditor(index + 1, variation, { updated -> variations = variations.mapIndexed { i, v -> if (i == index) updated else v } }) {
                        variations = variations.filterIndexed { i, _ -> i != index }
                    }
                }
                SecondaryButton("Добавить вариацию", icon = sfIcon("plus")) {
                    variations = variations + LineupVariation(position = "", technique = ThrowTechnique.STANDARD, aim = "")
                }
            }
        }
        if (role == UserRole.TEAM_PLAYER) {
            Row(Modifier.pulseCard(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Показать команде", color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Иначе запись видна только тебе", color = Palette.textSecondary, fontSize = 13.sp)
                }
                Switch(sharedWithTeam, { sharedWithTeam = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.blue, checkedThumbColor = Color.White))
            }
        }
        error?.let { ErrorBanner(it) }
        GradientButton("Сохранить", icon = sfIcon("checkmark")) {
            val filled = variations.filter { !it.isBlank }
            error = LineupValidator.validate(title, videoURL) ?: filled.firstNotNullOfOrNull { LineupValidator.validate(it) }
            if (error != null) return@GradientButton
            val visibility = when (role) {
                UserRole.STAFF -> LineupVisibility.TEAM
                UserRole.TEAM_PLAYER -> if (sharedWithTeam) LineupVisibility.TEAM else LineupVisibility.PERSONAL
                else -> LineupVisibility.PERSONAL
            }
            core.lineups.upsert(
                Lineup(
                    id = existing?.id ?: Uuid.random(),
                    title = title.trim(), map = map, side = side, type = type, details = details.trim(),
                    videoURL = videoURL.trim().ifEmpty { null },
                    author = existing?.author ?: user?.nickname ?: "—",
                    createdAt = existing?.createdAt ?: Clock.System.now(),
                    visibility = visibility,
                    isFavorite = existing?.isFavorite ?: false,
                    variations = if (type == LineupType.NOTE || type == LineupType.SCRIPT) emptyList() else filled
                )
            )
            onClose()
        }
    }
}

@Composable
private fun <T> ChipPicker(title: String, items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item -> ChipButton(label(item), item == selected) { onSelect(item) } }
        }
    }
}
