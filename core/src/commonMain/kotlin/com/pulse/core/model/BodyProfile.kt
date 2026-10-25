@file:UseSerializers(IsoInstantSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.localDate
import com.pulse.core.util.roundToIntSwift
import com.pulse.core.util.roundedSwift
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlinx.datetime.yearsUntil
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant

/** Questionnaire step «О тебе»: what Pulse knows about the player's body («body-profile.json»). */
@Serializable
data class BodyProfile(val birthDate: LocalDate, val heightCm: Int, val weightKg: Double, val updatedAt: Instant) {
    companion object {
        /** Demo players. The old timestamp lets any Health weight win, as a real measurement would. */
        val demo = BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, Instant.parse("2000-01-01T00:00:00Z"))
    }
}

object BodyMath {
    fun bmi(weightKg: Double, heightCm: Int): Double {
        val meters = heightCm / 100.0
        return (weightKg / (meters * meters) * 10).roundedSwift() / 10
    }

    /** The Health platform's weight when it was measured after the questionnaire, otherwise the questionnaire's. */
    fun currentWeight(profile: BodyProfile?, healthKg: Double?, healthAt: Instant?): Double? = when {
        healthKg == null -> profile?.weightKg
        profile == null -> healthKg
        healthAt != null && healthAt > profile.updatedAt -> healthKg
        else -> profile.weightKg
    }

    /** «Тело»: weight, height and BMI from the profile, body fat and lean mass from the Health platform. */
    fun metrics(profile: BodyProfile?, healthKg: Double?, healthAt: Instant?, fatPercent: Double?, leanKg: Double?): List<BodyMetric> {
        val weight = currentWeight(profile, healthKg, healthAt)
        return listOfNotNull(
            weight?.let { BodyMetric("Вес", it, "кг") },
            profile?.let { BodyMetric("Рост", it.heightCm.toDouble(), "см") },
            if (profile != null && weight != null) BodyMetric("ИМТ", bmi(weight, profile.heightCm), "") else null,
            fatPercent?.let { BodyMetric("Жир", it, "%") },
            leanKg?.let { BodyMetric("Сух. масса", it, "кг") }
        )
    }

    fun metrics(profile: BodyProfile?, readings: BodyReadings): List<BodyMetric> =
        metrics(profile, readings.weightKg, readings.weightAt, readings.bodyFatPercent, readings.leanMassKg)
}

enum class AboutYouField { BIRTH_DATE, HEIGHT, WEIGHT, WAKE }

/**
 * Answers of «О тебе» while they are being typed. Height and weight stay text until they parse; food kcal and water
 * follow the weight for a new player until the player sets them.
 */
data class AboutYouForm(
    val birthDate: LocalDate? = null,
    val heightText: String = "",
    val weightText: String = "",
    val bedtimeHour: Int,
    val bedtimeMinute: Int,
    val wakeHour: Int,
    val wakeMinute: Int,
    val activeMinutes: Int,
    val steps: Int,
    val activeKcal: Int,
    val foodKcal: Int,
    val waterMl: Int,
    val screenLimitHours: Int,
    val suggestsFromWeight: Boolean,
    val kcalEdited: Boolean = false,
    val waterEdited: Boolean = false
) {
    val heightCm: Int? get() = heightText.trim().toIntOrNull()
    val weightKg: Double? get() = weightText.trim().replace(',', '.').toDoubleOrNull()

    fun withWeightText(text: String): AboutYouForm {
        val updated = copy(weightText = text)
        val weight = updated.weightKg?.takeIf { it in AboutYou.WEIGHT_KG } ?: return updated
        if (!suggestsFromWeight) return updated
        return updated.copy(
            foodKcal = if (kcalEdited) foodKcal else AboutYou.suggestedKcal(weight),
            waterMl = if (waterEdited) waterMl else AboutYou.suggestedWaterMl(weight)
        )
    }

    fun withFoodKcal(value: Int) = copy(foodKcal = value, kcalEdited = true)
    fun withWater(ml: Int) = copy(waterMl = ml, waterEdited = true)
}

/**
 * Android's birth-date field keeps digits only («12032004») and shows them as «12.03.2004»; these map the cursor
 * between the two so it steps over the dots.
 */
object BirthDateMask {
    const val DIGITS = 8

    fun format(digits: String): String = when {
        digits.length <= 2 -> digits
        digits.length <= 4 -> "${digits.take(2)}.${digits.drop(2)}"
        else -> "${digits.take(2)}.${digits.substring(2, 4)}.${digits.drop(4)}"
    }

    fun toTransformed(offset: Int, length: Int): Int =
        offset + (if (offset > 2 && length > 2) 1 else 0) + (if (offset > 4 && length > 4) 1 else 0)

    fun toOriginal(offset: Int, length: Int): Int =
        (offset - (if (offset > 2 && length > 2) 1 else 0) - (if (offset > 5 && length > 4) 1 else 0)).coerceIn(0, length)
}

/** Rules of «О тебе», shared with iOS (`AboutYou` in `Pulse/Models/BodyProfile.swift`). */
object AboutYou {
    val AGE_YEARS = 10..80
    val HEIGHT_CM = 120..230
    val WEIGHT_KG = 30.0..200.0

    // Daily targets: the same ranges as the goal editors (Профиль → Цели, лимит экранного времени).
    val ACTIVE_MINUTES = 10..180
    val STEPS = 2_000..30_000
    const val STEPS_STEP = 500
    val ACTIVE_KCAL = 100..2_000
    val FOOD_KCAL = 1_200..4_500
    val WATER_ML = 1_000..5_000
    const val WATER_STEP_ML = 250
    val SCREEN_LIMIT_HOURS = 1..12

    private const val KCAL_PER_KG = 33.0
    private const val KCAL_STEP = 50
    private const val WATER_ML_PER_KG = 33.0

    fun suggestedKcal(weightKg: Double): Int =
        ((weightKg * KCAL_PER_KG / KCAL_STEP).roundToIntSwift() * KCAL_STEP).coerceIn(FOOD_KCAL)

    fun suggestedWaterMl(weightKg: Double): Int =
        ((weightKg * WATER_ML_PER_KG / WATER_STEP_ML).roundToIntSwift() * WATER_STEP_ML).coerceIn(WATER_ML)

    fun errors(form: AboutYouForm, now: Instant): Map<AboutYouField, String> = buildMap {
        val birthDate = form.birthDate
        when {
            birthDate == null -> put(AboutYouField.BIRTH_DATE, "Укажи дату рождения")
            birthDate.yearsUntil(now.localDate()) !in AGE_YEARS -> put(AboutYouField.BIRTH_DATE, "Возраст — от 10 до 80 лет")
        }
        if (form.heightCm?.let { it in HEIGHT_CM } != true) put(AboutYouField.HEIGHT, "Рост — от 120 до 230 см")
        if (form.weightKg?.let { it in WEIGHT_KG } != true) put(AboutYouField.WEIGHT, "Вес — от 30 до 200 кг")
        if (form.bedtimeHour == form.wakeHour && form.bedtimeMinute == form.wakeMinute) {
            put(AboutYouField.WAKE, "Подъём не может совпадать с отбоем")
        }
    }

    /**
     * Starting values: the current window and goals; body fields from an earlier profile, if any, with [weightKg] — the
     * weight «Тело» shows — in place of the typed one. Food kcal and water follow the weight only for a new player whose
     * nutrition goals are still the defaults: one who skipped the survey may have set them in Цели.
     */
    fun initial(
        window: SleepWindow, fitness: FitnessGoals, nutrition: NutritionGoals, screenLimitMinutes: Int,
        profile: BodyProfile?, isNewPlayer: Boolean, weightKg: Double? = profile?.weightKg
    ) = AboutYouForm(
        birthDate = profile?.birthDate,
        heightText = profile?.heightCm?.toString() ?: "",
        weightText = weightKg?.let(::weightText) ?: "",
        bedtimeHour = window.bedtimeHour, bedtimeMinute = window.bedtimeMinute,
        wakeHour = window.wakeHour, wakeMinute = window.wakeMinute,
        activeMinutes = fitness.activeMinutes.coerceIn(ACTIVE_MINUTES),
        steps = fitness.steps.coerceIn(STEPS),
        activeKcal = fitness.activeKcal.coerceIn(ACTIVE_KCAL),
        foodKcal = nutrition.kcal.coerceIn(FOOD_KCAL),
        waterMl = nutrition.waterMilliliters.coerceIn(WATER_ML),
        screenLimitHours = (screenLimitMinutes / 60).coerceIn(SCREEN_LIMIT_HOURS),
        suggestsFromWeight = isNewPlayer && nutrition.kcal == NutritionGoals().kcal && nutrition.waterMilliliters == NutritionGoals().waterMilliliters
    )

    /** The profile to save, or null while any answer is missing or out of range. */
    fun profile(form: AboutYouForm, now: Instant): BodyProfile? {
        if (errors(form, now).isNotEmpty()) return null
        return BodyProfile(form.birthDate ?: return null, form.heightCm ?: return null, form.weightKg ?: return null, now)
    }

    private val BIRTH_DATE = Regex("""(\d{1,2})[.\-/ ]?(\d{1,2})[.\-/ ]?(\d{4})""")

    /** «12.03.2004», «1.3.2004» or «12032004» (Android's text field); null when it is not a real date. */
    fun parseBirthDate(text: String): LocalDate? {
        val (day, month, year) = BIRTH_DATE.matchEntire(text.trim())?.destructured ?: return null
        return runCatching { LocalDate(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }

    fun birthDateText(date: LocalDate): String =
        "${date.day.toString().padStart(2, '0')}.${date.month.number.toString().padStart(2, '0')}.${date.year}"

    /** «74» or «74,5». */
    private fun weightText(kg: Double): String =
        if (kg % 1.0 == 0.0) kg.toInt().toString() else kg.toString().replace('.', ',')
}
