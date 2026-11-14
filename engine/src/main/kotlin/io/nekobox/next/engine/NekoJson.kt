package io.nekobox.next.engine

import kotlinx.serialization.json.Json

/** Shared JSON codec. Unknown fields are kept readable across schema bumps. */
val NekoJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "protocol"
    prettyPrint = false
}
