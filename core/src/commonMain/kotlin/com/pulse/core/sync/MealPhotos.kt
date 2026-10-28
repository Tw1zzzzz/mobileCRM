package com.pulse.core.sync

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Meal photos travel apart from the nutrition document (`PUT /photos/{meal_id}`), so a month of meals stays under the
 * request limit. In the synced copy a meal's base64 `photo` is replaced by `"photoRef": true`; the phone's own file
 * keeps its photos. Works on raw JSON like [CollectionMerge]: unknown keys and key order survive.
 */
object MealPhotos {
    private const val MEALS = "meals"
    private const val ID = "id"
    private const val PHOTO = "photo"
    private const val PHOTO_REF = "photoRef"

    /** The copy sent to the server: every photo → absent, with `"photoRef": true` in its place. */
    fun strip(nutrition: JsonElement): JsonElement = mapMeals(nutrition) { meal ->
        if (photoOf(meal) == null) return@mapMeals meal
        JsonObject(meal.entries.associate { (key, value) -> if (key == PHOTO) PHOTO_REF to JsonPrimitive(true) else key to value })
    }

    /** Meal id → base64 JPEG of every meal that has a photo. */
    fun photos(nutrition: JsonElement): Map<String, String> = meals(nutrition).mapNotNull { meal ->
        val id = idOf(meal) ?: return@mapNotNull null
        photoOf(meal)?.let { id to it }
    }.toMap()

    /** The phone's copy: `photoRef` markers are dropped, meals listed in [photos] get their photo back. */
    fun restore(nutrition: JsonElement, photos: Map<String, String>): JsonElement = mapMeals(nutrition) { meal ->
        val photo = idOf(meal)?.let(photos::get)
        if (photo == null && PHOTO_REF !in meal) return@mapMeals meal
        val restored = LinkedHashMap<String, JsonElement>()
        for ((key, value) in meal) {
            when (key) {
                PHOTO_REF -> if (photo != null) restored[PHOTO] = JsonPrimitive(photo)
                PHOTO -> restored[PHOTO] = photo?.let(::JsonPrimitive) ?: value
                else -> restored[key] = value
            }
        }
        if (photo != null && PHOTO !in restored) restored[PHOTO] = JsonPrimitive(photo)
        JsonObject(restored)
    }

    /** Ids of the meals whose photo lives on the server (`"photoRef": true`). */
    internal fun referenced(nutrition: JsonElement): Set<String> = meals(nutrition)
        .filter { (it[PHOTO_REF] as? JsonPrimitive)?.content == "true" }
        .mapNotNullTo(LinkedHashSet(), ::idOf)

    /** Ids of every meal in the document. */
    internal fun mealIds(nutrition: JsonElement): Set<String> = meals(nutrition).mapNotNullTo(LinkedHashSet(), ::idOf)

    private fun meals(nutrition: JsonElement): List<JsonObject> =
        ((nutrition as? JsonObject)?.get(MEALS) as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()

    private fun mapMeals(nutrition: JsonElement, transform: (JsonObject) -> JsonObject): JsonElement {
        val document = nutrition as? JsonObject ?: return nutrition
        val meals = document[MEALS] as? JsonArray ?: return nutrition
        return JsonObject(document + (MEALS to JsonArray(meals.map { (it as? JsonObject)?.let(transform) ?: it })))
    }

    private fun idOf(meal: JsonObject): String? = (meal[ID] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun photoOf(meal: JsonObject): String? = (meal[PHOTO] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
