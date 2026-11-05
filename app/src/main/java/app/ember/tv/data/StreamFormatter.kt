package app.ember.tv.data

import java.util.Locale
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Formats addon sources with user templates, in the custom-formatter syntax AIOStreams uses, so
 * templates written for AIOStreams work here too:
 *
 *   {stream.resolution}                      a value
 *   {stream.size::bytes}                     a value with modifiers
 *   {service.cached::istrue["▶ "||"▷ "]}     a check: the first text when true, the second when not
 *   {a::exists::and::b::~IMAX::isfalse[...]} checks combined with and, or, xor, neq, equal
 *
 * Branch texts can hold further {…} expressions. Lines that come out blank are dropped.
 * Anything that cannot be read is shown as it was typed, so mistakes are visible.
 */
object StreamFormatter {
    /** Values for `section.property`; null when unknown. */
    fun interface Fields {
        fun get(section: String, property: String): Any?
    }

    fun render(template: String, fields: Fields): String =
        renderPart(template, fields, 0)
            .split('\n')
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private const val MAX_DEPTH = 6
    private val COMPARATORS = setOf("and", "or", "xor", "neq", "equal", "left", "right")
    private val FIELD = Regex("""^\s*([A-Za-z_]+)\.([A-Za-z_]+)\s*$""")

    private class Fail : Exception()

    private class Expr(
        val operands: List<Pair<String, List<String>>>, // field (or 'literal'), modifiers
        val comparators: List<String>,
        val branches: List<String>?, // true, false[, absent]
    )

    private fun renderPart(t: String, f: Fields, depth: Int): String {
        if (depth > MAX_DEPTH) return t
        val out = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c != '{') {
                out.append(c)
                i++
                continue
            }
            val parsed = try {
                parseExpression(t, i)
            } catch (e: Fail) {
                null
            }
            if (parsed == null) {
                out.append(c)
                i++
                continue
            }
            val (expr, end) = parsed
            val text = try {
                evaluate(expr, f, depth)
            } catch (e: Fail) {
                t.substring(i, end)
            }
            out.append(text)
            i = end
        }
        return out.toString()
    }

    // ---- parsing ----

    /** Parses the expression whose `{` is at [start]; returns it and the index after its `}`. */
    private fun parseExpression(t: String, start: Int): Pair<Expr, Int>? {
        var j = start + 1
        var paren = 0
        while (j < t.length) {
            val c = t[j]
            when {
                c == '(' -> paren++
                c == ')' -> paren = maxOf(0, paren - 1)
                paren > 0 -> {}
                c == '{' || c == '\n' -> return null
                c == '}' -> return parseHead(t.substring(start + 1, j), null) to j + 1
                c == '[' && t.getOrNull(j + 1) == '"' -> {
                    val head = t.substring(start + 1, j)
                    val branches = ArrayList<String>()
                    var p = j + 2
                    while (true) {
                        val (text, after) = readBranch(t, p) ?: return null
                        branches += text
                        p = after // just past the closing quote
                        if (t.startsWith("||\"", p)) {
                            p += 3
                            if (branches.size >= 3) return null
                            continue
                        }
                        if (t.startsWith("]}", p)) break
                        return null
                    }
                    if (branches.size < 2) return null
                    return parseHead(head, branches) to p + 2
                }
            }
            j++
        }
        return null
    }

    /** Reads a branch text up to its closing quote (outside nested braces). Handles \" escapes. */
    private fun readBranch(t: String, from: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var depth = 0
        var k = from
        while (k < t.length) {
            val c = t[k]
            if (c == '\\' && t.getOrNull(k + 1) == '"') {
                sb.append('"')
                k += 2
                continue
            }
            if (c == '{') depth++
            if (c == '}') depth = maxOf(0, depth - 1)
            if (c == '"' && depth == 0) {
                val next = t.substring(k + 1).take(3)
                if (next.startsWith("||\"") || next.startsWith("]}")) return sb.toString() to k + 1
            }
            sb.append(c)
            k++
        }
        return null
    }

    private fun parseHead(head: String, branches: List<String>?): Expr {
        val tokens = splitTokens(head)
        if (tokens.isEmpty()) throw Fail()
        val operands = ArrayList<Pair<String, List<String>>>()
        val comparators = ArrayList<String>()
        var field: String? = null
        var mods = ArrayList<String>()
        var expectOperand = true
        for (tok in tokens) {
            if (expectOperand) {
                if (!FIELD.matches(tok) && !isLiteral(tok)) throw Fail()
                field = tok.trim()
                mods = ArrayList()
                expectOperand = false
            } else if (tok.trim().lowercase() in COMPARATORS) {
                operands += field!! to mods
                comparators += tok.trim().lowercase()
                expectOperand = true
            } else {
                mods += tok
            }
        }
        if (expectOperand) throw Fail()
        operands += field!! to mods
        return Expr(operands, comparators, branches)
    }

    private fun isLiteral(tok: String): Boolean {
        val s = tok.trim()
        return s.length >= 2 && ((s.first() == '\'' && s.last() == '\'') || (s.first() == '"' && s.last() == '"'))
    }

    /** Splits on `::`, but not inside a modifier's parentheses. */
    private fun splitTokens(head: String): List<String> {
        val out = ArrayList<String>()
        var paren = 0
        var last = 0
        var k = 0
        while (k < head.length) {
            val c = head[k]
            if (c == '(') paren++
            if (c == ')') paren = maxOf(0, paren - 1)
            if (paren == 0 && c == ':' && head.getOrNull(k + 1) == ':') {
                out += head.substring(last, k)
                k += 2
                last = k
                continue
            }
            k++
        }
        out += head.substring(last)
        return out
    }

    // ---- evaluation ----

    private fun evaluate(e: Expr, f: Fields, depth: Int): String {
        var value = operandValue(e.operands[0], f)
        for (i in e.comparators.indices) {
            val b = operandValue(e.operands[i + 1], f)
            value = when (e.comparators[i]) {
                "and" -> truthy(value) && truthy(b)
                "or" -> truthy(value) || truthy(b)
                "xor" -> truthy(value) != truthy(b)
                "neq" -> !same(value, b)
                "equal" -> same(value, b)
                "left" -> value
                else -> b
            }
        }
        val br = e.branches ?: return display(value)
        val chosen = when {
            value == null && br.size > 2 -> br[2]
            truthy(value) -> br[0]
            else -> br[1]
        }
        return renderPart(chosen, f, depth + 1)
    }

    private fun operandValue(op: Pair<String, List<String>>, f: Fields): Any? {
        val (src, mods) = op
        var v: Any? = if (isLiteral(src)) {
            src.trim().let { it.substring(1, it.length - 1) }
        } else {
            val m = FIELD.matchEntire(src) ?: throw Fail()
            normalise(f.get(m.groupValues[1].lowercase(), m.groupValues[2]))
        }
        for (mod in mods) v = modify(v, mod)
        return v
    }

    private fun normalise(v: Any?): Any? = when (v) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        is Collection<*> -> v.map { it.toString() }
        else -> v
    }

    private fun exists(v: Any?): Boolean = when (v) {
        null -> false
        is String -> v.isNotBlank()
        is List<*> -> v.isNotEmpty()
        else -> true
    }

    private fun truthy(v: Any?): Boolean = if (v is Boolean) v else exists(v)

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Double && b is Double -> a == b
        else -> a?.toString() == b?.toString()
    }

    private val KNOWN = setOf(
        "replace", "remove", "join", "upper", "lower", "title", "smallcaps", "truncate", "trim", "reverse", "length",
        "first", "last", "sort", "unique", "default", "string", "bytes", "bytes10", "rbytes", "rbytes10", "bytes2",
        "rbytes2", "sbytes", "sbytes10", "sbytes2", "bitrate", "rbitrate", "sbitrate", "comma", "time",
    )

    private val PREFIXES = listOf(">=", "<=", ">", "<", "=", "~", "$", "^")

    private fun modify(v: Any?, modRaw: String): Any? {
        val mod = modRaw.trim()
        val lower = mod.lowercase()
        when (lower) {
            "istrue" -> return v == true
            "isfalse" -> return v == false
            "exists" -> return exists(v)
        }
        PREFIXES.firstOrNull { mod.startsWith(it) }?.let { op ->
            val arg = unquote(mod.substring(op.length))
            return compare(v, op, arg)
        }
        val name = lower.substringBefore('(').trim()
        val args = if ('(' in mod) args(mod.substring(mod.indexOf('(') + 1, mod.lastIndexOf(')').takeIf { it > 0 } ?: mod.length)) else emptyList()
        if (name !in KNOWN) throw Fail()
        if (v == null && name != "default") return null
        return when (name) {
            "replace" -> {
                if (args.size != 2) throw Fail()
                mapText(v) { it.replace(args[0], args[1]) }
            }
            "remove" -> mapText(v) { s -> args.fold(s) { acc, a -> acc.replace(a, "") } }
            "join" -> if (v is List<*>) v.joinToString(args.firstOrNull() ?: ", ") else v
            "upper" -> mapText(v) { it.uppercase() }
            "lower" -> mapText(v) { it.lowercase() }
            "title" -> mapText(v) { s -> s.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } } }
            "smallcaps" -> mapText(v) { smallcaps(it) }
            "truncate" -> {
                val n = args.firstOrNull()?.toIntOrNull() ?: throw Fail()
                mapText(v) { if (it.length > n) it.take(n).trimEnd() + "…" else it }
            }
            "trim" -> mapText(v) { it.trim() }
            "reverse" -> if (v is List<*>) v.reversed() else mapText(v) { it.reversed() }
            "length" -> when (v) {
                is List<*> -> v.size.toDouble()
                else -> v.toString().length.toDouble()
            }
            "first" -> if (v is List<*>) v.firstOrNull() else v
            "last" -> if (v is List<*>) v.lastOrNull() else v
            "sort" -> if (v is List<*>) v.map { it.toString() }.sorted() else v
            "unique" -> if (v is List<*>) v.distinct() else v
            "default" -> if (exists(v)) v else args.firstOrNull().orEmpty()
            "string" -> display(v)
            "bytes", "bytes10", "rbytes", "rbytes10" -> num(v)?.let { bytes(it, 1000.0, name.startsWith("r")) }
            "bytes2", "rbytes2" -> num(v)?.let { bytes(it, 1024.0, name.startsWith("r")) }
            "sbytes", "sbytes10" -> num(v)?.let { smartUnits(it, 1000.0, listOf("B", "KB", "MB", "GB", "TB")) }
            "sbytes2" -> num(v)?.let { smartUnits(it, 1024.0, listOf("B", "KiB", "MiB", "GiB", "TiB")) }
            "bitrate", "rbitrate" -> num(v)?.let { bitrate(it, name == "rbitrate") }
            "sbitrate" -> num(v)?.let { smartUnits(it, 1000.0, listOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")) }
            "comma" -> num(v)?.let { String.format(Locale.US, "%,d", it.roundToLong()) }
            "time" -> num(v)?.let { duration(it) }
            else -> throw Fail()
        }
    }

    private fun compare(v: Any?, op: String, arg: String): Boolean {
        if (v == null) return false
        return when (op) {
            "~" -> if (v is List<*>) v.any { it.toString() == arg } else v.toString().contains(arg)
            "$" -> if (v is List<*>) v.firstOrNull()?.toString() == arg else v.toString().startsWith(arg)
            "^" -> if (v is List<*>) v.lastOrNull()?.toString() == arg else v.toString().endsWith(arg)
            "=" -> if (v is Double) v == arg.toDoubleOrNull() else display(v) == arg
            else -> {
                val a = (v as? Double) ?: (v as? List<*>)?.size?.toDouble() ?: v.toString().toDoubleOrNull() ?: return false
                val b = arg.toDoubleOrNull() ?: return false
                when (op) {
                    ">" -> a > b
                    ">=" -> a >= b
                    "<" -> a < b
                    else -> a <= b
                }
            }
        }
    }

    private fun mapText(v: Any?, fn: (String) -> String): Any? = when (v) {
        null -> null
        is List<*> -> v.map { fn(it.toString()) }
        is String -> fn(v)
        else -> fn(display(v))
    }

    /** Arguments of `name('a', 'b')`. Quotes may contain apostrophes, as in 'Director's Cut'. */
    private fun args(inner: String): List<String> {
        val s = inner.trim()
        if (s.isEmpty()) return emptyList()
        val q = s.first()
        if (q != '\'' && q != '"') return s.split(',').map { it.trim() }
        // Split at a closing quote followed by a comma and an opening quote.
        val parts = s.split(Regex("""$q\s*,\s*$q"""))
        return parts.mapIndexed { i, p ->
            var x = p
            if (i == 0) x = x.removePrefix(q.toString())
            if (i == parts.lastIndex) x = x.removeSuffix(q.toString())
            x
        }
    }

    private fun unquote(s: String): String {
        val t = s.trim()
        return if (t.length >= 2 && (t.first() == '\'' || t.first() == '"') && t.last() == t.first()) t.substring(1, t.length - 1) else t
    }

    private fun num(v: Any?): Double? = when (v) {
        is Double -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun display(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v == floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else trimNumber(v)
        is List<*> -> v.joinToString(", ")
        else -> v.toString()
    }

    private fun trimNumber(v: Double): String =
        String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun bytes(v: Double, k: Double, round: Boolean): String {
        if (v <= 0) return "0 B"
        val units = if (k == 1024.0) listOf("B", "KiB", "MiB", "GiB", "TiB") else listOf("B", "KB", "MB", "GB", "TB")
        val i = floor(ln(v) / ln(k)).toInt().coerceIn(0, units.lastIndex)
        val x = v / k.pow(i)
        return (if (round) x.roundToLong().toString() else trimNumber(x)) + " " + units[i]
    }

    private fun bitrate(v: Double, round: Boolean): String {
        if (v <= 0) return "0 bps"
        val units = listOf("bps", "Kbps", "Mbps", "Gbps", "Tbps")
        val i = floor(ln(v) / ln(1000.0)).toInt().coerceIn(0, units.lastIndex)
        val x = v / 1000.0.pow(i)
        return (if (round) x.roundToLong().toString() else trimNumber(x)) + " " + units[i]
    }

    /** Three significant digits at most: 152 Mbps, 15.2 Mbps, 1.52 Mbps. */
    private fun smartUnits(v: Double, k: Double, units: List<String>): String {
        if (v <= 0) return "0 " + units[0]
        val i = floor(ln(v) / ln(k)).toInt().coerceIn(0, units.lastIndex)
        val x = v / k.pow(i)
        val text = when {
            x >= 100 -> x.roundToLong().toString()
            x >= 10 -> String.format(Locale.US, "%.1f", x).removeSuffix(".0")
            else -> trimNumber(x)
        }
        return "$text ${units[i]}"
    }

    private fun duration(seconds: Double): String {
        val s = seconds.roundToLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private const val SMALL = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ"
    private fun smallcaps(s: String) = buildString {
        s.forEach { c ->
            val lc = c.lowercaseChar()
            append(if (lc in 'a'..'z') SMALL[lc - 'a'] else c)
        }
    }

    // ---- fields for one source ----

    /** Fields for an addon source. [runtimeMinutes] lets `stream.bitrate` be worked out from the size. */
    fun fieldsFor(i: StreamInfo, runtimeMinutes: Int? = null): Fields = Fields { section, property ->
        when (section) {
            "stream" -> streamField(i, property, runtimeMinutes)
            "service" -> when (property.lowercase()) {
                "cached" -> i.cached
                "id" -> i.service?.id
                "name" -> i.service?.name
                "shortname" -> i.service?.shortName
                else -> null
            }
            "addon", "config" -> when (property.lowercase()) {
                "name", "addonname" -> i.addonName
                else -> null
            }
            "tools" -> if (property.equals("newLine", true)) "\n" else null
            else -> null
        }
    }

    private fun streamField(i: StreamInfo, p: String, runtime: Int?): Any? = when (p.lowercase()) {
        "filename" -> i.filename
        "name" -> i.name
        "description" -> i.description
        "title" -> i.title
        "year" -> i.year
        "seasonepisode" -> i.seasonEpisode
        "resolution" -> i.resolution
        "quality" -> i.quality
        "encode" -> i.encode
        "visualtags" -> i.visualTags
        "audiotags" -> i.audioTags
        "audiochannels" -> i.audioChannels
        "languages" -> i.languages
        "size" -> i.sizeBytes
        "bitrate" -> if (i.sizeBytes != null && runtime != null && runtime > 0) i.sizeBytes * 8.0 / (runtime * 60.0) else null
        "seeders" -> i.seeders
        "indexer" -> i.indexer
        "releasegroup" -> i.releaseGroup
        "edition" -> i.edition
        "network" -> i.network
        "upscaled" -> i.upscaled
        "remastered" -> i.remastered
        "uncensored" -> i.uncensored
        "repack" -> i.repack
        "unrated" -> i.unrated
        "proxied" -> i.proxied
        "message" -> i.message
        "type" -> i.type
        "seasonpack" -> i.seasonEpisode.size == 1 && i.seasonEpisode[0].startsWith("S")
        else -> null
    }
}
