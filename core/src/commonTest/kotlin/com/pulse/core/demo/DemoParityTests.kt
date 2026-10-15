package com.pulse.core.demo

import com.pulse.core.model.TeamWeeklyBuilder
import com.pulse.core.model.WeekRange
import com.pulse.core.model.at
import com.pulse.core.model.pinTestZone
import com.pulse.core.util.Formatters
import com.pulse.core.util.daysUntil
import com.pulse.core.util.localDateTime
import com.pulse.core.util.startOfDay
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The Android demo must show the same numbers as the iPhone. Expected hashes were printed by the iOS code
 * (same `now`, same time zone); any change in the order of random calls breaks them.
 */
class DemoParityTests {
    @BeforeTest fun zone() = pinTestZone()

    private val now = at(2026, 10, 15, 12)

    private fun fnv(text: String): ULong {
        var hash = 1469598103934665603uL
        for (byte in text.encodeToByteArray()) hash = (hash xor (byte.toUByte().toULong())) * 1099511628211uL
        return hash
    }

    private fun t(date: Instant): String {
        val local = date.localDateTime()
        return "${date.startOfDay().daysUntil(now.startOfDay())}/${local.hour}:${local.minute}"
    }

    private fun d(value: Double, digits: Int) = Formatters.decimal(value, digits)

    @Test fun sleepHistoryMatchesIos() {
        assertEquals(16533734546151644357uL, fnv(DemoData.sleepHistory(now).joinToString(",") { "${t(it.start)}-${it.durationMinutes}" }))
    }

    @Test fun playersMatchIos() {
        val expected = mapOf(
            "Nova" to listOf(7047102998651477754uL, 16533734546151644357uL, 15878453059365052138uL, 1305639854435022177uL, 17901151005121649088uL, 14858080707252218053uL),
            "Blitz" to listOf(7003138108890779444uL, 585707404731062628uL, 8934646800281682575uL, 2720120140517819483uL, 2353739258964424312uL, 13206480721798694101uL),
            "Echo" to listOf(15414656327564190082uL, 15726980847033733217uL, 16195781076813915589uL, 13815694691223337334uL, 17380463962247203838uL, 1237144410407704243uL),
            "Frost" to listOf(8614326129800702805uL, 5159312984405641131uL, 15140089953314764528uL, 8406470868671091309uL, 477413630648826567uL, 14124177630371355666uL),
            "Ghost" to listOf(1465078863775854207uL, 10556124358032573260uL, 15084136564857041708uL, 670942302114971618uL, 1281139574666485125uL, 13550185511769744339uL)
        )
        for (p in DemoData.players) {
            val matches = GameData.matches(p, 30, now).joinToString(",") {
                "${t(it.date)}:${it.map.name.lowercase()}:${it.won}:${it.kills}:${it.deaths}:${it.assists}:${d(it.adr, 4)}:${it.eloDelta}:${it.roundsWon}:${it.roundsLost}"
            }
            val sleep = DemoData.sleepSessions(p, now).joinToString(",") { "${t(it.start)}-${it.durationMinutes}" }
            val load = TeamAnalytics.dailyLoad(p, now).joinToString(",") { d(it.value, 1) }
            val st = p.stats
            val stats = "${st.kills},${st.deaths},${st.assists},${d(st.adr, 6)},${d(st.kast, 6)},${d(st.avgFlashTime, 6)},${st.firstKills}," +
                "${st.clutchesWon},${st.matches},${st.wins},${st.draws},${st.rounds},${st.roundsWon},${st.pistolWon}"
            val series = DemoData.playerSeries(p, 14, 55..92, 1uL, now).joinToString(",") { it.value.toInt().toString() }
            val meals = DemoData.playerMeals(p, now).joinToString(",") { "${it.name}:${it.kcal}:${t(it.date)}" }
            assertEquals(expected.getValue(p.nickname), listOf(fnv(matches), fnv(sleep), fnv(load), fnv(stats), fnv(series), fnv(meals)), p.nickname)
        }
    }

    @Test fun personalHistoriesMatchIos() {
        assertEquals(17300472663207878719uL, fnv(DemoData.mealHistory(now).joinToString(",") { "${it.name}:${it.kcal}:${t(it.date)}" }))
        assertEquals(1328070966545188196uL, fnv(DemoData.moodHistory(now).joinToString(",") {
            "${t(it.day)}:${it.slot.name.lowercase()}:${it.mood}:${it.energy}"
        }))
        assertEquals(3931595663754769681uL, fnv(DemoData.balanceHistory(now).joinToString(",") { e -> e.orderedValues.joinToString(".") { it.toInt().toString() } }))
        assertEquals(9345798907098734730uL, fnv(DemoData.cognitiveHistory(now).joinToString(",") {
            "${t(it.date)}:${swiftName(it.kind.name)}:${it.score}:${it.metric}"
        }))
        assertEquals(970604101036273355uL, fnv(DemoData.loadHistory(now).joinToString(",") {
            "${t(it.date)}:${it.kind.name.lowercase()}:${it.minutes}:${it.kcal}:${it.averageHeartRate}:${it.rpe ?: 0}"
        }))
        assertEquals(14499562630956668513uL, fnv(DemoData.gameTestHistory(now).joinToString(",") { "${it.focus}.${it.fatigue}.${it.readiness}" }))
        assertEquals(8567020164604454675uL, fnv(DemoData.screenTimeSeries(21, now).joinToString(",") { it.value.toInt().toString() }))
    }

    @Test fun teamWeekMatchesIos() {
        val team = TeamWeeklyBuilder.demo(WeekRange.containing(now), now)
        val line = team.rows.joinToString(" | ") {
            "${it.nickname}:${it.readiness?.let { v -> d(v, 3) } ?: "-"}:${it.sleepMinutes ?: -1}:${it.matches}:" +
                "${it.kd?.let { v -> d(v, 3) } ?: "-"}:${it.load.status.name.lowercase()}"
        }
        assertEquals(
            "Blitz:7,425:441:2:1,194:optimal | Nova:7,175:438:3:1,089:optimal | Frost:6,775:400:3:1,579:optimal | " +
                "Ghost:6,700:388:3:0,933:optimal | Echo:5,500:439:0:-:elevated",
            line
        )
        assertEquals(
            listOf("Сильнее всех вырос Nova: готовность +1,0", "Лучший K/D недели — Frost: 1,58", "Echo спит на 44 мин больше"),
            team.highlights
        )
    }

    /** GO_NO_GO → goNoGo (Swift raw values). */
    private fun swiftName(name: String): String =
        name.lowercase().split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")
}
