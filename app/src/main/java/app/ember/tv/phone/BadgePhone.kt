package app.ember.tv.phone

import app.ember.tv.data.BadgeEditor
import app.ember.tv.data.BadgeFilter
import app.ember.tv.data.Badges
import app.ember.tv.data.appearance

/** #236: the badge option in use and your custom badges, for the phone page. */
data class PhoneBadges(val mode: String, val filters: List<BadgeFilter>)

/**
 * #236: the phone's badge editor. Each pattern shows its badge as the TV draws it, and opens a form
 * for its name, pattern, colours, border, corners and picture (a link or an uploaded file).
 * Pictures kept on the TV are shown through [ICON_PATH]; the page never shows file paths.
 */
internal object BadgePhone {
    const val ICON_PATH = "/badge-icon"
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** A colour for CSS: Android's #AARRGGBB as rgba(). */
    internal fun css(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        return "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},${String.format(java.util.Locale.ROOT, "%.2f", a / 255f)})"
    }

    /** Where the phone loads a badge's picture from: the web for links, the TV for everything else. */
    internal fun picture(f: BadgeFilter, auth: String): String? {
        val url = f.imageURL?.takeIf { it.isNotBlank() } ?: return null
        return if (url.startsWith("https://", true) || url.startsWith("http://", true)) url
        else "$ICON_PATH$auth&id=" + java.net.URLEncoder.encode(f.id, "UTF-8")
    }

    /** The badge as the TV draws it: a 24-pixel tag, a picture or text. */
    internal fun badge(f: BadgeFilter, auth: String): String {
        val look = f.appearance()
        val fill = look.background?.let(::css) ?: if (look.filled && f.imageURL.isNullOrBlank()) "#2c2d32" else "transparent"
        val border = look.border?.let { "border:${fmt(look.borderWidth)}px solid ${css(it)};" }.orEmpty()
        val style = "background:$fill;${border}border-radius:${fmt(look.radius)}px;color:${look.foreground?.let(::css) ?: "#e8eaed"}"
        val inner = picture(f, auth)?.let { "<img alt=\"\" referrerpolicy=no-referrer loading=lazy src=\"${esc(it)}\">" } ?: esc(f.name.ifBlank { "Badge" })
        return "<i class=bdg style=\"$style\">$inner</i>"
    }

    private fun fmt(v: Float) = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

    private fun colourInput(name: String, label: String, value: String?, none: String?, noneOn: Boolean) = buildString {
        append("<div class=cl><label>$label<input type=color name=$name value=\"${esc(value ?: "#ffffff").lowercase()}\"></label>")
        none?.let { append("<label class=tick><input type=checkbox name=${name}None${if (noneOn) " checked" else ""}> $it</label>") }
        append("</div>")
    }

    private fun range(name: String, label: String, min: Int, max: Int, step: Int, value: Int, unit: String = "") =
        "<label class=rl>$label <output>$value$unit</output><input type=range name=$name min=$min max=$max step=$step value=$value data-unit=\"$unit\"></label>"

    fun render(badges: PhoneBadges?, auth: String): String = buildString {
        append("<h2>Source badges</h2>")
        if (badges == null) { append("<p class=m>Badge editing is unavailable on this TV.</p>"); importForm(auth); return@buildString }
        val modeName = when (badges.mode) { Badges.LEGACY -> "Basic"; Badges.MODERN -> "Detailed"; else -> "Custom" }
        append("<p class=m>The TV shows <b>$modeName</b> badges. ")
        append(if (badges.mode == Badges.CUSTOM) "Changes here apply on the TV straight away." else "Changing your own badges here switches the TV to them.")
        append("</p>")
        // Start: add one, or copy the Detailed set to edit it.
        append("<section class=card><h3>Add a pattern</h3><form class=flat method=post action=\"/badge$auth\" enctype=multipart/form-data><input type=hidden name=action value=add>")
        append("<input name=name maxlength=60 placeholder=\"Badge name, e.g. REMUX\" required><textarea class=mono name=pattern rows=2 placeholder=\"Pattern (regular expression), e.g. \\bREMUX\\b\" required></textarea>")
        append("<button>Add pattern</button></form>")
        append("<form class=flat method=post action=\"/badge$auth\" enctype=multipart/form-data><input type=hidden name=action value=copy>")
        append("<label class=tick><input type=checkbox name=confirm required> Replace my badges with a copy of the Detailed badges</label><button class=quiet>Start from the Detailed badges</button></form></section>")
        if (badges.filters.isEmpty()) append("<p class=m>No custom badges yet.</p>")
        else {
            append("<p class=m>${badges.filters.size} ${if (badges.filters.size == 1) "pattern" else "patterns"}, in the order they show under a source. Tap one to change it.</p>")
            if (badges.filters.size > 8) append("<input id=bfind type=search placeholder=\"Find a badge\" aria-label=\"Find a badge\" autocomplete=off>")
            append("<div class=blist>")
            badges.filters.forEachIndexed { i, f -> filter(f, i, badges.filters.lastIndex, auth) }
            append("</div>")
        }
        importForm(auth)
        append("<script>").append(SCRIPT).append("</script>")
    }

    private fun StringBuilder.importForm(auth: String) {
        append("<details class=card><summary>Import a badge pack</summary><div class=in>")
        append("<form class=flat method=post action=\"/badges$auth\" enctype=multipart/form-data><p class=m>A badge pack (JSON). It replaces your custom badges. Pick the file, or paste its contents.</p>")
        append("<input type=file name=file accept=\".json,application/json\"><textarea name=json rows=3 placeholder=\"Or paste the JSON here\"></textarea><button>Use these badges</button></form></div></details>")
    }

    private fun StringBuilder.filter(f: BadgeFilter, index: Int, last: Int, auth: String) {
        val id = esc(f.id)
        val name = f.name.ifBlank { "Untitled" }
        append("<details class=\"card bd\" id=\"b-$id\" data-q=\"${esc((f.name + " " + f.pattern).lowercase())}\"><summary><span class=bp>").append(badge(f, auth)).append("</span>")
        append("<span class=bt><b>${esc(name)}</b><code>${esc(f.pattern)}</code></span>")
        if (!f.isEnabled) append("<em class=off>Off</em>")
        append("</summary><div class=in>")
        append("<form class=\"flat be\" method=post action=\"/badge$auth\" enctype=multipart/form-data><input type=hidden name=action value=save><input type=hidden name=id value=\"$id\">")
        append("<label class=\"tick sw2\"><span>Show this badge</span><input class=sw type=checkbox role=switch name=enabled value=true${if (f.isEnabled) " checked" else ""}></label>")
        append("<label>Name<input name=name maxlength=60 value=\"${esc(f.name)}\"></label>")
        append("<label>Pattern<textarea class=mono name=pattern rows=3>${esc(f.pattern)}</textarea></label>")
        append("<label>Try it<input class=try placeholder=\"A source name, e.g. Movie 2160p REMUX\" autocomplete=off></label><small class=tr></small>")
        append("<fieldset><legend>Colours and shape</legend><div class=cols2>")
        append(colourInput("tag", "Tag colour", BadgeEditor.rgb(f.tagColor), "No tag colour", f.tagColor == null))
        append(range("opacity", "Background opacity", 0, 100, 10, BadgeEditor.nearest(BadgeEditor.opacitySteps, BadgeEditor.opacity(f.tagColor).toFloat(), 100), "%"))
        append(colourInput("text", "Text colour", BadgeEditor.rgb(f.textColor), "Default", f.textColor == null))
        val thickness = if (f.borderColor == null) 0 else BadgeEditor.nearest(BadgeEditor.borderSteps, f.borderWidth ?: 1f, 1)
        append(colourInput("border", "Border colour", BadgeEditor.rgb(f.borderColor), null, false))
        append(range("borderWidth", "Border thickness", 0, 6, 1, thickness))
        append(range("radius", "Corner radius", 0, 12, 1, BadgeEditor.nearest((0..12).toList(), f.borderRadius, 6)))
        append(colourInput("tint", "Picture colour", BadgeEditor.rgb(f.imageTint), "Original colours", f.imageTint == null))
        append("</div></fieldset><fieldset><legend>Picture</legend>")
        val link = f.imageURL?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        append("<label>Picture link<input name=image type=url inputmode=url placeholder=\"https://… (blank keeps the current one)\" value=\"${esc(link.orEmpty())}\"></label>")
        append("<label>Or upload a picture (PNG, JPEG, WebP or GIF, up to ${BadgeEditor.MAX_ICON_BYTES / 1000} KB)<input type=file name=file accept=\"image/png,image/jpeg,image/webp,image/gif\"></label>")
        if (!f.imageURL.isNullOrBlank()) append("<label class=tick><input type=checkbox name=imageNone> Remove the picture (show text)</label>")
        append("</fieldset><button>Save ${esc(name)}</button></form><div class=bacts>")
        fun post(action: String, label: String, disabled: Boolean = false, danger: Boolean = false) =
            append("<form class=flat method=post action=\"/badge$auth\" enctype=multipart/form-data><input type=hidden name=action value=$action><input type=hidden name=id value=\"$id\">" +
                "<button class=\"${if (danger) "danger" else "quiet"}\"${if (disabled) " disabled" else ""}>$label</button></form>")
        post("up", "Move up", disabled = index == 0)
        post("down", "Move down", disabled = index == last)
        post("delete", "Delete", danger = true)
        append("</div></div></details>")
    }

    /** Live preview, the try-it field and search; the forms work without it. */
    private const val SCRIPT = """
function hexA(h,p){var n=parseInt(h.slice(1),16);return 'rgba('+(n>>16&255)+','+(n>>8&255)+','+(n&255)+','+(p/100)+')';}
document.querySelectorAll('form.be').forEach(function(f){
  var d=f.closest('details'),b=d.querySelector('.bdg'),v=function(n){return f.elements[n];};
  function paint(){
    f.querySelectorAll('input[type=range]').forEach(function(r){var o=r.parentNode.querySelector('output');if(o)o.textContent=r.value+(r.dataset.unit||'');});
    if(!b)return;var img=b.querySelector('img');
    b.style.background=v('tagNone').checked?(img?'transparent':'#2c2d32'):hexA(v('tag').value,+v('opacity').value);
    b.style.color=v('textNone').checked?'#e8eaed':v('text').value;
    var w=+v('borderWidth').value;b.style.border=w>0?w+'px solid '+v('border').value:'0';
    b.style.borderRadius=v('radius').value+'px';
    if(!img)b.textContent=v('name').value||'Badge';
  }
  f.addEventListener('input',paint);f.addEventListener('change',paint);
  var t=f.querySelector('.try'),out=f.querySelector('.tr');
  function test(){if(!t.value){out.textContent='';return;}try{out.textContent=new RegExp(v('pattern').value).test(t.value)?'Matches: this badge shows.':'No match.';}catch(e){out.textContent='The pattern cannot run here; the TV checks it when you save.';}}
  t.addEventListener('input',test);v('pattern').addEventListener('input',test);
});
var bf=document.getElementById('bfind');
if(bf)bf.addEventListener('input',function(){var q=bf.value.trim().toLowerCase();document.querySelectorAll('details.bd').forEach(function(d){d.hidden=!!q&&d.dataset.q.indexOf(q)<0;});});
"""

    const val CSS = """
.blist{display:grid;gap:8px;margin:10px 0}.blist details.card{margin:0}
details.bd>summary{gap:12px!important}.bp{flex:none;display:grid;place-items:center;width:96px;height:40px;border-radius:8px;background:#16171b}
.bt{flex:1;min-width:0}.bt b{display:block;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.bt code,textarea.mono{font:12px ui-monospace,Menlo,Consolas,monospace}.bt code{display:block;color:var(--mute);font-weight:400;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
em.off{flex:none;font-style:normal;font-size:11px;font-weight:700;color:var(--mute);border:1px solid #3a3b40;border-radius:6px;padding:1px 6px}
.bdg{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;height:24px;min-width:24px;padding:0 7px;font:600 12px system-ui,sans-serif;font-style:normal;white-space:nowrap;max-width:88px;overflow:hidden}
.bdg img{height:15px;width:auto;display:block}
form.be>label,fieldset label{display:block;margin-top:10px;font-size:14px;color:var(--mute)}.tr{display:block;color:#8fe3b0;font-size:12px;min-height:14px}
.cols2{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:4px 18px}
.cl label{display:flex;align-items:center;justify-content:space-between;gap:10px}.cl input[type=color]{width:52px;height:34px;padding:2px;margin:0}
.cl .tick{margin-top:2px;font-size:13px}.rl{display:block}.rl output{float:right;color:var(--fg);font-weight:600}.rl input{width:100%;margin-top:6px}
label.sw2{display:flex;justify-content:space-between;align-items:center;color:var(--fg)!important}
.bacts{display:flex;gap:8px;margin-top:10px}.bacts form{flex:1;margin:0}.bacts button{margin:0;padding:10px}
"""
}
