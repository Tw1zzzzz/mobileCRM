package com.pulse.core.store

import com.pulse.core.demo.DemoData
import com.pulse.core.model.BalanceCategory
import com.pulse.core.model.BalanceEntry
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.CognitiveOutcome
import com.pulse.core.model.CognitiveResult
import com.pulse.core.model.CognitiveTestKind
import com.pulse.core.model.DailyValue
import com.pulse.core.model.DaySlot
import com.pulse.core.model.EventTag
import com.pulse.core.model.FoodAnalysis
import com.pulse.core.model.GameTestPhase
import com.pulse.core.model.GameTestResult
import com.pulse.core.model.Meal
import com.pulse.core.model.MealSource
import com.pulse.core.model.MoodEntry
import com.pulse.core.model.NutritionGoals
import com.pulse.core.model.NutritionTotals
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.StatisticsMath
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.localDate
import com.pulse.core.util.startOfDay
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

// Nutrition

/** AI food recognizer contract. Production calls the backend (which holds the AI key) — never a key in the app. */
interface FoodAnalyzer {
    suspend fun analyze(image: ByteArray): FoodAnalysis
}

class FoodAnalysisException : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "Не удалось распознать блюдо. Попробуйте другое фото или введите данные вручную."
    }
}

/** Offline stand-in for the AI API (demo accounts only): a plausible dish after a short delay. */
class MockFoodAnalyzer(private val latency: Duration = 2.seconds) : FoodAnalyzer {
    private val catalog = listOf(
        FoodAnalysis("Курица с рисом и овощами", 520, 42, 14, 58, 0.86),
        FoodAnalysis("Овсянка с ягодами", 340, 11, 8, 56, 0.91),
        FoodAnalysis("Паста болоньезе", 640, 31, 22, 78, 0.82),
        FoodAnalysis("Салат Цезарь", 410, 24, 26, 18, 0.88),
        FoodAnalysis("Омлет с тостом", 380, 23, 22, 24, 0.84),
        FoodAnalysis("Боул с лососем", 590, 35, 21, 62, 0.87)
    )

    override suspend fun analyze(image: ByteArray): FoodAnalysis {
        delay(latency)
        if (image.size <= 1024) throw FoodAnalysisException()
        return catalog[image.size % catalog.size]
    }
}

/** Meals, water and nutrition goals («nutrition.json»). */
class NutritionStore(
    private val storage: JsonFileStorage<Saved>?,
    val analyzer: FoodAnalyzer = MockFoodAnalyzer(),
    seed: List<Meal> = DemoData.mealHistory()
) : PulseStore<NutritionStore.State>(State(meals = seed.sortedByDescending { it.date })) {

    @Serializable
    data class Saved(val meals: List<Meal>, val water: Map<String, Int> = emptyMap(), val goals: NutritionGoals = NutritionGoals())

    data class State(
        val meals: List<Meal> = emptyList(),
        val waterByDay: Map<String, Int> = emptyMap(),
        val goals: NutritionGoals = NutritionGoals(),
        val isDemo: Boolean = true
    )

    init {
        storage?.load()?.let { current = current.copy(meals = it.meals.sortedByDescending { m -> m.date }, waterByDay = it.water, goals = it.goals) }
    }

    val meals get() = current.meals
    val goals get() = current.goals

    /** Demo accounts keep the offline «analysis» stand-in; real accounts enter food manually until server-side AI exists. */
    val canAnalyzePhotos: Boolean get() = current.isDemo

    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = State(
            (saved?.meals ?: if (isDemo) DemoData.mealHistory() else emptyList()).sortedByDescending { it.date },
            saved?.water ?: emptyMap(), saved?.goals ?: NutritionGoals(), isDemo
        )
    }

    fun meals(day: Instant): List<Meal> = current.meals.filter { it.date.isSameDay(day) }

    fun totals(day: Instant): NutritionTotals = NutritionTotals.of(meals(day))

    fun water(day: Instant): Int = current.waterByDay[key(day)] ?: 0

    fun addWater(milliliters: Int = 250, day: Instant = Clock.System.now()) {
        val key = key(day)
        current = current.copy(waterByDay = current.waterByDay + (key to (current.waterByDay[key] ?: 0) + milliliters))
        persist()
    }

    fun addMeal(
        name: String, kcal: Int, protein: Int = 0, fat: Int = 0, carbs: Int = 0, photo: ByteArray? = null,
        source: MealSource, date: Instant = Clock.System.now(), author: String? = null
    ) {
        val meal = Meal(Uuid.random(), date, name, maxOf(kcal, 0), maxOf(protein, 0), maxOf(fat, 0), maxOf(carbs, 0),
            Meal.encodePhoto(photo), source, author)
        current = current.copy(meals = (listOf(meal) + current.meals).sortedByDescending { it.date })
        persist()
    }

    fun deleteMeal(id: Uuid) {
        current = current.copy(meals = current.meals.filter { it.id != id })
        persist()
    }

    /** Daily kcal totals for the last `days` days (oldest first), skipping days with no meals. */
    fun dailyCalories(lastDays: Int, now: Instant = Clock.System.now()): List<DailyValue> = (lastDays - 1 downTo 0).mapNotNull { offset ->
        val day = now.startOfDay().addingDays(-offset)
        val dayMeals = meals(day)
        if (dayMeals.isEmpty()) null else DailyValue(day, NutritionTotals.of(dayMeals).kcal.toDouble())
    }

    fun averageCalories(lastDays: Int, now: Instant = Clock.System.now()): Int? =
        StatisticsMath.mean(dailyCalories(lastDays, now).map { it.value })?.toInt()

    fun mealCount(lastDays: Int, now: Instant = Clock.System.now()): Int {
        val from = now.startOfDay().addingDays(-lastDays)
        return current.meals.count { it.date >= from }
    }

    fun updateGoals(goals: NutritionGoals) {
        current = current.copy(goals = goals)
        persist()
    }

    fun resetToDemo() {
        current = current.copy(meals = DemoData.mealHistory().sortedByDescending { it.date }, waterByDay = emptyMap(), goals = NutritionGoals())
        persist()
    }

    private fun persist() {
        storage?.save(Saved(current.meals, current.waterByDay, current.goals))
    }

    companion object {
        const val FILE = "nutrition.json"

        /** iOS key format: "2026-10-5" (no zero padding). */
        fun key(day: Instant): String {
            val date = day.localDate()
            return "${date.year}-${date.month.ordinal + 1}-${date.day}"
        }
    }
}

// Mood & balance

/** Mood & energy slots + balance wheel entries («wellbeing.json»). */
class WellbeingStore(
    private val storage: JsonFileStorage<Saved>?,
    moodSeed: List<MoodEntry> = DemoData.moodHistory(),
    balanceSeed: List<BalanceEntry> = DemoData.balanceHistory()
) : PulseStore<WellbeingStore.Saved>(Saved(moodSeed, balanceSeed)) {

    @Serializable
    data class Saved(val mood: List<MoodEntry> = emptyList(), val balance: List<BalanceEntry> = emptyList())

    init {
        storage?.load()?.let { current = it }
    }

    val moodEntries get() = current.mood
    val balanceEntries get() = current.balance

    /** Demo accounts start with demo history, real ones empty. */
    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = Saved(
            saved?.mood ?: if (isDemo) DemoData.moodHistory() else emptyList(),
            saved?.balance ?: if (isDemo) DemoData.balanceHistory() else emptyList()
        )
    }

    fun moodEntry(day: Instant, slot: DaySlot): MoodEntry? = current.mood.firstOrNull { it.day.isSameDay(day) && it.slot == slot }

    fun filledSlots(day: Instant): Set<DaySlot> = current.mood.filter { it.day.isSameDay(day) }.map { it.slot }.toSet()

    /** Saves (or replaces) the entry for a given day + slot. */
    fun saveMood(day: Instant, slot: DaySlot, mood: Int, energy: Int) {
        val entry = MoodEntry(Uuid.random(), day.startOfDay(), slot, mood, energy)
        current = current.copy(mood = current.mood.filterNot { it.day.isSameDay(day) && it.slot == slot } + entry)
        persist()
    }

    data class MoodAverage(val mood: Double, val energy: Double)

    fun averageMood(day: Instant): MoodAverage? {
        val entries = current.mood.filter { it.day.isSameDay(day) }
        val mood = StatisticsMath.mean(entries.map { it.mood.toDouble() }) ?: return null
        val energy = StatisticsMath.mean(entries.map { it.energy.toDouble() }) ?: return null
        return MoodAverage(mood, energy)
    }

    fun balanceEntry(day: Instant): BalanceEntry? = current.balance.firstOrNull { it.day.isSameDay(day) }

    val latestBalance: BalanceEntry? get() = current.balance.maxByOrNull { it.day }

    fun saveBalance(day: Instant, values: Map<BalanceCategory, Double>) {
        val entry = BalanceEntry(Uuid.random(), day.startOfDay(), values.mapKeys { it.key.key })
        current = current.copy(balance = current.balance.filterNot { it.day.isSameDay(day) } + entry)
        persist()
    }

    fun resetToDemo() {
        current = Saved(DemoData.moodHistory(), DemoData.balanceHistory())
        persist()
    }

    private fun persist() {
        storage?.save(current)
    }

    companion object {
        const val FILE = "wellbeing.json"
    }
}

// Cognitive & game tests

/** Cognitive battery results and pre/post-training game tests («cognitive.json»). */
class CognitiveStore(
    private val storage: JsonFileStorage<Saved>?,
    seed: List<CognitiveResult> = DemoData.cognitiveHistory(),
    gameSeed: List<GameTestResult> = DemoData.gameTestHistory()
) : PulseStore<CognitiveStore.Saved>(Saved(seed.sortedByDescending { it.date }, gameSeed.sortedByDescending { it.date })) {

    @Serializable
    data class Saved(val results: List<CognitiveResult> = emptyList(), val gameResults: List<GameTestResult> = emptyList())

    init {
        storage?.load()?.let { current = Saved(it.results.sortedByDescending { r -> r.date }, it.gameResults.sortedByDescending { r -> r.date }) }
    }

    val results get() = current.results
    val gameResults get() = current.gameResults

    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = Saved(
            (saved?.results ?: if (isDemo) DemoData.cognitiveHistory() else emptyList()).sortedByDescending { it.date },
            (saved?.gameResults ?: if (isDemo) DemoData.gameTestHistory() else emptyList()).sortedByDescending { it.date }
        )
    }

    fun record(kind: CognitiveTestKind, outcome: CognitiveOutcome, date: Instant = Clock.System.now()) {
        val result = CognitiveResult(Uuid.random(), kind, date, outcome.score, outcome.metric)
        current = current.copy(results = (listOf(result) + current.results).sortedByDescending { it.date })
        persist()
    }

    fun latest(kind: CognitiveTestKind): CognitiveResult? = current.results.firstOrNull { it.kind == kind }

    /** Average battery score for a given day (null if no tests that day). */
    fun averageScore(day: Instant): Double? = StatisticsMath.mean(current.results.filter { it.date.isSameDay(day) }.map { it.score.toDouble() })

    fun dailyScores(lastDays: Int, now: Instant = Clock.System.now()): List<DailyValue> = (lastDays - 1 downTo 0).mapNotNull { offset ->
        val day = now.startOfDay().addingDays(-offset)
        averageScore(day)?.let { DailyValue(day, it) }
    }

    fun completedToday(now: Instant = Clock.System.now()): Boolean =
        current.results.filter { it.date.isSameDay(now) }.map { it.kind }.toSet().size == CognitiveTestKind.entries.size

    fun updateGameResult(updated: GameTestResult) {
        current = current.copy(gameResults = current.gameResults.map { if (it.id == updated.id) updated else it })
        persist()
    }

    fun addGameResult(phase: GameTestPhase, focus: Int, fatigue: Int, readiness: Int, date: Instant = Clock.System.now()) {
        val result = GameTestResult(Uuid.random(), date, phase, focus, fatigue, readiness)
        current = current.copy(gameResults = (listOf(result) + current.gameResults).sortedByDescending { it.date })
        persist()
    }

    fun resetToDemo() {
        current = Saved(DemoData.cognitiveHistory().sortedByDescending { it.date }, DemoData.gameTestHistory().sortedByDescending { it.date })
        persist()
    }

    private fun persist() {
        storage?.save(current)
    }

    companion object {
        const val FILE = "cognitive.json"
    }
}

// Calendar

/** Calendar events («calendar.json» — a plain array, like iOS). */
class CalendarStore(
    private val storage: JsonFileStorage<List<CalendarEvent>>?,
    seed: List<CalendarEvent> = DemoData.calendarEvents()
) : PulseStore<List<CalendarEvent>>(seed.sortedBy { it.start }) {

    init {
        storage?.load()?.let { current = it.sortedBy { e -> e.start } }
    }

    val events get() = current

    fun reload(isDemo: Boolean) {
        current = (storage?.load() ?: if (isDemo) DemoData.calendarEvents() else emptyList()).sortedBy { it.start }
    }

    /** Events visible for a date under an optional tag filter (staff view). */
    fun events(day: Instant, tag: EventTag?): List<CalendarEvent> =
        current.filter { it.start.isSameDay(day) && (tag == null || it.tag == tag) }.sortedBy { it.start }

    fun hasEvents(day: Instant, tag: EventTag?): Boolean = current.any { it.start.isSameDay(day) && (tag == null || it.tag == tag) }

    fun event(id: Uuid): CalendarEvent? = current.firstOrNull { it.id == id }

    fun upsert(event: CalendarEvent) {
        current = (current.filter { it.id != event.id } + event).sortedBy { it.start }
        storage?.save(current)
    }

    fun delete(id: Uuid) {
        current = current.filter { it.id != id }
        storage?.save(current)
    }

    fun resetToDemo() {
        current = DemoData.calendarEvents().sortedBy { it.start }
        storage?.save(current)
    }

    val playerTags: List<EventTag> get() = DemoData.players.map { EventTag.Player(it.nickname) }

    companion object {
        const val FILE = "calendar.json"
    }
}
