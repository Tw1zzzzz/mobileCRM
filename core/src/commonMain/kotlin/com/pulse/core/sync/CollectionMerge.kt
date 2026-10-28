package com.pulse.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where the records of a collection document live. `""` = the document itself is the list. */
data class MergeSpec(val lists: Map<String, String>, val maps: Set<String> = emptySet())

/**
 * Per-record three-way merge of collection documents («Синхронизация данных»).
 *
 * Works on the raw JSON only, so every collection goes through one code path and unknown keys survive.
 * It never relies on key order or number formatting: the server keeps documents as jsonb, which reorders keys.
 * `merge` never throws; a document that has not the expected shape falls back to «the newer side wins whole».
 */
object CollectionMerge {
    /** Collection → record lists (field → key of each record) and map fields. */
    val specs: Map<String, MergeSpec> = mapOf(
        "sleep" to MergeSpec(mapOf("sessions" to "id")),
        "nutrition" to MergeSpec(mapOf("meals" to "id"), maps = setOf("water")),
        "wellbeing" to MergeSpec(mapOf("mood" to "id", "balance" to "id")),
        "cognitive" to MergeSpec(mapOf("results" to "id", "gameResults" to "id")),
        "calendar" to MergeSpec(mapOf("" to "id")),
        "fitness-state" to MergeSpec(mapOf("workouts" to "id"), maps = setOf("ratings")),
        "screen-time" to MergeSpec(mapOf("entries" to "date")),
        "lineups" to MergeSpec(mapOf("" to "id")),
        "game-goals" to MergeSpec(emptyMap()),
        "faceit" to MergeSpec(mapOf("snapshots" to "date")),
        "player-survey" to MergeSpec(mapOf("mindset" to "id")),
        "body-profile" to MergeSpec(emptyMap())
    )

    /** Record keys per list field, used as the merge base after a successful sync. */
    fun recordKeys(collection: String, document: JsonElement): Map<String, Set<String>> {
        val spec = specs[collection] ?: return emptyMap()
        return spec.lists.mapValues { (field, keyName) ->
            listAt(document, field)?.mapNotNullTo(LinkedHashSet()) { keyOf(it, keyName) } ?: emptySet()
        }
    }

    fun merge(
        collection: String,
        local: JsonElement,
        remote: JsonElement,
        base: Map<String, Set<String>>,
        localIsNewer: Boolean
    ): JsonElement {
        val newer = if (localIsNewer) local else remote
        val spec = specs[collection] ?: return newer

        spec.lists[""]?.let { keyName ->
            return if (local is JsonArray && remote is JsonArray) mergeRecords(local, remote, keyName, base[""].orEmpty()) else newer
        }
        if (local !is JsonObject || remote !is JsonObject) return newer

        val newerFields = if (localIsNewer) local else remote
        val merged = LinkedHashMap<String, JsonElement>()
        for (field in local.keys + remote.keys) {
            val value = when (field) {
                in spec.lists -> mergeListField(local[field], remote[field], spec.lists.getValue(field), base[field].orEmpty(), newerFields[field])
                in spec.maps -> mergeMapField(local[field], remote[field], newerFields[field])
                else -> newerFields[field]
            }
            if (value != null) merged[field] = value
        }
        return JsonObject(merged)
    }

    private fun listAt(document: JsonElement, field: String): JsonArray? =
        if (field.isEmpty()) document as? JsonArray else (document as? JsonObject)?.get(field) as? JsonArray

    private fun keyOf(record: JsonElement, keyName: String): String? {
        val key = (record as? JsonObject)?.get(keyName) as? JsonPrimitive ?: return null
        return if (key is JsonNull) null else key.content
    }

    /** A missing list counts as empty; a list field that is not an array follows the newer side whole. */
    private fun mergeListField(local: JsonElement?, remote: JsonElement?, keyName: String, baseKeys: Set<String>, newer: JsonElement?): JsonElement? {
        if (local == null && remote == null) return null
        if ((local != null && local !is JsonArray) || (remote != null && remote !is JsonArray)) return newer
        return mergeRecords(local ?: JsonArray(emptyList()), remote ?: JsonArray(emptyList()), keyName, baseKeys)
    }

    private fun mergeRecords(local: JsonArray, remote: JsonArray, keyName: String, baseKeys: Set<String>): JsonArray {
        val localKeys = local.mapNotNullTo(HashSet()) { keyOf(it, keyName) }
        val remoteKeys = remote.mapNotNullTo(HashSet()) { keyOf(it, keyName) }
        val merged = ArrayList<JsonElement>()
        val unkeyed = HashSet<JsonElement>()

        for (record in local) {
            val key = keyOf(record, keyName)
            when {
                key == null -> { merged += record; unkeyed += record }
                key in remoteKeys || key !in baseKeys -> merged += record
            }
        }
        val addedRemote = HashSet<String>()
        for (record in remote) {
            val key = keyOf(record, keyName)
            when {
                key == null -> if (unkeyed.add(record)) merged += record
                key in localKeys || key in baseKeys -> Unit
                addedRemote.add(key) -> merged += record
            }
        }
        return JsonArray(merged)
    }

    /**
     * Union of both maps, the local value wins on equal keys. A map is a JSON object or, as `ratings` of
     * «fitness-state» is written (Swift dictionary compatibility), a flat `[key, value, key, value, …]` array.
     */
    private fun mergeMapField(local: JsonElement?, remote: JsonElement?, newer: JsonElement?): JsonElement? {
        if (local == null && remote == null) return null
        val localMap = local?.let(::asMap)
        val remoteMap = remote?.let(::asMap)
        if ((local != null && localMap == null) || (remote != null && remoteMap == null)) return newer
        if (localMap != null && remoteMap != null && localMap.isFlatArray != remoteMap.isFlatArray) return newer

        val flat = (localMap ?: remoteMap)!!.isFlatArray
        val entries = LinkedHashMap<String, JsonElement>()
        localMap?.entries?.let { entries.putAll(it) }
        remoteMap?.entries?.forEach { (key, value) -> entries.putIfAbsent(key, value) }
        return if (flat) JsonArray(entries.flatMap { (key, value) -> listOf(JsonPrimitive(key), value) }) else JsonObject(entries)
    }

    private class MapView(val entries: Map<String, JsonElement>, val isFlatArray: Boolean)

    private fun asMap(element: JsonElement): MapView? = when (element) {
        is JsonObject -> MapView(element, isFlatArray = false)
        is JsonArray -> flatPairs(element)?.let { MapView(it, isFlatArray = true) }
        else -> null
    }

    private fun flatPairs(array: JsonArray): Map<String, JsonElement>? {
        if (array.size % 2 != 0) return null
        val pairs = LinkedHashMap<String, JsonElement>()
        for (index in array.indices step 2) {
            val key = array[index] as? JsonPrimitive ?: return null
            if (!key.isString) return null
            pairs.putIfAbsent(key.content, array[index + 1])
        }
        return pairs
    }
}
