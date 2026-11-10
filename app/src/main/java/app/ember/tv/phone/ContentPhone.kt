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
)
/** [style] and [shape] are HomeWidgets values; [source] and [status] are labels. */
data class PhoneWidget(val id: String, val title: String, val style: String, val shape: String, val limit: Int, val source: String, val status: String,
    val canMoveUp: Boolean = true, val canMoveDown: Boolean = true)
data class PhoneCollection(val id: String, val title: String, val folders: List<PhoneFolder>)
/** [shape] is "poster", "wide" or "square"; [sources] are opaque ids with their labels, in order. */
data class PhoneFolder(val id: String, val title: String, val shape: String, val hideTitle: Boolean, val cover: String?, val sources: List<Pair<String, String>>)
data class PhoneCatalogExtras(val id: String, val label: String, val extras: List<app.ember.tv.data.CatalogExtraDef>)

object ContentPhone {
    /** The export form's value for every collection in one pack (#158); real ids are opaque hashes starting "c-". */
    const val EXPORT_ALL = "all"
    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private const val BIND = "function bind(id,attribute){const source=document.getElementById(id);function filters(){document.querySelectorAll('['+attribute+']').forEach(box=>{const on=box.getAttribute(attribute)===source.value;box.hidden=!on;box.querySelectorAll('input,select').forEach(input=>input.disabled=!on);});}source.addEventListener('change',filters);filters();}"
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

    /** Your Home widgets (#161), in Home order, with move, edit and delete, and Add a widget. */
    private fun StringBuilder.widgets(content: PhoneContent, auth: String) {
        append("<section class=\"card lib wide\"><div class=head><h2>Home widgets</h2><span class=m>").append(content.widgets.size).append(if (content.widgets.size == 1) " widget" else " widgets").append("</span></div>")
        append("<p class=m>Your own rows on the Ember Home. The banner, apps, catalogs and collections keep their place in Settings &gt; Home on the TV, where widgets can also be switched off.</p>")
        content.widgets.forEach { w ->
            val name = w.title.ifBlank { "Widget" }
            append("<div class=row><span class=\"thumb st-").append(esc(w.style)).append("\">").append(when (w.style) { app.ember.tv.data.HomeWidgets.NUMBERED -> "1"; app.ember.tv.data.HomeWidgets.CAROUSEL -> "&#9645;"; else -> "&#9776;" }).append("</span>")
            append("<div class=t><b>").append(esc(name)).append("</b><small><span class=chip>").append(esc(app.ember.tv.data.HomeWidgets.styleName(w.style))).append("</span>")
                .append(esc(w.source)).append(" · ").append(esc(w.status)).append("</small></div><div class=acts>")
            post(auth, "move-widget", w.id, "<input type=hidden name=dir value=up>", "Move $name up on Home", "&uarr;", disabled = !w.canMoveUp)
            post(auth, "move-widget", w.id, "<input type=hidden name=dir value=down>", "Move $name down on Home", "&darr;", disabled = !w.canMoveDown)
            append("<a class=ib href=\"/content?k=$auth&amp;widget=").append(esc(w.id)).append("\" aria-label=\"Edit ").append(esc(name)).append("\" title=\"Edit ").append(esc(name)).append("\">&#9998;</a>")
            post(auth, "delete-widget", w.id, "", "Delete $name", "&#10005;", danger = true)
            append("</div></div>")
        }
        append("<a class=btn href=\"/content?k=$auth&amp;widget=new\">Add a widget</a></section>")
    }

    /**
     * The widget editor (#161): title, style, card shape, size and source in one form. [id] is a
     * widget id or "new". An existing widget keeps its source unless another kind is chosen.
     */
    fun widgetEditor(content: PhoneContent, code: String, id: String): String = buildString {
        val auth = esc(code)
        val widget = content.widgets.firstOrNull { it.id == id }
        append("<p><a class=back href=\"/content?k=$auth\">&lsaquo; Organize content</a></p>")
        if (id != "new" && widget == null) { append("<p class=ok>That widget no longer exists. Go back and pick another.</p>"); return@buildString }
        append("<form class=ed method=post action=\"/content?k=$auth\"><input type=hidden name=action value=save-widget><input type=hidden name=target value=\"").append(esc(id)).append("\">")
        append("<h2>").append(if (widget == null) "New widget" else "Edit widget").append("</h2>")
        append("<label for=wt>Widget title</label><input id=wt name=title maxlength=100 required value=\"").append(esc(widget?.title.orEmpty())).append("\">")
        append("<label>Style</label><div class=shapes>")
        val style = widget?.style ?: app.ember.tv.data.HomeWidgets.ROW
        app.ember.tv.data.HomeWidgets.styles.forEach { (value, name) ->
            append("<label><input type=radio name=style value=").append(value).append(if (style == value) " checked" else "").append("><span><i class=\"w-").append(value).append("\"></i>").append(name).append("</span></label>")
        }
        append("</div><label for=shape>Card shape</label><select id=shape name=shape>")
        app.ember.tv.data.HomeWidgets.shapes.forEach { (value, name) -> append("<option value=").append(value).append(if ((widget?.shape ?: "default") == value) " selected" else "").append(">").append(name).append("</option>") }
        append("</select><p class=m>The cinematic carousel always uses large wide cards.</p>")
        append("<label for=limit>Titles to show</label><input id=limit name=limit type=number inputmode=numeric min=").append(app.ember.tv.data.HomeWidgets.LIMITS.first).append(" max=").append(app.ember.tv.data.HomeWidgets.LIMITS.last)
            .append(" value=").append(widget?.limit ?: app.ember.tv.data.HomeWidgets.DEFAULT_LIMIT).append(">")
        append("<h3>Content source</h3>")
        if (widget != null) append("<p class=m>Now: <b>").append(esc(widget.source)).append("</b></p>")
        append("<label for=kind>").append(if (widget == null) "Where the titles come from" else "Change the source").append("</label><select id=kind name=kind>")
        if (widget != null) append("<option value=keep>Keep the current source</option>")
        append("<option value=catalog>A catalog or saved Discover search</option><option value=filters>Filters</option><option value=trakt>A Trakt list</option><option value=tmdb>A TMDB list</option><option value=list>One of my lists</option></select>")
        append("<fieldset data-kind=catalog><legend>Catalog</legend><select name=source id=source>"); options(content.sources); append("</select>")
        extras(content.catalogExtras, "extra", "data-source")
        append("</fieldset><fieldset data-kind=filters><legend>Filters</legend><label for=fk>Type</label><select id=fk name=fkind>")
        app.ember.tv.discover.ExploreKind.entries.forEach { append("<option value=").append(it.name).append(">").append(esc(it.label)).append("</option>") }
        append("</select><label for=fs>Order</label><select id=fs name=fsort>")
        app.ember.tv.discover.ExploreSort.entries.forEach { append("<option value=").append(it.name).append(">").append(esc(it.label)).append("</option>") }
        append("</select><label>Genres (any of)</label><div class=picks>")
        app.ember.tv.discover.ExploreGenres.all.forEach { g -> append("<label><input type=checkbox name=\"genre:").append(esc(g.key)).append("\"><span>").append(esc(g.label)).append("</span></label>") }
        append("</div><label for=fy>From year</label><input id=fy name=fromYear type=number inputmode=numeric placeholder=\"Any\"><label for=ty>To year</label><input id=ty name=toYear type=number inputmode=numeric placeholder=\"Any\">")
        append("<label for=mr>Minimum rating (0–10)</label><input id=mr name=minRating type=number inputmode=decimal step=0.1 min=0 max=10 placeholder=\"Any\"></fieldset>")
        append("<fieldset data-kind=trakt><legend>Trakt list</legend><label for=tk>List link or number</label><input id=tk name=trakt placeholder=\"https://trakt.tv/lists/…\">")
        append("<label for=tkm>Show</label><select id=tkm name=traktMedia><option value=MOVIE>Movies</option><option value=TV>Shows</option></select></fieldset>")
        append("<fieldset data-kind=tmdb><legend>TMDB list</legend><label for=tm>List link or number</label><input id=tm name=tmdb placeholder=\"https://www.themoviedb.org/list/…\">")
        append("<label for=tmm>Show</label><select id=tmm name=tmdbMedia><option value=MOVIE>Movies</option><option value=TV>Shows</option></select></fieldset>")
        append("<fieldset data-kind=list><legend>My lists</legend><select name=list>"); options(app.ember.tv.data.HomeWidgets.lists); append("</select></fieldset>")
        append("<p class=m>MDBList lists come through their addon: add it in Setup, then pick its catalog above.</p>")
        append("<button>Save</button><p class=m>Saving sends the widget to the TV. Approve it there in Phone setup.</p></form>")
        append("<script>").append(BIND).append("bind('source','data-source');bind('kind','data-kind');</script>")
    }

    /** The collections list (#160): every folder with its cover, shape and sources, and move, edit and delete buttons. */
    private fun StringBuilder.library(content: PhoneContent, auth: String) {
        append("<div class=cols>")
        content.library.forEach { c ->
            append("<section class=\"card lib\"><div class=head><h2>").append(esc(c.title)).append("</h2><span class=m>").append(c.folders.size).append(if (c.folders.size == 1) " folder" else " folders").append("</span></div>")
            c.folders.forEachIndexed { index, f ->
                val name = f.title.ifBlank { "Folder" }
                append("<div class=row><span class=thumb>")
                web(f.cover)?.let { append("<img loading=lazy referrerpolicy=no-referrer alt=\"\" src=\"").append(esc(it)).append("\">") } ?: append(esc(name.take(1).uppercase()))
                append("</span><div class=t><b>").append(esc(name)).append("</b><small><span class=chip>").append(shapeName(f.shape)).append("</span>")
                    .append(esc(f.sources.joinToString(" · ") { it.second }.ifBlank { "No sources" })).append("</small></div><div class=acts>")
                post(auth, "move-folder", f.id, "<input type=hidden name=dir value=up>", "Move $name up", "&uarr;", disabled = index == 0)
                post(auth, "move-folder", f.id, "<input type=hidden name=dir value=down>", "Move $name down", "&darr;", disabled = index == c.folders.lastIndex)
                append("<a class=ib href=\"/content?k=$auth&amp;edit=").append(esc(f.id)).append("\" aria-label=\"Edit ").append(esc(name)).append("\" title=\"Edit ").append(esc(name)).append("\">&#9998;</a>")
                post(auth, "delete", f.id, "", "Delete $name", "&#10005;", danger = true)
                append("</div></div>")
            }
            append("<a class=\"btn quiet\" href=\"/content?k=$auth&amp;edit=new&amp;in=").append(esc(c.id)).append("\">Add a folder</a></section>")
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
        append("<p><a class=back href=\"/content?k=$auth\">&lsaquo; Collections</a></p>")
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

    fun render(content: PhoneContent, code: String): String = buildString {
        val auth = esc(code)
        append("<h2>Organize content</h2><p class=m>Changes are sent to the TV for review. Approve them there before they are saved.</p>")
        widgets(content, auth)
        append("<h2>Collections</h2>")
        library(content, auth)
        // #159: each task is its own card, and the cards flow into columns on tablets and wider screens.
        append("<h2>More tools</h2><div class=cols>")
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
