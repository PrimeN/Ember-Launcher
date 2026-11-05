package app.ember.tv.light

import android.content.ContentResolver
import android.database.Cursor
import android.media.tv.TvContract
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Platform records are kept separate from Home's presentation and profile preferences. */
internal data class TvChannelRecord(
    val id: Long, val owner: String, val name: String, val preview: Boolean, val browsable: Boolean,
)

internal data class TvProgramRecord(
    val id: Long,
    val owner: String,
    val title: String,
    val link: String,
    val channelId: Long = -1,
    val watchType: Int = TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE,
    val browsable: Boolean = true,
    val duration: Long = 0,
    val position: Long = 0,
    val engagement: Long = 0,
    val weight: Long = 0,
    val thumbnail: String = "",
    val poster: String = "",
    val season: String = "",
    val episode: String = "",
    val episodeTitle: String = "",
    val synopsis: String = "",
)

internal interface TvProgramSource {
    suspend fun channels(): List<TvChannelRecord>
    suspend fun watchNext(): List<TvProgramRecord>
    suspend fun previews(): List<TvProgramRecord>
}

/** Reads public Android TV contracts only; a provider failure does not affect another table. */
internal class AndroidTvProgramSource(private val resolver: ContentResolver) : TvProgramSource {
    override suspend fun channels(): List<TvChannelRecord> = scan(
        TvContract.Channels.CONTENT_URI,
        arrayOf("_id", TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_DISPLAY_NAME,
            TvContract.Channels.COLUMN_TYPE, TvContract.Channels.COLUMN_BROWSABLE),
    ) { it.channel() }

    override suspend fun watchNext(): List<TvProgramRecord> = scan(
        TvContract.WatchNextPrograms.CONTENT_URI,
        programColumns + arrayOf(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
        "${TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC",
    ) { it.program() }

    override suspend fun previews(): List<TvProgramRecord> = scan(
        TvContract.PreviewPrograms.CONTENT_URI,
        programColumns + arrayOf(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_WEIGHT),
    ) { it.program() }

    private suspend fun <T> scan(uri: Uri, columns: Array<String>, order: String? = null, decode: (TvCursorFields) -> T): List<T> = coroutineScope {
        val cancellation = CancellationSignal()
        val cancelQuery = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { cancellation.cancel() }
        }
        val records = ArrayList<T>()
        try {
            resolver.query(uri, columns, null, null, order, cancellation)?.use { cursor ->
                val fields = TvCursorFields(cursor)
                while (records.size < ROW_LIMIT && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    records.add(decode(fields))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
        } finally {
            cancelQuery.cancel()
        }
        records
    }

    private companion object {
        const val ROW_LIMIT = 512
        val programColumns = arrayOf(
            "_id", TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE, TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_BROWSABLE, TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE, TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION,
        )
    }
}

/** Resolve column indices once per cursor, including optional columns omitted by firmware. */
internal class TvCursorFields(private val cursor: Cursor) {
    private val indices = HashMap<String, Int>()
    private fun index(column: String) = indices.getOrPut(column) { cursor.getColumnIndex(column) }
    private fun text(column: String, limit: Int): String {
        val index = index(column)
        if (index < 0) return ""
        return try {
            if (cursor.isNull(index)) "" else cursor.getString(index).orEmpty().take(limit)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
    }
    private fun number(column: String, fallback: Long = 0): Long {
        val index = index(column)
        if (index < 0) return fallback
        return try {
            if (cursor.isNull(index)) fallback else cursor.getLong(index)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { fallback }
    }
    fun channel() = TvChannelRecord(
        number("_id", -1), text(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, 512),
        text(TvContract.Channels.COLUMN_DISPLAY_NAME, 240),
        text(TvContract.Channels.COLUMN_TYPE, 64) == TvContract.Channels.TYPE_PREVIEW,
        number(TvContract.Channels.COLUMN_BROWSABLE, 1) != 0L,
    )
    fun program() = TvProgramRecord(
        id = number("_id", -1), owner = text(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, 512),
        title = text(TvContract.PreviewPrograms.COLUMN_TITLE, 240),
        link = text(TvContract.PreviewPrograms.COLUMN_INTENT_URI, 8192),
        channelId = number(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, -1),
        watchType = number(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE).let { if (it in 0L..3L) it.toInt() else -1 },
        browsable = number(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1) != 0L,
        duration = number(TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS).coerceAtLeast(0),
        position = number(TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS).coerceAtLeast(0),
        engagement = number(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
        weight = number(TvContract.PreviewPrograms.COLUMN_WEIGHT),
        thumbnail = text(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, 8192),
        poster = text(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, 8192),
        season = text(TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER, 64),
        episode = text(TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, 64),
        episodeTitle = text(TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE, 240),
        synopsis = text(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, 320),
    )
}
