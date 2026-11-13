package app.ember.tv.phone

/**
 * Phone pages expose labels and opaque references, never configured add-on URLs or API keys.
 * Since #160 a folder's cover image link is also sent, so the editor can show and change it.
 */
data class PhoneContent(
    val sources: List<Pair<String, String>>,
    val targets: List<Pair<String, String>>,
    val addons: List<Pair<String, String>>,
    val catalogExtras: List<PhoneCatalogExtras> = emptyList(),
    val collections: List<Pair<String, String>> = emptyList(),
    val folderSources: List<Pair<String, String>> = emptyList(),
    val existingExtras: List<PhoneCatalogExtras> = emptyList(),
    /** Every collection with its folders, for the list and the folder editor (#160). */
    val library: List<PhoneCollection> = emptyList(),
    /** Your Home widgets in Home order (#161). */
    val widgets: List<PhoneWidget> = emptyList(),
    /** #240: everything on Home in order, and what is switched off. */
    val home: List<PhoneHomeEntry> = emptyList(),
    val homeOff: List<PhoneHomeEntry> = emptyList(),
    /** Your lists a widget can show: the built-in three and what your trackers offer right now. */
    val lists: List<Pair<String, String>> = app.ember.tv.data.HomeWidgets.lists,
)
/**
 * #240: one Home widget for the phone list. [id] is opaque; [kind] is "built", "yours" or "catalog";
 * [style] picks its picture; [widget] is the widget's own id when it is yours.
 */
data class PhoneHomeEntry(val id: String, val title: String, val kind: String, val note: String, val style: String, val widget: String? = null)
/** One result of a search made from the widget editor: a person, studio, service or Trakt list. */
data class PhoneLookup(val id: String, val name: String, val note: String = "")
/** [style] and [shape] are HomeWidgets values; [source] and [status] are labels. */
data class PhoneWidget(val id: String, val title: String, val style: String, val shape: String, val limit: Int, val source: String, val status: String,
    val canMoveUp: Boolean = true, val canMoveDown: Boolean = true, val art: String = app.ember.tv.data.HeroArtworkMode.STANDARD)
/** [cover] is the collection backdrop link, shown like a folder cover (#234). */
data class PhoneCollection(val id: String, val title: String, val folders: List<PhoneFolder>, val cover: String? = null)
/** [shape] is "poster", "wide" or "square"; [sources] are opaque ids with their labels, in order. */
data class PhoneFolder(val id: String, val title: String, val shape: String, val hideTitle: Boolean, val cover: String?, val sources: List<Pair<String, String>>)
data class PhoneCatalogExtras(val id: String, val label: String, val extras: List<app.ember.tv.data.CatalogExtraDef>)

object ContentPhone {
    /** The export form's value for every collection in one pack (#158); real ids are opaque hashes starting "c-". */
    const val EXPORT_ALL = "all"
    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    /** A collection with more folders than this starts collapsed. */
    const val FOLDERS_OPEN = 6
    /** Folder pictures on a closed collection, as on the TV (#231). */
    const val STRIP = 5
    /**
     * Reordering the widget list on the page (#186). Arrows and the drag handle move rows locally;
     * the Send form appears once the order differs and carries the opaque row ids, comma separated.
     */
    internal const val REORDER = "(function(){var L=document.getElementById('worder'),F=document.getElementById('wsend');if(!L||!F)return;L.classList.add('live');" +
        "function ids(){return [].map.call(L.querySelectorAll('.row[data-id]'),function(r){return r.getAttribute('data-id')}).join(',')}" +
        "var start=ids(),drag=null;function changed(){var now=ids();F.hidden=now===start;F.elements.order.value=now}" +
        "L.addEventListener('click',function(e){var b=e.target.closest('.up,.down');if(!b)return;var r=b.closest('.row');" +
        "if(b.classList.contains('up')){var p=r.previousElementSibling;if(p)L.insertBefore(r,p)}else{var n=r.nextElementSibling;if(n)L.insertBefore(n,r)}changed();b.focus()});" +
        "L.addEventListener('pointerdown',function(e){var g=e.target.closest('.grip');if(!g)return;drag=g.closest('.row');drag.classList.add('drag');e.preventDefault()});" +
        "document.addEventListener('pointermove',function(e){if(!drag)return;var p=drag.previousElementSibling,n=drag.nextElementSibling,b;" +
        "if(p){b=p.getBoundingClientRect();if(e.clientY<b.top+b.height/2){L.insertBefore(drag,p);return}}" +
        "if(n){b=n.getBoundingClientRect();if(e.clientY>b.top+b.height/2)L.insertBefore(n,drag)}});" +
        "function end(){if(!drag)return;drag.classList.remove('drag');drag=null;changed()}" +
        "document.addEventListener('pointerup',end);document.addEventListener('pointercancel',end)})();"
    private const val BIND = "function bind(id,attribute){const source=document.getElementById(id);function filters(){document.querySelectorAll('['+attribute+']').forEach(box=>{const on=box.getAttribute(attribute)===source.value;box.hidden=!on;box.querySelectorAll('input,select').forEach(input=>input.disabled=!on);});}source.addEventListener('change',filters);filters();}"
    /** Search-as-you-type for the widget editor: people, studios and services become chips; a Trakt list fills its number. */
    private const val FINDERS = "(function(){var K=(location.search.match(/[?&]k=([^&]+)/)||[])[1]||'';" +
        "function ask(kind,q,tab,done){fetch('/lookup?k='+K+'&kind='+kind+'&tab='+encodeURIComponent(tab||'')+'&q='+encodeURIComponent(q||'')).then(function(r){return r.json()}).then(function(j){if(j&&j.error)done([],j.error);else done(j)}).catch(function(){done([],'The TV did not answer. The session may have ended.')})}" +
        "function note(box,text){box.textContent='';var p=document.createElement('p');p.className='m';p.textContent=text;box.appendChild(p)}" +
        "function list(box,rows,pick){box.textContent='';if(!rows.length){note(box,'Nothing found.');return}rows.forEach(function(r){var b=document.createElement('button');b.type='button';b.className='res';var t=document.createElement('b');t.textContent=r.name;b.appendChild(t);if(r.note){var s=document.createElement('small');s.textContent=r.note;b.appendChild(s)}b.onclick=function(){pick(r)};box.appendChild(b)})}" +
        "function quiet(i){i.addEventListener('keydown',function(e){if(e.key==='Enter')e.preventDefault()})}" +
        "document.querySelectorAll('.pick').forEach(function(p){var i=p.querySelector('input[type=search]'),res=p.querySelector('.results'),chosen=p.querySelector('.chosen'),t;quiet(i);" +
        "i.addEventListener('input',function(){clearTimeout(t);var q=i.value.trim();if(q.length<2){res.textContent='';return}t=setTimeout(function(){ask(p.dataset.lookup,q,'',function(rows,err){if(err){note(res,err);return}list(res,rows,function(r){" +
        "var name='ref:'+p.dataset.ref+':'+r.id;if(!chosen.querySelector('[name=\"'+name+'\"]')){var c=document.createElement('label'),h=document.createElement('input'),s=document.createElement('span');h.type='hidden';h.name=name;h.value=r.name;s.textContent=r.name+' \u00d7';c.appendChild(h);c.appendChild(s);c.onclick=function(){c.remove()};chosen.appendChild(c)}res.textContent='';i.value=''})})},350)})});" +
        "var tf=document.getElementById('trakt-find');if(tf){var res=tf.querySelector('.results'),q=tf.querySelector('input[type=search]'),id=document.getElementById('tk'),title=document.getElementById('wt'),t2;quiet(q);" +
        "function show(tab,text){note(res,'Loading\u2026');ask('trakt',text,tab,function(rows,err){if(err){note(res,err);return}list(res,rows,function(r){id.value=r.id;if(!title.value)title.value=r.name;note(res,'Chosen: '+r.name)})})}" +
        "tf.querySelectorAll('[data-tab]').forEach(function(b){b.onclick=function(){show(b.dataset.tab,'')}});" +
        "q.addEventListener('input',function(){clearTimeout(t2);var v=q.value.trim();if(v.length<2)return;t2=setTimeout(function(){show('',v)},400)})}})();"
    private fun web(url: String?) = url?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
    private fun shapeName(shape: String) = when (shape) { "poster" -> "Poster"; "wide" -> "Wide"; else -> "Square" }
    private fun StringBuilder.options(values: List<Pair<String, String>>) { values.forEach { (id, name) -> append("<option value=\"").append(esc(id)).append("\">").append(esc(name)).append("</option>") } }
    private fun StringBuilder.extras(catalogs: List<PhoneCatalogExtras>, prefix: String, attribute: String) { catalogs.forEach { catalog ->
        append("<fieldset ").append(attribute).append("=\"").append(esc(catalog.id)).append("\"><legend>").append(esc(catalog.label)).append(" filters</legend>")
        catalog.extras.forEach { extra ->
            val key = "$prefix:${catalog.id}:${extra.name}"
            append("<label>").append(esc(extra.name)).append(if (extra.required) " (required)" else "").append("</label>")
            if (extra.options.isNotEmpty() && !app.ember.tv.data.CollectionSecrets.sensitive(extra.name)) {
                append("<select name=\"").append(esc(key)).append("\"><option value=''>Choose / any</option>")
                extra.options.forEach { value -> append("<option value=\"").append(esc(value)).append("\"" + if (value == extra.default) " selected" else "").append(">").append(esc(value)).append("</option>") }
                append("</select>")
            } else {
                append("<input autocomplete=off name=\"").append(esc(key)).append("\" type=")
                    .append(if (app.ember.tv.data.CollectionSecrets.sensitive(extra.name)) "password" else "text")
                    .append(" value=\"").append(esc(if (app.ember.tv.data.CollectionSecrets.sensitive(extra.name)) "" else extra.default.orEmpty())).append("\">")
            }
        }; append("</fieldset>")
    } }

    private fun StringBuilder.post(auth: String, action: String, target: String, extra: String, label: String, glyph: String, disabled: Boolean = false, danger: Boolean = false) {
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=").append(action).append("><input type=hidden name=target value=\"").append(esc(target)).append("\">")
            .append(extra).append("<button class=\"ib").append(if (danger) " danger" else "").append("\" aria-label=\"").append(esc(label)).append("\" title=\"").append(esc(label)).append("\"")
            .append(if (disabled) " disabled" else "").append(">").append(glyph).append("</button></form>")
    }

    private fun kindName(kind: String) = when (kind) { "yours" -> "Yours"; "catalog" -> "Catalog"; else -> "Built in" }

    /**
     * Home widgets (#161, #240): everything on Home in order, with move, off, and edit or delete for your
     * own; turned-off widgets in their own list to turn back on. Every change is approved on the TV.
     */
    private fun StringBuilder.widgets(content: PhoneContent, auth: String) {
        val home = content.home.ifEmpty { content.widgets.map { PhoneHomeEntry(it.id, it.title, "yours",
            app.ember.tv.data.HomeWidgets.styleName(it.style) + " · " + it.source + " · " + it.status, it.style, it.id) } }
        append("<section class=\"card lib wide\"><div class=head><h2>Home widgets</h2><span class=m>").append(home.size).append(" on Home")
        if (content.homeOff.isNotEmpty()) append(" · ").append(content.homeOff.size).append(" off")
        append("</span></div>")
        append("<p class=m>Everything on the Ember Home, in order: the banner, your apps and lists, catalogs and your own widgets. Drag or use the arrows, then send the order; switch a widget off with &#8856;. The TV asks you to approve each change.</p>")
        // #186: a picture of each widget's style; drag (or the arrows) reorders here, then one approval sends the order.
        append("<div id=worder>")
        home.forEachIndexed { index, e ->
            val name = e.title.ifBlank { "Widget" }
            append("<div class=row data-id=\"").append(esc(e.id)).append("\"><button type=button class=\"ib grip wj\" aria-label=\"Drag ").append(esc(name)).append(" to reorder\" title=\"Drag to reorder\">&#8645;</button>")
            append("<span class=\"thumb wt\"><i class=\"w-").append(esc(e.style)).append("\"></i></span>")
            append("<div class=t><b>").append(esc(name)).append("</b><small><span class=chip>").append(kindName(e.kind)).append("</span>").append(esc(e.note)).append("</small></div><div class=acts>")
            append("<button type=button class=\"ib up wj\" aria-label=\"Move ").append(esc(name)).append(" up\">&uarr;</button><button type=button class=\"ib down wj\" aria-label=\"Move ").append(esc(name)).append(" down\">&darr;</button>")
            // Without scripts, the one-place moves still work.
            append("<noscript>")
            post(auth, "move-home", e.id, "<input type=hidden name=dir value=up>", "Move $name up on Home", "&uarr;", disabled = index == 0)
            post(auth, "move-home", e.id, "<input type=hidden name=dir value=down>", "Move $name down on Home", "&darr;", disabled = index == home.lastIndex)
            append("</noscript>")
            if (e.widget != null) append("<a class=ib href=\"/content?k=$auth&amp;widget=").append(esc(e.widget)).append("\" aria-label=\"Edit ").append(esc(name)).append("\" title=\"Edit ").append(esc(name)).append("\">&#9998;</a>")
            post(auth, "home-off", e.id, "", "Switch $name off on Home", "&#8856;")
            if (e.widget != null) post(auth, "delete-widget", e.widget, "", "Delete $name", "&#10005;", danger = true)
            append("</div></div>")
        }
        append("</div>")
        if (home.size > 1) append("<form id=wsend class=flat method=post action=\"/content?k=$auth\" hidden><input type=hidden name=action value=order-home><input type=hidden name=target value=\"\"><input type=hidden name=order value=\"\">")
            .append("<p class=m>The order changed. Send it to the TV and approve it there.</p><button>Send new order to the TV</button></form><script>").append(REORDER).append("</script>")
        if (content.homeOff.isNotEmpty()) {
            append("<details class=fold><summary>Turned off · ").append(content.homeOff.size).append(if (content.homeOff.size == 1) " widget" else " widgets").append("</summary>")
            content.homeOff.groupBy { kindName(it.kind) }.forEach { (group, rows) ->
                append("<h3>").append(group).append("</h3>")
                rows.forEach { e ->
                    val name = e.title.ifBlank { "Widget" }
                    append("<div class=row><span class=\"thumb wt\"><i class=\"w-").append(esc(e.style)).append("\"></i></span><div class=t><b>").append(esc(name)).append("</b><small>").append(esc(e.note)).append("</small></div><div class=acts>")
                    append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=home-on><input type=hidden name=target value=\"").append(esc(e.id)).append("\"><button class=quiet aria-label=\"Turn ").append(esc(name)).append(" on\">Turn on</button></form>")
                    append("</div></div>")
                }
            }
            append("</details>")
        }
        append("<a class=btn href=\"/content?k=$auth&amp;widget=new\">Add a widget</a>")
        // #163: move widgets between TVs and profiles as a file, like collections.
        append("<details><summary>Import or export widgets</summary>")
        if (content.widgets.isNotEmpty()) append("<form class=flat method=post action=\"/widgets-export?k=$auth\"><button class=quiet>Download all widgets</button></form>")
        append("<form class=flat method=post action=\"/widgets?k=$auth\" enctype=multipart/form-data><p class=m>A widget pack exported from Ember. Widgets with the same id are updated; the rest are added. Approve the import on the TV.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the pack here\"></textarea><button>Import widgets</button></form></details></section>")
    }

    /**
     * The widget editor (#161): title, style, card shape, size and source in one form. [id] is a
     * widget id or "new". An existing widget keeps its source unless another kind is chosen.
     */
    fun widgetEditor(content: PhoneContent, code: String, id: String): String = buildString {
        val auth = esc(code)
        val widget = content.widgets.firstOrNull { it.id == id }
        append("<p><a class=back href=\"/content?k=$auth&amp;part=widgets\">&lsaquo; Home widgets</a></p>")
        if (id != "new" && widget == null) { append("<p class=ok>That widget no longer exists. Go back and pick another.</p>"); return@buildString }
        append("<form class=ed method=post action=\"/content?k=$auth\"><input type=hidden name=action value=save-widget><input type=hidden name=target value=\"").append(esc(id)).append("\">")
        append("<h2>").append(if (widget == null) "New widget" else "Edit widget").append("</h2>")
        append("<label for=wt>Widget title</label><input id=wt name=title maxlength=100 required value=\"").append(esc(widget?.title.orEmpty())).append("\">")
        append("<label>Style</label><div class=\"shapes styles\">")
        val style = widget?.style ?: app.ember.tv.data.HomeWidgets.ROW
        app.ember.tv.data.HomeWidgets.styles.forEach { (value, name) ->
            append("<label><input type=radio name=style value=").append(value).append(if (style == value) " checked" else "").append("><span><i class=\"w-").append(value).append("\"></i>").append(name).append("<small>").append(esc(app.ember.tv.data.HomeWidgets.about(value))).append("</small></span></label>")
        }
        append("</div><label for=shape>Card shape</label><select id=shape name=shape>")
        app.ember.tv.data.HomeWidgets.shapes.forEach { (value, name) -> append("<option value=").append(value).append(if ((widget?.shape ?: "default") == value) " selected" else "").append(">").append(name).append("</option>") }
        append("</select><p class=m>Card shape applies to rows. The cinematic carousel and hero banner are one large panel showing a title at a time.</p>")
        // #196
        append("<label for=art>Banner and carousel artwork</label><select id=art name=art>")
        app.ember.tv.data.HomeWidgets.arts.forEach { (value, name) -> append("<option value=").append(value).append(if ((widget?.art ?: app.ember.tv.data.HeroArtworkMode.STANDARD) == value) " selected" else "").append(">").append(name).append("</option>") }
        append("</select><p class=m>Fanart prefers Fanart backgrounds and logos; it needs TMDB and Fanart API keys and falls back to standard artwork.</p>")
        append("<label for=limit>Titles to show</label><input id=limit name=limit type=number inputmode=numeric min=").append(app.ember.tv.data.HomeWidgets.LIMITS.first).append(" max=").append(app.ember.tv.data.HomeWidgets.LIMITS.last)
            .append(" value=").append(widget?.limit ?: app.ember.tv.data.HomeWidgets.DEFAULT_LIMIT).append(">")
        append("<h3>Content source</h3>")
        if (widget != null) append("<p class=m>Now: <b>").append(esc(widget.source)).append("</b></p>")
        append("<label for=kind>").append(if (widget == null) "Where the titles come from" else "Change the source").append("</label><select id=kind name=kind>")
        if (widget != null) append("<option value=keep>Keep the current source</option>")
        append("<option value=catalog>A catalog or saved Discover search</option><option value=filters>Filters</option><option value=trakt>A Trakt list</option><option value=mdblist>An MDBList list</option><option value=tmdb>A TMDB list</option><option value=list>One of my lists</option></select>")
        append("<fieldset data-kind=catalog><legend>Catalog</legend><select name=source id=source>"); options(content.sources); append("</select>")
        extras(content.catalogExtras, "extra", "data-source")
        append("</fieldset><fieldset data-kind=filters><legend>Filters</legend><label for=fk>Type</label><select id=fk name=fkind>")
        app.ember.tv.discover.ExploreKind.entries.forEach { append("<option value=").append(it.name).append(">").append(esc(it.label)).append("</option>") }
        append("</select><label for=fs>Order</label><select id=fs name=fsort>")
        app.ember.tv.discover.ExploreSort.entries.forEach { append("<option value=").append(it.name).append(">").append(esc(it.label)).append("</option>") }
        append("</select><label>Genres (any of)</label><div class=picks>")
        app.ember.tv.discover.ExploreGenres.all.forEach { g -> append("<label><input type=checkbox name=\"genre:").append(esc(g.key)).append("\"><span>").append(esc(g.label)).append("</span></label>") }
        append("</div><label for=fy>From year</label><input id=fy name=fromYear type=number inputmode=numeric placeholder=\"Any\"><label for=ty>To year</label><input id=ty name=toYear type=number inputmode=numeric placeholder=\"Any\">")
        append("<label for=mr>Minimum rating (0–10)</label><input id=mr name=minRating type=number inputmode=decimal step=0.1 min=0 max=10 placeholder=\"Any\">")
        // Cast, directors, studios and streaming services are found by name and added as chips.
        listOf(Triple("actor", "person", "Cast"), Triple("director", "person", "Directors"), Triple("company", "company", "Studios"), Triple("provider", "provider", "Streaming services")).forEach { (ref, lookup, name) ->
            append("<label>").append(name).append("</label><div class=pick data-ref=").append(ref).append(" data-lookup=").append(lookup).append("><div class=chosen></div><input type=search autocomplete=off placeholder=\"Search to add\" aria-label=\"Search ").append(name.lowercase()).append("\"><div class=results></div></div>")
        }
        append("</fieldset>")
        append("<fieldset data-kind=trakt><legend>Trakt list</legend><div id=trakt-find><div class=tabs2><button type=button data-tab=mine>My lists</button><button type=button data-tab=liked>Liked</button><button type=button data-tab=popular>Popular</button><button type=button data-tab=trending>Trending</button></div>")
        append("<input type=search autocomplete=off placeholder=\"Search Trakt lists\" aria-label=\"Search Trakt lists\"><div class=results></div></div>")
        append("<label for=tk>List number, or a link that contains it</label><input id=tk name=trakt placeholder=\"Pick a list above, or paste here\">")
        append("<label for=tkm>Show</label><select id=tkm name=traktMedia><option value=MOVIE>Movies</option><option value=TV>Shows</option></select></fieldset>")
        append("<fieldset data-kind=tmdb><legend>TMDB list</legend><label for=tm>List link or number</label><input id=tm name=tmdb placeholder=\"https://www.themoviedb.org/list/…\">")
        append("<label for=tmm>Show</label><select id=tmm name=tmdbMedia><option value=MOVIE>Movies</option><option value=TV>Shows</option></select></fieldset>")
        append("<fieldset data-kind=mdblist><legend>MDBList list</legend><label for=md>List link, user/list-name or number</label><input id=md name=mdblist placeholder=\"https://mdblist.com/lists/user/list-name\">")
        append("<label for=mdm>Show</label><select id=mdm name=mdblistMedia><option value=''>Movies and shows</option><option value=MOVIE>Movies</option><option value=TV>Shows</option></select><p class=m>Needs your MDBList API key (Setup &gt; API keys).</p></fieldset>")
        append("<fieldset data-kind=list><legend>My lists</legend><select name=list>"); options(content.lists); append("</select><p class=m>Tracker lists appear here once the TV has loaded them.</p></fieldset>")
        append("<button>Save</button><p class=m>Saving sends the widget to the TV. Approve it there in Phone setup.</p></form>")
        append("<script>").append(BIND).append("bind('source','data-source');bind('kind','data-kind');").append(FINDERS).append("</script>")
    }

    /** Width over height of a folder tile, for its picture (#234). */
    private fun ratio(shape: String) = when (shape) { "wide" -> "16/9"; "poster" -> "2/3"; else -> "1/1" }
    private fun StringBuilder.picture(cover: String?, name: String, shape: String) {
        append("<span class=pf style=\"aspect-ratio:").append(ratio(shape)).append("\">")
        web(cover)?.let { append("<img loading=lazy referrerpolicy=no-referrer alt=\"\" src=\"").append(esc(it)).append("\">") } ?: append(esc(name.take(1).uppercase()))
        append("</span>")
    }

    /**
     * The collections list (#160): every folder with its cover, shape and sources, and move, edit and
     * delete buttons. #234: as on the TV, a collection shows its backdrop and folder pictures, and its
     * folders are picture tiles in their own shapes.
     */
    private fun StringBuilder.library(content: PhoneContent, auth: String) {
        append("<div class=cols>")
        content.library.forEach { c ->
            val backdrop = web(c.cover) ?: c.folders.firstNotNullOfOrNull { web(it.cover) }
            append("<section class=\"card lib\"><div class=\"head ch\"><span class=cv>")
            backdrop?.let { append("<img loading=lazy referrerpolicy=no-referrer alt=\"\" src=\"").append(esc(it)).append("\">") } ?: append(esc(c.title.take(1).uppercase()))
            append("</span><h2>").append(esc(c.title)).append("</h2><span class=m>").append(c.folders.size).append(if (c.folders.size == 1) " folder" else " folders").append("</span></div>")
            // #165: a long collection opens on demand instead of filling the page; closed, it still shows its first folders.
            append("<details class=fold").append(if (c.folders.size <= FOLDERS_OPEN) " open" else "").append("><summary>")
            if (c.folders.isNotEmpty()) {
                append("<span class=strip aria-hidden=true>")
                c.folders.take(STRIP).forEach { f -> picture(f.cover, f.title.ifBlank { "Folder" }, f.shape) }
                if (c.folders.size > STRIP) append("<small>+").append(c.folders.size - STRIP).append("</small>")
                append("</span>")
            }
            append("Folders</summary><div class=fgrid>")
            c.folders.forEachIndexed { index, f ->
                val name = f.title.ifBlank { "Folder" }
                append("<div class=ft><span class=pic>"); picture(f.cover, name, f.shape)
                append("</span><div class=t><b>").append(esc(name)).append("</b><small><span class=chip>").append(shapeName(f.shape)).append("</span>")
                    .append(esc(f.sources.joinToString(" · ") { it.second }.ifBlank { "No sources" })).append("</small></div><div class=acts>")
                post(auth, "move-folder", f.id, "<input type=hidden name=dir value=up>", "Move $name up", "&uarr;", disabled = index == 0)
                post(auth, "move-folder", f.id, "<input type=hidden name=dir value=down>", "Move $name down", "&darr;", disabled = index == c.folders.lastIndex)
                append("<a class=ib href=\"/content?k=$auth&amp;edit=").append(esc(f.id)).append("\" aria-label=\"Edit ").append(esc(name)).append("\" title=\"Edit ").append(esc(name)).append("\">&#9998;</a>")
                post(auth, "delete", f.id, "", "Delete $name", "&#10005;", danger = true)
                append("</div></div>")
            }
            append("</div></details><a class=\"btn quiet\" href=\"/content?k=$auth&amp;edit=new&amp;in=").append(esc(c.id)).append("\">Add a folder</a></section>")
        }
        append("<section class=card><h2>New collection</h2><p class=m>A collection is a row on Home and Discover; each folder in it is a tile that opens one or more catalogs.</p>")
        append("<a class=btn href=\"/content?k=$auth&amp;edit=new\">Create a collection</a></section></div>")
    }

    /**
     * The folder editor (#160): title, tile shape, hide title, cover and sources in one form, so one
     * Save sends the whole edit to the TV. [edit] is a folder id or "new"; [into] the collection a new
     * folder joins (absent: a new collection).
     */
    fun editor(content: PhoneContent, code: String, edit: String, into: String?): String = buildString {
        val auth = esc(code)
        val owner = if (edit == "new") content.library.firstOrNull { it.id == into } else content.library.firstOrNull { c -> c.folders.any { it.id == edit } }
        val folder = owner?.folders?.firstOrNull { it.id == edit }
        append("<p><a class=back href=\"/content?k=$auth&amp;part=collections\">&lsaquo; Collections</a></p>")
        if (edit != "new" && folder == null) { append("<p class=ok>That folder no longer exists. Go back and pick another.</p>"); return@buildString }
        append("<form class=ed method=post action=\"/content?k=$auth\"><input type=hidden name=action value=save-folder>")
        append("<input type=hidden name=target value=\"").append(esc(edit)).append("\"><input type=hidden name=in value=\"").append(esc(owner?.id ?: "new")).append("\">")
        append("<h2>").append(if (folder == null) "New folder" else "Edit folder").append("</h2>")
        if (owner == null) append("<label for=ct>Collection title</label><p class=m>The row's name on Home, for example Studios.</p><input id=ct name=collection maxlength=100 required>")
        else append("<p class=m>In the collection <b>").append(esc(owner.title)).append("</b></p>")
        append("<label for=ft>Folder title</label><input id=ft name=name maxlength=100 required value=\"").append(esc(folder?.title.orEmpty())).append("\">")
        append("<label>Tile shape</label><div class=shapes>")
        val shape = folder?.shape ?: "wide"
        listOf(Triple("POSTER", "poster", "Poster"), Triple("LANDSCAPE", "wide", "Wide"), Triple("SQUARE", "square", "Square")).forEach { (value, key, name) ->
            append("<label><input type=radio name=shape value=").append(value).append(if (shape == key) " checked" else "").append("><span><i class=").append(key).append("></i>").append(name).append("</span></label>")
        }
        append("</div><label class=\"tick opt\"><span>Hide title<small>Only show the cover image</small></span><input class=sw type=checkbox role=switch name=hideTitle").append(if (folder?.hideTitle == true) " checked" else "").append("></label>")
        append("<label for=cover>Cover image</label><p class=m>A link to an image, or leave empty to show the title.</p>")
        append("<input id=cover name=cover type=url inputmode=url autocomplete=off placeholder=\"https://…\" value=\"").append(esc(web(folder?.cover).orEmpty())).append("\">")
        append("<img id=pv class=cover referrerpolicy=no-referrer alt=\"Cover preview\" hidden>")
        append("<h3>Content sources</h3><p class=m>What this folder shows. Several sources are combined.</p>")
        folder?.sources?.forEach { (id, label) ->
            append("<label class=\"tick src\"><span>").append(esc(label)).append("</span><input type=checkbox name=\"remove:").append(esc(id)).append("\"> Remove</label>")
        }
        append("<label for=source>").append(if (folder == null) "First source" else "Add another source").append("</label><select name=source id=source>")
        append("<option value=''>").append(if (folder == null) "Choose a catalog or saved search" else "Don't add another").append("</option>"); options(content.sources); append("</select>")
        extras(content.catalogExtras, "extra", "data-source")
        append("<button>Save</button><p class=m>Saving sends the edit to the TV. Approve it there in Phone setup.</p></form>")
        append("<script>").append(BIND).append("bind('source','data-source');(function(){var i=document.getElementById('cover'),p=document.getElementById('pv');function show(){var v=i.value.trim();if(/^https?:\\/\\//i.test(v)){p.src=v;p.hidden=false}else{p.hidden=true}}p.onerror=function(){p.hidden=true};i.addEventListener('input',show);show();})();</script>")
    }

    /** Organize content's three pages (#165): Home widgets, Collections, and the source and addon tools. */
    val PARTS = listOf("widgets", "collections", "tools")
    fun title(part: String) = when (part) { "widgets" -> "Home widgets"; "collections" -> "Collections"; else -> "Sources and addon tools" }
    /** The page a change belongs to, so the answer to a form shows the page it was sent from. */
    fun partOf(action: String?) = when (action) {
        "save-widget", "move-widget", "order-widgets", "delete-widget", "order-home", "move-home", "home-on", "home-off" -> "widgets"
        "save-folder", "move-folder", "delete" -> "collections"
        else -> "tools"
    }

    /** [part] is one of [PARTS], or null for everything on one page. */
    fun render(content: PhoneContent, code: String, part: String? = null): String = buildString {
        val auth = esc(code)
        if (part != null) append("<p><a class=back href=\"/?k=$auth&amp;tab=").append(if (part == "tools") "addons" else "ui").append("\">&lsaquo; ").append(if (part == "tools") "Addons" else "UI").append("</a></p>")
        if (part == null) append("<h2>Organize content</h2>")
        append("<p class=m>Changes are sent to the TV for review. Approve them there before they are saved.</p>")
        if (part == null || part == "widgets") widgets(content, auth)
        if (part == null) append("<h2>Collections</h2>")
        if (part == null || part == "collections") library(content, auth)
        if (part != null && part != "tools") return@buildString
        // Each task is its own card.
        append(if (part == null) "<h2>More tools</h2><div class=cols>" else "<div class=cols>")
        var open = false
        fun group() { if (open) append("</div>"); open = true; append("<div class=grp>") }
        // #158: headed sections, so creating, exporting and deleting are easy to find.
        group(); append("<h3>Create or add to a collection</h3><p class=m>The quick way to drop one catalog into a new or existing folder. To create a collection here, keep Destination on <b>New collection and folder</b> and name both.</p>")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=add-source>")
        append("<label>Catalog or saved search</label><select name=source id=source>"); options(content.sources); append("</select>")
        extras(content.catalogExtras, "extra", "data-source")
        append("<label>Destination</label><select name=target><option value=new>New collection and folder</option>"); options(content.targets); append("</select>")
        append("<input name=collection maxlength=100 placeholder=\"New collection name\"><input name=folder maxlength=100 placeholder=\"New folder name\">")
        append("<button>Send collection change for review</button></form>")
        group(); append("<h3>Edit a folder</h3><form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=folder>")
        append("<label>Edit a folder</label><select name=target>"); options(content.targets.filterNot { it.first.startsWith("c-") }); append("</select>")
        append("<input name=name maxlength=100 placeholder=\"Folder name\"><input name=cover placeholder=\"Cover image URL (blank keeps current)\"><label><input type=checkbox name=clearCover>Remove current cover</label><button>Preview folder edit on TV</button></form>")
        group(); append("<h3>Edit a collection</h3><form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=collection><label>Collection</label><select name=collectionId>")
        options(content.collections); append("</select><input name=name maxlength=100 placeholder='New name (blank keeps current)'><select name=viewMode><option value=''>Keep layout</option><option value=TABBED_GRID>Tabs</option><option value=ROWS>Rows</option></select><select name=operation><option value=edit>Save changes</option><option value=up>Move up</option><option value=down>Move down</option><option value=duplicate>Duplicate</option></select><button>Preview collection change on TV</button></form>")
        group(); append("<h3>Edit a source</h3><form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=source><label>Existing folder source</label><select name=sourceId id=existing-source>")
        options(content.folderSources); append("</select>")
        extras(content.existingExtras, "edit-extra", "data-existing-source")
        append("<input name=name maxlength=100 placeholder='New source name (blank keeps current)'><label>Replace with catalog or saved search (optional)</label><select name=replacement><option value=''>Keep source</option>"); options(content.sources)
        append("</select><input name=year placeholder='Release year (blank keeps current)'><input name=rating placeholder='Minimum rating 0–10 (blank keeps current)'><select name=operation><option value=edit>Save changes</option><option value=up>Move source up</option><option value=down>Move source down</option></select><button>Preview source change on TV</button></form>")
        group(); append("<h3>Export</h3><p class=m>Downloads an encrypted pack to this phone. Import it on any Ember TV with the same passphrase.</p>")
        append("<form method=post action=\"/collection-export?k=$auth\"><label>Collection to export</label><select name=collectionId>")
        if (content.collections.size > 1) append("<option value=\"").append(EXPORT_ALL).append("\">All collections (").append(content.collections.size).append(")</option>")
        options(content.collections); append("</select><input type=password name=passphrase minlength=8 autocomplete=off placeholder='Export passphrase (at least 8 characters)'><button>Download encrypted collection</button></form>")
        append("<script>").append(BIND).append("bind('source','data-source');bind('existing-source','data-existing-source');</script>")
        group(); append("<h3>Delete</h3><p class=m>Deleting can't be undone; export first to keep a copy. Deleting a collection's only folder deletes the collection.</p>")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=delete><label>Collection or folder to delete</label><select name=target>")
        options(content.collections.map { (id, name) -> id to "$name (whole collection)" } + content.targets.filterNot { it.first.startsWith("c-") })
        append("</select><button>Send delete for review on the TV</button></form>")
        group(); append("<h3>Add-ons</h3><form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=addon><label>Add-on</label><select name=addon>")
        options(content.addons); append("</select><select name=enabled><option value=on>Enable</option><option value=off>Pause</option></select><button>Preview add-on change on TV</button></form>")
        append("</div></div>")
    }
}

/** Why a search made from the widget editor failed, in words that say what to do (#165). */
internal object PhoneLookups {
    /** "HTTP 403" from a provider error, and nothing else of it. */
    private fun http(message: String): String? = Regex("HTTP [0-9]{3}").find(message)?.value
    fun reason(kind: String, failure: Throwable): String {
        val message = failure.message.orEmpty()
        return when {
            message.contains("adult profile", true) -> "Switch the TV to an adult profile to search."
            kind == "trakt" && message.contains("client id", true) -> "Trakt search needs a Trakt client ID. Add one under Accounts & APIs > Trakt, then search again."
            kind == "trakt" && (message.contains("HTTP 401") || message.contains("HTTP 403")) -> "Trakt refused the request (${http(message)}). Check the Trakt client ID under Accounts & APIs."
            kind == "trakt" && http(message) != null -> "Trakt answered with an error (${http(message)}). Try again in a minute."
            kind == "trakt" -> "Trakt could not be reached. Check the TV's connection and try again."
            message.contains("key", true) -> "This search needs the TMDB key. Add it under Accounts & APIs > TMDB."
            else -> "The TV could not search right now. Check its connection and try again."
        }
    }
}
