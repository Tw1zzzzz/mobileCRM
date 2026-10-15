package com.pulse.core.util

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.uuid.Uuid

/** UUIDs as iOS writes them: upper-case "E621E1F8-C36C-495A-93FC-0C247A3E6E5F". */
object UuidSerializer : KSerializer<Uuid> {
    override val descriptor = PrimitiveSerialDescriptor("PulseUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Uuid) {
        encoder.encodeString(value.toString().uppercase())
    }

    override fun deserialize(decoder: Decoder): Uuid = Uuid.parse(decoder.decodeString().lowercase())
}
