package app.ember.tv.remote

/**
 * #241, #247: the phone remote, built as a small app: it can be added to the home screen (manifest
 * and icons), opens full screen, and has three tabs (Home, Search, Remote), a now-playing card, a
 * touch pad and sheets for questions and typing. Everything from the TV is set as text, never as
 * markup; markup is only set from the fixed icon table below.
 */
internal object RemotePage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private val ACCENT = Regex("^#[0-9A-Fa-f]{6}$")
    private fun accentOf(a: String) = a.takeIf { ACCENT.matches(it) } ?: "#FF7A2F"

    /** Line icons, 24×24, drawn with the current text colour. */
    private val ICONS: Map<String, String> = mapOf(
        "home" to """<path d="M3 11l9-7 9 7v9a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z"/>""",
        "search" to """<circle cx="11" cy="11" r="7"/><path d="M20 20l-3.6-3.6"/>""",
        "remote" to """<rect x="7" y="2" width="10" height="20" rx="3"/><circle cx="12" cy="8" r="2"/><path d="M10 14h4M10 17h4"/>""",
        "kbd" to """<rect x="2" y="6" width="20" height="12" rx="2.5"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7.5 14h9"/>""",
        "play" to """<path d="M8 5.5v13l10.5-6.5z" fill="currentColor"/>""",
        "pause" to """<path d="M7 5h3.2v14H7zM13.8 5H17v14h-3.2z" fill="currentColor" stroke="none"/>""",
        "rew" to """<path d="M4 12a8 8 0 1 0 2.4-5.7"/><path d="M4 4v4.5h4.5"/><text x="12" y="15.2" text-anchor="middle" font-size="7" font-weight="700" fill="currentColor" stroke="none">10</text>""",
        "fwd" to """<path d="M20 12a8 8 0 1 1-2.4-5.7"/><path d="M20 4v4.5h-4.5"/><text x="12" y="15.2" text-anchor="middle" font-size="7" font-weight="700" fill="currentColor" stroke="none">30</text>""",
        "voldown" to """<path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z"/><path d="M16 12h5"/>""",
        "volup" to """<path d="M4 9.5h3.5L12 6v12l-4.5-3.5H4z"/><path d="M16 9a4.5 4.5 0 0 1 0 6M18.8 6.5a8 8 0 0 1 0 11"/>""",
        "star" to """<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/>""",
        "starOn" to """<path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z" fill="currentColor"/>""",
        "check" to """<path d="M5 12.5l4.5 4.5L19 7.5"/>""",
        "next" to """<path d="M6 5.5l9 6.5-9 6.5z" fill="currentColor"/><path d="M18 5.5v13"/>""",
        "stop" to """<rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" stroke="none"/>""",
        "back" to """<path d="M15 5l-7 7 7 7"/>""",
        "close" to """<path d="M6 6l12 12M18 6L6 18"/>""",
        "up" to """<path d="M6 15l6-6 6 6"/>""",
        "down" to """<path d="M6 9l6 6 6-6"/>""",
        "left" to """<path d="M15 6l-6 6 6 6"/>""",
        "right" to """<path d="M9 6l6 6-6 6"/>""",
        "menu" to """<path d="M4 7h16M4 12h16M4 17h16"/>""",
        "audio" to """<path d="M4 10v4M8 7v10M12 4v16M16 8v8M20 11v2"/>""",
        "cc" to """<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10.5 10.2a2.2 2.2 0 1 0 0 3.6M17 10.2a2.2 2.2 0 1 0 0 3.6"/>""",
        "radio" to """<circle cx="12" cy="12" r="8"/>""",
        "radioOn" to """<circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="4" fill="currentColor" stroke="none"/>""",
        "erase" to """<path d="M9 5h11v14H9l-6-7z"/><path d="M12.5 9.5l5 5M17.5 9.5l-5 5"/>""",
    )

    private fun svg(name: String) = """<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${ICONS.getValue(name)}</svg>"""

    /** The icon table for the script, as JSON of fixed strings. */
    private fun iconsJson(): String = ICONS.keys.joinToString(",", "{", "}") { name ->
        "\"" + name + "\":" + kotlinx.serialization.json.JsonPrimitive(svg(name)).toString()
    }

    fun manifest(tv: String, accent: String): String {
        fun obj(vararg pairs: Pair<String, String>) = kotlinx.serialization.json.JsonObject(pairs.associate { (k, v) -> k to kotlinx.serialization.json.JsonPrimitive(v) })
        val icons = listOf(192, 512).map { size -> obj("src" to "/icon-$size.png", "sizes" to "${size}x$size", "type" to "image/png", "purpose" to "any maskable") } +
            obj("src" to "/icon.svg", "sizes" to "any", "type" to "image/svg+xml")
        val fields = obj("name" to "Ember remote · ${tv.take(40)}", "short_name" to "Ember remote", "start_url" to "/", "scope" to "/",
            "display" to "standalone", "orientation" to "portrait", "background_color" to "#0F1013", "theme_color" to "#0F1013",
            "description" to "Control Ember on your TV")
        return kotlinx.serialization.json.JsonObject(fields + ("icons" to kotlinx.serialization.json.JsonArray(icons))).toString()
    }

    fun iconSvg(accent: String) = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><rect width="100" height="100" fill="#121316"/><rect x="16" y="16" width="68" height="68" rx="20" fill="${accentOf(accent)}"/><path d="M43 36l23 14-23 14z" fill="#121316"/></svg>"""

    private fun shell(title: String, accent: String, body: String, script: String) = """<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover,user-scalable=no"><meta name=theme-color content="#0F1013">
<meta name=apple-mobile-web-app-capable content=yes><meta name=mobile-web-app-capable content=yes><meta name=apple-mobile-web-app-status-bar-style content=black-translucent>
<meta name=apple-mobile-web-app-title content="Ember remote"><meta name=format-detection content="telephone=no">
<link rel=manifest href="/manifest.webmanifest"><link rel=icon href="/icon.svg" type="image/svg+xml"><link rel=apple-touch-icon href="/apple-touch-icon.png">
<title>${esc(title)}</title><style>:root{--accent:${accentOf(accent)}}$CSS</style></head><body>$body<script>$script</script></body></html>"""

    fun login(tv: String, accent: String) = shell("Ember remote", accent, """
<main class=login><div class=lcard><div class=logo>${svg("play")}</div><h1>Ember remote</h1><p class=m>${esc(tv)}</p>
<form id=lf><input id=pw type=password autocomplete=current-password placeholder=Password aria-label=Password required>
<button class=primary>Sign in</button><p id=le class=err role=alert></p></form>
<p class=m>Set the password on the TV in Settings › Phone setup › Phone remote. This phone stays signed in.</p></div></main>""", LOGIN)

    fun app(tv: String, accent: String) = shell("Ember remote", accent, """
<div class=app>
<header class=top><div class=brand><span class=dot id=dot></span><div class=bt><b>Ember</b><small>${esc(tv)}</small></div></div>
<button class=round id=kbd aria-label="Type on the TV">${svg("kbd")}</button></header>
<main id=main>
<section id=vhome class=view>
<div id=ask class=ask hidden role=alertdialog aria-live=assertive><b id=at></b><small id=ax></small><div id=ac class=askb></div></div>
<div id=hero class=hero hidden><div class=hglow id=hglow></div><div class=hart id=hart></div><div class=hbody><small class=eye>Now playing</small><b id=ht></b><span id=hs></span>
<div class=seek><span id=h0>0:00</span><input type=range id=seek min=0 max=1 value=0 aria-label=Position><span id=h1>0:00</span></div>
<div class=ctl><button class=round data-p=skip data-v=-10000 aria-label="Back 10 seconds">${svg("rew")}</button><button class="round play" id=hpp aria-label=Pause>${svg("pause")}</button><button class=round data-p=skip data-v=30000 aria-label="Forward 30 seconds">${svg("fwd")}</button></div>
<div class=vol id=volrow><button class=round data-vol=down aria-label="Volume down">${svg("voldown")}</button><input type=range id=vol min=0 max=15 value=5 aria-label=Volume><button class=round data-vol=up aria-label="Volume up">${svg("volup")}</button></div>
<div class=chips><button id=hnext data-p=next>${svg("next")}<span>Next</span></button><button id=hwatched>${svg("check")}<span>Watched</span></button><button id=hsave>${svg("star")}<span>Watchlist</span></button><button data-p=stop>${svg("stop")}<span>Stop</span></button></div>
<div class=tracks><button id=haud hidden>${svg("audio")}<span><small>Sound</small><b id=haudv></b></span></button><button id=hsub hidden>${svg("cc")}<span><small>Subtitles</small><b id=hsubv></b></span></button></div></div></div>
<div id=idle class=idle><b>Nothing is playing</b><span>Pick something below, or search.</span></div>
<h2>Continue watching</h2><div id=progress class=strip></div>
<h2>Watchlist</h2><div id=watchlist class=strip></div>
</section>
<section id=vsearch class=view hidden>
<form id=sf class=sbar>${svg("search")}<input id=sq type=search placeholder="Movies and shows" aria-label=Search autocomplete=off enterkeyhint=search><button type=button id=sx class=clear aria-label=Clear hidden>${svg("close")}</button></form>
<div id=recent class=recent></div><p id=sn class=m></p><div id=results class=grid></div>
</section>
<section id=vremote class=view hidden>
<div class=seg><button data-mode=pad class=on>Touch pad</button><button data-mode=dpad>Buttons</button></div>
<div id=pad class=pad aria-label="Touch pad: swipe to move, tap to select"><span class=hint>Swipe to move · Tap to select</span><i id=dotp></i></div>
<div id=dpad class=dpad hidden><button data-k=up class=u aria-label=Up>${svg("up")}</button><button data-k=left class=l aria-label=Left>${svg("left")}</button><button data-k=ok class=o>OK</button><button data-k=right class=r aria-label=Right>${svg("right")}</button><button data-k=down class=d aria-label=Down>${svg("down")}</button></div>
<div class=keys><button data-k=back>${svg("back")}<span>Back</span></button><button data-k=home>${svg("home")}<span>Home</span></button><button data-k=menu>${svg("menu")}<span>Menu</span></button></div>
<div class=keys><button id=rpp>${svg("play")}<span>Play/Pause</span></button><button data-vol=down>${svg("voldown")}<span>Vol −</span></button><button data-vol=up>${svg("volup")}<span>Vol +</span></button><button id=rkbd>${svg("kbd")}<span>Type</span></button></div>
</section>
</main>
<div id=mini class=mini hidden><span class=mart id=mart></span><span class=mt><b id=mtt></b><small id=mts></small></span><button class=round id=mpp aria-label=Pause>${svg("pause")}</button></div>
<nav class=tabs><button data-v=home class=on>${svg("home")}<span>Home</span></button><button data-v=search>${svg("search")}<span>Search</span></button><button data-v=remote>${svg("remote")}<span>Remote</span></button></nav>
<div id=page class=page hidden><div class=pbar><button class=round id=pclose aria-label=Close>${svg("back")}</button></div><div id=pbody></div></div>
<div id=keys class=sheet hidden><div class=panel><div class=grab></div><p id=kn class=m></p>
<form id=kf><input id=kt autocomplete=off autocapitalize=off autocorrect=off spellcheck=false placeholder="Type here, it appears on the TV" aria-label="Text for the TV" enterkeyhint=send></form>
<div class=keys><button id=kbs>${svg("erase")}<span>Delete</span></button><button id=kgo class=primary><span>Enter</span></button></div><button class=done id=kc>Done</button></div></div>
<div id=tsheet class=sheet hidden><div class=panel><div class=grab></div><h3 id=tst class=sht></h3><div id=tlist class=tlist></div><button class=done id=tclose>Done</button></div></div>
<p id=toast role=status aria-live=polite></p>
</div>""", "var I=" + iconsJson() + ";" + APP)

    private const val CSS = """
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
:root{--bg:#0F1013;--s1:#17181C;--s2:#1F2126;--s3:#2A2C33;--line:rgba(255,255,255,.08);--fg:#F1F3F5;--mute:#9AA0A8;--top:env(safe-area-inset-top);--bot:env(safe-area-inset-bottom);--r:18px}
html,body{margin:0;height:100%;background:var(--bg);color:var(--fg);font:16px/1.35 -apple-system,BlinkMacSystemFont,"SF Pro Text",system-ui,Roboto,sans-serif;overscroll-behavior:none}
button,input{font:inherit;color:inherit}button{border:0;background:var(--s2);border-radius:14px;padding:12px 16px;touch-action:manipulation;user-select:none;-webkit-user-select:none;cursor:pointer;transition:transform .12s,background .2s}
button:active{transform:scale(.95)}button svg{width:22px;height:22px;display:block}
input{width:100%;background:var(--s2);border:1px solid var(--line);border-radius:14px;padding:13px 15px;outline:none}input:focus{border-color:var(--accent)}
.m{color:var(--mute);font-size:14px;margin:8px 2px}.err{color:#FFB4AB;min-height:18px;font-size:14px;margin:0}[hidden]{display:none!important}
.primary{background:var(--accent);color:#111;font-weight:700}
.round{width:46px;height:46px;padding:0;border-radius:50%;display:grid;place-items:center;flex:none;background:var(--s2)}
.login{min-height:100%;display:grid;place-items:center;padding:24px}.lcard{width:100%;max-width:360px;text-align:center}
.lcard h1{margin:14px 0 2px;font-size:24px}.lcard form{display:grid;gap:12px;margin:22px 0 10px}.lcard .primary{padding:14px}
.logo{width:64px;height:64px;margin:0 auto;border-radius:20px;background:var(--accent);color:#111;display:grid;place-items:center}.logo svg{width:30px;height:30px}
.app{height:100%;display:flex;flex-direction:column}
.top{display:flex;align-items:center;gap:12px;padding:calc(var(--top) + 10px) 16px 8px}
.brand{flex:1;display:flex;align-items:center;gap:10px;min-width:0}.bt b{display:block;font-size:18px;letter-spacing:.2px}.bt small{display:block;color:var(--mute);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.dot{width:10px;height:10px;border-radius:50%;background:#34D399;box-shadow:0 0 0 4px rgba(52,211,153,.15);flex:none}.dot.off{background:#FF6B6B;box-shadow:0 0 0 4px rgba(255,107,107,.15)}
main{flex:1;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch;overscroll-behavior:contain;padding:4px 16px 20px}
.view{animation:in .22s ease}@keyframes in{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
h2{font-size:17px;margin:22px 2px 10px}
.tabs{display:flex;background:rgba(23,24,28,.92);backdrop-filter:blur(18px);-webkit-backdrop-filter:blur(18px);border-top:1px solid var(--line);padding:3px 8px max(4px,calc(var(--bot) - 16px))}
.tabs button{flex:1;background:none;display:flex;flex-direction:column;align-items:center;gap:1px;padding:5px 0 3px;color:var(--mute);font-size:11px;font-weight:600;border-radius:12px}.tabs svg{width:21px;height:21px}
.tabs button.on{color:var(--fg)}.tabs button.on svg{color:var(--accent)}
.hero{position:relative;border-radius:24px;overflow:hidden;background:var(--s1);border:1px solid var(--line)}
.hart{height:150px;background:transparent center/cover no-repeat;position:relative;-webkit-mask-image:linear-gradient(#000 35%,transparent);mask-image:linear-gradient(#000 35%,transparent)}
.hbody{padding:0 18px 18px;margin-top:-34px;position:relative}.eye{color:var(--accent);font-weight:700;font-size:12px;letter-spacing:.8px;text-transform:uppercase}
.hbody>b{display:block;font-size:21px;margin-top:2px;line-height:1.2}.hbody>span{display:block;color:var(--mute);font-size:14px;margin-top:3px}
.seek{display:flex;align-items:center;gap:10px;margin-top:14px}.seek span{font-size:12px;color:var(--mute);min-width:42px;font-variant-numeric:tabular-nums}.seek span:last-child{text-align:right}
input[type=range]{flex:1;padding:0;border:0;background:none;accent-color:var(--accent);height:32px}
.ctl{display:flex;justify-content:center;align-items:center;gap:26px;margin:8px 0 4px}.ctl .round{width:54px;height:54px;background:none}.ctl .round svg{width:28px;height:28px}
.ctl .play{width:72px;height:72px;background:var(--fg);color:#111}.ctl .play svg{width:30px;height:30px}
.vol{display:flex;align-items:center;gap:10px;margin-top:6px}.vol .round{width:40px;height:40px;background:none;color:var(--mute)}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:14px}.pc .chips.scroll{flex-wrap:nowrap;overflow-x:auto;scrollbar-width:none}.chips::-webkit-scrollbar{display:none}
.hero .chips{display:grid;grid-template-columns:1fr 1fr}.hero .chips button{justify-content:center}.chips button{flex:none;display:flex;align-items:center;gap:7px;padding:10px 14px;font-size:14px;font-weight:600;border-radius:999px;background:var(--s2)}.chips button svg{width:18px;height:18px}.chips button.on{background:var(--accent);color:#111}
.hglow{position:absolute;inset:-60px;background:center/cover no-repeat;filter:blur(44px) saturate(1.5);opacity:.32;pointer-events:none}.hbody{z-index:1}.hart{z-index:1}
.tracks{display:grid;grid-template-columns:1fr 1fr;gap:8px;margin-top:8px}.tracks button{display:flex;align-items:center;gap:10px;text-align:left;padding:10px 12px;border-radius:16px;background:rgba(255,255,255,.06);border:1px solid var(--line);min-width:0}
.tracks svg{width:20px;height:20px;flex:none;color:var(--accent)}.tracks span{min-width:0}.tracks small{display:block;font-size:11px;color:var(--mute);font-weight:600;letter-spacing:.3px;text-transform:uppercase}.tracks b{display:block;font-size:14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.sht{margin:2px 4px 10px;font-size:17px}.tlist{display:grid;gap:6px;max-height:55vh;overflow-y:auto}.tlist button{display:flex;align-items:center;gap:12px;text-align:left;padding:13px 14px;background:var(--s2);font-size:15px}.tlist button.on{background:var(--s3)}.tlist button svg{flex:none;color:var(--mute)}.tlist button.on svg{color:var(--accent)}
.recent{display:flex;flex-wrap:wrap;gap:8px;margin:2px 0 6px}.recent button{padding:8px 13px;border-radius:999px;font-size:13px;background:var(--s1);border:1px solid var(--line);color:#C6CAD0}
.card .pic.sk{background:linear-gradient(90deg,var(--s1),var(--s2),var(--s1));background-size:200% 100%;animation:sk 1.2s linear infinite}
button:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
@media (orientation:landscape) and (max-height:540px){#vremote:not([hidden]){display:grid;grid-template-columns:1.3fr 1fr;gap:6px 16px;align-items:start}#vremote .seg{grid-column:1/-1}#vremote .pad,#vremote .dpad{grid-row:2/span 2}.pad{height:68vh}.hart{height:110px}}
.idle{border:1px dashed rgba(255,255,255,.14);border-radius:22px;padding:22px;text-align:center}.idle b{display:block}.idle span{color:var(--mute);font-size:14px}
.strip{display:flex;gap:12px;overflow-x:auto;scroll-snap-type:x mandatory;margin:0 -16px;padding:0 16px 4px;scrollbar-width:none}.strip::-webkit-scrollbar{display:none}
.card{flex:none;width:126px;scroll-snap-align:start;background:none;padding:0;text-align:left;border-radius:14px;position:relative}
.card .pic{display:block;width:100%;aspect-ratio:2/3;border-radius:14px;background:var(--s2) center/cover no-repeat;position:relative;overflow:hidden;border:1px solid var(--line)}
.card .bar{position:absolute;left:8px;right:8px;bottom:8px;height:4px;border-radius:2px;background:rgba(255,255,255,.25)}.card .bar i{display:block;height:100%;border-radius:2px;background:var(--accent)}
.card b{display:block;font-size:13px;font-weight:600;margin-top:7px;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}.card small{display:block;color:var(--mute);font-size:12px;margin-top:1px}
.card .fav{position:absolute;top:6px;right:6px;width:32px;height:32px;border-radius:50%;background:rgba(0,0,0,.55);display:grid;place-items:center;color:#fff}.card .fav svg{width:17px;height:17px}.card .fav.on{color:#F5C518}
.card.live .pic{outline:3px solid var(--accent);outline-offset:-3px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:14px 12px}.grid .card{width:auto}
.empty{color:var(--mute);font-size:14px;padding:6px 2px}
.sbar{position:sticky;top:0;z-index:2;display:flex;align-items:center;gap:10px;background:var(--s2);border-radius:16px;padding:0 6px 0 14px;margin-bottom:8px;border:1px solid var(--line)}
.sbar svg{width:20px;height:20px;color:var(--mute);flex:none}.sbar input{border:0;background:none;padding:14px 4px}.sbar input:focus{border:0}input[type=search]::-webkit-search-cancel-button{-webkit-appearance:none;appearance:none}.clear{width:38px;height:38px;padding:0;border-radius:50%;background:none;display:grid;place-items:center}
.seg{display:flex;background:var(--s1);border-radius:14px;padding:4px;margin-bottom:14px}.seg button{flex:1;background:none;font-weight:600;font-size:14px;padding:10px}.seg button.on{background:var(--s3)}
.pad{position:relative;height:min(52vh,420px);border-radius:28px;background:radial-gradient(120% 90% at 50% 0%,var(--s2),var(--s1));border:1px solid var(--line);touch-action:none;display:grid;place-items:end center;overflow:hidden;user-select:none;-webkit-user-select:none}
.pad .hint{color:var(--mute);font-size:13px;margin-bottom:16px;pointer-events:none}
.pad i{position:absolute;width:64px;height:64px;margin:-32px 0 0 -32px;border-radius:50%;background:var(--accent);opacity:0;pointer-events:none;transition:opacity .25s,transform .25s;transform:scale(.4)}.pad i.on{opacity:.35;transform:scale(1)}
.dpad{display:grid;grid-template-columns:repeat(3,84px);grid-template-rows:repeat(3,76px);gap:10px;justify-content:center;margin:6px 0 4px}
.dpad button{display:grid;place-items:center;border-radius:20px;background:var(--s2)}.dpad svg{width:28px;height:28px}.dpad .u{grid-area:1/2}.dpad .l{grid-area:2/1}.dpad .r{grid-area:2/3}.dpad .d{grid-area:3/2}
.dpad .o{grid-area:2/2;border-radius:50%;background:var(--accent);color:#111;font-weight:800;font-size:18px}
.keys{display:flex;gap:10px;margin-top:12px}.keys button{flex:1;display:flex;flex-direction:column;align-items:center;gap:5px;padding:12px 6px;font-size:12px;font-weight:600;border-radius:18px;background:var(--s1);border:1px solid var(--line)}
.mini{display:flex;align-items:center;gap:12px;margin:0 10px 8px;padding:8px 8px 8px 8px;border-radius:18px;background:rgba(36,38,44,.95);backdrop-filter:blur(14px);-webkit-backdrop-filter:blur(14px);border:1px solid var(--line);box-shadow:0 8px 28px rgba(0,0,0,.45)}
.mart{width:54px;height:36px;border-radius:9px;background:var(--s3) center/cover no-repeat;flex:none}.mt{flex:1;min-width:0}.mt b,.mt small{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.mt b{font-size:14px}.mt small{font-size:12px;color:var(--mute)}
.mini .round{width:42px;height:42px;background:var(--fg);color:#111}
.ask{border-radius:22px;padding:16px;margin:0 0 14px;background:linear-gradient(135deg,var(--s2),var(--s1));border:1.5px solid var(--accent);box-shadow:0 10px 30px rgba(0,0,0,.4);animation:in .25s ease}
.ask b{display:block;font-size:18px}.ask small{display:block;color:var(--mute);margin-top:3px;font-size:14px}.askb{display:grid;gap:8px;margin-top:14px}.askb button{padding:14px;font-weight:600}.askb button.go{background:var(--accent);color:#111}
.page{position:fixed;inset:0;z-index:10;background:var(--bg);overflow-y:auto;-webkit-overflow-scrolling:touch;animation:up .28s cubic-bezier(.2,.8,.2,1)}@keyframes up{from{transform:translateY(40px);opacity:0}to{transform:none;opacity:1}}
.pbar{position:sticky;top:0;z-index:2;padding:calc(var(--top) + 10px) 14px 0;height:0}.pbar .round{background:rgba(0,0,0,.55);backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px)}
.ph{height:46vh;max-height:360px;background:var(--s1) center/cover no-repeat;position:relative}.ph:after{content:"";position:absolute;inset:0;background:linear-gradient(180deg,rgba(15,16,19,.1) 40%,var(--bg))}
.pc{padding:0 18px calc(var(--bot) + 28px);margin-top:-60px;position:relative}.pc h1{font-size:26px;margin:0;line-height:1.15}.pc .sub{color:var(--mute);margin-top:6px;font-size:14px}
.pc .desc{color:#C6CAD0;font-size:14px;margin-top:12px;overflow:hidden;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical}.pc .desc.open{display:block}
.pc .chips{margin-top:16px}.pc h3{font-size:15px;margin:24px 0 10px;color:var(--mute);font-weight:600}
.eps{display:flex;gap:12px;overflow-x:auto;margin:0 -18px;padding:0 18px 4px;scroll-snap-type:x mandatory;scrollbar-width:none}.eps::-webkit-scrollbar{display:none}
.ep{flex:none;width:210px;scroll-snap-align:start;background:none;padding:0;text-align:left}.ep .pic{display:block;aspect-ratio:16/9;border-radius:14px;background:var(--s2) center/cover no-repeat;border:1px solid var(--line)}
.ep b{display:block;font-size:14px;margin-top:7px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.ep small{color:var(--mute);font-size:12px}.ep.on .pic{outline:3px solid var(--accent);outline-offset:-3px}
.src{display:flex;align-items:center;gap:12px;width:100%;text-align:left;background:var(--s1);border:1px solid var(--line);border-radius:18px;padding:14px;margin-top:10px}
.src .st{flex:1;min-width:0}.src b{display:block;font-size:15px}.src small{display:block;color:var(--mute);font-size:12.5px;white-space:pre-line;margin-top:4px;overflow:hidden;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical}
.src .go{width:42px;height:42px;border-radius:50%;background:var(--accent);color:#111;display:grid;place-items:center;flex:none}.src .go svg{width:20px;height:20px}
.bd{display:inline-block;font-size:11px;font-weight:700;border-radius:6px;padding:2px 7px;margin:7px 5px 0 0;background:var(--s3)}
.skel{background:linear-gradient(90deg,var(--s1),var(--s2),var(--s1));background-size:200% 100%;animation:sk 1.2s linear infinite;border-radius:14px;height:64px;margin-top:10px}@keyframes sk{to{background-position:-200% 0}}
.sheet{position:fixed;inset:0;z-index:20;background:rgba(0,0,0,.5);display:flex;align-items:flex-end}.panel{width:100%;max-width:640px;margin:0 auto;background:var(--s1);border-radius:26px 26px 0 0;padding:10px 16px calc(var(--bot) + 16px);animation:up .25s ease}
.grab{width:42px;height:5px;border-radius:3px;background:var(--s3);margin:0 auto 10px}.done{width:100%;margin-top:12px;background:var(--s2)}
#toast{position:fixed;left:50%;transform:translateX(-50%);top:calc(var(--top) + 12px);background:rgba(42,44,51,.97);border-radius:14px;padding:10px 16px;margin:0;font-size:14px;max-width:90%;opacity:0;transition:opacity .2s;pointer-events:none;z-index:30;box-shadow:0 6px 20px rgba(0,0,0,.4)}#toast.show{opacity:1}
@media(min-width:700px){main{padding:4px max(16px,calc(50% - 320px)) 20px}.mini{max-width:620px;margin:0 auto 8px;width:calc(100% - 20px)}}
"""

    private const val LOGIN = """
document.getElementById('lf').addEventListener('submit',function(e){e.preventDefault();var b=new URLSearchParams();b.set('password',document.getElementById('pw').value);
fetch('/login',{method:'POST',headers:{'X-Ember-Remote':'1','Content-Type':'application/x-www-form-urlencoded'},body:b}).then(function(r){return r.json()}).then(function(j){
if(j.ok)location.reload();else document.getElementById('le').textContent=j.error||'Not signed in.'}).catch(function(){document.getElementById('le').textContent='The TV did not answer.'})});
"""

    private const val APP = """
(function(){
function q(id){return document.getElementById(id)}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}
function icon(n){var s=el('span','i');s.innerHTML=I[n];return s}
function setIcon(b,n){var old=b.querySelector('svg');var s=icon(n).firstChild;if(old)b.replaceChild(s,old);else b.insertBefore(s,b.firstChild)}
function buzz(p){try{if(navigator.vibrate)navigator.vibrate(p||8)}catch(e){}}
var keep={get:function(k){try{return localStorage.getItem(k)}catch(e){return null}},set:function(k,v){try{localStorage.setItem(k,v)}catch(e){}}};
function api(name,params,post){var b=new URLSearchParams(params||{});
 var o=post?{method:'POST',headers:{'X-Ember-Remote':'1','Content-Type':'application/x-www-form-urlencoded'},body:b}:{};
 return fetch('/api/'+name+(post?'':'?'+b),o).then(function(r){if(r.status===401){location.reload();throw 0}return r.json()}).then(function(j){if(j&&j.ok===false&&j.error)toast(j.error);return j});}
var tt;function toast(t){var e=q('toast');e.textContent=t;e.classList.add('show');clearTimeout(tt);tt=setTimeout(function(){e.classList.remove('show')},2600)}
function time(ms){var s=Math.max(0,Math.round(ms/1000)),h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return (h?h+':'+(m<10?'0':''):'')+m+':'+(x<10?'0':'')+x}
function bg(node,url){if(url)node.style.backgroundImage='url("'+String(url).replace(/["\\\\]/g,'')+'")'}
var state={},liveRef=null,tab='home';
// Tabs.
function go(v){tab=v;keep.set('tab',v);['home','search','remote'].forEach(function(n){q('v'+n).hidden=n!==v});
 document.querySelectorAll('.tabs button').forEach(function(b){b.classList.toggle('on',b.dataset.v===v)});q('main').scrollTop=0;
 if(v==='home')loadHome();if(v==='search'){recents();setTimeout(function(){if(!q('sq').value)q('sq').focus()},250)}mini()}
document.querySelectorAll('.tabs button').forEach(function(b){b.onclick=function(){buzz(5);go(b.dataset.v)}});
// Cards.
function card(it){var c=el('button','card');c.dataset.tref=it.tref||'';if(it.tref&&it.tref===liveRef)c.classList.add('live');
 var p=el('span','pic');bg(p,it.image);if(it.progress!=null){var bar=el('span','bar'),fill=el('i');fill.style.width=Math.round(it.progress*100)+'%';bar.appendChild(fill);p.appendChild(bar)}
 var f=el('span','fav'+(it.saved?' on':''));f.appendChild(icon(it.saved?'starOn':'star').firstChild);f.setAttribute('role','button');f.setAttribute('aria-label',(it.saved?'Remove from':'Add to')+' watchlist');
 f.onclick=function(e){e.stopPropagation();buzz(10);api('watchlist',{ref:it.ref},1).then(function(j){if(j.ok){it.saved=!it.saved;f.className='fav'+(it.saved?' on':'');f.replaceChild(icon(it.saved?'starOn':'star').firstChild,f.firstChild);toast(it.saved?'Added to your watchlist':'Removed from your watchlist')}})};
 p.appendChild(f);c.appendChild(p);c.appendChild(el('b',null,it.title));c.appendChild(el('small',null,it.sub));c.onclick=function(){buzz(6);openTitle(it.ref)};return c}
function fill(box,items,empty){box.textContent='';if(!items||!items.length){box.appendChild(el('p','empty',empty));return}items.forEach(function(it){box.appendChild(card(it))})}
function skel(box){if(box.children.length)return;for(var i=0;i<3;i++){var c=el('div','card');c.appendChild(el('span','pic sk'));box.appendChild(c)}}
function loadHome(){skel(q('progress'));skel(q('watchlist'));api('lists').then(function(j){fill(q('progress'),j.progress,'Nothing in progress.');fill(q('watchlist'),j.watchlist,'Your watchlist is empty.')})}
// Search.
function recents(){var box=q('recent');box.textContent='';if(q('sq').value.trim())return;var r=[];try{r=JSON.parse(keep.get('recent')||'[]')}catch(e){}
 r.forEach(function(t){var b=el('button',null,t);b.onclick=function(){buzz(5);q('sq').value=t;search()};box.appendChild(b)})}
function remember(v){var r=[];try{r=JSON.parse(keep.get('recent')||'[]')}catch(e){}r=[v].concat(r.filter(function(x){return x.toLowerCase()!==v.toLowerCase()})).slice(0,6);keep.set('recent',JSON.stringify(r))}
var sTimer;function search(){var v=q('sq').value.trim();q('sx').hidden=!v;recents();if(v.length<2){q('results').textContent='';q('sn').textContent='';return}
 q('sn').textContent='Searching on the TV…';api('search',{q:v}).then(function(j){if(q('sq').value.trim()!==v)return;q('sn').textContent=j.note||(j.items.length?'':'Nothing found.');if(j.items.length)remember(v);fill(q('results'),j.items,'')})}
q('sq').addEventListener('input',function(){clearTimeout(sTimer);sTimer=setTimeout(search,550)});
q('sf').addEventListener('submit',function(e){e.preventDefault();clearTimeout(sTimer);q('sq').blur();search()});
q('sx').onclick=function(){q('sq').value='';search();q('sq').focus()};
// A title, full screen.
var dt,openRef=null;function openTitle(ref){openRef=ref;var pg=q('page');pg.hidden=false;pg.scrollTop=0;var b=q('pbody');b.textContent='';
 var h=el('div','ph');b.appendChild(h);var c=el('div','pc');c.appendChild(el('p','m','Opening on the TV…'));c.appendChild(el('div','skel'));c.appendChild(el('div','skel'));b.appendChild(c);
 api('open',{ref:ref},1).then(function(){clearTimeout(dt);dt=setTimeout(loadTitle,700)})}
function closeTitle(){q('page').hidden=true;openRef=null;clearTimeout(dt)}
q('pclose').onclick=function(){buzz(5);closeTitle()};
(function(){var pg=q('page'),y0=null,dy=0;pg.addEventListener('touchstart',function(e){y0=pg.scrollTop<=0?e.touches[0].clientY:null;dy=0},{passive:true});
 pg.addEventListener('touchmove',function(e){if(y0==null)return;dy=e.touches[0].clientY-y0;if(dy>0){pg.style.transform='translateY('+Math.min(dy*.6,160)+'px)';pg.style.opacity=String(1-Math.min(dy/600,.4))}},{passive:true});
 pg.addEventListener('touchend',function(){if(y0!=null&&dy>110){buzz(6);closeTitle()}pg.style.transform='';pg.style.opacity='';y0=null})})();
function loadTitle(){if(q('page').hidden)return;api('details').then(function(d){if(q('page').hidden)return;drawTitle(d);clearTimeout(dt);if(d.open&&(d.loading||!d.sources.length&&!d.needsEpisode))dt=setTimeout(loadTitle,1500)})}
function chip(label,ic,on,fn){var b=el('button',on?'on':'');b.appendChild(icon(ic).firstChild);b.appendChild(el('span',null,label));b.onclick=function(){buzz(8);fn()};return b}
function drawTitle(d){var b=q('pbody');var y=q('page').scrollTop;b.textContent='';if(!d.open){b.appendChild(el('p','empty','Nothing is open on the TV.'));return}
 var h=el('div','ph');bg(h,d.background);b.appendChild(h);var c=el('div','pc');b.appendChild(c);
 c.appendChild(el('h1',null,d.item.title));c.appendChild(el('div','sub',d.item.sub));
 var a=el('div','chips');a.appendChild(chip(d.watched?'Watched':'Mark watched','check',d.watched,function(){api('watched',{},1).then(function(){setTimeout(loadTitle,400)})}));
 a.appendChild(chip(d.saved?'In watchlist':'Watchlist',d.saved?'starOn':'star',d.saved,function(){api('watchlist',{ref:d.item.ref},1).then(function(){setTimeout(loadTitle,300)})}));c.appendChild(a);
 if(d.description){var ds=el('p','desc',d.description);ds.onclick=function(){ds.classList.toggle('open')};c.appendChild(ds)}
 if(d.seasons&&d.seasons.length>1){c.appendChild(el('h3',null,'Seasons'));var s=el('div','chips scroll');d.seasons.forEach(function(n){var x=el('button',n===d.season?'on':'',n===0?'Specials':'Season '+n);
  x.onclick=function(){buzz(6);api('season',{season:n},1).then(function(){setTimeout(loadTitle,500)})};s.appendChild(x)});c.appendChild(s)}
 if(d.episodes&&d.episodes.length){c.appendChild(el('h3',null,'Episodes'));var e=el('div','eps');d.episodes.forEach(function(x){var r=el('button','ep'+(x.chosen?' on':''));var p=el('span','pic');bg(p,x.image);r.appendChild(p);
  r.appendChild(el('b',null,x.episode+'. '+x.title));r.appendChild(el('small',null,'Season '+x.season+' · Episode '+x.episode));
  r.onclick=function(){buzz(6);api('episode',{season:x.season,episode:x.episode},1).then(function(){setTimeout(loadTitle,600)})};e.appendChild(r)});c.appendChild(e);
  setTimeout(function(){var on=e.querySelector('.on');if(on)e.scrollLeft+=on.getBoundingClientRect().left-e.getBoundingClientRect().left-18},0)}
 c.appendChild(el('h3',null,'Sources'));
 if(d.needsEpisode&&!d.sources.length)c.appendChild(el('p','m','Pick an episode to see its sources.'));
 else if(d.loading&&!d.sources.length){c.appendChild(el('p','m','Finding sources…'));c.appendChild(el('div','skel'));c.appendChild(el('div','skel'))}
 else if(!d.sources.length)c.appendChild(el('p','m',d.note||'No sources found.'));
 d.sources.forEach(function(s){var r=el('button','src');var t=el('span','st');t.appendChild(el('b',null,s.title));if(s.desc)t.appendChild(el('small',null,s.desc));
  (s.badges||[]).forEach(function(n){t.appendChild(el('span','bd',n))});r.appendChild(t);var g=el('span','go');g.appendChild(icon('play').firstChild);r.appendChild(g);
  r.onclick=function(){buzz(15);toast('Starting on the TV…');api('source',{index:s.index},1).then(function(j){if(j.ok)setTimeout(function(){closeTitle();go('home')},900)})};c.appendChild(r)});
 if(d.loading&&d.sources.length)c.appendChild(el('p','m','Still finding more…'));q('page').scrollTop=y}
// Now playing.
var dragging=false,volDrag=false;
q('seek').addEventListener('input',function(){dragging=true;q('h0').textContent=time(q('seek').value*1000)});
q('seek').addEventListener('change',function(){buzz(8);api('player',{action:'seek',value:q('seek').value*1000},1);setTimeout(function(){dragging=false},900)});
q('vol').addEventListener('input',function(){volDrag=true});q('vol').addEventListener('change',function(){api('volume',{action:'set',level:q('vol').value},1);setTimeout(function(){volDrag=false},900)});
document.querySelectorAll('[data-vol]').forEach(function(b){b.onclick=function(){buzz(6);api('volume',{action:b.dataset.vol},1).then(function(j){if(j.volume)vol(j.volume)})}});
function toggle(){buzz(10);api('player',{action:'toggle'},1).then(poll)}
q('hpp').onclick=toggle;q('mpp').onclick=toggle;q('rpp').onclick=toggle;
q('mini').onclick=function(e){if(e.target.closest('#mpp'))return;go('home')};
document.querySelectorAll('[data-p]').forEach(function(b){b.onclick=function(){buzz(8);api('player',{action:b.dataset.p,value:b.dataset.v||''},1).then(poll)}});
q('hsave').onclick=function(){var n=state.now;if(n&&n.ref){buzz(10);api('watchlist',{ref:n.ref},1).then(function(j){if(j.ok)toast(n.saved?'Removed from your watchlist':'Added to your watchlist');poll()})}};
q('hwatched').onclick=function(){var n=state.now;if(n&&n.ref){buzz(10);api('watched',{ref:n.ref},1).then(function(j){if(j.ok)toast(n.watched?'Marked unwatched':'Marked watched');poll()})}};
function mini(){var n=state.now;q('mini').hidden=!n||tab==='home'}
// #249: sound and subtitles while something plays.
function current(list,none){var on=(list||[]).filter(function(x){return x.on})[0];return on?on.label:none}
function tracks(n){var a=n.audio||[],s=n.subtitles||[];q('haud').hidden=a.length<2;q('hsub').hidden=!s.length;
 q('haudv').textContent=current(a,'Default');q('hsubv').textContent=current(s,'Off');if(!q('tsheet').hidden)fillTracks()}
var tkind='audio';function fillTracks(){var n=state.now;if(!n){q('tsheet').hidden=true;return}var list=tkind==='audio'?(n.audio||[]):(n.subtitles||[]);
 q('tst').textContent=tkind==='audio'?'Sound':'Subtitles';var box=q('tlist');box.textContent='';
 list.forEach(function(x,i){var b=el('button',x.on?'on':'');b.appendChild(icon(x.on?'radioOn':'radio').firstChild);b.appendChild(el('span',null,x.label));
  b.onclick=function(){buzz(10);api('track',{kind:tkind,index:i},1).then(function(j){if(j.ok){toast((tkind==='audio'?'Sound: ':'Subtitles: ')+x.label);setTimeout(poll,450)}});q('tsheet').hidden=true};box.appendChild(b)})}
function openTracks(k){tkind=k;buzz(6);fillTracks();q('tsheet').hidden=false}
q('haud').onclick=function(){openTracks('audio')};q('hsub').onclick=function(){openTracks('subtitles')};q('tclose').onclick=function(){q('tsheet').hidden=true};
q('tsheet').addEventListener('click',function(e){if(e.target===q('tsheet'))q('tsheet').hidden=true});
function draw(s){state=s;ask(s.prompt);var n=s.now;q('kn').textContent=s.typing?'A text box is open on the TV: what you type goes there.':'No text box is open on the TV: what you type is searched for.';
 q('hero').hidden=!n;q('idle').hidden=!!n;mini();
 if(!n){if(liveRef){liveRef=null;mark()}return}
 q('ht').textContent=n.title;q('hs').textContent=n.sub||(n.live?'Live':'');q('mtt').textContent=n.title;q('mts').textContent=n.sub||'';
 if(n.image&&q('hart').dataset.u!==n.image){q('hart').dataset.u=n.image;bg(q('hart'),n.image);bg(q('mart'),n.image);bg(q('hglow'),n.image)}
 [q('hpp'),q('mpp'),q('rpp')].forEach(function(b){setIcon(b,n.playing?'pause':'play');b.setAttribute('aria-label',n.playing?'Pause':'Play')});
 q('hsave').hidden=!n.ref;q('hwatched').hidden=!n.ref;q('hsave').classList.toggle('on',!!n.saved);setIcon(q('hsave'),n.saved?'starOn':'star');q('hwatched').classList.toggle('on',!!n.watched);
 if(!dragging){q('seek').max=Math.max(1,Math.round(n.duration/1000));q('seek').value=Math.round(n.position/1000);q('h0').textContent=time(n.position)}
 q('h1').textContent=n.live?'Live':time(n.duration);q('seek').disabled=n.live||!n.duration;tracks(n);
 if(liveRef!==(n.tref||null)){liveRef=n.tref||null;mark()}}
function mark(){document.querySelectorAll('.card[data-tref]').forEach(function(r){r.classList.toggle('live',!!liveRef&&r.dataset.tref===liveRef)})}
function vol(v){if(!v)return;q('vol').hidden=v.fixed;if(!volDrag){q('vol').max=v.max;q('vol').value=v.level}}
// The TV's questions.
var askKey='';
function ask(p){var box=q('ask');if(!p){box.hidden=true;askKey='';return}
 q('at').textContent=p.title;q('ax').textContent=p.text||'';q('ax').hidden=!p.text;
 var key=p.kind+'|'+p.choices.map(function(c){return c.id+':'+c.label}).join(',');
 if(key!==askKey){askKey=key;var row=q('ac');row.textContent='';
  p.choices.forEach(function(c,i){var b=el('button',i===0&&c.id!=='cancel'?'go':'',c.label);b.onclick=function(){buzz(12);box.hidden=true;askKey='';api('prompt',{kind:p.kind,choice:c.id},1).then(poll)};row.appendChild(b)});
  if(box.hidden){buzz([20,40,20]);if(tab!=='home')go('home');if(!q('page').hidden)closeTitle()}}
 box.hidden=false}
var busy=false;function poll(){if(busy)return;busy=true;api('state').then(function(s){q('dot').classList.remove('off');draw(s);vol(s.volume)}).catch(function(){q('dot').classList.add('off')}).then(function(){busy=false})}
setInterval(function(){if(!document.hidden)poll()},1000);document.addEventListener('visibilitychange',function(){if(!document.hidden){poll();if(tab==='home')loadHome()}});
// Remote: touch pad or buttons.
var kq=Promise.resolve();function key(k){buzz(k==='ok'?14:6);kq=kq.then(function(){return api('key',{key:k},1)}).catch(function(){})}
// Arrow buttons repeat while held; the others press once.
document.querySelectorAll('[data-k]').forEach(function(b){var k=b.dataset.k;
 if(['up','down','left','right'].indexOf(k)<0){b.onclick=function(){key(k)};return}
 var t1,t2;function stop(){clearTimeout(t1);clearInterval(t2)}
 b.addEventListener('pointerdown',function(e){e.preventDefault();key(k);t1=setTimeout(function(){t2=setInterval(function(){key(k)},160)},420)});
 ['pointerup','pointerleave','pointercancel'].forEach(function(n){b.addEventListener(n,stop)})});
function mode(m){keep.set('pad',m);q('pad').hidden=m!=='pad';q('dpad').hidden=m!=='dpad';document.querySelectorAll('.seg button').forEach(function(b){b.classList.toggle('on',b.dataset.mode===m)})}
document.querySelectorAll('.seg button').forEach(function(b){b.onclick=function(){buzz(5);mode(b.dataset.mode)}});
(function(){var pad=q('pad'),dot=q('dotp'),x0=0,y0=0,t0=0,moved=false,id=null,STEP=46;
 function at(e){var r=pad.getBoundingClientRect();dot.style.left=(e.clientX-r.left)+'px';dot.style.top=(e.clientY-r.top)+'px'}
 pad.addEventListener('pointerdown',function(e){id=e.pointerId;pad.setPointerCapture(id);x0=e.clientX;y0=e.clientY;t0=Date.now();moved=false;at(e);dot.classList.add('on')});
 pad.addEventListener('pointermove',function(e){if(e.pointerId!==id)return;at(e);var dx=e.clientX-x0,dy=e.clientY-y0;
  if(Math.max(Math.abs(dx),Math.abs(dy))<STEP)return;moved=true;
  if(Math.abs(dx)>Math.abs(dy)){key(dx>0?'right':'left');x0+=dx>0?STEP:-STEP;y0=e.clientY}else{key(dy>0?'down':'up');y0+=dy>0?STEP:-STEP;x0=e.clientX}});
 function end(e){if(e.pointerId!==id)return;id=null;dot.classList.remove('on');var dx=e.clientX-x0,dy=e.clientY-y0;
  if(!moved&&Math.abs(dx)<12&&Math.abs(dy)<12&&Date.now()-t0<450)key('ok')}
 pad.addEventListener('pointerup',end);pad.addEventListener('pointercancel',function(e){id=null;dot.classList.remove('on')})})();
// Typing on the TV.
var sheet=q('keys'),kt=q('kt'),kd;function openKeys(){buzz(6);sheet.hidden=false;kt.value='';poll();setTimeout(function(){kt.focus()},120)}
q('kbd').onclick=openKeys;q('rkbd').onclick=openKeys;q('kc').onclick=function(){sheet.hidden=true};
sheet.addEventListener('click',function(e){if(e.target===sheet)sheet.hidden=true});
function send(submit){var v=kt.value;if(!v&&!submit)return;api('text',{text:v,replace:'1',submit:submit?'1':''},1).then(function(j){if(j.searched){toast('Searching for “'+v+'” on the TV');sheet.hidden=true}})}
kt.addEventListener('input',function(){if(!state.typing)return;clearTimeout(kd);kd=setTimeout(function(){send(false)},250)});
q('kf').addEventListener('submit',function(e){e.preventDefault();buzz(10);send(true)});q('kgo').onclick=function(){buzz(10);send(true)};
q('kbs').onclick=function(){buzz(5);if(state.typing){kt.value=kt.value.slice(0,-1);api('backspace',{},1)}else kt.value=kt.value.slice(0,-1)};
// Start where the phone left off.
mode(keep.get('pad')==='dpad'?'dpad':'pad');go(['home','search','remote'].indexOf(keep.get('tab'))>=0?keep.get('tab'):'home');poll();
})();
"""
}
