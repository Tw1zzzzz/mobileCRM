package com.pulse.core.util

import kotlinx.serialization.json.Json

/** JSON settings shared by storage and the API: compatible with the iOS documents. */
val PulseJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}
