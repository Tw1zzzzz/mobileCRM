@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Fitness

@Serializable
enum class WorkoutKind(val title: String, val icon: String, val tint: Tint) {
    @SerialName("strength") STRENGTH("Силовая", "dumbbell", Tint.GREEN),
    @SerialName("hiit") HIIT("HIIT", "bolt", Tint.ORANGE),
    @SerialName("cardio") CARDIO("Кардио", "figure.run", Tint.CYAN),
    @SerialName("yoga") YOGA("Йога", "figure.mind.and.body", Tint.PURPLE),
    @SerialName("stretch") STRETCH("Растяжка", "figure.flexibility", Tint.PINK)
}

@Serializable
data class Workout(
    val id: Uuid,
    val kind: WorkoutKind,
    val detail: String,
    val date: Instant,
    val minutes: Int,
    val kcal: Int,
    val averageHeartRate: Int,
    /** Perceived effort 1–10 after the workout; null = not rated (estimated from pulse or type). */
    val rpe: Int? = null
) {
    val title: String get() = if (detail.isEmpty()) kind.title else "${kind.title} · $detail"

    fun rated(value: Int): Workout = copy(rpe = value)
}

data class HeartRateZone(val name: String, val range: String, val percent: Int, val tint: Tint)

data class BodyMetric(
    val title: String,
    val value: Double,
    val unit: String,
    /** Change since the previous measurement; null when unknown (Health latest value). */
    val delta: Double? = null
)

data class DailyValue(val date: Instant, val value: Double)

// Screen time

enum class AppCategory(val title: String, val tint: Tint) {
    SOCIAL("Соцсети", Tint.CYAN),
    GAMES("Игры", Tint.PINK),
    BROWSER("Браузер", Tint.BLUE),
    WORK("Работа", Tint.PURPLE),
    VIDEO("Видео", Tint.RED),
    HEALTH("Здоровье", Tint.GREEN),
    OTHER("Прочее", Tint.REST_ZONE)
}

data class AppUsage(val name: String, val category: AppCategory, val minutes: Int, val icon: String, val tint: Tint)

data class ScreenTimeDay(val date: Instant, val minutes: Int, val categories: Map<AppCategory, Int>) {
    val topCategory: AppCategory? get() = categories.maxByOrNull { it.value }?.key
}
