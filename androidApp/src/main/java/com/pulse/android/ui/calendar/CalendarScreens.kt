package com.pulse.android.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import com.pulse.android.platform.ReminderScheduler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.LocalRouter
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.DividerLine
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.ErrorBanner
import com.pulse.android.ui.components.GradientButton
import com.pulse.android.ui.components.HeaderIconButton
import com.pulse.android.ui.components.NavHeader
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.PulseTextField
import com.pulse.android.ui.components.RussianLocale
import com.pulse.android.ui.components.ScreenScaffold
import com.pulse.android.ui.components.SecondaryButton
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.StatusPill
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.main.Route
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Gradients
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.color
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.model.EventValidator
import com.pulse.core.model.WebLinks
import com.pulse.core.util.Formatters
import com.pulse.core.util.PulseCalendar
import com.pulse.core.util.addingMinutes
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDate
import com.pulse.core.util.localDateTime
import com.pulse.core.util.setting
import com.pulse.core.util.startInstant
import com.pulse.core.util.startOfDay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Month grid, events of the selected day, «Создать событие», tag filter for staff. */
@Composable
fun CalendarScreen(startsWithCreate: Boolean) {
    val core = observeCore()
    val router = LocalRouter.current
    val store = core.calendar
    val isStaff = core.session.isStaff
    val nickname = core.session.user.value?.nickname
    val today = Clock.System.now().startOfDay()
    var month by remember { mutableStateOf(today.localDate().let { LocalDate(it.year, it.month, 1) }) }
    var selectedDay by remember { mutableStateOf(today) }
    var tag by remember { mutableStateOf<EventTag?>(if (isStaff) EventTag.Team else null) }
    var create by remember { mutableStateOf(startsWithCreate) }
    // Players see their personal + team events; staff filter by tag.
    val effectiveTag = if (isStaff) tag else null
    // Staff see everything for the selected tag; players never see other players' personal schedules.
    fun isVisible(event: CalendarEvent): Boolean = isStaff || (event.tag as? EventTag.Player)?.name?.let { it == nickname } ?: true

    ScreenScaffold(header = {
        NavHeader("Календарь", onBack = { router.pop() }) { HeaderIconButton("plus", "Создать событие") { create = true } }
    }) {
        if (isStaff) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(EventTag.Personal, EventTag.Team) + store.playerTags).forEach { item -> ChipButton(item.title, tag == item) { tag = item } }
            }
        }
        Box(Modifier.pulseCard(16.dp)) {
            MonthGrid(month, selectedDay, { month = it }, { selectedDay = it }) { day -> store.events(day, effectiveTag).any(::isVisible) }
        }
        PulseSection(Formatters.dayMonthWeekday(selectedDay)) {
            val events = store.events(selectedDay, effectiveTag).filter(::isVisible)
            if (events.isEmpty()) {
                EmptyStateView("calendar", "Событий нет", "На выбранную дату ничего не запланировано")
            } else {
                Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
                    events.forEachIndexed { index, event ->
                        Box(Modifier.pressable { router.open(Route.Event(event.id)) }) { EventRow(event) }
                        if (index < events.size - 1) DividerLine()
                    }
                }
            }
        }
        GradientButton("Создать событие", icon = sfIcon("plus")) { create = true }
    }
    if (create) PulseSheet({ create = false }) {
        EventFormSheet(null, selectedDay, effectiveTag ?: if (isStaff) EventTag.Team else EventTag.Personal, { create = false }) { saved ->
            selectedDay = saved.start.startOfDay()
            month = saved.start.localDate().let { LocalDate(it.year, it.month, 1) }
            if (isStaff) tag = saved.tag
        }
    }
}

@Composable
fun EventRow(event: CalendarEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(4.dp, 44.dp).clip(RoundedCornerShape(2.dp)).background(event.tag.tint.color))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(event.title, color = Palette.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("${Formatters.time(event.start)} – ${Formatters.time(event.end)}  ·  ${event.tag.title}", color = Palette.textSecondary, fontSize = 14.sp)
        }
        Icon(sfIcon("chevron.right"), null, tint = Palette.textTertiary, modifier = Modifier.size(14.dp))
    }
}

private val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

/** Month with Monday-first weeks; swipe left/right or the arrows change the month. */
@Composable
private fun MonthGrid(
    month: LocalDate, selectedDay: Instant, onMonth: (LocalDate) -> Unit, onSelect: (Instant) -> Unit, hasEvents: (Instant) -> Boolean
) {
    val today = Clock.System.now()
    val next = month.plus(DatePeriod(months = 1))
    val count = (next.toEpochDays() - month.toEpochDays()).toInt()
    val leading = month.dayOfWeek.ordinal // Monday = 0
    val cells: List<LocalDate?> = List(leading) { null } + (0 until count).map { month.plus(DatePeriod(days = it)) }
    Column(
        Modifier.pointerInput(month) {
            var total = 0f
            detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                if (total < -120) onMonth(next) else if (total > 120) onMonth(month.plus(DatePeriod(months = -1)))
            }) { _, amount -> total += amount }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Formatters.monthYear(month.startInstant()), color = Palette.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            MonthButton("chevron.left", "Предыдущий месяц") { onMonth(month.plus(DatePeriod(months = -1))) }
            Box(Modifier.width(8.dp))
            MonthButton("chevron.right", "Следующий месяц") { onMonth(next) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                weekdays.forEach { Text(it, color = Palette.textTertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
            cells.chunked(7).forEach { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { date ->
                        if (date == null) Box(Modifier.weight(1f).height(44.dp)) else {
                            val day = date.startInstant()
                            DayCell(date.day, day.isSameDay(selectedDay), day.isSameDay(today), hasEvents(day), Modifier.weight(1f)) { onSelect(day) }
                        }
                    }
                    repeat(7 - week.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun DayCell(number: Int, isSelected: Boolean, isToday: Boolean, hasEvents: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.height(44.dp).clip(shape)
            .then(if (isSelected) Modifier.background(Gradients.accentDiagonal) else Modifier)
            .then(if (isToday && !isSelected) Modifier.border(1.dp, Palette.cyan.copy(alpha = 0.6f), shape) else Modifier)
            .pressable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Text("$number", color = if (isSelected) Color.White else if (isToday) Palette.cyan else Palette.textPrimary, fontSize = 16.sp,
            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal)
        Box(Modifier.padding(top = 3.dp).size(5.dp).clip(CircleShape).background(if (hasEvents) (if (isSelected) Color.White else Palette.cyan) else Color.Transparent))
    }
}

@Composable
private fun MonthButton(icon: String, description: String, onClick: () -> Unit) {
    Box(Modifier.size(Metrics.minTouchTarget).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.track), contentAlignment = Alignment.Center) {
            Icon(sfIcon(icon), description, tint = Palette.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Event card: title, tag, description, start and end, links. */
@Composable
fun EventDetailScreen(id: Uuid) {
    val core = observeCore()
    val router = LocalRouter.current
    val uri = LocalUriHandler.current
    val store = core.calendar
    val isStaff = core.session.isStaff
    val event = store.event(id)
    val context = LocalContext.current
    var edit by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    ScreenScaffold(header = {
        NavHeader("Событие", onBack = { router.pop() }) { if (event != null) HeaderIconButton("pencil", "Редактировать") { edit = true } }
    }) {
        if (event == null) {
            EmptyStateView("calendar.badge.exclamationmark", "Событие не найдено", "Возможно, оно было удалено")
            return@ScreenScaffold
        }
        notice?.let { StatusPill(it, Palette.cyan) }
        Column(Modifier.pulseCard(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (isStaff) {
                Text(event.tag.title, color = event.tag.tint.color, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).background(event.tag.tint.color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp))
            }
            Text(event.title, color = Palette.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (event.details.isNotEmpty()) Text(event.details, color = Palette.textSecondary, fontSize = 17.sp)
        }
        Column(Modifier.pulseCard(padding = 0.dp).padding(horizontal = 16.dp)) {
            TimeRow("Начало", event.start, "play.circle")
            DividerLine()
            TimeRow("Окончание", event.end, "stop.circle")
        }
        // Links were validated on save; only web links are opened.
        val links = event.links.filter { WebLinks.isValid(it) }
        if (links.isNotEmpty()) PulseSection("Ссылки") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                links.forEach { link ->
                    Row(Modifier.pressable { runCatching { uri.openUri(link) } }.pulseCard(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(sfIcon("link"), null, tint = Palette.cyan, modifier = Modifier.size(18.dp))
                        Text("  $link", color = Palette.cyan, autoSize = fit(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(sfIcon("arrow.up.right"), null, tint = Palette.cyan, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        SecondaryButton("Удалить событие", icon = sfIcon("trash"), tint = Palette.red) { confirmDelete = true }
    }
    if (edit && event != null) PulseSheet({ edit = false }) {
        EventFormSheet(event, event.start, event.tag, { edit = false }) { saved ->
            if (isStaff && saved.tag != EventTag.Personal) notice = "Игрокам отправлено уведомление об изменении"
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        containerColor = Palette.tile,
        title = { Text("Удалить событие?", color = Palette.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
        confirmButton = {
            TextButton({
                confirmDelete = false
                store.delete(id)
                ReminderScheduler.eventDeleted(context, id)
                router.pop()
            }) { Text("Удалить", color = Palette.red, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Отмена", color = Palette.textSecondary) } }
    )
}

@Composable
private fun TimeRow(title: String, date: Instant, icon: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(sfIcon(icon), null, tint = Palette.cyan, modifier = Modifier.size(22.dp))
        Text(title, color = Palette.textSecondary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(Formatters.dayMonthTime(date), color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Create or edit an event with the validation from the spec. */
@Composable
private fun EventFormSheet(existing: CalendarEvent?, day: Instant, defaultTag: EventTag, onClose: () -> Unit, onSaved: (CalendarEvent) -> Unit) {
    val core = observeCore()
    val context = LocalContext.current
    val store = core.calendar
    val isStaff = core.session.isStaff
    val initialStart = remember {
        existing?.start ?: run {
            val now = Clock.System.now()
            val base = if (day.isSameDay(now)) now.addingMinutes(60) else day.setting(12, 0)
            base.setting(base.localDateTime().hour, 0)
        }
    }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var details by remember { mutableStateOf(existing?.details ?: "") }
    var start by remember { mutableStateOf(initialStart) }
    var end by remember { mutableStateOf(existing?.end ?: initialStart.addingMinutes(60)) }
    var linksText by remember { mutableStateOf(existing?.links?.joinToString("\n") ?: "") }
    var tag by remember { mutableStateOf(existing?.tag ?: if (isStaff) defaultTag else EventTag.Personal) }
    var error by remember { mutableStateOf<String?>(null) }
    SheetScaffold(if (existing != null) "Редактирование" else "Новое событие", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PulseTextField("Название *", title, "Тренировка") { title = it }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EventValidator.templates.forEach { template -> ChipButton(template, title == template) { title = template } }
            }
        }
        if (isStaff) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Тэг", color = Palette.textSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(EventTag.Personal, EventTag.Team) + store.playerTags).forEach { item -> ChipButton(item.title, tag == item) { tag = item } }
            }
        }
        PulseTextField("Описание", details, "Что планируется", singleLine = false) { details = it }
        Column(Modifier.pulseCard(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DateTimeRow("Начало", start) { newStart ->
                // Keep the duration when the start moves past the end.
                if (end <= newStart) end = newStart + maxOf(end - start, 1.hours)
                start = newStart
            }
            DividerLine()
            DateTimeRow("Окончание", end) { end = it }
        }
        PulseTextField("Ссылки (через запятую или с новой строки)", linksText, "https://…", KeyboardType.Uri, singleLine = false) { linksText = it }
        error?.let { ErrorBanner(it) }
        GradientButton(if (isStaff) "Сохранить событие" else "Сохранить", icon = sfIcon("checkmark")) {
            val links = EventValidator.parseLinks(linksText)
            EventValidator.validate(title, start, end, links)?.let {
                error = it.message
                return@GradientButton
            }
            val event = existing?.copy(title = title.trim(), details = details.trim(), start = start, end = end, links = links, tag = tag)
                ?: CalendarEvent(Uuid.random(), title.trim(), details.trim(), start, end, links, tag, core.session.user.value?.id)
            store.upsert(event)
            ReminderScheduler.eventChanged(context, core)
            onSaved(event)
            onClose()
        }
    }
}

/** «20 окт.» and «18:00» pills that open the system date and time pickers (in Russian, 24-hour). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(title: String, value: Instant, onChange: (Instant) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val local = value.localDateTime()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Palette.textPrimary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Pill(Formatters.dayMonthShort(value), "$title: дата") { pickDate = true }
        Pill(Formatters.time(value), "$title: время") { pickTime = true }
    }
    if (pickDate) {
        // The picker works in UTC midnights: hand it the local calendar date and read one back.
        val state = remember {
            DatePickerState(
                locale = Locale.forLanguageTag("ru"),
                initialSelectedDateMillis = local.date.toEpochDays() * 86_400_000L,
                initialDisplayMode = DisplayMode.Picker
            )
        }
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { millis ->
                        val date = LocalDate.fromEpochDays((millis / 86_400_000L).toInt())
                        onChange(LocalDateTime(date, local.time).toInstant(PulseCalendar.timeZone))
                    }
                    pickDate = false
                }) { Text("Готово", color = Palette.cyan) }
            },
            dismissButton = { TextButton({ pickDate = false }) { Text("Отмена", color = Palette.textSecondary) } },
            colors = DatePickerDefaults.colors(containerColor = Palette.tile)
        ) {
            // The dialog is its own window with the phone's locale — set Russian inside it.
            RussianLocale { DatePicker(state, title = null, showModeToggle = false, colors = DatePickerDefaults.colors(
                containerColor = Palette.tile, dayContentColor = Palette.textPrimary, selectedDayContainerColor = Palette.blue,
                selectedDayContentColor = Color.White, todayDateBorderColor = Palette.cyan, todayContentColor = Palette.cyan,
                weekdayContentColor = Palette.textSecondary, subheadContentColor = Palette.textSecondary, headlineContentColor = Palette.textPrimary,
                navigationContentColor = Palette.textPrimary, yearContentColor = Palette.textPrimary, currentYearContentColor = Palette.cyan,
                selectedYearContainerColor = Palette.blue
            )) }
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            containerColor = Palette.tile,
            text = {
                TimePicker(state, colors = TimePickerDefaults.colors(
                    clockDialColor = Palette.track, selectorColor = Palette.blue, clockDialSelectedContentColor = Color.White,
                    clockDialUnselectedContentColor = Palette.textPrimary, timeSelectorSelectedContainerColor = Palette.blue,
                    timeSelectorUnselectedContainerColor = Palette.track, timeSelectorSelectedContentColor = Color.White,
                    timeSelectorUnselectedContentColor = Palette.textPrimary
                ))
            },
            confirmButton = {
                TextButton({
                    onChange(LocalDateTime(local.date, LocalTime(state.hour, state.minute)).toInstant(PulseCalendar.timeZone))
                    pickTime = false
                }) { Text("Готово", color = Palette.cyan) }
            },
            dismissButton = { TextButton({ pickTime = false }) { Text("Отмена", color = Palette.textSecondary) } }
        )
    }
}

@Composable
private fun Pill(text: String, description: String, onClick: () -> Unit) {
    Text(text, color = Palette.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.track).pressable(onClick = onClick)
            .semantics { contentDescription = description }.padding(horizontal = 12.dp, vertical = 8.dp))
}
