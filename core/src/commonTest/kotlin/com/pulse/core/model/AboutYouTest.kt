package com.pulse.core.model

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class AboutYouTest {
    @BeforeTest fun zone() = pinTestZone()

    private val now = at(2026, 10, 25)

    private fun blank(isNewPlayer: Boolean = true, nutrition: NutritionGoals = NutritionGoals()) =
        AboutYou.initial(SleepWindow(), FitnessGoals(), nutrition, screenLimitMinutes = 300, profile = null, isNewPlayer = isNewPlayer)

    private fun complete() = blank().copy(birthDate = LocalDate(2004, 3, 12)).withWeightText("74").copy(heightText = "182")

    @Test fun bmiFromWeightAndHeight() {
        assertEquals(22.3, BodyMath.bmi(74.0, 182))
    }

    @Test fun suggestionsFromWeight() {
        assertEquals(2450, AboutYou.suggestedKcal(74.0))
        assertEquals(2500, AboutYou.suggestedWaterMl(74.0))
        assertEquals(1200, AboutYou.suggestedKcal(20.0))
        assertEquals(5000, AboutYou.suggestedWaterMl(200.0))
    }

    @Test fun newPlayerKcalFollowsWeightUntilEdited() {
        val withWeight = blank().withWeightText("74")
        assertEquals(2450, withWeight.foodKcal)
        val edited = withWeight.withFoodKcal(2600).withWeightText("80")
        assertEquals(2600, edited.foodKcal)
        assertEquals(2750, edited.waterMl)
    }

    @Test fun existingPlayerKeepsCurrentGoals() {
        val form = blank(isNewPlayer = false, nutrition = NutritionGoals(kcal = 3000, waterMilliliters = 3000)).withWeightText("74")
        assertEquals(3000, form.foodKcal)
        assertEquals(3000, form.waterMl)
    }

    @Test fun missingFieldsAreErrors() {
        val errors = AboutYou.errors(blank(), now)
        assertEquals("Укажи дату рождения", errors[AboutYouField.BIRTH_DATE])
        assertEquals("Рост — от 120 до 230 см", errors[AboutYouField.HEIGHT])
        assertEquals("Вес — от 30 до 200 кг", errors[AboutYouField.WEIGHT])
        assertNull(AboutYou.profile(blank(), now))
    }

    @Test fun rangesAreChecked() {
        fun errors(form: AboutYouForm) = AboutYou.errors(form, now)
        assertTrue(AboutYouField.HEIGHT in errors(complete().copy(heightText = "119")))
        assertTrue(AboutYouField.HEIGHT in errors(complete().copy(heightText = "231")))
        assertTrue(AboutYouField.HEIGHT !in errors(complete().copy(heightText = "120")))
        assertTrue(AboutYouField.HEIGHT !in errors(complete().copy(heightText = "230")))
        assertTrue(AboutYouField.WEIGHT in errors(complete().withWeightText("29,9")))
        val decimal = complete().withWeightText("74,5")
        assertEquals(74.5, decimal.weightKg)
        assertTrue(AboutYouField.WEIGHT !in errors(decimal))
        assertTrue(AboutYouField.BIRTH_DATE in errors(complete().copy(birthDate = LocalDate(2017, 1, 1))))
        assertTrue(errors(complete()).isEmpty())
    }

    @Test fun wakeEqualToBedtimeIsAnError() {
        val same = complete().copy(bedtimeHour = 23, bedtimeMinute = 30, wakeHour = 23, wakeMinute = 30)
        assertEquals("Подъём не может совпадать с отбоем", AboutYou.errors(same, now)[AboutYouField.WAKE])
        val afterMidnight = complete().copy(bedtimeHour = 1, bedtimeMinute = 0, wakeHour = 9, wakeMinute = 0)
        assertTrue(AboutYouField.WAKE !in AboutYou.errors(afterMidnight, now))
    }

    @Test fun newerHealthWeightWins() {
        val profile = BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, now)
        assertEquals(76.0, BodyMath.currentWeight(profile, 76.0, now + 1.days))
        assertEquals(76.0, BodyMath.currentWeight(null, 76.0, now))
    }

    @Test fun olderHealthWeightLoses() {
        val profile = BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, now)
        assertEquals(74.0, BodyMath.currentWeight(profile, 76.0, now - 1.days))
        assertEquals(74.0, BodyMath.currentWeight(profile, null, null))
    }

    @Test fun metricsOrder() {
        val metrics = BodyMath.metrics(BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, now), null, null, 14.0, 60.0)
        assertEquals(listOf("Вес", "Рост", "ИМТ", "Жир", "Сух. масса"), metrics.map { it.title })
        assertEquals(listOf(74.0, 182.0, 22.3, 14.0, 60.0), metrics.map { it.value })
    }

    @Test fun profileFromCompleteForm() {
        val profile = AboutYou.profile(complete(), now)
        assertEquals(BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, now), profile)
    }

    @Test fun profileJsonShape() {
        val json = Json.encodeToString(BodyProfile.serializer(), BodyProfile(LocalDate(2004, 3, 12), 182, 74.0, Instant.parse("2026-10-25T10:00:00Z")))
        assertEquals("""{"birthDate":"2004-03-12","heightCm":182,"weightKg":74.0,"updatedAt":"2026-10-25T10:00:00Z"}""", json)
    }
}
