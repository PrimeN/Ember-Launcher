package app.ember.tv.data

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex, identical to `joinToString("") { "%02x".format(it) }` without a String.format per byte (#57). */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        out[i * 2] = HEX[v ushr 4]; out[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
}
