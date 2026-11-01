package com.pulse.android.ui.staff

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.ChipButton
import com.pulse.android.ui.components.EmptyStateView
import com.pulse.android.ui.components.PulseSection
import com.pulse.android.ui.components.PulseSheet
import com.pulse.android.ui.components.SheetScaffold
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.components.pressable
import com.pulse.android.ui.components.pulseCard
import com.pulse.android.ui.theme.Metrics
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.staff.StaffViews
import com.pulse.core.staff.TeamSnapshot
import com.pulse.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val FOOD_DAYS = 7
private const val MAX_MEALS = 30
private val CARD_PHOTO_SIZE = 200.dp
private val SHEET_PHOTO_SIZE = 640.dp

/** «Питание игроков» of a real team: the dish photos, calories and macros of the last week, as the players entered them. */
@Composable
fun RealTeamFood() = RealTeamScreenContent { RealFood(it.team) }

@Composable
private fun RealFood(team: TeamSnapshot) {
    val day = teamDay()
    val computed by produceState<List<StaffViews.FoodEntry>?>(null, team, day.today) {
        value = withContext(Dispatchers.Default) { StaffViews.teamFood(team, day.today, day.tz, FOOD_DAYS) }
    }
    var player by remember { mutableStateOf<String?>(null) }
    var onlyPhotos by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<StaffViews.FoodEntry?>(null) }
    val all = computed
    if (all == null) {
        TeamLoadingState()
        return
    }
    if (all.isEmpty()) {
        EmptyStateView("fork.knife", "Записей о питании пока нет", "Когда игроки начнут вносить еду в приложении, она появится здесь.")
        return
    }
    val entries = all.filter { player == null || it.playerId == player }.filter { !onlyPhotos || it.hasPhoto }
    val shown = entries.take(MAX_MEALS)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipButton("Все", player == null) { player = null }
            team.players.forEach { item -> ChipButton(item.nickname, player == item.id) { player = item.id } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChipButton("Только с фото", onlyPhotos) { onlyPhotos = !onlyPhotos }
            Text(
                "${Formatters.plural(entries.size, "запись", "записи", "записей")} · ${entries.count { it.hasPhoto }} с фото",
                color = Palette.textSecondary, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f)
            )
        }
        if (all.none { it.hasPhoto }) {
            Row(Modifier.pulseCard(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(sfIcon("camera"), null, tint = Palette.cyan, modifier = Modifier.size(22.dp))
                Text("Фото появятся здесь, когда игроки сфотографируют еду во вкладке «Питание». Ниже — записи без фото.", color = Palette.textSecondary, fontSize = 14.sp)
            }
        }
        if (entries.isEmpty()) EmptyStateView("camera", "Нет записей", "Под этот выбор записей за неделю нет")
        shown.groupBy { it.day }.forEach { (_, meals) ->
            PulseSection(Formatters.dayMonthWeekday(meals.first().at)) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    meals.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { entry -> Box(Modifier.weight(1f).pressable { opened = entry }) { FoodCard(entry) } }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (entries.size > MAX_MEALS) {
            Text("Показаны последние $MAX_MEALS записей", color = Palette.textTertiary, fontSize = 13.sp)
        }
    }
    opened?.let { entry -> PulseSheet({ opened = null }) { FoodEntrySheet(entry) { opened = null } } }
}

@Composable
private fun FoodCard(entry: StaffViews.FoodEntry) {
    val shape = RoundedCornerShape(Metrics.tileRadius)
    val name = entry.name ?: MEAL_NAME_FALLBACK
    Column(Modifier.fillMaxWidth().clip(shape).background(Palette.tile).border(1.dp, Palette.stroke, shape)) {
        Box(Modifier.fillMaxWidth().height(118.dp)) {
            MealPhoto(entry, name, with(LocalDensity.current) { CARD_PHOTO_SIZE.roundToPx() })
            Text(entry.nickname, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = Palette.textPrimary, autoSize = fit(14.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.kcal?.let { "${it.roundToInt()} ккал" } ?: NO_DATA} · ${Formatters.time(entry.at)}", color = Palette.textSecondary, fontSize = 12.sp)
        }
    }
}

/** The photo the player took (downloaded when this is composed), otherwise a neutral placeholder. */
@Composable
private fun MealPhoto(entry: StaffViews.FoodEntry, name: String, targetPx: Int) {
    val bitmap = rememberMealPhoto(entry, targetPx)
    if (bitmap != null) {
        Image(bitmap, "Фото: $name", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else {
        Column(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Palette.track, Palette.card))),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            Icon(sfIcon("fork.knife"), null, tint = Palette.textTertiary, modifier = Modifier.size(26.dp))
            Text("без фото", color = Palette.textTertiary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FoodEntrySheet(entry: StaffViews.FoodEntry, onClose: () -> Unit) {
    val name = entry.name ?: MEAL_NAME_FALLBACK
    SheetScaffold(entry.nickname, onClose) {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(Metrics.cardRadius))) {
            MealPhoto(entry, name, with(LocalDensity.current) { SHEET_PHOTO_SIZE.roundToPx() })
        }
        Column(Modifier.pulseCard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, color = Palette.textPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(listOfNotNull(Formatters.dayMonthTime(entry.at), entry.source?.title).joinToString(" · "), color = Palette.textSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    Triple("Ккал", entry.kcal, Palette.textPrimary), Triple("Белки", entry.protein, Palette.cyan),
                    Triple("Жиры", entry.fat, Palette.orange), Triple("Углев.", entry.carbs, Palette.purple)
                ).forEach { (title, value, tint) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(grams(value), color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(title, color = Palette.textSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
