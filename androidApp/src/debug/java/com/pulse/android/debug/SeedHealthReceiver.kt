package com.pulse.android.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Debug-only: fills Health Connect with a week of plausible test data (steps, a strength workout with pulse and
 * calories, nights with stages and night pulse) so the integration can be checked on an emulator.
 */
class SeedHealthReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                HealthConnectClient.getOrCreate(context).insertRecords(records())
                Log.i(TAG, "Seeded Health Connect")
            } catch (error: Exception) {
                Log.e(TAG, "Seeding failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun records(): List<Record> {
        val zone = ZoneId.systemDefault()
        val offset: ZoneOffset = zone.rules.getOffset(Instant.now())
        val today = LocalDate.now(zone)
        val manual = Metadata.manualEntry()
        val records = mutableListOf<Record>()
        for (daysAgo in 0..6) {
            val day = today.minusDays(daysAgo.toLong())
            // Morning brisk walk (1-minute records ≥ 100 steps) plus a slow afternoon.
            val walk = day.atTime(8, 0).atZone(zone).toInstant()
            for (minute in 0 until 30 + daysAgo * 3) {
                val start = walk.plus(Duration.ofMinutes(minute.toLong()))
                records += StepsRecord(start, offset, start.plus(Duration.ofMinutes(1)), offset, 115, manual)
            }
            val afternoon = day.atTime(14, 0).atZone(zone).toInstant()
            records += StepsRecord(afternoon, offset, afternoon.plus(Duration.ofHours(2)), offset, 3200L + daysAgo * 150, manual)
            // Night ending this morning: light / deep / REM / awake.
            val bed = day.minusDays(1).atTime(23, 10).atZone(zone).toInstant()
            val stages = listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT to 40L, SleepSessionRecord.STAGE_TYPE_DEEP to 70L, SleepSessionRecord.STAGE_TYPE_LIGHT to 60L,
                SleepSessionRecord.STAGE_TYPE_REM to 35L, SleepSessionRecord.STAGE_TYPE_AWAKE to 5L, SleepSessionRecord.STAGE_TYPE_DEEP to 50L,
                SleepSessionRecord.STAGE_TYPE_LIGHT to 80L, SleepSessionRecord.STAGE_TYPE_REM to 45L + daysAgo * 2
            )
            var cursor = bed
            val stageList = stages.map { (type, minutes) ->
                val end = cursor.plus(Duration.ofMinutes(minutes))
                SleepSessionRecord.Stage(cursor, end, type).also { cursor = end }
            }
            records += SleepSessionRecord(bed, offset, cursor, offset, manual, "Сон", null, stageList)
            records += HeartRateRecord(bed, offset, cursor, offset,
                (0 until 8).map { HeartRateRecord.Sample(bed.plus(Duration.ofMinutes(it * 50L)), 52L + (it % 3) * 3) }, manual)
        }
        // Yesterday's strength workout, 60 min, with pulse and calories.
        val workout = today.minusDays(1).atTime(18, 30).atZone(zone).toInstant()
        val workoutEnd = workout.plus(Duration.ofMinutes(60))
        records += ExerciseSessionRecord(workout, offset, workoutEnd, offset, manual, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, "Силовая", null)
        records += ActiveCaloriesBurnedRecord(workout, offset, workoutEnd, offset, Energy.kilocalories(410.0), manual)
        records += HeartRateRecord(workout, offset, workoutEnd, offset, (0 until 12).map { HeartRateRecord.Sample(workout.plus(Duration.ofMinutes(it * 5L)), 118L + it * 3) }, manual)
        // Today's daytime pulse for the zones.
        val noon = today.atTime(12, 0).atZone(zone).toInstant()
        records += HeartRateRecord(noon, offset, noon.plus(Duration.ofMinutes(55)), offset, (0 until 12).map { HeartRateRecord.Sample(noon.plus(Duration.ofMinutes(it * 5L)), 72L + it * 6) }, manual)
        return records
    }

    private companion object {
        const val TAG = "PulseSeed"
    }
}
