package com.pulse.core.store

import com.pulse.core.demo.DemoData
import com.pulse.core.model.BodyMath
import com.pulse.core.model.BodyMetric
import com.pulse.core.model.BodyProfile
import com.pulse.core.model.BodyReadings
import com.pulse.core.model.DailyValue
import com.pulse.core.model.FitnessGoals
import com.pulse.core.model.FitnessMath
import com.pulse.core.model.HealthSnapshot
import com.pulse.core.model.HeartRateZone
import com.pulse.core.model.TrainingLoadMath
import com.pulse.core.model.Workout
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.UuidSerializer
import com.pulse.core.util.addingDays
import com.pulse.core.util.isSameDay
import com.pulse.core.util.startOfDay
import kotlinx.serialization.KSerializer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Read-only access to the phone's health store (Health Connect on Android, HealthKit on iOS). */
interface HealthDataProvider {
    /** Shown in messages: «Health Connect», «Apple Health». */
    val name: String
    val isAvailable: Boolean
    suspend fun requestAuthorization()
    /** [body] («О тебе») feeds age, height and weight into the snapshot. */
    suspend fun snapshot(now: Instant, body: BodyProfile?): HealthSnapshot
}

/** Swift encodes `[UUID: Int]` as a flat array: ["UUID", 7, "UUID", 9]. */
object UuidIntMapSerializer : KSerializer<Map<Uuid, Int>> {
    override val descriptor: SerialDescriptor = JsonArray.serializer().descriptor

    override fun serialize(encoder: Encoder, value: Map<Uuid, Int>) {
        (encoder as JsonEncoder).encodeJsonElement(
            JsonArray(value.flatMap { (key, rating) -> listOf(JsonPrimitive(key.toString().uppercase()), JsonPrimitive(rating)) })
        )
    }

    override fun deserialize(decoder: Decoder): Map<Uuid, Int> {
        val items = (decoder as JsonDecoder).decodeJsonElement().jsonArray
        return items.chunked(2).filter { it.size == 2 }.associate { (key, value) ->
            Uuid.parse(key.jsonPrimitive.content.lowercase()) to value.jsonPrimitive.int
        }
    }
}

/** Workouts, activity goals, training load and Health data («fitness-state.json»). */
class FitnessStore(
    private val storage: JsonFileStorage<Saved>?,
    private val healthService: HealthDataProvider?,
    isDemo: Boolean = true,
    seed: List<Workout> = DemoData.workouts()
) : PulseStore<FitnessStore.State>(State(isDemo = isDemo)) {

    @Serializable
    data class Saved(
        val workouts: List<Workout>,
        val goals: FitnessGoals = FitnessGoals(),
        /** Effort ratings for Health workouts (their data can't be edited, so ratings live here). */
        @Serializable(with = UuidIntMapSerializer::class) val ratings: Map<Uuid, Int> = emptyMap(),
        val healthConnected: Boolean = false
    )

    data class State(
        val workouts: List<Workout> = emptyList(),
        val goals: FitnessGoals = FitnessGoals(),
        val healthRatings: Map<Uuid, Int> = emptyMap(),
        /** Real data from the health store; null until connected and fetched. */
        val health: HealthSnapshot? = null,
        val isSyncing: Boolean = false,
        val healthError: String? = null,
        val isHealthConnected: Boolean = false,
        /** Demo accounts show a demo baseline without Health; real accounts show only their own numbers. */
        val isDemo: Boolean = true
    )

    /** Lets other stores (sleep, recovery) use the same Health pass; null = disconnected. */
    var onHealthSnapshot: ((HealthSnapshot?) -> Unit)? = null

    init {
        val saved = storage?.load()
        current = current.copy(
            workouts = (saved?.workouts ?: if (isDemo) seed else emptyList()).sortedBy { it.date },
            goals = saved?.goals ?: FitnessGoals(),
            healthRatings = saved?.ratings ?: emptyMap(),
            isHealthConnected = healthService != null && (saved?.healthConnected ?: false)
        )
    }

    val goals get() = current.goals

    /** «О тебе» (set by PulseCore): age for heart-rate estimates, height and weight for «Тело». */
    var bodyProfile: BodyProfile? = null
    val workouts get() = current.workouts
    val isHealthAvailable: Boolean get() = healthService?.isAvailable ?: false
    val healthName: String get() = healthService?.name ?: "Health Connect"

    fun reload(isDemo: Boolean) {
        val saved = storage?.load()
        current = State(
            workouts = (saved?.workouts ?: if (isDemo) DemoData.workouts() else emptyList()).sortedBy { it.date },
            goals = saved?.goals ?: FitnessGoals(),
            healthRatings = saved?.ratings ?: emptyMap(),
            isHealthConnected = healthService != null && (saved?.healthConnected ?: false),
            isDemo = isDemo
        )
    }

    private fun persist() {
        storage?.save(Saved(current.workouts, current.goals, current.healthRatings, current.isHealthConnected))
    }

    fun updateGoals(goals: FitnessGoals) {
        current = current.copy(goals = goals)
        persist()
    }

    // Training load

    data class Effort(val value: Int, val isEstimated: Boolean)

    /** Effort for a workout: the player's rating, else an estimate from pulse or type. */
    fun effort(workout: Workout): Effort {
        (workout.rpe ?: current.healthRatings[workout.id])?.let { return Effort(it, false) }
        return Effort(
            TrainingLoadMath.estimatedRPE(workout.kind, workout.averageHeartRate, FitnessMath.maxHeartRate(FitnessMath.age(bodyProfile?.birthDate))),
            true
        )
    }

    fun rate(workout: Workout, effort: Int) {
        val clamped = effort.coerceIn(1, 10)
        current = if (current.workouts.any { it.id == workout.id }) {
            current.copy(workouts = current.workouts.map { if (it.id == workout.id) it.rated(clamped) else it })
        } else {
            current.copy(healthRatings = current.healthRatings + (workout.id to clamped))
        }
        persist()
    }

    /** Workouts counted in the load: Health (28 days) + logged ones; demo history until Health is connected. */
    fun loadWorkouts(now: Instant = Clock.System.now()): List<Workout> {
        val device = current.health?.loadWorkouts ?: if (current.isDemo) DemoData.loadHistory(now) else emptyList()
        return device + loggedWorkouts
    }

    fun dailyLoad(days: Int = TrainingLoadMath.CHRONIC_DAYS, now: Instant = Clock.System.now()): List<DailyValue> {
        val entries = loadWorkouts(now).map { TrainingLoadMath.LoadEntry(it.date, (effort(it).value * it.minutes).toDouble()) }
        return TrainingLoadMath.dailyLoads(entries, days, now)
    }

    fun loadRatio(now: Instant = Clock.System.now()): TrainingLoadMath.Ratio = TrainingLoadMath.ratio(dailyLoad(now = now))

    // Health

    /** Shows the system permission screen, then loads the data. */
    suspend fun connectHealth() {
        val service = healthService
        if (service == null || !service.isAvailable) {
            current = current.copy(healthError = "${healthName}: недоступно на этом устройстве")
            return
        }
        try {
            service.requestAuthorization()
            current = current.copy(isHealthConnected = true)
            persist()
            refreshHealth()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current = current.copy(healthError = "Не удалось получить доступ к $healthName")
        }
    }

    suspend fun refreshHealth(now: Instant = Clock.System.now()) {
        val service = healthService ?: return
        if (!current.isHealthConnected || current.isSyncing) return
        current = current.copy(isSyncing = true)
        try {
            val snapshot = service.snapshot(now, bodyProfile)
            current = current.copy(health = snapshot, healthError = null)
            onHealthSnapshot?.invoke(snapshot)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            current = current.copy(healthError = "Не удалось обновить данные $healthName")
        } finally {
            current = current.copy(isSyncing = false)
        }
    }

    /** Back to demo data (the permission itself is managed in system settings). */
    fun disconnectHealth() {
        current = current.copy(isHealthConnected = false, health = null, healthError = null)
        persist()
        onHealthSnapshot?.invoke(null)
    }

    /** Connected, but nothing was recorded today — usually access to the data types was not granted. */
    val healthLooksEmpty: Boolean
        get() = current.health?.let { it.today.steps == 0 && it.workouts.isEmpty() && it.averageHeartRate == null } ?: false

    // Numbers shown on screens

    /** Workouts logged in the app (demo seeds are hidden once real data is connected). */
    private val loggedWorkouts: List<Workout>
        get() = if (current.health == null) current.workouts else current.workouts.filter { it.id !in DemoData.seededWorkoutIDs }

    fun todayWorkouts(now: Instant = Clock.System.now()): List<Workout> =
        ((current.health?.workouts ?: emptyList()) + loggedWorkouts).filter { it.date.isSameDay(now) }.sortedBy { it.date }

    /** Workouts added by the user today on top of the device data. */
    private fun manualToday(now: Instant) = current.workouts.filter { it.date.isSameDay(now) && it.id !in DemoData.seededWorkoutIDs }

    /** Device baseline without Health: the demo numbers for demo accounts, nothing for real accounts. */
    private val showsDemoBaseline: Boolean get() = current.health == null && current.isDemo

    fun activeMinutesToday(now: Instant = Clock.System.now()): Int =
        (current.health?.today?.activeMinutes ?: if (showsDemoBaseline) DemoData.BASELINE_ACTIVE_MINUTES else 0) + manualToday(now).sumOf { it.minutes }

    fun kcalToday(now: Instant = Clock.System.now()): Int =
        (current.health?.today?.activeKcal ?: if (showsDemoBaseline) DemoData.BASELINE_ACTIVE_KCAL else 0) + manualToday(now).sumOf { it.kcal }

    val steps: Int get() = current.health?.today?.steps ?: if (showsDemoBaseline) DemoData.BASELINE_STEPS else 0
    val distanceKm: Double get() = current.health?.today?.distanceKm ?: if (showsDemoBaseline) DemoData.BASELINE_DISTANCE_KM else 0.0

    fun weeklyActiveMinutes(now: Instant = Clock.System.now()): List<DailyValue> {
        val base = current.health?.weeklyActiveMinutes ?: if (showsDemoBaseline) DemoData.weeklyActiveMinutes(now) else loggedMinutesByDay(now)
        val last = base.lastOrNull() ?: return base
        return base.dropLast(1) + DailyValue(last.date, activeMinutesToday(now).toDouble())
    }

    /** Without Health: minutes of logged workouts for the last 7 days. */
    private fun loggedMinutesByDay(now: Instant): List<DailyValue> = (0 until 7).map { index ->
        val day = now.startOfDay().addingDays(index - 6)
        DailyValue(day, current.workouts.filter { it.date.isSameDay(day) }.sumOf { it.minutes }.toDouble())
    }

    /** null = no heart-rate data (no Health / no watch). */
    val heartRateZones: List<HeartRateZone>? get() = if (showsDemoBaseline) DemoData.heartRateZones else current.health?.heartRateZones
    val averageHeartRate: Int? get() = if (showsDemoBaseline) DemoData.AVERAGE_HEART_RATE else current.health?.averageHeartRate
    val bodyMetrics: List<BodyMetric>
        get() = if (showsDemoBaseline) DemoData.bodyMetrics else BodyMath.metrics(bodyProfile, current.health?.bodyReadings ?: BodyReadings())

    /** The weight «Тело» shows: newer scales in Health beat the one typed in «О тебе». */
    val currentWeightKg: Double?
        get() = current.health?.bodyReadings.let { BodyMath.currentWeight(bodyProfile, it?.weightKg, it?.weightAt) }

    fun add(workout: Workout) {
        current = current.copy(workouts = (current.workouts + workout).sortedBy { it.date })
        persist()
    }

    fun resetToDemo() {
        current = current.copy(workouts = DemoData.workouts().sortedBy { it.date })
        persist()
    }

    companion object {
        const val FILE = "fitness-state.json"
    }
}
