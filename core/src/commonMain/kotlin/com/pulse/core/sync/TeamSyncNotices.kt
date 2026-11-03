package com.pulse.core.sync

import com.pulse.core.api.ApiException
import com.pulse.core.feature.NotificationInbox
import com.pulse.core.storage.JsonFileStorage
import com.pulse.core.util.Formatters
import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** What a team feed does with a change the server answered with an error. */
internal object TeamSyncRefusal {
    /** 4xx except 401 (sign-in), 408 and 429 (try again later): the server will never accept this change as it is. */
    fun isPermanent(error: ApiException): Boolean {
        val status = error.status ?: return false
        return status in 400..499 && status != 401 && status != 408 && status != 429
    }

    fun reason(status: Int?): String = when (status) {
        403 -> "нет прав"
        422 -> "проверьте поля"
        else -> "сервер отклонил"
    }

    fun text(title: String, status: Int?): String = "Не удалось сохранить «$title»: ${reason(status)}"

    /** Only the kind of failure: a message may quote what the player wrote. */
    fun logLine(feed: String, error: ApiException): String =
        "$feed: change refused (kind=${error.kind} code=${error.code ?: "-"} status=${error.status ?: "-"})"
}

@Serializable
data class TeamSyncNotice(
    val id: String,
    val text: String,
    @Serializable(with = IsoInstantSerializer::class) val at: Instant
)

/**
 * One-time notices about team changes the server refused («Не удалось сохранить «…»: нет прав»). They are kept in the
 * account's folder until the player swipes them away in the bell's inbox, so a refusal found by a background round is
 * still there at the next launch.
 */
class TeamSyncNotices(
    private val storage: JsonFileStorage<List<TeamSyncNotice>>? = null,
    private val clock: () -> Instant = { Clock.System.now() }
) {
    private var notices: List<TeamSyncNotice> = storage?.load().orEmpty()

    val texts: List<String> get() = notices.map { it.text }

    /** Re-reads the active account's notices (after sign-in or an account switch). */
    fun reload() {
        notices = storage?.load().orEmpty()
    }

    fun add(recordId: String, text: String) {
        val at = clock()
        notices = (notices + TeamSyncNotice("team-refused-$recordId-${at.epochSeconds}", text, at)).takeLast(MAX_KEPT)
        storage?.save(notices)
    }

    fun items(): List<NotificationInbox.Item> = notices.reversed().map {
        NotificationInbox.Item(it.id, "exclamationmark.triangle.fill", Tint.ORANGE, it.text, "Изменение отменено", Formatters.time(it.at))
    }

    private companion object {
        const val MAX_KEPT = 20
    }
}
