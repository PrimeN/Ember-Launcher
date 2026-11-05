package app.ember.tv.phone

/** Phone pages expose labels and opaque references, never configured add-on URLs or API keys. */
data class PhoneContent(
    val sources: List<Pair<String, String>>,
    val targets: List<Pair<String, String>>,
    val addons: List<Pair<String, String>>,
    val catalogExtras: List<PhoneCatalogExtras> = emptyList(),
    val collections: List<Pair<String, String>> = emptyList(),
    val folderSources: List<Pair<String, String>> = emptyList(),
    val existingExtras: List<PhoneCatalogExtras> = emptyList(),
)
data class PhoneCatalogExtras(val id: String, val label: String, val extras: List<app.ember.tv.data.CatalogExtraDef>)

object ContentPhone {
    private fun esc(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    fun render(content: PhoneContent, code: String): String = buildString {
        val auth = esc(code)
        append("<h2>Organize content</h2><p class=m>Changes are sent to the TV for review. Approve them there before they are saved.</p>")
        fun options(values: List<Pair<String, String>>) { values.forEach { (id, name) -> append("<option value=\"").append(esc(id)).append("\">").append(esc(name)).append("</option>") } }
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=add-source>")
        append("<label>Catalog or saved search</label><select name=source id=source>"); options(content.sources); append("</select>")
        fun extras(catalogs: List<PhoneCatalogExtras>, prefix: String, attribute: String) { catalogs.forEach { catalog ->
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
        extras(content.catalogExtras, "extra", "data-source")
        append("<label>Destination</label><select name=target><option value=new>New collection and folder</option>"); options(content.targets); append("</select>")
        append("<input name=collection maxlength=100 placeholder=\"New collection name\"><input name=folder maxlength=100 placeholder=\"New folder name\">")
        append("<button>Send collection change for review</button></form>")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=folder>")
        append("<label>Edit a folder</label><select name=target>"); options(content.targets.filterNot { it.first.startsWith("c-") }); append("</select>")
        append("<input name=name maxlength=100 placeholder=\"Folder name\"><input name=cover placeholder=\"Cover image URL (blank keeps current)\"><label><input type=checkbox name=clearCover>Remove current cover</label><button>Preview folder edit on TV</button></form>")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=collection><label>Collection</label><select name=collectionId>")
        options(content.collections); append("</select><input name=name maxlength=100 placeholder='New name (blank keeps current)'><select name=viewMode><option value=''>Keep layout</option><option value=TABBED_GRID>Tabs</option><option value=ROWS>Rows</option></select><select name=operation><option value=edit>Save changes</option><option value=up>Move up</option><option value=down>Move down</option><option value=duplicate>Duplicate</option></select><button>Preview collection change on TV</button></form>")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=source><label>Existing folder source</label><select name=sourceId id=existing-source>")
        options(content.folderSources); append("</select>")
        extras(content.existingExtras, "edit-extra", "data-existing-source")
        append("<input name=name maxlength=100 placeholder='New source name (blank keeps current)'><label>Replace with catalog or saved search (optional)</label><select name=replacement><option value=''>Keep source</option>"); options(content.sources)
        append("</select><input name=year placeholder='Release year (blank keeps current)'><input name=rating placeholder='Minimum rating 0–10 (blank keeps current)'><select name=operation><option value=edit>Save changes</option><option value=up>Move source up</option><option value=down>Move source down</option></select><button>Preview source change on TV</button></form>")
        append("<form method=post action=\"/collection-export?k=$auth\"><label>Export collection</label><select name=collectionId>")
        options(content.collections); append("</select><input type=password name=passphrase minlength=8 autocomplete=off placeholder='Export passphrase (at least 8 characters)'><button>Download encrypted collection</button></form>")
        append("""<script>function bind(id,attribute){const source=document.getElementById(id);function filters(){document.querySelectorAll('['+attribute+']').forEach(box=>{const on=box.getAttribute(attribute)===source.value;box.hidden=!on;box.querySelectorAll('input,select').forEach(input=>input.disabled=!on);});}source.addEventListener('change',filters);filters();}bind('source','data-source');bind('existing-source','data-existing-source');</script>""")
        append("<form method=post action=\"/content?k=$auth\"><input type=hidden name=action value=addon><label>Add-on</label><select name=addon>")
        options(content.addons); append("</select><select name=enabled><option value=on>Enable</option><option value=off>Pause</option></select><button>Preview add-on change on TV</button></form>")
    }
}
