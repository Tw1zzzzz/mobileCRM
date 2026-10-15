package com.pulse.core.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * ISO 8601 in UTC with whole seconds ("2026-10-15T19:00:00Z") — the format iOS `JSONEncoder.iso8601`
 * writes and, importantly, the only one it can read back (no fractional seconds).
 */
object IsoInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("PulseInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(Instant.fromEpochSeconds(value.epochSeconds).toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
