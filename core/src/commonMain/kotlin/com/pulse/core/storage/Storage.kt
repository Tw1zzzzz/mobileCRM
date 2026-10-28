package com.pulse.core.storage

import com.pulse.core.model.User
import com.pulse.core.platform.FileSystem
import com.pulse.core.util.PulseJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Which account's data folder is active. Every account (and every demo role) has its own folder,
 * so demo data never mixes with the user's own records. Same layout as iOS: one folder per account under `accounts/`.
 */
class DataScope(val files: FileSystem) {
    /** Set when an account is activated, before stores reload. */
    var current: String = "guest"

    fun folder(scope: String = current): String = "accounts/$scope"

    /** Account deletion: removes every file of that account. */
    fun erase(scope: String) = files.deleteRecursively(folder(scope))

    companion object {
        fun id(user: User): String = if (user.isDemo) "demo-${user.role.serialName}" else user.id.toString().uppercase()

        /** Files shared by all accounts (current session, local account list). */
        val globalFiles = setOf("session.json", "accounts.json")
    }
}

private val com.pulse.core.model.UserRole.serialName: String
    get() = when (this) {
        com.pulse.core.model.UserRole.SOLO_PLAYER -> "soloPlayer"
        com.pulse.core.model.UserRole.TEAM_PLAYER -> "teamPlayer"
        com.pulse.core.model.UserRole.STAFF -> "staff"
    }

/**
 * Persists a value as JSON inside the active account's folder (or the shared folder for `global` files).
 * Local changes are recorded in the [SyncLedger] so they can be uploaded once the backend exists.
 */
class JsonFileStorage<T>(
    val filename: String,
    private val serializer: KSerializer<T>,
    private val scope: DataScope,
    private val global: Boolean = false,
    private val ledger: SyncLedger? = if (global) null else SyncLedger(scope)
) {
    private val path: String get() = if (global) filename else "${scope.folder()}/$filename"

    fun load(): T? {
        val text = scope.files.readText(path) ?: return null
        return runCatching { PulseJson.decodeFromString(serializer, text) }.getOrNull()
    }

    fun save(value: T) {
        scope.files.writeText(path, PulseJson.encodeToString(serializer, value))
        ledger?.markChanged(filename)
    }

    fun clear() = scope.files.delete(path)
}

/**
 * Remembers which local collections (sleep.json, nutrition.json…) changed since the last server sync, and the server
 * copy each one is based on (version, record keys for the three-way merge, server time).
 * Kept per account folder in `sync-ledger.json`; files written before sync existed decode as never synced.
 */
class SyncLedger(private val scope: DataScope) {
    @Serializable
    data class Entry(
        @Serializable(with = LedgerInstantSerializer::class) val changedAt: Instant,
        @Serializable(with = LedgerInstantSerializer::class) val syncedAt: Instant? = null,
        /** Server version the local copy is based on; null = never synced. */
        val version: Int? = null,
        /** Record keys per list field of that server version: the merge base («deleted records do not come back»). */
        val baseKeys: Map<String, Set<String>>? = null,
        @Serializable(with = LedgerInstantSerializer::class) val remoteUpdatedAt: Instant? = null
    ) {
        val isPending: Boolean get() = syncedAt?.let { it < changedAt } ?: true
    }

    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private fun path(folder: String) = "$folder/sync-ledger.json"

    fun markChanged(collection: String, at: Instant = Clock.System.now(), folder: String = scope.folder()) = update(folder) {
        it + (collection to (it[collection]?.copy(changedAt = at) ?: Entry(at)))
    }

    fun markSynced(collection: String, at: Instant = Clock.System.now(), folder: String = scope.folder()) = update(folder) { entries ->
        entries[collection]?.let { entries + (collection to it.copy(syncedAt = at)) } ?: entries
    }

    /**
     * Records the server copy the local file is now based on. With [upToDate] the local file equals that copy
     * (nothing left to send); otherwise the collection is pending, also when the ledger did not know it or counted
     * it as sent.
     */
    fun recordServerCopy(
        collection: String,
        version: Int,
        baseKeys: Map<String, Set<String>>,
        remoteUpdatedAt: Instant,
        upToDate: Boolean,
        at: Instant = Clock.System.now(),
        folder: String = scope.folder()
    ) = update(folder) { entries ->
        val entry = entries[collection] ?: Entry(changedAt = at)
        entries + (collection to entry.copy(
            version = version,
            baseKeys = baseKeys,
            remoteUpdatedAt = remoteUpdatedAt,
            syncedAt = if (upToDate) at else entry.syncedAt.takeIf { entry.isPending }
        ))
    }

    /** Collections with local changes not yet on the server, sorted for a stable upload order. */
    fun pending(folder: String = scope.folder()): List<String> = entries(folder).filterValues { it.isPending }.keys.sorted()

    fun entries(folder: String = scope.folder()): Map<String, Entry> =
        scope.files.readText(path(folder))?.let { runCatching { PulseJson.decodeFromString(serializer, it) }.getOrNull() } ?: emptyMap()

    private fun update(folder: String, change: (Map<String, Entry>) -> Map<String, Entry>) {
        scope.files.writeText(path(folder), PulseJson.encodeToString(serializer, change(entries(folder))))
    }
}

/**
 * ISO 8601 with the full precision of the clock (whole-second files of earlier versions still parse):
 * an edit made in the same second as the last sync must still count as newer.
 */
internal object LedgerInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("PulseLedgerInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
