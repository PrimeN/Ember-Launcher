package app.ember.tv.mediaserver

import app.ember.tv.data.Meta
import app.ember.tv.data.Quality
import app.ember.tv.data.StreamOption
import app.ember.tv.tracker.Http
import app.ember.tv.tracker.arr
import app.ember.tv.tracker.int
import app.ember.tv.tracker.list
import app.ember.tv.tracker.long
import app.ember.tv.tracker.obj
import app.ember.tv.tracker.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Jellyfin and Emby share an API:
 * sign in with AuthenticateByName, read Resume and Latest, play from /Videos/{id}/stream.
 */
class JellyfinClient(private val accounts: Accounts,private val http:ServerHttp=ServerHttp()) {

    private fun authValue(token: String?) =
        "MediaBrowser Client=\"Ember TV\", Device=\"Android TV\", DeviceId=\"${accounts.deviceId()}\", Version=\"0.7\"" +
            (token?.let { ", Token=\"$it\"" } ?: "")

    private fun headers(token: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        // Emby reads X-Emby-Authorization; current Jellyfin prefers Authorization. Send both.
        put("Authorization", authValue(token))
        put("X-Emby-Authorization", authValue(token))
        token?.let { put("X-Emby-Token", it) }
    }

    /**
     * Accepts "192.168.1.5:8096", "jellyfin.example.com" or a full URL. Without a scheme,
     * home-network addresses get http and internet names get https, so passwords for
     * servers reached over the internet are not sent in the clear.
     */
    fun normalize(raw:String):String = ServerAddress.normalize(raw)

    suspend fun login(kind: String, rawUrl: String, user: String, password: String): ServerAccount {
        val url = normalize(rawUrl)
        val r = http.post(
            "$url/Users/AuthenticateByName",
            buildJsonObject {
                put("Username", user)
                put("Pw", password)
            },
            headers(null),
        )
        val token = r.str("AccessToken") ?: error("Sign-in failed")
        val userId = r.obj("User").str("Id") ?: error("Sign-in failed")
        val name = serverResult { http.get("$url/System/Info/Public", headers(null)).str("ServerName") }.getOrNull()
            ?: kind.replaceFirstChar { it.uppercase() }
        return ServerAccount(UUID.randomUUID().toString(), kind, name, url, userId, token, r.obj("User").str("Name") ?: user)
    }
    suspend fun users(a: ServerAccount): List<ServerUser> = http.get("${a.url}/Users/Public", headers(a.token)).list().mapNotNull { item ->
        val id = item.str("Id") ?: return@mapNotNull null
        val name = item.str("Name") ?: return@mapNotNull null
        ServerUser(id, name, item.str("HasPassword") == "true")
    }

    suspend fun rows(a: ServerAccount, onFailure: (Throwable) -> Unit = {}): List<ServerRow> = coroutineScope {
        val label = a.kind.replaceFirstChar { it.uppercase() }
        val fields = "Fields=ProviderIds,Overview&EnableImageTypes=Primary,Backdrop,Thumb,Logo"
        val favorites = async { serverResult {
            http.get("${a.url}/Users/${a.userId}/Items?Recursive=true&IncludeItemTypes=Movie,Series&Filters=IsFavorite&Limit=100&$fields", headers(a.token))
                .arr("Items").list().mapNotNull { if (it.str("Type") == "Series") seriesMeta(a, it) else toMeta(a, it) }
        }.getOrDefault(emptyList()) }
        val resume = serverResult {
            http.get("${a.url}/Users/${a.userId}/Items/Resume?Limit=20&MediaTypes=Video&$fields", headers(a.token)).arr("Items").list()
        }.onFailure { onFailure(it) }.getOrDefault(emptyList())
        val latest = serverResult {
            http.get(
                "${a.url}/Users/${a.userId}/Items/Latest?Limit=30&IncludeItemTypes=Movie,Episode&GroupItems=false&$fields",
                headers(a.token),
            ).list()
        }.onFailure { onFailure(it) }.getOrDefault(emptyList())
        val out = ArrayList<ServerRow>()
        resume.mapNotNull { toMeta(a, it) }.takeIf { it.isNotEmpty() }?.let { out += ServerRow("Continue on ${a.name}", label, it, inProgress = true,id="${a.kind}:${a.key}:resume") }
        latest.mapNotNull { toMeta(a, it) }.takeIf { it.isNotEmpty() }?.let { out += ServerRow("Recently added on ${a.name}", label, it,id="${a.kind}:${a.key}:recent") }
        val favoriteItems = favorites.await()
        if (favoriteItems.isNotEmpty()) out += ServerRow("Favorites on ${a.name}", label, favoriteItems, id = "${a.kind}:${a.key}:watchlist")
        out
    }

    /** Movie and TV libraries (views) on a Jellyfin or Emby server, the newest 60 of each. */
    suspend fun libraries(a: ServerAccount, onFailure: (Throwable) -> Unit = {}): List<ServerLibrary> {
        val views = http.get("${a.url}/Users/${a.userId}/Views", headers(a.token)).arr("Items").list()
        val out = ArrayList<ServerLibrary>()
        for (v in views) {
            val kind = v.str("CollectionType") ?: continue
            if (kind != "movies" && kind != "tvshows") continue
            val id = v.str("Id") ?: continue
            val ref=ServerRef(a.kind,a.key,"library:$kind:$id").value
            val result=serverResult { browse(a,"library:$kind:$id",ServerFilter(),0) }.onFailure { onFailure(it) }.getOrNull() ?: continue
            out += ServerLibrary(a.name,v.str("Name")?:kind,if(kind=="movies")"movies" else "shows",result.items,ref,ref,result.total)

        }
        return out
    }

    /** A series: opens as a normal title by its ids; its episodes on the server are found there. */
    private fun logo(a:ServerAccount,item:JsonElement,id:String):String? {
        val target=if(item.obj("ImageTags").str("Logo")!=null)id else item.str("ParentLogoItemId")
        return target?.let {ServerMetadata.artwork(ServerRef(a.kind,a.key,id),"/Items/$it/Images/Logo?maxWidth=720")}
    }
    private fun backdrop(a:ServerAccount,item:JsonElement,id:String):String? {
        val ownBackdrop=item.arr("BackdropImageTags").list().isNotEmpty()
        val banner=!ownBackdrop && item.obj("ImageTags").str("Banner")!=null
        val target=if(ownBackdrop || banner)id else item.str("ParentBackdropItemId") ?: item.str("SeriesId") ?: id
        return ServerMetadata.artwork(ServerRef(a.kind,a.key,id),"/Items/$target/Images/${if(banner)"Banner" else "Backdrop"}?maxWidth=1280")
    }

    private fun seriesMeta(a: ServerAccount, it: JsonElement): Meta? {
        val id = it.str("Id") ?: return null
        val name = it.str("Name") ?: return null
        val providers = it.obj("ProviderIds")
        val imdb = providers.str("Imdb")
        val key = "api_key=${a.token}"
        return Meta(
            id = "${a.kind}show:${a.key}:$id",
            serverRef=ServerRef(a.kind,a.key,id).value,
            type = "series",
            name = name,
            poster = ServerMetadata.artwork(ServerRef(a.kind,a.key,id),"/Items/$id/Images/Primary?fillHeight=450&quality=90"),
            background = backdrop(a,it,id),
            logo = logo(a,it,id),
            description = it.str("Overview"),
            releaseInfo = it.int("ProductionYear")?.toString(),
            imdbId = imdb,
            tmdbId = providers.str("Tmdb")?.toLongOrNull(),
            serverName = a.name,
            watched = it.obj("UserData").str("Played")?.toBooleanStrictOrNull(),
            ageRating = it.str("OfficialRating"),
        )
    }

    private fun toMeta(a: ServerAccount, it: JsonElement): Meta? {
        val type = it.str("Type")
        if (type != "Movie" && type != "Episode" && type != "BoxSet") return null
        val id = it.str("Id") ?: return null
        val isEpisode = type == "Episode"
        val name = (if (isEpisode) it.str("SeriesName") else null) ?: it.str("Name") ?: return null
        val key = "api_key=${a.token}"
        val posterItem = (if (isEpisode) it.str("SeriesId") else null) ?: it.str("PrimaryImageItemId") ?: it.str("ParentPrimaryImageItemId") ?: id
        val runtime = it.long("RunTimeTicks")
        val position = it.obj("UserData").long("PlaybackPositionTicks")
        val providers = it.obj("ProviderIds")
        val season = it.int("ParentIndexNumber")
        val episode = it.int("IndexNumber")
        return Meta(
            id = "${a.kind}:${a.key}:$id",
            type = if (type == "BoxSet") "collection" else if (isEpisode) "series" else "movie",
            name = name,
            poster = ServerMetadata.artwork(ServerRef(a.kind,a.key,id),"/Items/$posterItem/Images/Primary?fillHeight=450&quality=90"),
            background = backdrop(a,it,id),
            logo = logo(a,it,id),
            description = if (isEpisode) {
                "S$season · E$episode · ${it.str("Name").orEmpty()}" + (it.str("Overview")?.let { o -> "\n\n$o" } ?: "")
            } else {
                it.str("Overview")
            },
            releaseInfo = it.int("ProductionYear")?.toString(),
            runtime = runtime?.takeIf { it > 0 }?.let { "${kotlin.math.ceil(it / 600_000_000.0).toInt()} min" },
            progress = if (runtime != null && position != null && runtime > 0) position.toFloat() / runtime else null,
            // Episode ids would point at the episode, not the show, so only movies carry them for trackers.
            imdbId = if (isEpisode) null else providers.str("Imdb"),
            tmdbId = if (isEpisode) null else providers.str("Tmdb")?.toLongOrNull(),
            season = if (isEpisode) season else null,
            episode = if (isEpisode) episode else null,
            badge = if (isEpisode) it.str("Name") else null,
            episodeThumbnail = if (isEpisode) ServerMetadata.artwork(ServerRef(a.kind,a.key,id),"/Items/$id/Images/Primary?maxWidth=640&quality=90") else null,
            playUrl = null,
            serverSeriesRef = it.str("SeriesId")?.let { series->ServerRef(a.kind,a.key,series).value },
            serverRef = "${a.kind}|${a.key}|$id",
            serverName = a.name,
            watched = it.obj("UserData").str("Played")?.toBooleanStrictOrNull(),
            ageRating = it.str("OfficialRating"),
        )
    }

    /**
     * Copies of [q] on this server, matched by IMDb or TMDB id, or by title and year when the
     * server has no ids. For series, the episode in [q]. One row per version of the file.
     */
    suspend fun find(a: ServerAccount, q: ServerQuery): List<StreamOption> {
        val term = URLEncoder.encode(q.title, "UTF-8")
        val type = if (q.movie) "Movie" else "Series"
        val found = http.get(
            "${a.url}/Users/${a.userId}/Items?searchTerm=$term&IncludeItemTypes=$type&Recursive=true" +
                "&Fields=ProviderIds,ProductionYear&Limit=20",
            headers(a.token),
        ).arr("Items").list()
        val match = found.firstOrNull { m ->
            val p = m.obj("ProviderIds")
            (q.imdbId != null && p.str("Imdb").equals(q.imdbId, ignoreCase = true)) ||
                (q.tmdbId != null && p.str("Tmdb") == q.tmdbId.toString())
        } ?: found.firstOrNull { m ->
            val p = m.obj("ProviderIds")
            ((p.str("Imdb") == null && p.str("Tmdb") == null) || !q.hasIds) &&
                ServerQuery.sameTitle(m.str("Name"), q.title) && ServerQuery.sameYear(m.int("ProductionYear"), q.year)
        } ?: return emptyList()
        val matchId = match.str("Id") ?: return emptyList()

        val item: JsonElement = if (q.movie) {
            http.get("${a.url}/Users/${a.userId}/Items/$matchId?Fields=MediaSources", headers(a.token))
        } else {
            http.get(
                "${a.url}/Shows/$matchId/Episodes?UserId=${a.userId}&Season=${q.season}&Fields=MediaSources",
                headers(a.token),
            ).arr("Items").list().firstOrNull { it.int("ParentIndexNumber") == q.season && it.int("IndexNumber") == q.episode }
                ?: return emptyList()
        }
        return options(a, item)
    }

    /** File versions of one movie or episode, by its item id. */
    /** IMDb and TMDB ids of an item, or of its show when the item is an episode. */
    suspend fun titleIds(a: ServerAccount, itemId: String): Pair<String?, Long?> {
        val item = http.get("${a.url}/Users/${a.userId}/Items/$itemId", headers(a.token))
        val target = if (item.str("Type") == "Episode") {
            val series = item.str("SeriesId") ?: return null to null
            http.get("${a.url}/Users/${a.userId}/Items/$series", headers(a.token))
        } else {
            item
        }
        val p = target.obj("ProviderIds")
        return p.str("Imdb")?.takeIf { it.startsWith("tt") } to p.str("Tmdb")?.toLongOrNull()
    }

    suspend fun sourcesFor(a: ServerAccount, itemId: String): List<StreamOption> =
        options(a, http.get("${a.url}/Users/${a.userId}/Items/$itemId?Fields=MediaSources", headers(a.token)))

    private fun options(a: ServerAccount, item: JsonElement): List<StreamOption> {
        val itemId = item.str("Id") ?: return emptyList()
        val sources = item.arr("MediaSources").list().ifEmpty { listOf(item) }
        val key = "api_key=${a.token}"
        return sources.map { src ->
            val streams = src.arr("MediaStreams").list()
            val video = streams.firstOrNull { it.str("Type") == "Video" }
            val audio = streams.firstOrNull { it.str("Type") == "Audio" && it.str("IsDefault") == "true" }
                ?: streams.firstOrNull { it.str("Type") == "Audio" }
            val sourceId = src.str("Id")
            val ref=ServerRef(a.kind,a.key,itemId)
            val uri="${a.url}/Videos/$itemId/stream?static=true"+(sourceId?.let{"&MediaSourceId=$it"}?:"")+"&$key"
            val subtitles=streams.filter { it.str("Type")=="Subtitle"&&(it.str("IsExternal")=="true"||it.str("DeliveryUrl")!=null) }.mapNotNull { t->
                val path=t.str("DeliveryUrl")?:t.int("Index")?.let { "/Videos/$itemId/${sourceId?:itemId}/Subtitles/$it/Stream.${t.str("Codec")?:"srt"}" }?:return@mapNotNull null
                if(!path.startsWith('/')||path.startsWith("//"))return@mapNotNull null
                app.ember.tv.data.SubtitleOption(a.name,t.str("Language")?:"und",
                    a.url+ServerMetadata.cleanPath(path)+(if('?' in ServerMetadata.cleanPath(path))"&" else "?")+key, forced=t.str("IsForced")=="true",hearingImpaired=t.str("IsHearingImpaired")=="true",server=true)
            }
            val playback=ServerPlayback(ref,sourceId?:itemId,listOf(ServerPart(sourceId?:itemId,uri,src.long("RunTimeTicks")?.div(10000)?:item.long("RunTimeTicks")?.div(10000),subtitles)),
                item.long("RunTimeTicks")?.div(10000),item.str("SeriesId")?.let { ServerRef(a.kind,a.key,it).value })
            StreamOption(
                addonName = a.name,
                name = a.name,
                title = src.str("Path")?.substringAfterLast('/')?.substringAfterLast('\\') ?: src.str("Name") ?: "Play from ${a.name}",
                url = uri,
                serverPlayback = playback,
                infoHash = null,
                fileIdx = null,
                quality = Quality.fromSize(video.int("Width"), video.int("Height")),
                hdr = when (video.str("VideoRangeType") ?: video.str("VideoRange")) {
                    null, "", "SDR", "Unknown" -> ""
                    "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR" -> "DV"
                    "HDR10Plus" -> "HDR10+"
                    "HDR10" -> "HDR10"
                    else -> "HDR"
                },
                codec = when (video.str("Codec")?.lowercase()) {
                    "hevc", "h265" -> "HEVC"
                    "h264" -> "H.264"
                    "av1" -> "AV1"
                    "vp9" -> "VP9"
                    else -> ""
                },
                audio = ServerQuery.audioLabel(audio.str("Codec"), audio.int("Channels")),
                source = "",
                releaseGroup = "",
                sizeGb = src.long("Size")?.let { it / 1_073_741_824.0 },
                seeders = null,
                cached = false,
                serverRef = "${a.kind}|${a.key}|$itemId",
            )
        }
    }

    /**
     * Intro, recap and credits: Jellyfin 10.10+ media segments (from intro-skipper plugins), else
     * chapter markers (Emby's IntroStart/IntroEnd/CreditsStart).
     */
    suspend fun qualities(a:ServerAccount):List<ServerQuality> {
        val policy=http.get("${a.url}/Users/${a.userId}",headers(a.token)).obj("Policy")
        return if(policy.str("EnableVideoPlaybackTranscoding")=="true")ServerQuality.entries.toList() else listOf(ServerQuality.ORIGINAL)
    }
    suspend fun playback(a:ServerAccount,itemId:String,sourceId:String,quality:ServerQuality):StreamOption {
        val original=sourcesFor(a,itemId).firstOrNull{it.serverPlayback?.sourceId==sourceId}?:error("This version is unavailable.")
        if(quality==ServerQuality.ORIGINAL)return original
        check(quality in qualities(a)) { "This account does not permit conversion." }
        val profile=kotlinx.serialization.json.Json.parseToJsonElement("""{"Name":"Ember TV","MaxStreamingBitrate":${quality.bitrate},"DirectPlayProfiles":[],"TranscodingProfiles":[{"Container":"ts","Type":"Video","VideoCodec":"h264","AudioCodec":"aac","Protocol":"hls","MaxAudioChannels":"2","MinSegments":1,"BreakOnNonKeyFrames":true}]}""")
        val response=http.post("${a.url}/Items/$itemId/PlaybackInfo",buildJsonObject{
            put("UserId",a.userId);put("MediaSourceId",sourceId);put("MaxStreamingBitrate",quality.bitrate)
            put("EnableDirectPlay",false);put("EnableDirectStream",true);put("EnableTranscoding",true);put("DeviceProfile",profile)
        },headers(a.token))
        val selected=response.arr("MediaSources").list().firstOrNull{it.str("Id")==sourceId}?:error("The selected version was not returned.")
        val path=selected.str("TranscodingUrl")?:selected.str("DirectStreamUrl")?:error("The server did not offer a compatible stream.")
        val base=a.url.toHttpUrl()
        val url=base.resolve(path)?:error("Invalid stream address.")
        check(url.scheme==base.scheme&&url.host==base.host&&url.port==base.port) { "Cross-origin stream conversion was refused." }
        val authenticated=url.newBuilder().setQueryParameter("api_key",a.token).setQueryParameter("MaxWidth",quality.width.toString()).setQueryParameter("MaxHeight",quality.height.toString()).build().toString()
        val session=response.str("PlaySessionId")?:error("The server returned no playback session.")
        val method=if(selected.str("TranscodingUrl")!=null)"Transcode" else "DirectStream"
        val transport=original.serverPlayback!!.copy(parts=listOf(ServerPart(sourceId,authenticated,original.serverPlayback.durationMs)),sessionId=session,method=method,quality=quality)
        return original.copy(url=authenticated,quality=if(method=="Transcode")quality.height.toString()+"p max" else original.quality,hdr=if(method=="Transcode")"" else original.hdr,codec=if(method=="Transcode")"H264" else original.codec,audio=if(method=="Transcode")"AAC" else original.audio,title=quality.label+" · "+method,serverPlayback=transport)
    }
    suspend fun stopTranscode(a:ServerAccount,transport:ServerPlayback) {
        if(transport.method!="Transcode")return
        http.ack("${a.url}/Videos/ActiveEncodings?DeviceId=${URLEncoder.encode(accounts.deviceId(),"UTF-8")}&PlaySessionId=${URLEncoder.encode(transport.sessionId,"UTF-8")}",headers(a.token),"DELETE")
    }

    suspend fun markers(a: ServerAccount, itemId: String): List<Marker> {
        val segments = serverResult {
            http.get(
                "${a.url}/MediaSegments/$itemId?includeSegmentTypes=Intro&includeSegmentTypes=Recap&includeSegmentTypes=Outro",
                headers(a.token),
            ).arr("Items").list().mapNotNull { m ->
                val kind = when (m.str("Type")) { "Intro" -> "intro"; "Recap" -> "recap"; "Outro" -> "credits"; else -> return@mapNotNull null }
                Marker(kind, (m.long("StartTicks") ?: return@mapNotNull null) / 10_000, (m.long("EndTicks") ?: return@mapNotNull null) / 10_000)
            }
        }.getOrDefault(emptyList())
        if (segments.isNotEmpty()) return segments
        val item = http.get("${a.url}/Users/${a.userId}/Items/$itemId?Fields=Chapters", headers(a.token))
        val chapters = item.arr("Chapters").list()
        val runtime = (item.long("RunTimeTicks") ?: 0L) / 10_000
        fun at(type: String) = chapters.firstOrNull { it.str("MarkerType") == type }?.long("StartPositionTicks")?.div(10_000)
        val out = ArrayList<Marker>()
        val introStart = at("IntroStart")
        val introEnd = at("IntroEnd")
        if (introStart != null && introEnd != null) out += Marker("intro", introStart, introEnd)
        at("CreditsStart")?.let { c -> out += Marker("credits", c, if (runtime > c) runtime else c + 60_000) }
        return out
    }

    suspend fun chapters(a: ServerAccount, itemId: String): List<Chapter> {
        val item = http.get("${a.url}/Users/${a.userId}/Items/$itemId?Fields=Chapters", headers(a.token))
        return item.arr("Chapters").list().mapIndexedNotNull { i, c ->
            // Emby's intro and credits markers are not chapters to jump to.
            if (c.str("MarkerType")?.let { it != "Chapter" } == true) return@mapIndexedNotNull null
            Chapter(c.str("Name")?.takeIf { it.isNotBlank() } ?: "Chapter ${i + 1}", (c.long("StartPositionTicks") ?: return@mapIndexedNotNull null) / 10_000)
        }
    }

    /** Seek thumbnails (Jellyfin 10.9+ trickplay): tiles of thumbnails, the size closest to 320 px wide. */
    suspend fun trickplay(a: ServerAccount, itemId: String, selectedSource:String?=null): Trickplay? {
        val item = http.get("${a.url}/Users/${a.userId}/Items/$itemId?Fields=Trickplay", headers(a.token))
        val bySource = item.obj("Trickplay") ?: return null
        val (sourceId, sizes) = (if (selectedSource != null) bySource.entries.firstOrNull { it.key == selectedSource } else bySource.entries.firstOrNull()) ?: return null
        val pick = (sizes as? kotlinx.serialization.json.JsonObject)?.entries
            ?.minByOrNull { kotlin.math.abs((it.key.toIntOrNull() ?: 0) - 320) } ?: return null
        val t = pick.value
        val width = t.int("Width") ?: return null
        return Trickplay(
            intervalMs = t.long("Interval") ?: 10_000L,
            count = t.int("ThumbnailCount") ?: return null,
            cols = t.int("TileWidth") ?: 10,
            rows = t.int("TileHeight") ?: 10,
            width = width,
            height = t.int("Height") ?: (width * 9 / 16),
        ) { tile -> ServerMetadata.artwork(ServerRef(a.kind,a.key,itemId),"/Videos/$itemId/Trickplay/${pick.key}/$tile.jpg?MediaSourceId=$sourceId")!! }.takeIf { it.valid }
    }

    suspend fun actions(a:ServerAccount,itemId:String):ServerActionState {
        val body=http.get("${a.url}/Users/${a.userId}/Items/$itemId",headers(a.token));val data=body.obj("UserData")
        return ServerActionState(data.str("Played")=="true",data.str("IsFavorite")?.toBooleanStrictOrNull(),a.kind=="jellyfin" && (data.long("PlaybackPositionTicks")?:0L)>0)
    }
    suspend fun favorite(a:ServerAccount,itemId:String,value:Boolean) {
        http.ack("${a.url}/Users/${a.userId}/FavoriteItems/$itemId",headers(a.token),if(value)"POST" else "DELETE")
    }
    suspend fun hideResume(a:ServerAccount,itemId:String) {
        check(a.kind=="jellyfin") { "This server does not expose resume hiding." }
        http.ack("${a.url}/Users/${a.userId}/Items/$itemId/HideFromResume?Hide=true",headers(a.token),"POST")
    }
    suspend fun markWatched(a:ServerAccount,itemId:String,watched:Boolean=true):Boolean = serverResult {
        http.ack("${a.url}/Users/${a.userId}/PlayedItems/$itemId",headers(a.token),if(watched)"POST" else "DELETE")
    }.isSuccess
    suspend fun report(a:ServerAccount,itemId:String,state:ReportState,positionMs:Long,session:ServerPlayback?=null) {
        val path=when(state){ReportState.START->"Sessions/Playing";ReportState.STOP->"Sessions/Playing/Stopped";else->"Sessions/Playing/Progress"}
        val body=buildJsonObject { put("ItemId",itemId);put("PositionTicks",positionMs*10000);put("IsPaused",state==ReportState.PAUSE)
            session?.let { put("MediaSourceId",it.sourceId);put("PlaySessionId",it.sessionId);put("PlayMethod",it.method) }
        }
        http.ack("${a.url}/$path",headers(a.token),"POST",body)
    }
    suspend fun item(a:ServerAccount,id:String):Meta {
        val body=http.get("${a.url}/Users/${a.userId}/Items/$id?Fields=ProviderIds,Overview,MediaSources",headers(a.token))
        return (if(body.str("Type")=="Series")seriesMeta(a,body) else toMeta(a,body))?:error("The server item is unavailable.")
    }
    suspend fun seasons(a:ServerAccount,series:String):List<ServerSeason> = http.get("${a.url}/Shows/$series/Seasons?UserId=${a.userId}",headers(a.token)).arr("Items").list().mapNotNull { item->item.int("IndexNumber")?.let{ServerSeason(it,item.str("Name")?:"Season $it")} }.sortedBy{it.number}
    suspend fun episodes(a:ServerAccount,series:String,season:Int,offset:Int):ServerPage {
        val body=http.get("${a.url}/Shows/$series/Episodes?UserId=${a.userId}&Season=$season&StartIndex=$offset&Limit=100&IsMissing=false&Fields=ProviderIds,Overview",headers(a.token))
        val raw=body.arr("Items").list();val next=offset+raw.size;val total=body.int("TotalRecordCount")
        return ServerPage(raw.mapNotNull{toMeta(a,it)},next,raw.isEmpty()||total?.let{next>=it}?: (raw.size<100),total)
    }
    suspend fun browse(a:ServerAccount,library:String,filter:ServerFilter,offset:Int,limit:Int=60):ServerPage {
        val type=if(filter.collections)"BoxSet" else if(filter.parent!=null)"Movie,Series" else if(library.substringAfter("library:").substringBefore(':')=="movies")"Movie" else "Series"
        val id=filter.parent ?: library.substringAfterLast(':')
        val sort=when(filter.sort){"title"->"SortName";"rating"->"CommunityRating";"year"->"ProductionYear";else->"DateCreated"}
        val fields="ProviderIds,Overview"
        val uri="${a.url}/Users/${a.userId}/Items?ParentId=$id&IncludeItemTypes=$type&Recursive=true&Fields=$fields&SortBy=$sort&SortOrder=${if(filter.sort=="title")"Ascending" else "Descending"}&StartIndex=$offset&Limit=$limit"+
            (if(filter.query.isNotBlank())"&SearchTerm="+URLEncoder.encode(filter.query,"UTF-8") else "")+(if(filter.unwatched)"&IsPlayed=false" else "")+(filter.genre?.let{"&GenreIds="+URLEncoder.encode(it,"UTF-8")}?:"")
        val body=http.get(uri,headers(a.token));val raw=body.arr("Items").list();val next=offset+raw.size;val total=body.int("TotalRecordCount")
        return ServerPage(raw.mapNotNull{if(it.str("Type")=="Series")seriesMeta(a,it) else toMeta(a,it)},next,raw.isEmpty()||total?.let{next>=it}?: (raw.size<limit),total)
    }
    suspend fun genres(a:ServerAccount,library:String):List<Pair<String,String>> = http.get("${a.url}/Genres?UserId=${a.userId}&ParentId=${library.substringAfterLast(':')}&Recursive=true",headers(a.token)).arr("Items").list().mapNotNull{m->m.str("Id")?.let{it to (m.str("Name")?:it)}}
    fun artwork(a:ServerAccount,path:String)=a.url+path to headers(a.token)
}
