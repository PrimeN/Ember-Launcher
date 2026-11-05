package app.ember.tv.data

import kotlinx.serialization.json.*

/** Unknown fields travel with their owning model through copy/edit/import/export. Known edits win. */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object CollectionCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }
    private val collectionKeys = keys(MediaCollection.serializer().descriptor)
    private val folderKeys = keys(CollectionFolder.serializer().descriptor)
    private val sourceKeys = keys(CollectionSource.serializer().descriptor)
    private fun keys(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) = (0 until descriptor.elementsCount).map(descriptor::getElementName).toSet()
    fun decode(text: String): List<MediaCollection> {
        val root = json.parseToJsonElement(text)
        return (if (root is JsonArray) root else JsonArray(listOf(root))).map { entry ->
            val obj = entry.jsonObject
            json.decodeFromJsonElement<MediaCollection>(obj).copy(extensionFields = obj.filterKeys { it !in collectionKeys },
                folders = obj["folders"]?.jsonArray.orEmpty().map(::folder))
        }
    }
    private fun folder(entry: JsonElement): CollectionFolder {
        val obj = entry.jsonObject
        return json.decodeFromJsonElement<CollectionFolder>(obj).copy(extensionFields = obj.filterKeys { it !in folderKeys },
            sources = obj["sources"]?.takeUnless { it is JsonNull }?.jsonArray?.map(::source),
            catalogSources = obj["catalogSources"]?.jsonArray.orEmpty().map(::source))
    }
    private fun source(entry: JsonElement): CollectionSource {
        val obj = entry.jsonObject
        return json.decodeFromJsonElement<CollectionSource>(obj).copy(extensionFields = obj.filterKeys { it !in sourceKeys })
    }
    fun sourceElement(value: CollectionSource) = JsonObject(value.extensionFields + json.encodeToJsonElement(value).jsonObject)
    private fun folderElement(value: CollectionFolder) = JsonObject(value.extensionFields + json.encodeToJsonElement(value).jsonObject + buildMap {
        value.sources?.let { put("sources", JsonArray(it.map(::sourceElement))) }
        put("catalogSources", JsonArray(value.catalogSources.map(::sourceElement)))
    })
    fun encode(values: List<MediaCollection>) = JsonArray(values.map { value ->
        JsonObject(value.extensionFields + json.encodeToJsonElement(value).jsonObject + ("folders" to JsonArray(value.folders.map(::folderElement))))
    }).toString()
}
