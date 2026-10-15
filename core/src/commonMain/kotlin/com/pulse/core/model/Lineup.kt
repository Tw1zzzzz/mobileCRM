@file:UseSerializers(IsoInstantSerializer::class, UuidSerializer::class)

package com.pulse.core.model

import com.pulse.core.util.IsoInstantSerializer
import com.pulse.core.util.Tint
import com.pulse.core.util.UuidSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Serializable
enum class CSMap(val title: String) {
    @SerialName("mirage") MIRAGE("Mirage"),
    @SerialName("inferno") INFERNO("Inferno"),
    @SerialName("nuke") NUKE("Nuke"),
    @SerialName("ancient") ANCIENT("Ancient"),
    @SerialName("anubis") ANUBIS("Anubis"),
    @SerialName("dust2") DUST2("Dust II"),
    @SerialName("train") TRAIN("Train");

    companion object
}

@Serializable
enum class LineupType(val title: String, val icon: String, val tint: Tint) {
    @SerialName("smoke") SMOKE("Смок", "cloud", Tint.TEXT_SECONDARY),
    @SerialName("flash") FLASH("Флешка", "sun.max", Tint.YELLOW),
    @SerialName("molotov") MOLOTOV("Молотов", "flame", Tint.ORANGE),
    @SerialName("grenade") GRENADE("HE", "burst", Tint.RED),
    @SerialName("script") SCRIPT("Скрипт", "list.bullet.rectangle", Tint.CYAN),
    @SerialName("note") NOTE("Заметка", "note.text", Tint.PURPLE)
}

@Serializable
enum class LineupSide(val title: String) {
    @SerialName("t") T("T"),
    @SerialName("ct") CT("CT")
}

/** How the grenade is thrown — the main thing that differs between variations of one lineup. */
@Serializable
enum class ThrowTechnique(val title: String) {
    @SerialName("standard") STANDARD("Обычный"),
    @SerialName("jump") JUMP("Jump-throw"),
    @SerialName("run") RUN("Run-throw"),
    @SerialName("runJump") RUN_JUMP("Run + jump"),
    @SerialName("walk") WALK("Shift + бросок"),
    @SerialName("rightClick") RIGHT_CLICK("ПКМ (под себя)")
}

/** One way to land the same grenade: another spot, another technique, another timing. */
@Serializable
data class LineupVariation(
    val id: Uuid = Uuid.random(),
    val position: String,
    val technique: ThrowTechnique,
    val aim: String,
    val videoURL: String? = null
) {
    val isBlank: Boolean get() = position.isBlank() && aim.isBlank()
}

/** Who sees a diary entry: only its author, or the whole team (staff included). */
@Serializable
enum class LineupVisibility(val title: String, val icon: String) {
    @SerialName("personal") PERSONAL("Личная", "lock"),
    @SerialName("team") TEAM("Команде", "person.3")
}

/** Defaults let entries saved before `visibility` / `isFavorite` / `variations` existed load fine. */
@Serializable
data class Lineup(
    val id: Uuid,
    val title: String,
    val map: CSMap,
    val side: LineupSide,
    val type: LineupType,
    val details: String,
    val videoURL: String? = null,
    val author: String,
    val createdAt: Instant,
    val visibility: LineupVisibility = LineupVisibility.TEAM,
    val isFavorite: Boolean = false,
    /** Alternative ways to throw the same grenade (not used for scripts and notes). */
    val variations: List<LineupVariation> = emptyList()
) {
    val supportsVariations: Boolean get() = type != LineupType.NOTE && type != LineupType.SCRIPT

    val subtitle: String
        get() = if (type == LineupType.NOTE) "${map.title} · Заметка" else "${map.title} · ${side.title} · ${type.title}"

    fun toggledFavorite(): Lineup = copy(isFavorite = !isFavorite)
}

/** Which entries a user may see and the diary scope filter. */
enum class DiaryScope(val title: String) {
    ALL("Все"), MINE("Мои"), TEAM("Команда"), FAVORITES("Избранное");

    companion object {
        /** Solo players and staff have no «Мои / Команда» split — only «Все / Избранное». */
        fun options(role: UserRole?): List<DiaryScope> = if (role == UserRole.TEAM_PLAYER) entries else listOf(ALL, FAVORITES)
    }
}

object LineupValidator {
    fun validate(title: String, videoURL: String): String? {
        if (title.isBlank()) return AuthError.EMPTY_FIELDS.message
        val link = videoURL.trim()
        if (link.isEmpty()) return null
        return if (WebLinks.isValid(link)) null else EventValidationError.INVALID_LINK.message
    }

    /** A variation needs at least a spot to throw from; its video link must be valid when present. */
    fun validate(variation: LineupVariation): String? {
        if (variation.position.isBlank()) return "Укажи, откуда бросать"
        return validate(variation.position, variation.videoURL ?: "")
    }
}
