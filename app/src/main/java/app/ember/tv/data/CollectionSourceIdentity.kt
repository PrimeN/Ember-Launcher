package app.ember.tv.data

import kotlinx.serialization.json.*

/** Encryption references and presentation labels are not source configuration identity. */
object CollectionSourceIdentity {
    fun key(source: CollectionSource, read: (String) -> String? = { null }): String {
        val resolved = runCatching { CollectionSecrets.resolve(source, read) }.getOrNull()
        val normalized = source.copy(title = null, extraArgs = resolved ?: source.extraArgs,
            secretArgsRef = if (resolved != null) null else source.secretArgsRef)
        return AddonAccess.key(canonical(CollectionCodec.sourceElement(normalized)).toString())
    }
    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
}
