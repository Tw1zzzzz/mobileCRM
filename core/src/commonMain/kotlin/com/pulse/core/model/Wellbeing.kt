@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.io.encoding.Base64
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Nutrition

@Serializable
data class Meal(
    val id: Uuid,
    val date: Instant,
    val name: String,
    val kcal: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
    /** JPEG as base64 — the way iOS `Data` is encoded in JSON. */
    @SerialName("photo") val photoBase64: String? = null,
    val source: MealSource,
    /** Nickname of the player who logged the meal (shown to staff); null for legacy entries. */
    val author: String? = null
) {
    val photo: ByteArray? get() = photoBase64?.let { Base64.decode(it) }

    companion object {
        fun encodePhoto(bytes: ByteArray?): String? = bytes?.let { Base64.encode(it) }
    }
}

@Serializable
enum class MealSource(val title: String) {
    @SerialName("photo") PHOTO("AI-анализ фото"),
    @SerialName("manual") MANUAL("Вручную"),
    @SerialName("quick") QUICK("Быстрое добавление")
}

@Serializable
data class NutritionGoals(
    val kcal: Int = 2400,
    val protein: Int = 140,
    val fat: Int = 80,
    val carbs: Int = 300,
    val waterMilliliters: Int = 2500
)

data class NutritionTotals(val kcal: Int = 0, val protein: Int = 0, val fat: Int = 0, val carbs: Int = 0) {
    companion object {
        fun of(meals: List<Meal>) = NutritionTotals(
            kcal = meals.sumOf { it.kcal },
            protein = meals.sumOf { it.protein },
            fat = meals.sumOf { it.fat },
            carbs = meals.sumOf { it.carbs }
        )
    }
}

/** Result returned by the food photo analyzer (AI). */
data class FoodAnalysis(
    val dishName: String,
    val kcal: Int,
    val protein: Int,
    val fat: Int,
    val carbs: Int,
    val confidence: Double
)

// Mood & energy

@Serializable
enum class DaySlot(val title: String, val icon: String) {
    @SerialName("morning") MORNING("Утро", "sunrise"),
    @SerialName("day") DAY("День", "sun.max"),
    @SerialName("evening") EVENING("Вечер", "moon.stars")
}

@Serializable
data class MoodEntry(val id: Uuid, val day: Instant, val slot: DaySlot, val mood: Int, val energy: Int)

// Balance wheel

@Serializable
enum class BalanceCategory(val key: String, val title: String, val shortTitle: String) {
    @SerialName("physical") PHYSICAL("physical", "Физическое здоровье", "Здоровье"),
    @SerialName("emotional") EMOTIONAL("emotional", "Эмоциональное состояние", "Эмоции"),
    @SerialName("intellectual") INTELLECTUAL("intellectual", "Интеллектуальное развитие", "Интеллект"),
    @SerialName("spiritual") SPIRITUAL("spiritual", "Духовное развитие", "Духовность"),
    @SerialName("professional") PROFESSIONAL("professional", "Профессиональная сфера", "Профессия"),
    @SerialName("social") SOCIAL("social", "Социальные отношения", "Отношения"),
    @SerialName("environment") ENVIRONMENT("environment", "Окружающая среда", "Среда"),
    @SerialName("financial") FINANCIAL("financial", "Финансовое благополучие", "Финансы")
}

@Serializable
data class BalanceEntry(val id: Uuid, val day: Instant, val values: Map<String, Double>) {
    fun value(category: BalanceCategory): Double = values[category.key] ?: 5.0

    val orderedValues: List<Double> get() = BalanceCategory.entries.map { value(it) }

    val average: Double get() = StatisticsMath.mean(orderedValues) ?: 0.0
}
