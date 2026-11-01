package com.pulse.android.ui.staff

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.android.ui.components.ValueUnitText
import com.pulse.android.ui.components.fit
import com.pulse.android.ui.observeCore
import com.pulse.android.ui.theme.Palette
import com.pulse.android.ui.theme.sfIcon
import com.pulse.core.model.MealSource
import com.pulse.core.staff.StaffViews
import com.pulse.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val MEAL_NAME_FALLBACK = "Приём пищи"
private val THUMBNAIL_SIZE = 52.dp

/** The photo at about [targetPx] on its short side: the bounds are read first and the decoder subsamples (30 rows must not hold 30 full-size bitmaps). */
private fun decodeThumbnail(bytes: ByteArray, targetPx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

/**
 * A meal of a team player: name, macros and time, and the photo when the player took one. The photo is downloaded
 * (`staffTeam.photo`) and decoded off the main thread only when the row is composed; a meal without a stored photo,
 * or one that cannot be had (offline, not allowed), keeps the plain icon.
 */
@Composable
fun StaffMealRow(entry: StaffViews.FoodEntry) {
    val core = observeCore()
    val thumbnailPx = with(LocalDensity.current) { THUMBNAIL_SIZE.roundToPx() }
    val photo by produceState<ImageBitmap?>(null, entry.playerId, entry.mealId, entry.hasPhoto) {
        value = if (!entry.hasPhoto) null else withContext(Dispatchers.Default) {
            core.staffTeam.photo(entry.playerId, entry.mealId)?.let { decodeThumbnail(it, thumbnailPx) }?.asImageBitmap()
        }
    }
    val name = entry.name ?: MEAL_NAME_FALLBACK
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        val bitmap = photo
        if (bitmap != null) {
            Image(bitmap, "Фото: $name", contentScale = ContentScale.Crop, modifier = Modifier.size(THUMBNAIL_SIZE).clip(RoundedCornerShape(14.dp)))
        } else {
            Box(Modifier.size(THUMBNAIL_SIZE).clip(RoundedCornerShape(16.dp)).background(Palette.track), contentAlignment = Alignment.Center) {
                Icon(sfIcon(if (entry.source == MealSource.QUICK) "cup.and.saucer" else "fork.knife"), null, tint = Palette.textSecondary, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, color = Palette.textPrimary, autoSize = fit(17.sp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Formatters.dayMonthShort(entry.at)}, ${Formatters.time(entry.at)}  ·  Б ${grams(entry.protein)} · Ж ${grams(entry.fat)} · У ${grams(entry.carbs)}",
                color = Palette.textSecondary, autoSize = fit(14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        val kcal = entry.kcal
        if (kcal != null) ValueUnitText("${kcal.roundToInt()}", "ккал", valueSize = 18.sp, unitSize = 12.sp)
        else Text(NO_DATA, color = Palette.textTertiary, fontSize = 13.sp)
    }
}

/** A macro in whole grams; «—» when the player did not give it. */
private fun grams(value: Double?): String = value?.let { "${it.roundToInt()}" } ?: "—"
