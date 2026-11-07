package app.ember.tv.mediaserver

import app.ember.tv.data.*
import app.ember.tv.tracker.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.net.URLEncoder


data class PlexServer(val id: String,val name: String,val token: String,val uris: List<String>,val canTranscode:Boolean=false)
internal suspend fun <T> serverResult(block:suspend()->T):Result<T> = try { Result.success(block()) } catch(c:CancellationException){throw c} catch(e:Exception){Result.failure(e)}
/** One account's resources and route decisions expire and are invalidated on failed transport. */
class PlexClient(private val accounts: Accounts,private val http:ServerHttp=ServerHttp(),private val clock:()->Long=System::currentTimeMillis) {
    private fun headers(token:String?=null)=buildMap {
        put("Accept","application/json");put("X-Plex-Product","Ember TV");put("X-Plex-Client-Identifier",accounts.deviceId())
        put("X-Plex-Device","Android TV");put("X-Plex-Device-Name","Ember TV");put("X-Plex-Platform","Android");put("X-Plex-Version",app.ember.tv.BuildConfig.VERSION_NAME)
        token?.let { put("X-Plex-Token",it) }
    }
    val connected get()=!accounts.plexToken.isNullOrBlank()
    fun disconnect(){accounts.plexToken=null;refreshConnections()}
    fun refreshConnections(){synchronized(state){state.clear();resources=null;watchlistCache=null}}
    suspend fun beginLink():LinkSession {
        val r=http.post("https://plex.tv/api/v2/pins?strong=false",null,headers())
        return LinkSession(r.str("code")?:error("Plex returned no code"),"plex.tv/link",r.long("id")?.toString()?:error("Plex returned no pin"),3,r.int("expiresIn")?:900)
    }
    suspend fun pollLink(session:LinkSession):Boolean {
        val token=http.get("https://plex.tv/api/v2/pins/${session.deviceCode}",headers()).str("authToken")?.takeIf { it.isNotBlank() }?:return false
        accounts.plexToken=token;refreshConnections();return true
    }
    suspend fun servers(force:Boolean=false):List<PlexServer> {
        val token=accounts.plexAccessToken?:return emptyList();val scope=AddonAccess.key(token)
        synchronized(state){resources?.takeIf { !force&&it.scope==scope&&clock()-it.at<60_000 }?.let{return it.servers}}
        val list=http.get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1",headers(token)).list().filter { it.str("provides")?.contains("server")==true }.mapNotNull { res->
            val id=res.str("clientIdentifier")?:return@mapNotNull null
            val uris=res.arr("connections").list().sortedBy { c->(if(c.bool("relay")==true) 10 else 0)+(if(c.bool("local")==true) 0 else 2)+(if(c.str("uri")?.startsWith("https:")==true) 0 else 1) }.mapNotNull{it.str("uri")}
            if(uris.isEmpty()) null else PlexServer(id,res.str("name")?:"Plex",res.str("accessToken")?:token,uris,res.bool("transcodeVideo")==true)
        }
        synchronized(state){resources=Resources(scope,clock(),list)};return list
    }
    private fun routeKey(s:PlexServer)=s.id+":"+AddonAccess.key(s.token)
    private suspend fun reachable(s:PlexServer,exclude:String?=null):String {
        val key=routeKey(s)
        synchronized(state){state[key]?.takeIf { clock()-it.at<60_000&&it.uri!=exclude }?.let{return it.uri}}
        val uris=s.uris.filter { it!=exclude }
        for(group in uris.chunked(2)) {
            val tested=coroutineScope { group.map { uri->async { withTimeoutOrNull(3000) { serverResult {
                val text=http.rawGet(uri.trimEnd('/')+"/identity",headers(s.token))
                val id=runCatching { Json.parseToJsonElement(text).obj("MediaContainer").str("machineIdentifier") }.getOrNull()
                    ?:Regex("machineIdentifier=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                check(id==s.id){"The connection belongs to another Plex server."};uri
            }.getOrNull() } } }.awaitAll() }
            tested.filterNotNull().firstOrNull()?.let { uri->synchronized(state){state[key]=Route(uri,clock());while(state.size>32)state.remove(state.keys.first())};return uri }
        };error("No Plex route answered. Check the server or select another route.")
    }
    private suspend fun <T> request(s:PlexServer,block:suspend(String)->T):T {
        val first=reachable(s)
        return try { block(first.trimEnd('/')) } catch(c:CancellationException){throw c} catch(e:Exception){
            if(e is ServerHttpException&&e.status in listOf(401,403))throw e
            synchronized(state){state.remove(routeKey(s))}
            block(reachable(s,first).trimEnd('/'))
        }
    }
    private suspend fun get(s:PlexServer,path:String)=request(s){http.get(it+path,headers(s.token))}
    private suspend fun server(id:String)=servers().firstOrNull{it.id==id}?:error("This Plex server is no longer connected.")
    suspend fun rows(onFailure:(Throwable)->Unit={}):List<ServerRow> = coroutineScope {
        val watchlist = async { watchlist() }
        val permits=Semaphore(3)
        serverResult{servers()}.onFailure{onFailure(it)}.getOrDefault(emptyList()).map { s->async { permits.withPermit {
            val page="includeGuids=1&X-Plex-Container-Start=0&X-Plex-Container-Size=30"
            buildList {
                for((path,title) in listOf("onDeck" to "Continue on ${s.name}","recentlyAdded" to "Recently added on ${s.name}")) {
                    val result=serverResult{get(s,"/library/$path?$page")}.onFailure{onFailure(it)}.getOrNull()
                    val items=result.obj("MediaContainer").arr("Metadata").list().mapNotNull{meta(s,it)}
                    if(items.isNotEmpty())add(ServerRow(title,"Plex",items,path=="onDeck", "plex:${s.id}:$path"))
                }
            }
        } } }.awaitAll().flatten() + watchlist.await().takeIf { it.isNotEmpty() }.let { items ->
            if (items == null) emptyList() else listOf(ServerRow("Plex watchlist", "Plex", items, id = "plex:watchlist"))
        }
    }
    private var watchlistCache: Pair<String, List<Meta>>? = null
    private suspend fun watchlist(): List<Meta> {
        val token = accounts.plexAccessToken ?: return emptyList()
        val scope = AddonAccess.key(token)
        val result = serverResult { PlexPersonalLists.items(http.get(
            "https://discover.provider.plex.tv/library/sections/watchlist/all?includeGuids=1&X-Plex-Container-Size=100", headers(token))) }
        if (accounts.plexAccessToken != token) return emptyList()
        return result.getOrNull()?.also { watchlistCache = scope to it }
            ?: watchlistCache?.takeIf { it.first == scope }?.second.orEmpty()
    }
    suspend fun removeFromWatchlist(key: String) {
        val token = accounts.plexAccessToken ?: error("Plex is not connected.")
        http.ack("https://discover.provider.plex.tv/actions/removeFromWatchlist?ratingKey=${enc(key)}", headers(token), "PUT")
        watchlistCache = null
    }
    suspend fun hideResume(ref: String) {
        val r = ServerRef.parse(ref) ?: error("Invalid server reference.")
        val s = server(r.account)
        request(s) { base -> http.ack("$base/actions/removeFromContinueWatching?ratingKey=${enc(r.item)}", headers(s.token), "PUT") }
    }
    suspend fun homeUsers(): List<ServerUser> = PlexUsers.users(http.rawGet("https://plex.tv/api/home/users", headers(accounts.plexToken ?: error("Connect Plex first."))))
    internal suspend fun authenticateHomeUser(user: ServerUser, pin: String): PlexHomeSession {
        val token = accounts.plexToken ?: error("Connect Plex first.")
        val response = http.rawPost("https://plex.tv/api/home/users/${enc(user.id)}/switch" +
            if (pin.isBlank()) "" else "?pin=${enc(pin)}", headers(token))
        return PlexHomeSession(user.id, user.name, PlexUsers.token(response))
    }
    internal fun selectHomeUser(session: PlexHomeSession) { accounts.plexHome = session; refreshConnections() }
    /** Every server's movie and show libraries, a few at a time (#98), in the servers' order. */
    suspend fun libraries(onFailure:(Throwable)->Unit={}):List<ServerLibrary> = coroutineScope {
        val permits=Semaphore(4)
        serverResult{servers()}.onFailure{onFailure(it)}.getOrDefault(emptyList()).map { s->async {
            val dirs=permits.withPermit { serverResult{get(s,"/library/sections")}.onFailure{onFailure(it)}.getOrNull() }.obj("MediaContainer").arr("Directory").list()
            dirs.map { d->async { permits.withPermit {
                val type=d.str("type")?.takeIf{it in listOf("movie","show")}?:return@withPermit null;val id=d.str("key")?:return@withPermit null
                val ref=ServerRef("plex",s.id,"library:$id").value
                val p=serverResult{browse(ref,ServerFilter(),0,60)}.onFailure{onFailure(it)}.getOrNull()?:return@withPermit null
                ServerLibrary(s.name,d.str("title")?:type,if(type=="movie")"movies" else "shows",p.items,ref,ref,p.total)
            } } }.awaitAll().filterNotNull()
        } }.awaitAll().flatten()
    }
    suspend fun browse(ref:String,filter:ServerFilter,offset:Int,limit:Int=60):ServerPage {
        val r=ServerRef.parse(ref)?:error("Invalid Plex library.");val s=server(r.account);val id=r.item.removePrefix("library:")
        val sort=when(filter.sort){"title"->"titleSort:asc";"year"->"year:desc";"rating"->"rating:desc";else->"addedAt:desc"}
        val args=mutableMapOf("includeGuids" to "1","sort" to sort,"X-Plex-Container-Start" to offset.toString(),"X-Plex-Container-Size" to limit.toString())
        if(filter.query.isNotBlank())args["title"]=filter.query;if(filter.unwatched)args["unwatched"]="1";filter.genre?.let{args["genre"]=it}
        val path=when { filter.parent!=null -> "/library/collections/${enc(filter.parent)}/children"; filter.collections -> "/library/sections/$id/collections"; else -> "/library/sections/$id/all" }
        val body=get(s,path+"?"+query(args)).obj("MediaContainer")
        return page(s,body,offset,limit)
    }
    suspend fun genres(ref:String):List<Pair<String,String>> {
        val r=ServerRef.parse(ref)?:return emptyList();val s=server(r.account)
        return get(s,"/library/sections/${r.item.removePrefix("library:")}/genre").obj("MediaContainer").arr("Directory").list().mapNotNull { d->d.str("key")?.let{it to (d.str("title")?:it)} }
    }
    private fun page(s:PlexServer,body:JsonElement?,offset:Int,limit:Int):ServerPage {
        val raw=body.arr("Metadata").list();val total=body.int("totalSize");val next=offset+raw.size
        return ServerPage(raw.mapNotNull{meta(s,it)},next,raw.isEmpty()||total?.let{next>=it}?: (raw.size<limit),total)
    }
    suspend fun item(ref:String):Meta {val r=ServerRef.parse(ref)?:error("Invalid server item.");val s=server(r.account);return meta(s,itemJson(s,r.item))?:error("This server item is unavailable.")}
    private suspend fun itemJson(s:PlexServer,id:String)=get(s,"/library/metadata/$id?includeGuids=1&includeMarkers=1&includeChapters=1").obj("MediaContainer").arr("Metadata").list().firstOrNull()?:error("Plex returned no item.")
    private fun meta(s:PlexServer,m:JsonElement):Meta? {
        val type=m.str("type")?:return null;if(type !in listOf("movie","show","episode","collection"))return null
        val id=m.str("ratingKey")?:return null;val ref=ServerRef("plex",s.id,id);val episode=type=="episode"
        val guids=m.arr("Guid").list().mapNotNull{it.str("id")};val duration=m.long("duration");val offset=m.long("viewOffset")
        val season=m.int("parentIndex");val number=m.int("index")
        fun image(vararg paths:String?)=paths.firstNotNullOfOrNull {path->path?.let {ServerMetadata.artwork(ref,ServerMetadata.versioned(it,m.str("updatedAt")))}}
        return Meta(id="plex:${s.id}:$id",type=if(type=="collection")"collection" else if(type=="movie")"movie" else "series",name=(if(episode)m.str("grandparentTitle") else null)?:m.str("title")?:return null,
            poster=image(if(episode)m.str("grandparentThumb") else null,
                if(episode)m.str("grandparentRatingKey")?.let {"/library/metadata/$it/thumb"} else null,m.str("thumb"),"/library/metadata/$id/thumb"),
            background=image(m.str("art"),m.str("grandparentArt"),m.str("grandparentRatingKey")?.let {"/library/metadata/$it/art"},"/library/metadata/$id/art"),description=m.str("summary"),releaseInfo=m.int("year")?.toString(),
            runtime=duration?.takeIf{it>0}?.let{"${kotlin.math.ceil(it/60000.0).toInt()} min"},progress=if(duration!=null&&duration>0&&offset!=null)offset.toFloat()/duration else null,
            imdbId=if(episode)null else guids.firstOrNull{it.startsWith("imdb://")}?.removePrefix("imdb://"),tmdbId=if(episode)null else guids.firstOrNull{it.startsWith("tmdb://")}?.removePrefix("tmdb://")?.toLongOrNull(),
            season=if(episode)season else null,episode=if(episode)number else null,serverRef=ref.value,serverName=s.name,
            episodeThumbnail=if(episode)ServerMetadata.artwork(ref,m.str("thumb")) else null,
            serverSeriesRef=m.str("grandparentRatingKey")?.let{ServerRef("plex",s.id,it).value},badge=if(episode)m.str("title") else null,
            ageRating=m.str("contentRating"),
            watched=if(type=="show")m.int("leafCount")?.takeIf{it>0}?.let {total->m.int("viewedLeafCount")?.let {it>=total}}
                else if(type=="movie" || type=="episode")m.int("viewCount")?.let {it>0} else null)
    }
    suspend fun seasons(ref:String):List<ServerSeason> {
        val r=ServerRef.parse(ref)?:return emptyList();val s=server(r.account)
        return get(s,"/library/metadata/${r.item}/children").obj("MediaContainer").arr("Metadata").list().mapNotNull { it.int("index")?.let{n->ServerSeason(n,it.str("title")?:"Season $n")} }.sortedBy{it.number}
    }
    suspend fun episodes(ref:String,season:Int,offset:Int=0):ServerPage {
        val r=ServerRef.parse(ref)?:error("Invalid show.");val s=server(r.account)
        val children=get(s,"/library/metadata/${r.item}/children").obj("MediaContainer").arr("Metadata").list()
        val id=children.firstOrNull{it.int("index")==season}?.str("ratingKey")?:return ServerPage(emptyList(),offset,true)
        return page(s,get(s,"/library/metadata/$id/children?includeGuids=1&X-Plex-Container-Start=$offset&X-Plex-Container-Size=100").obj("MediaContainer"),offset,100)
    }
    suspend fun sourcesFor(serverId:String,ratingKey:String):List<StreamOption> {val s=server(serverId);return options(s,itemJson(s,ratingKey))}
    private suspend fun options(s:PlexServer,item:JsonElement):List<StreamOption> {
        val id=item.str("ratingKey")?:return emptyList();val ref=ServerRef("plex",s.id,id);val uri=reachable(s).trimEnd('/')
        return item.arr("Media").list().mapIndexedNotNull { index,m->
            val parts=m.arr("Part").list().mapNotNull { p->val key=p.str("key")?:return@mapNotNull null
                val subs=p.arr("Stream").list().filter{it.int("streamType")==3&&it.str("key")!=null}.map { t->SubtitleOption(s.name,t.str("languageCode")?:t.str("language")?:"und",tokenUrl(uri+t.str("key"),s.token),forced=t.int("forced")==1,hearingImpaired=t.int("hearingImpaired")==1,server=true) }
                ServerPart(p.str("id")?:key,tokenUrl(uri+key,s.token),p.long("duration"),subs)
            };if(parts.isEmpty())return@mapIndexedNotNull null
            val sourceId=m.str("id")?:index.toString();val media=ServerPlayback(ref,sourceId,parts,item.long("duration"),item.str("grandparentRatingKey")?.let{ServerRef("plex",s.id,it).value})
            val video=m.arr("Part").list().firstOrNull().arr("Stream").list().firstOrNull{it.int("streamType")==1}
            StreamOption(s.name,s.name,m.arr("Part").list().firstOrNull().str("file")?.substringAfterLast('/')?.substringAfterLast('\\')?:"Original ${index+1}",parts.first().url,null,null,
                Quality.fromSize(m.int("width"),m.int("height")),when{video.int("DOVIPresent")==1->"DV";video.str("colorTrc")=="smpte2084"->"HDR10";else->""},
                m.str("videoCodec")?.uppercase().orEmpty(),ServerQuery.audioLabel(m.str("audioCodec"),m.int("audioChannels")),"","",m.arr("Part").list().sumOf{it.long("size")?:0L}.takeIf{it>0}?.let{it/1073741824.0},null,false,serverRef=ref.value,serverPlayback=media)
        }
    }
    suspend fun find(q:ServerQuery):List<StreamOption> {
        val out=mutableListOf<StreamOption>()
        for(s in servers())serverResult {
            val candidates=get(s,"/hubs/search?query=${enc(q.title)}&limit=20&includeGuids=1").obj("MediaContainer").arr("Hub").list().flatMap{it.arr("Metadata").list()}.filter{it.str("type")==if(q.movie)"movie" else "show"}
            val match=candidates.firstOrNull { m->val ids=m.arr("Guid").list().mapNotNull{it.str("id")};q.imdbId?.let{"imdb://$it" in ids}==true||q.tmdbId?.let{"tmdb://$it" in ids}==true }
                ?:candidates.firstOrNull { (it.arr("Guid").list().isEmpty()||!q.hasIds)&&ServerQuery.sameTitle(it.str("title"),q.title)&&ServerQuery.sameYear(it.int("year"),q.year) }?:return@serverResult
            val id=match.str("ratingKey")?:return@serverResult
            if(q.movie)out+=options(s,itemJson(s,id)) else {
                var skip=0
                do { val p=episodes(ServerRef("plex",s.id,id).value,q.season?:1,skip);val ep=p.items.firstOrNull{it.episode==q.episode}
                    if(ep!=null){out+=sourcesFor(s.id,ServerRef.parse(ep.serverRef)!!.item);break};if(p.end||p.next<=skip)break;skip=p.next
                }while(true)
            }
        };return out
    }
    suspend fun qualities(ref:String,sourceId:String):List<ServerQuality> {
        val r=ServerRef.parse(ref)?:return listOf(ServerQuality.ORIGINAL)
        val s=server(r.account)
        val selected=options(s,itemJson(s,r.item)).firstOrNull { it.serverPlayback?.sourceId==sourceId }
        return if(s.canTranscode && selected?.serverPlayback?.parts?.size==1) ServerQuality.entries.toList() else listOf(ServerQuality.ORIGINAL)
    }
    suspend fun playback(ref:String,sourceId:String,quality:ServerQuality):StreamOption {
        val r=ServerRef.parse(ref)?:error("Invalid server item.");val s=server(r.account)
        val item=itemJson(s,r.item);val options=options(s,item)
        val original=options.firstOrNull { it.serverPlayback?.sourceId==sourceId }?:error("This version is unavailable.")
        if(quality==ServerQuality.ORIGINAL)return original
        check(s.canTranscode && original.serverPlayback?.parts?.size==1) { "This server/version does not support conversion." }
        val mediaIndex=item.arr("Media").list().withIndex().firstOrNull { (i,m)->(m.str("id")?:i.toString())==sourceId }?.index?:error("Version unavailable.")
        val session=java.util.UUID.randomUUID().toString();val uri=reachable(s).trimEnd('/')
        val args=mapOf("path" to "/library/metadata/${r.item}","mediaIndex" to mediaIndex.toString(),"partIndex" to "0","protocol" to "hls",
            "directPlay" to "0","directStream" to "0","fastSeek" to "1","videoResolution" to "${quality.width}x${quality.height}",
            "maxVideoBitrate" to (quality.bitrate/1000).toString(),"videoQuality" to "100","session" to session,
            "X-Plex-Client-Identifier" to accounts.deviceId(),"X-Plex-Platform" to "Chrome","X-Plex-Product" to "Ember TV")
        val url=tokenUrl(uri+"/video/:/transcode/universal/start.m3u8?"+query(args),s.token)
        val transport=original.serverPlayback!!.copy(parts=listOf(ServerPart("transcode",url,original.serverPlayback.durationMs)),sessionId=session,method="Transcode",quality=quality)
        return original.copy(url=url,quality=quality.height.toString()+"p max",hdr="",codec="H264",audio="AAC",title=quality.label+" · server conversion",serverPlayback=transport)
    }
    suspend fun stopTranscode(transport:ServerPlayback) {
        if(transport.method!="Transcode")return
        val s=server(transport.ref.account)
        request(s){http.ack(it+"/video/:/transcode/universal/stop?session="+enc(transport.sessionId),headers(s.token))}
    }
    suspend fun titleIds(serverId:String,ratingKey:String):Pair<String?,Long?> {val s=server(serverId);val m=itemJson(s,ratingKey);val target=if(m.str("type")=="episode")m.str("grandparentRatingKey")?.let{itemJson(s,it)}?:m else m;val ids=target.arr("Guid").list().mapNotNull{it.str("id")};return ids.firstOrNull{it.startsWith("imdb://")}?.removePrefix("imdb://") to ids.firstOrNull{it.startsWith("tmdb://")}?.removePrefix("tmdb://")?.toLongOrNull()}
    suspend fun artwork(ref:ServerRef,path:String):Pair<String,Map<String,String>> {val s=server(ref.account);return reachable(s).trimEnd('/')+path to headers(s.token)}
    suspend fun markers(serverId:String,ratingKey:String):List<Marker> {val s=server(serverId);return itemJson(s,ratingKey).arr("Marker").list().mapNotNull { m->val kind=m.str("type")?.takeIf{it in listOf("intro","credits")}?:return@mapNotNull null;Marker(kind,m.long("startTimeOffset")?:return@mapNotNull null,m.long("endTimeOffset")?:return@mapNotNull null) }}
    suspend fun chapters(serverId:String,ratingKey:String):List<Chapter> {val s=server(serverId);return itemJson(s,ratingKey).arr("Chapter").list().mapIndexedNotNull{i,c->Chapter(c.str("tag")?:"Chapter ${i+1}",c.long("startTimeOffset")?:return@mapIndexedNotNull null)}}
    suspend fun trickplay(serverId:String,ratingKey:String,sourceId:String?=null):Trickplay? {
        val s = server(serverId)
        val media = itemJson(s,ratingKey).arr("Media").list()
        val selected = if (sourceId == null) media.firstOrNull() else media.withIndex().firstOrNull { (i,m) -> (m.str("id") ?: i.toString()) == sourceId }?.value
        val parts = selected.arr("Part").list()
        if (parts.isEmpty() || parts.any { it.str("indexes")?.contains("sd") != true || it.str("id") == null || (it.long("duration") ?: 0L) <= 0L }) return null
        val duration = parts.sumOf { it.long("duration")!! }
        return Trickplay(10000,(duration/10000).toInt()+1,1,1,320,180) { tile ->
            var position = tile * 10000L
            val part = parts.firstOrNull { p -> if (position < p.long("duration")!!) true else { position -= p.long("duration")!!; false } } ?: parts.last()
            ServerMetadata.artwork(ServerRef("plex",s.id,ratingKey),"/library/parts/${part.str("id")}/indexes/sd/${position.coerceAtLeast(0) / 10000 * 10000}")!!
        }
    }

    suspend fun actions(ref:String):ServerActionState {
        val r=ServerRef.parse(ref)?:error("Invalid item.");val m=itemJson(server(r.account),r.item)
        return ServerActionState((m.int("viewCount")?:0)>0)
    }
    suspend fun markWatched(serverId:String,ratingKey:String,watched:Boolean=true):Boolean {
        val s=server(serverId)
        return serverResult{request(s){http.ack("$it/:/${if(watched)"scrobble" else "unscrobble"}?key=${enc(ratingKey)}&identifier=com.plexapp.plugins.library",headers(s.token))}}.isSuccess
    }
    suspend fun report(serverId:String,ratingKey:String,state:ReportState,positionMs:Long,durationMs:Long) {
        val s=server(serverId);val st=when(state){ReportState.START,ReportState.PROGRESS->"playing";ReportState.PAUSE->"paused";ReportState.STOP->"stopped"}
        request(s){http.ack("$it/:/timeline?"+query(mapOf("ratingKey" to ratingKey,"key" to "/library/metadata/$ratingKey","state" to st,"time" to positionMs.toString(),"duration" to durationMs.toString())),headers(s.token))}
    }
    private fun tokenUrl(url:String,token:String)=url+(if('?' in url)"&" else "?")+"X-Plex-Token="+enc(token)
    private fun enc(s:String)=URLEncoder.encode(s,"UTF-8")
    private fun query(values:Map<String,String>)=values.entries.joinToString("&"){enc(it.key)+"="+enc(it.value)}
    companion object {
        private data class Route(val uri:String,val at:Long)
        private data class Resources(val scope:String,val at:Long,val servers:List<PlexServer>)
        private val state=linkedMapOf<String,Route>();private var resources:Resources?=null
    }
}
