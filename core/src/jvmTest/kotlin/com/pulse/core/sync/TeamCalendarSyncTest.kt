package com.pulse.core.sync

import com.pulse.core.api.ApiException
import com.pulse.core.model.CalendarEvent
import com.pulse.core.model.EventTag
import com.pulse.core.store.CalendarStore
import com.pulse.core.store.PendingTeamCalendarChanges
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TeamCalendarSyncTest {
    private val harness = TeamFeedHarness()
    private val server = harness.server
    private val notices = TeamSyncNotices(harness.file("team-sync-notices.json", ListSerializer(TeamSyncNotice.serializer())))
    private val logs = mutableListOf<String>()

    private val calendar = CalendarStore(
        harness.file("calendar.json", ListSerializer(CalendarEvent.serializer())),
        harness.file("team-calendar.json", ListSerializer(CalendarEvent.serializer())),
        harness.file("team-calendar-pending.json", PendingTeamCalendarChanges.serializer()),
        harness.file("team-calendar-team.json", String.serializer()),
        seed = emptyList()
    ).also { it.reload(isDemo = false, shareLegacyEvents = false, isTeamMember = true) }

    private val sync = TeamCalendarSync(harness.client, calendar, notices).also { it.log = logs::add }

    private val vegaId = "9a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val novaId = "8a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    private fun event(title: String, tag: EventTag = EventTag.Team, id: Uuid = Uuid.random()) = CalendarEvent(
        id, title, "details", Instant.parse("2026-10-27T10:00:00Z"), Instant.parse("2026-10-27T11:00:00Z"),
        listOf("https://example.com/a"), tag
    )

    /** The event as the server lists it. */
    private fun serverEvent(id: Uuid, title: String, tag: String = "team", playerId: String? = null, nickname: String? = null) = buildJsonObject {
        put("id", id.toString()); put("title", title); put("details", ""); put("start", "2026-10-27T10:00:00Z"); put("end", "2026-10-27T11:00:00Z")
        put("links", JsonArray(emptyList())); put("tag", tag)
        put("player_id", playerId?.let(::JsonPrimitive) ?: JsonNull); put("player_nickname", nickname?.let(::JsonPrimitive) ?: JsonNull)
        put("owner_id", JsonNull)
    }

    private fun pendingIds() = calendar.pendingSharedChanges().keys

    // ------------------------------------------------------------------------------------------------ pulling

    @Test fun pullAppliesTheServerState() = runTest {
        val team = Uuid.random()
        val forVega = Uuid.random()
        server.players = linkedMapOf(vegaId to "vega", novaId to "nova")
        server.calendar[team.toString()] = serverEvent(team, "Тренировка")
        server.calendar[forVega.toString()] = serverEvent(forVega, "Разбор", "player", vegaId, "vega")

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(listOf("GET calendar"), server.lines())
        assertEquals(setOf("Тренировка", "Разбор"), calendar.events.map { it.title }.toSet())
        assertEquals(EventTag.Player("vega"), calendar.event(forVega)?.tag)
        assertEquals(listOf(EventTag.Player("vega"), EventTag.Player("nova")), calendar.playerTags)
    }

    @Test fun pullDropsWhatTheServerNoLongerHas() = runTest {
        val gone = Uuid.random()
        server.calendar[gone.toString()] = serverEvent(gone, "Удалено на сайте")
        sync.sync()
        assertEquals("Удалено на сайте", calendar.event(gone)?.title)

        server.calendar.clear()
        sync.sync()

        assertNull(calendar.event(gone))
    }

    // ------------------------------------------------------------------------------------------------ pushing

    @Test fun pendingCreateUpdateAndDeleteAreSent() = runTest {
        val existing = event("Было")
        val removed = event("Лишнее")
        server.calendar[existing.id.toString()] = serverEvent(existing.id, "Было")
        server.calendar[removed.id.toString()] = serverEvent(removed.id, "Лишнее")
        sync.sync()
        val created = event("Новое")
        calendar.upsert(created)
        calendar.upsert(existing.copy(title = "Стало"))
        calendar.delete(removed.id)
        server.requests.clear()

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(listOf("calendar"), result.uploaded)
        val lines = server.lines()
        assertEquals("GET calendar", lines.first())
        assertEquals("GET calendar", lines.last())
        assertEquals(
            setOf("POST calendar", "PUT calendar/${existing.id}", "DELETE calendar/${removed.id}"),
            lines.drop(1).dropLast(1).toSet()
        )
        assertEquals(emptySet(), pendingIds())
        assertEquals(setOf("Новое", "Стало"), calendar.events.map { it.title }.toSet())
        assertEquals("Стало", server.calendar.getValue(existing.id.toString())["title"]?.jsonPrimitive?.content)
        assertTrue(removed.id.toString() !in server.calendar)
        assertTrue(notices.texts.isEmpty())
    }

    @Test fun aPlayerEventCarriesThePlayerIdAndTheNicknameForOldServers() = runTest {
        server.players = linkedMapOf(vegaId to "vega", novaId to "nova")
        sync.sync()
        val planned = event("Разбор vega", EventTag.Player("vega"))
        calendar.upsert(planned)

        sync.sync()

        val body = server.bodiesOf("POST calendar").single()
        assertEquals("player", body.getValue("tag").jsonPrimitive.content)
        assertEquals(vegaId, body.getValue("player_id").jsonPrimitive.content)
        assertEquals("vega", body.getValue("player_nickname").jsonPrimitive.content)
        assertEquals(planned.id.toString(), body.getValue("id").jsonPrimitive.content)
        assertEquals(setOf("id", "title", "details", "start", "end", "links", "tag", "player_id", "player_nickname"), body.keys)
    }

    @Test fun twoPlayersWithTheSameNicknameSendNoGuessedId() = runTest {
        server.players = linkedMapOf(vegaId to "vega", novaId to "vega")
        sync.sync()
        calendar.upsert(event("Разбор", EventTag.Player("vega")))

        sync.sync()

        val body = server.bodiesOf("POST calendar").single()
        assertTrue("player_id" !in body || body.getValue("player_id") is JsonNull)
        assertEquals("vega", body.getValue("player_nickname").jsonPrimitive.content)
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Test fun aRefusedChangeDoesNotBlockTheNextOneAndLeavesANotice() = runTest {
        val refused = event("Запрещённое")
        val fine = event("Хорошее")
        calendar.upsert(refused)
        calendar.upsert(fine)
        server.refusals = mapOf(refused.id.toString() to 403)

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(emptySet(), pendingIds(), "one was sent, the other left the queue")
        assertTrue(fine.id.toString() in server.calendar, "the next change reached the server")
        assertTrue(refused.id.toString() !in server.calendar)
        assertNull(calendar.event(refused.id), "the server has no such event: the local copy is dropped")
        assertEquals("Хорошее", calendar.event(fine.id)?.title)
        assertEquals(listOf("Не удалось сохранить «Запрещённое»: нет прав"), notices.texts)
        assertEquals("Не удалось сохранить «Запрещённое»: нет прав", notices.items().single().title)
    }

    @Test fun aRefusedEditPutsTheServersVersionBack() = runTest {
        val id = Uuid.random()
        server.calendar[id.toString()] = serverEvent(id, "Серверное")
        sync.sync()
        calendar.upsert(event("Моя правка", id = id))
        server.refusals = mapOf(id.toString() to 422)

        sync.sync()

        assertEquals("Серверное", calendar.event(id)?.title)
        assertEquals(emptySet(), pendingIds())
        assertEquals(listOf("Не удалось сохранить «Моя правка»: проверьте поля"), notices.texts)
        // and the refusal is not retried by the next round
        server.requests.clear()
        sync.sync()
        assertEquals(listOf("GET calendar"), server.lines())
    }

    @Test fun aRefusedDeleteBringsTheEventBack() = runTest {
        val id = Uuid.random()
        server.calendar[id.toString()] = serverEvent(id, "Нельзя удалять")
        sync.sync()
        calendar.delete(id)
        assertNull(calendar.event(id))
        server.refusals = mapOf(id.toString() to 403)

        sync.sync()

        assertEquals("Нельзя удалять", calendar.event(id)?.title)
        assertEquals(emptySet(), pendingIds())
        assertEquals(listOf("Не удалось сохранить «Нельзя удалять»: нет прав"), notices.texts)
    }

    @Test fun deletingWhatIsAlreadyGoneIsNotAFailure() = runTest {
        val id = Uuid.random()
        server.calendar[id.toString()] = serverEvent(id, "Было")
        sync.sync()
        calendar.delete(id)
        server.calendar.clear()

        sync.sync()

        assertEquals(emptySet(), pendingIds())
        assertTrue(notices.texts.isEmpty())
    }

    @Test fun reasonsFollowTheStatus() = runTest {
        for ((status, reason) in listOf(403 to "нет прав", 422 to "проверьте поля", 409 to "сервер отклонил", 413 to "сервер отклонил", 404 to "сервер отклонил", 400 to "сервер отклонил")) {
            val id = Uuid.random()
            calendar.upsert(event("Запись $status", id = id))
            server.refusals = mapOf(id.toString() to status)
            sync.sync()
            assertEquals("Не удалось сохранить «Запись $status»: $reason", notices.texts.last())
        }
        assertEquals(6, notices.texts.size)
    }

    @Test fun theLogGetsOnlyKindCodeAndStatus() = runTest {
        calendar.upsert(event("Личное название"))
        server.refusals = calendar.events.associate { it.id.toString() to 403 }

        sync.sync()

        val line = logs.single { "refused" in it }
        assertTrue("status=403" in line && "code=forbidden" in line && "kind=REJECTED" in line, line)
        assertFalse("Личное название" in line)
    }

    // ------------------------------------------------------------------------------------------------ transient errors

    @Test fun transientErrorsKeepTheChangePendingAndStopTheRound() = runTest {
        val first = event("Первое")
        val second = event("Второе")
        calendar.upsert(first)
        calendar.upsert(second)
        for (status in listOf(500, 503, 408, 429, 401)) {
            server.failures = mapOf("POST calendar" to status)
            val error = assertFailsWith<ApiException>("status $status") { sync.sync() }
            assertEquals(status, error.status)
            assertEquals(setOf(first.id.toString(), second.id.toString()), pendingIds(), "status $status keeps both")
            assertTrue(notices.texts.isEmpty(), "status $status leaves no notice")
        }
        assertEquals(5, server.requests.count { it.line == "POST calendar" }, "each round stops at the first failing change")

        server.failures = emptyMap()
        sync.sync()
        assertEquals(emptySet(), pendingIds())
        assertEquals(setOf(first.id.toString(), second.id.toString()), server.calendar.keys)
    }

    @Test fun offlineKeepsTheChangePending() = runTest {
        sync.sync()
        calendar.upsert(event("Без сети"))
        server.offline = true

        val error = assertFailsWith<ApiException> { sync.sync() }

        assertTrue(error.isOffline)
        assertEquals(1, pendingIds().size)
        assertTrue(notices.texts.isEmpty())
    }
}
