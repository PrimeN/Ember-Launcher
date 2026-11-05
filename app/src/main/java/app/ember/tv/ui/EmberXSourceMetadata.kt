package app.ember.tv.ui

import app.ember.tv.data.StreamOption

/** Also works when an add-on exposes its file size only through structured formatter metadata. */
internal fun emberXSourcePrimaryPills(source: StreamOption, runtime: String?): List<String> {
    val size = source.sizeGb ?: source.info?.sizeBytes?.let { it / 1_073_741_824.0 }
    return listOfNotNull(runtime?.takeIf { it.isNotBlank() }, size?.let { "%.1f GB".format(it) })
}
