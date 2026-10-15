@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable(with = EventTagSerializer::class)
sealed class EventTag {
    data object Personal : EventTag()
    data object Team : EventTag()
    data class Player(val name: String) : EventTag()

    val id: String
        get() = when (this) {
            Personal -> "personal"
            Team -> "team"
            is Player -> "player-$name"
        }

    val title: String
        get() = when (this) {
            Personal -> "#личный"
            Team -> "#командный"
            is Player -> "#$name"
        }

    val tint: Tint
        get() = when (this) {
            Personal -> Tint.PURPLE
            Team -> Tint.CYAN
            is Player -> Tint.ORANGE
        }
}

/** Swift's synthesized enum coding: {"personal":{}}, {"team":{}}, {"player":{"_0":"Nova"}}. */
object EventTagSerializer : KSerializer<EventTag> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun serialize(encoder: Encoder, value: EventTag) {
        val json = encoder as JsonEncoder
        val element = when (value) {
            EventTag.Personal -> buildJsonObject { put("personal", JsonObject(emptyMap())) }
            EventTag.Team -> buildJsonObject { put("team", JsonObject(emptyMap())) }
            is EventTag.Player -> buildJsonObject { put("player", buildJsonObject { put("_0", JsonPrimitive(value.name)) }) }
        }
        json.encodeJsonElement(element)
    }

    override fun deserialize(decoder: Decoder): EventTag {
        val obj = (decoder as JsonDecoder).decodeJsonElement().jsonObject
        return when {
            "personal" in obj -> EventTag.Personal
            "team" in obj -> EventTag.Team
            "player" in obj -> EventTag.Player(obj.getValue("player").jsonObject.getValue("_0").jsonPrimitive.content)
            else -> EventTag.Personal
        }
    }
}

@Serializable
data class CalendarEvent(
    val id: Uuid,
    val title: String,
    val details: String,
    val start: Instant,
    val end: Instant,
    val links: List<String>,
    val tag: EventTag,
    val ownerId: Uuid? = null
)

enum class EventValidationError(val message: String) {
    EMPTY_REQUIRED_FIELDS("Заполните обязательные поля"),
    END_BEFORE_START("Время окончания не может быть раньше времени начала"),
    INVALID_LINK("Проверьте корректность ссылок")
}

object EventValidator {
    val templates = listOf("Тренировка", "Матч", "Разбор игры", "Буткемп", "Отдых")

    fun validate(title: String, start: Instant, end: Instant, links: List<String>): EventValidationError? {
        if (title.isBlank()) return EventValidationError.EMPTY_REQUIRED_FIELDS
        if (end <= start) return EventValidationError.END_BEFORE_START
        return if (links.any { !WebLinks.isValid(it) }) EventValidationError.INVALID_LINK else null
    }

    /** Splits a free-form links field into trimmed, non-empty lines / comma-separated values. */
    fun parseLinks(text: String): List<String> = text.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
}

/** http(s) links with a host — the same rule iOS checks with `URL(string:)`. */
object WebLinks {
    private val pattern = Regex("""^(?i)(https?)://([^/?#:@\s]+@)?([^/?#:\s]+)(:\d+)?([/?#]\S*)?$""")

    fun host(link: String): String? = pattern.matchEntire(link.trim())?.groupValues?.get(3)?.lowercase()

    fun isValid(link: String): Boolean = host(link) != null
}
