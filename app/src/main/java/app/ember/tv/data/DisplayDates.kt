package app.ember.tv.data

import android.content.Context
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #325: every date and time Ember shows, in the TV's language and with its 12- or 24-hour clock,
 * so the guide, quick settings, backups and title pages agree.
 */
object DisplayDates {
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    private val locale: Locale get() = Locale.getDefault()

    /** The TV's clock setting; the language's own habit before Ember has started. */
    private fun timeFormat(): DateFormat =
        app?.let { android.text.format.DateFormat.getTimeFormat(it) } ?: DateFormat.getTimeInstance(DateFormat.SHORT, locale)

    private fun pattern(skeleton: String): SimpleDateFormat =
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    /** "9:51 PM" or "21:51". */
    fun time(ms: Long): String = timeFormat().format(Date(ms))

    /** "Mon 16 Nov" (or the language's order). */
    fun dayMonth(ms: Long): String = pattern("EEEdMMM").format(Date(ms))

    /** "Mon" */
    fun weekday(ms: Long): String = pattern("EEE").format(Date(ms))

    /** "16 Nov 2026" (or the language's order). */
    fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(ms))

    /** "16 Nov 2026, 9:51 PM" */
    fun dateTime(ms: Long): String = date(ms) + ", " + time(ms)

    /** "Mon 16 Nov, 9:51 PM": within the coming days, where the year goes without saying. */
    fun dayTime(ms: Long): String = dayMonth(ms) + ", " + time(ms)

    /**
     * A release date from metadata ("2026-01-01" or "2026-01-01T00:00:00.000Z") as "1 Jan 2026";
     * anything that is not a full date (a year, "2019–2023") is shown as it came.
     */
    fun release(text: String?): String? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val match = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$""").matchEntire(value) ?: return value
        val (y, m, d) = match.destructured
        return runCatching {
            val calendar = java.util.Calendar.getInstance().apply { clear(); set(y.toInt(), m.toInt() - 1, d.toInt()) }
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(calendar.time)
        }.getOrDefault(value)
    }

    /** The year of a full release date, so a line that shows the date does not repeat the year. */
    fun releaseYear(text: String?): String? = text?.trim()?.let { Regex("""^(\d{4})-\d{2}-\d{2}""").find(it)?.groupValues?.get(1) }
}
