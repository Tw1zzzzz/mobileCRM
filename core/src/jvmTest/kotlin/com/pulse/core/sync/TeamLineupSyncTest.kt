package com.pulse.core.sync

import com.pulse.core.api.ApiException
import com.pulse.core.model.CSMap
import com.pulse.core.model.Lineup
import com.pulse.core.model.LineupSide
import com.pulse.core.model.LineupType
import com.pulse.core.model.LineupVariation
import com.pulse.core.model.LineupVisibility
import com.pulse.core.model.ThrowTechnique
import com.pulse.core.store.LineupStore
import com.pulse.core.store.PendingTeamLineupChanges
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

class TeamLineupSyncTest {
    private val harness = TeamFeedHarness()
    private val server = harness.server
    private val notices = TeamSyncNotices(harness.file("team-sync-notices.json", ListSerializer(TeamSyncNotice.serializer())))
    private val logs = mutableListOf<String>()

    private val lineups = LineupStore(
        harness.file("lineups.json", ListSerializer(Lineup.serializer())),
        seed = emptyList(),
        teamStorage = harness.file("team-lineups.json", ListSerializer(Lineup.serializer())),
        pendingStorage = harness.file("team-lineups-pending.json", PendingTeamLineupChanges.serializer())
    ).also { it.reload(isDemo = false, isTeamMember = true) }

    private val sync = TeamLineupSync(harness.client, lineups, notices).also { it.log = logs::add }

    private fun lineup(title: String, type: LineupType = LineupType.SMOKE, id: Uuid = Uuid.random()) = Lineup(
        id, title, CSMap.MIRAGE, LineupSide.T, type, "Jump-throw", "https://example.com/v", "Coach", Instant.parse("2026-10-26T12:00:00Z"),
        LineupVisibility.TEAM, false,
        listOf(LineupVariation(Uuid.random(), "T-спавн", ThrowTechnique.RUN_JUMP, "Антенна", "https://example.com/v2"))
    )

    /** A lineup as `GET /lineups` answers it (snake_case, lower-case ids). */
    private fun serverLineup(id: Uuid, title: String): JsonObject = Json.parseToJsonElement(
        """{"id":"$id","title":"$title","map":"inferno","side":"ct","type":"flash","details":"d","video_url":null,"author":"Coach",
            "created_at":"2026-10-26T12:00:00.000Z","visibility":"team","is_favorite":true,
            "variations":[{"id":"${Uuid.random()}","position":"P","technique":"standard","aim":"A","video_url":null}]}"""
    ).jsonObject

    private fun pendingIds() = lineups.pendingTeamChanges().keys

    @Test fun pullAppliesTheServerState() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = serverLineup(id, "Флешка с сервера")

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(listOf("GET lineups"), server.lines())
        val pulled = assertNotNull(lineups.lineup(id))
        assertEquals("Флешка с сервера", pulled.title)
        assertEquals(CSMap.INFERNO, pulled.map)
        assertEquals(LineupType.FLASH, pulled.type)
        assertTrue(pulled.isFavorite)
        assertEquals(LineupVisibility.TEAM, pulled.visibility)
        assertEquals("P", pulled.variations.single().position)
    }

    @Test fun pendingCreateUpdateAndDeleteAreSent() = runTest {
        val existing = Uuid.random()
        val removed = Uuid.random()
        server.lineups[existing.toString()] = serverLineup(existing, "Было")
        server.lineups[removed.toString()] = serverLineup(removed, "Лишняя")
        sync.sync()
        val created = lineup("Новая")
        lineups.upsert(created)
        lineups.upsert(lineups.lineup(existing)!!.copy(title = "Стало"))
        lineups.delete(removed)
        server.requests.clear()

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(listOf("team-lineups"), result.uploaded)
        val lines = server.lines()
        assertEquals("GET lineups", lines.first())
        assertEquals("GET lineups", lines.last())
        assertEquals(setOf("POST lineups", "PUT lineups/$existing", "DELETE lineups/$removed"), lines.drop(1).dropLast(1).toSet())
        assertEquals(emptySet(), pendingIds())
        assertEquals(setOf("Новая", "Стало"), lineups.lineups.map { it.title }.toSet())
        assertTrue(notices.texts.isEmpty())
    }

    @Test fun theBodyUsesTheApiWireFormatTheServerReads() = runTest {
        val created = lineup("Смок в окно")
        lineups.upsert(created)

        sync.sync()

        val body = server.bodiesOf("POST lineups").single()
        assertEquals(created.id.toString().lowercase(), body.getValue("id").jsonPrimitive.content.lowercase())
        assertEquals("smoke", body.getValue("type").jsonPrimitive.content)
        assertEquals("https://example.com/v", body.getValue("video_url").jsonPrimitive.content)
        assertEquals("team", body.getValue("visibility").jsonPrimitive.content)
        assertTrue("is_favorite" in body && "created_at" in body)
        val variation = body.getValue("variations").toString()
        assertTrue("\"technique\":\"runJump\"" in variation && "\"video_url\":\"https://example.com/v2\"" in variation, variation)
        // the server's answer, in its own format, reads back into the same entry
        assertEquals("Смок в окно", lineups.lineup(created.id)?.title)
        assertEquals("https://example.com/v", lineups.lineup(created.id)?.videoURL)
        assertEquals("https://example.com/v2", lineups.lineup(created.id)?.variations?.single()?.videoURL)
    }

    @Test fun aNoteTheTeamAlreadySharesIsSentAsANote() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = Json.parseToJsonElement(
            """{"id":"$id","title":"Разбор Train","map":"train","side":"ct","type":"note","details":"Текст","video_url":null,"author":"Coach",
                "created_at":"2026-10-26T12:00:00.000Z","visibility":"team","is_favorite":false,"variations":[]}"""
        ).jsonObject
        sync.sync()
        assertEquals(LineupType.NOTE, lineups.lineup(id)?.type)
        lineups.upsert(lineups.lineup(id)!!.copy(details = "Новый текст"))

        sync.sync()

        assertEquals("note", server.bodiesOf("PUT lineups/$id").single().getValue("type").jsonPrimitive.content)
        assertEquals(emptySet(), pendingIds())
        assertTrue(notices.texts.isEmpty())
    }

    @Test fun aNewTeamVisibleNoteIsPushedAndAppearsInTheTeamFeed() = runTest {
        val note = lineup("Разбор Train", LineupType.NOTE).copy(variations = emptyList())
        lineups.upsert(note)
        assertEquals(setOf(note.id.toString()), pendingIds())

        sync.sync()

        assertEquals("note", server.bodiesOf("POST lineups").single().getValue("type").jsonPrimitive.content)
        assertTrue(note.id.toString() in server.lineups)
        assertEquals(emptySet(), pendingIds())
        assertEquals(LineupType.NOTE, lineups.lineup(note.id)?.type)
        // it is the team's copy now: a reload keeps it and it is not queued again
        lineups.reload(isDemo = false, isTeamMember = true)
        assertEquals(emptySet(), pendingIds())
        assertEquals("Разбор Train", lineups.lineup(note.id)?.title)
    }

    @Test fun aPersonalNoteStaysOnThePhone() = runTest {
        val note = lineup("Личный разбор", LineupType.NOTE).copy(visibility = LineupVisibility.PERSONAL, variations = emptyList())
        lineups.upsert(note)

        sync.sync()

        assertEquals(emptySet(), pendingIds())
        assertEquals(listOf("GET lineups"), server.lines())
        assertEquals("Личный разбор", lineups.lineup(note.id)?.title)
    }

    @Test fun aRefusalThenATransientErrorStillShowsTheServersVersion() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = serverLineup(id, "Серверная")
        sync.sync()
        val edited = lineups.lineup(id)!!.copy(title = "Моя правка")
        val later = lineup("Следующая")
        lineups.upsert(edited)
        lineups.upsert(later)
        server.refusals = mapOf(id.toString() to 403)
        server.failures = mapOf("POST lineups" to 503)

        assertFailsWith<ApiException> { sync.sync() }

        assertEquals("Серверная", lineups.lineup(id)?.title, "the refused edit is replaced right away")
        assertEquals(setOf(later.id.toString()), pendingIds(), "the transient change stays queued")
        assertEquals(listOf("Не удалось сохранить «Моя правка»: нет прав"), notices.texts)
    }

    @Test fun aRefusedChangeDoesNotBlockTheNextOneAndLeavesANotice() = runTest {
        val refused = lineup("Запрещённая")
        val fine = lineup("Хорошая")
        lineups.upsert(refused)
        lineups.upsert(fine)
        server.refusals = mapOf(refused.id.toString() to 422)

        val result = sync.sync()

        assertFalse(result.failed)
        assertEquals(emptySet(), pendingIds())
        assertTrue(fine.id.toString() in server.lineups)
        assertNull(lineups.lineup(refused.id), "the server has no such lineup: the local copy is dropped")
        assertEquals("Хорошая", lineups.lineup(fine.id)?.title)
        assertEquals(listOf("Не удалось сохранить «Запрещённая»: проверьте поля"), notices.texts)
        assertEquals("Не удалось сохранить «Запрещённая»: проверьте поля", notices.items().single().title)
        server.requests.clear()
        sync.sync()
        assertEquals(listOf("GET lineups"), server.lines(), "the refusal is not retried")
    }

    @Test fun aRefusedEditPutsTheServersVersionBack() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = serverLineup(id, "Серверная")
        sync.sync()
        lineups.upsert(lineups.lineup(id)!!.copy(title = "Моя правка"))
        server.refusals = mapOf(id.toString() to 403)

        sync.sync()

        assertEquals("Серверная", lineups.lineup(id)?.title)
        assertEquals(emptySet(), pendingIds())
        assertEquals(listOf("Не удалось сохранить «Моя правка»: нет прав"), notices.texts)
    }

    @Test fun aRefusedDeleteBringsTheEntryBack() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = serverLineup(id, "Чужая")
        sync.sync()
        lineups.delete(id)
        assertNull(lineups.lineup(id))
        server.refusals = mapOf(id.toString() to 403)

        sync.sync()

        assertEquals("Чужая", lineups.lineup(id)?.title)
        assertEquals(listOf("Не удалось сохранить «Чужая»: нет прав"), notices.texts)
    }

    @Test fun deletingWhatIsAlreadyGoneIsNotAFailure() = runTest {
        val id = Uuid.random()
        server.lineups[id.toString()] = serverLineup(id, "Была")
        sync.sync()
        lineups.delete(id)
        server.lineups.clear()

        sync.sync()

        assertEquals(emptySet(), pendingIds())
        assertTrue(notices.texts.isEmpty())
    }

    @Test fun reasonsFollowTheStatus() = runTest {
        for ((status, reason) in listOf(403 to "нет прав", 422 to "проверьте поля", 409 to "сервер отклонил", 413 to "сервер отклонил", 404 to "сервер отклонил")) {
            val entry = lineup("Запись $status")
            lineups.upsert(entry)
            server.refusals = mapOf(entry.id.toString() to status)
            sync.sync()
            assertEquals("Не удалось сохранить «Запись $status»: $reason", notices.texts.last())
        }
    }

    @Test fun theLogGetsOnlyKindCodeAndStatus() = runTest {
        val entry = lineup("Личное название")
        lineups.upsert(entry)
        server.refusals = mapOf(entry.id.toString() to 403)

        sync.sync()

        val line = logs.single { "refused" in it }
        assertTrue("status=403" in line && "code=forbidden" in line && "kind=REJECTED" in line, line)
        assertFalse("Личное название" in line)
    }

    @Test fun transientErrorsKeepTheChangePendingAndStopTheRound() = runTest {
        val first = lineup("Первая")
        val second = lineup("Вторая")
        lineups.upsert(first)
        lineups.upsert(second)
        for (status in listOf(500, 503, 408, 429, 401)) {
            server.failures = mapOf("POST lineups" to status)
            val error = assertFailsWith<ApiException>("status $status") { sync.sync() }
            assertEquals(status, error.status)
            assertEquals(setOf(first.id.toString(), second.id.toString()), pendingIds(), "status $status keeps both")
            assertTrue(notices.texts.isEmpty(), "status $status leaves no notice")
        }
        assertEquals(5, server.requests.count { it.line == "POST lineups" }, "each round stops at the first failing change")

        server.failures = emptyMap()
        sync.sync()
        assertEquals(emptySet(), pendingIds())
        assertEquals(setOf(first.id.toString(), second.id.toString()), server.lineups.keys)
    }

    @Test fun offlineKeepsTheChangePending() = runTest {
        sync.sync()
        lineups.upsert(lineup("Без сети"))
        server.offline = true

        val error = assertFailsWith<ApiException> { sync.sync() }

        assertTrue(error.isOffline)
        assertEquals(1, pendingIds().size)
        assertTrue(notices.texts.isEmpty())
    }
}
