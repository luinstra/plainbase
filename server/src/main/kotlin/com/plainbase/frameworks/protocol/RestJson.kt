package com.plainbase.frameworks.protocol

import kotlinx.serialization.json.Json

/** The shared serializer for protocol DTOs; nullable fields remain present and defaults remain encoded. */
val RestJson: Json = Json {
    explicitNulls = true
    encodeDefaults = true
}
