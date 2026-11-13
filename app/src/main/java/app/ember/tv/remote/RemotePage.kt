package app.ember.tv.remote

/**
 * #241: the phone remote's page. One page, drawn by its own script from the TV's answers; every
 * title, name and line from the TV is set as text, never as markup.
 */
internal object RemotePage {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun shell(title: String, accent: String, body: String) = """<!doctype html><html lang=en><head><meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name=theme-color content="#1c1d21">
<meta name=apple-mobile-web-app-capable content=yes><meta name=mobile-web-app-capable content=yes><meta name=apple-mobile-web-app-status-bar-style content=black-translucent>
<title>${esc(title)}</title><style>:root{--accent:${accent.takeIf { ACCENT.matches(it) } ?: "#FF7A2F"}}$CSS</style></head><body>$body</body></html>"""

    fun login(tv: String, accent: String) = shell("Ember remote · $tv", accent, """
<main class=login><div class=card><div class=logo>&#9654;</div><h1>Ember remote</h1><p class=m>${esc(tv)}</p>
<form id=lf><input id=pw type=password autocomplete=current-password placeholder=Password aria-label=Password required autofocus>
<button>Sign in</button><p id=le class=err role=alert></p></form><p class=m>The password is set on the TV in Settings › Phone setup › Phone remote.</p></div></main>
<script>$LOGIN</script>""")

    fun app(tv: String, accent: String) = shell("Ember remote · $tv", accent, """
<div class=app>
<header><button class=ic id=bl aria-label="Your lists" title="Your lists">&#9733;</button>
<div class=tv><span class=dot></span><span>${esc(tv)}</span></div>
<button class=ic id=bk aria-label="Keyboard and arrows" title="Keyboard and arrows">&#9000;</button>
<button class=ic id=bs aria-label=Search title=Search><svg width=22 height=22 viewBox="0 0 24 24" fill=none stroke=currentColor stroke-width=2.4 stroke-linecap=round aria-hidden=true><circle cx=10.5 cy=10.5 r=6.5 /><path d="M20 20l-4.6-4.6"/></svg></button></header>
<div id=ask class=ask hidden role=alertdialog aria-live=assertive><b id=at></b><small id=ax></small><div id=ac class=acts></div></div>
<section id=vlists class=view><nav class=tabs><button data-l=progress class=on>In progress</button><button data-l=watchlist>Watchlist</button></nav><div id=list class=rows></div></section>
<section id=vsearch class=view hidden><form id=sf><input id=sq type=search placeholder="Search movies and shows" aria-label=Search autocomplete=off enterkeyhint=search></form><p id=sn class=m></p><div id=results class=rows></div></section>
<section id=vtitle class=view hidden><button class=back id=tb>&#8249; Back</button><div id=title></div></section>
<p id=toast role=status aria-live=polite></p>
<footer id=now hidden><div class=np><img id=ni alt="" referrerpolicy=no-referrer><div class=nt><b id=nn></b><small id=ns></small></div>
<button class=ic id=nw aria-label="Add to watchlist" title=Watchlist>&#9734;</button><button class="ic big" id=np aria-label=Pause>&#10074;&#10074;</button></div>
<div class=bar><span id=n0>0:00</span><input id=seek type=range min=0 max=1 value=0 aria-label=Position><span id=n1>0:00</span></div>
<div class=bar><button class=ic id=vd aria-label="Volume down">&#128264;</button><input id=vol type=range min=0 max=15 value=5 aria-label=Volume><button class=ic id=vu aria-label="Volume up">&#128266;</button></div>
<div class=ctl><button data-p=skip data-v=-10000>&#8634; 10</button><button data-p=skip data-v=30000>30 &#8635;</button><button id=nx data-p=next>Next &#9197;</button><button id=nwd>Watched</button><button data-p=stop>Stop</button></div></footer>
<div id=sheet class=sheet hidden><div class=sh><div class=grab></div><p id=kn class=m></p>
<form id=kf><input id=kt autocomplete=off autocapitalize=off placeholder="Type here; it appears on the TV" aria-label="Text for the TV"></form>
<div class=krow><button id=kbs>&#9003;</button><button id=kgo>Enter</button></div>
<div class=pad><button data-k=up class=u>&#9650;</button><button data-k=left class=l>&#9664;</button><button data-k=ok class=o>OK</button><button data-k=right class=r>&#9654;</button><button data-k=down class=d>&#9660;</button></div>
<div class=krow><button data-k=back>Back</button><button data-k=home>Home</button><button data-k=menu>Menu</button></div>
<button class=done id=kc>Done</button></div></div>
</div><script>$APP</script>""")

    private val ACCENT = Regex("^#[0-9A-Fa-f]{6}$")

    private const val CSS = """
*{box-sizing:border-box}html,body{margin:0;background:#1c1d21;color:#e8eaed;font:16px/1.35 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;-webkit-tap-highlight-color:transparent}
button,input{font:inherit;color:inherit}button{cursor:pointer;border:0;background:#2c2d32;border-radius:12px;padding:10px 14px}
input{width:100%;background:#2c2d32;border:1px solid #3a3b40;border-radius:12px;padding:12px 14px;outline:none}input:focus{border-color:var(--accent)}
.m{color:#a9adb3;font-size:14px;margin:6px 0}.err{color:#ffb4ab;min-height:18px;font-size:14px}[hidden]{display:none!important}
.login{min-height:100vh;display:grid;place-items:center;padding:20px}.login .card{width:100%;max-width:360px;background:#26272c;border-radius:22px;padding:26px;text-align:center}
.login h1{margin:8px 0 0;font-size:22px}.login form{display:grid;gap:12px;margin-top:16px}.login button{background:var(--accent);color:#111;font-weight:700;padding:13px}
.logo{width:52px;height:52px;margin:0 auto;border-radius:16px;background:var(--accent);color:#111;display:grid;place-items:center;font-size:22px}
.app{max-width:620px;margin:0 auto;min-height:100vh;padding:max(12px,env(safe-area-inset-top)) 14px 0;padding-bottom:calc(230px + env(safe-area-inset-bottom))}
header{display:flex;align-items:center;gap:10px;margin-bottom:14px}.tv{flex:1;display:flex;align-items:center;gap:8px;justify-content:center;background:#26272c;border:1px solid #34353a;border-radius:24px;padding:9px 14px;font-weight:600;min-width:0}
.tv span:last-child{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.dot{width:8px;height:8px;border-radius:50%;background:#34d399;flex:none}.dot.off{background:#ff6b6b}
.ic{width:46px;height:46px;border-radius:50%;padding:0;background:#26272c;border:1px solid #34353a;font-size:20px;display:grid;place-items:center;flex:none}
.tabs{display:flex;gap:8px;margin-bottom:10px}.tabs button{flex:1;background:#26272c}.tabs button.on{background:var(--accent);color:#111;font-weight:700}
.rows{display:grid;gap:2px}.row{display:flex;align-items:center;gap:14px;padding:8px;border-radius:14px;background:none;text-align:left;width:100%}
.row:active,.row.live{background:var(--accent);color:#111}.row.live small{color:#222}
.th{flex:none;width:112px;height:63px;border-radius:9px;background:#2c2d32;overflow:hidden;position:relative}.th img{width:100%;height:100%;object-fit:cover;display:block}
.th i{position:absolute;left:0;bottom:0;height:3px;background:var(--accent)}.rt{flex:1;min-width:0}.rt b{display:block;font-weight:600;overflow:hidden;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical}
.rt small{display:block;color:#a9adb3;font-size:14px;margin-top:2px}.star{flex:none;width:40px;height:40px;padding:0;background:none;font-size:22px;color:#a9adb3}.star.on{color:#f5c518}
#title .hero{border-radius:18px;overflow:hidden;background:#26272c center/cover no-repeat;aspect-ratio:16/9;position:relative}
#title .hero:after{content:"";position:absolute;inset:0;background:linear-gradient(transparent 40%,rgba(0,0,0,.85))}
#title h2{margin:12px 0 2px;font-size:22px}.acts{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.acts button.on{background:var(--accent);color:#111;font-weight:700}
.chips{display:flex;gap:8px;overflow-x:auto;padding:4px 0 8px}.chips button{flex:none}.chips button.on{background:var(--accent);color:#111;font-weight:700}
.src{display:block;width:100%;text-align:left;background:#26272c;border-radius:14px;padding:12px 14px;margin:6px 0}.src b{display:block}.src small{display:block;color:#a9adb3;font-size:13px;white-space:pre-line;margin-top:3px}
.bd{display:inline-block;font-size:11px;font-weight:700;border-radius:6px;padding:1px 6px;margin:6px 4px 0 0;background:#3a3b40}
.back{background:none;padding:6px 0;color:#a9adb3}h3{font-size:15px;margin:16px 0 6px;color:#a9adb3;font-weight:600}
footer{position:fixed;left:0;right:0;bottom:0;padding:0 10px max(10px,env(safe-area-inset-bottom));display:flex;justify-content:center}
footer>*{max-width:600px}#now{display:block}#now>div{max-width:600px;margin-left:auto;margin-right:auto}
#now{background:#26272c;border-top:1px solid #34353a;border-radius:24px 24px 0 0;padding-top:14px;box-shadow:0 -10px 30px rgba(0,0,0,.4)}
.np{display:flex;align-items:center;gap:12px;padding:0 6px}.np img{width:84px;height:48px;border-radius:9px;object-fit:cover;background:#2c2d32}.nt{flex:1;min-width:0}
.nt b{display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:17px}.nt small{color:#a9adb3;display:block;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.big{width:52px;height:52px;background:#e8eaed;color:#111;font-size:18px}#nw.on{color:#f5c518}
.bar{display:flex;align-items:center;gap:10px;padding:8px 6px 0}.bar span{font-size:13px;color:#a9adb3;min-width:44px;font-variant-numeric:tabular-nums}.bar span:last-child{text-align:right}
input[type=range]{flex:1;padding:0;border:0;background:none;accent-color:var(--accent);height:30px}
.bar .ic{width:36px;height:36px;font-size:16px}.ctl{display:flex;gap:6px;padding:8px 6px 0;overflow-x:auto}.ctl button{flex:1 0 auto;font-size:14px;padding:9px 10px}
#toast{position:fixed;left:50%;transform:translateX(-50%);top:12px;background:#3a3b40;border-radius:12px;padding:8px 14px;margin:0;font-size:14px;max-width:90%;opacity:0;transition:opacity .2s;pointer-events:none;z-index:9}#toast.show{opacity:1}
.sheet{position:fixed;inset:0;background:rgba(0,0,0,.5);display:flex;align-items:flex-end;z-index:5}.sh{width:100%;max-width:620px;margin:0 auto;background:#26272c;border-radius:24px 24px 0 0;padding:10px 16px max(16px,env(safe-area-inset-bottom))}
.grab{width:42px;height:5px;border-radius:3px;background:#4a4b50;margin:0 auto 8px}.krow{display:flex;gap:8px;margin-top:10px}.krow button{flex:1}
.pad{display:grid;grid-template-columns:repeat(3,72px);grid-template-rows:repeat(3,64px);gap:8px;justify-content:center;margin-top:14px}
.pad button{font-size:20px}.pad .u{grid-area:1/2}.pad .l{grid-area:2/1}.pad .o{grid-area:2/2;background:var(--accent);color:#111;font-weight:700;border-radius:50%}.pad .r{grid-area:2/3}.pad .d{grid-area:3/2}
.done{width:100%;margin-top:12px;background:#3a3b40}
.ask{position:sticky;top:8px;z-index:4;background:#2c2d32;border:2px solid var(--accent);border-radius:18px;padding:14px 16px;margin-bottom:12px;box-shadow:0 8px 24px rgba(0,0,0,.45)}
.ask b{display:block;font-size:17px}.ask small{display:block;color:#a9adb3;margin-top:2px}.ask .acts{margin:12px 0 0}.ask .acts button{flex:1 1 auto}.ask .acts button.go{background:var(--accent);color:#111;font-weight:700}.empty{color:#a9adb3;text-align:center;padding:30px 10px}
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
function api(name,params,post){var b=new URLSearchParams(params||{});
 var o=post?{method:'POST',headers:{'X-Ember-Remote':'1','Content-Type':'application/x-www-form-urlencoded'},body:b}:{};
 return fetch('/api/'+name+(post?'':'?'+b),o).then(function(r){if(r.status===401){location.reload();throw 0}return r.json()}).then(function(j){if(j&&j.ok===false&&j.error)toast(j.error);return j});}
var tt;function toast(t){var e=q('toast');e.textContent=t;e.classList.add('show');clearTimeout(tt);tt=setTimeout(function(){e.classList.remove('show')},2600)}
function time(ms){var s=Math.max(0,Math.round(ms/1000)),h=Math.floor(s/3600),m=Math.floor(s%3600/60),x=s%60;return (h?h+':'+(m<10?'0':''):'')+m+':'+(x<10?'0':'')+x}
var state={},liveRef=null,view='lists',listKind='progress';
function show(v){view=v;['lists','search','title'].forEach(function(n){q('v'+n).hidden=n!==v});if(v==='search')q('sq').focus()}
function thumb(it){var t=el('span','th');if(it.image){var i=el('img');i.alt='';i.loading='lazy';i.referrerPolicy='no-referrer';i.src=it.image;t.appendChild(i)}
 if(it.progress!=null){var p=el('i');p.style.width=Math.round(it.progress*100)+'%';t.appendChild(p)}return t}
function row(it){var r=el('div','row');r.setAttribute('role','button');r.tabIndex=0;r.dataset.ref=it.ref;r.dataset.tref=it.tref||'';if(it.tref&&it.tref===liveRef)r.classList.add('live');r.appendChild(thumb(it));
 var t=el('span','rt');t.appendChild(el('b',null,it.title));t.appendChild(el('small',null,it.sub));r.appendChild(t);
 var s=el('button','star'+(it.saved?' on':''),it.saved?'★':'☆');s.setAttribute('aria-label',(it.saved?'Remove from':'Add to')+' watchlist');
 s.onclick=function(e){e.stopPropagation();api('watchlist',{ref:it.ref},1).then(function(j){if(j.ok){it.saved=!it.saved;s.className='star'+(it.saved?' on':'');s.textContent=it.saved?'★':'☆';toast(it.saved?'Added to your watchlist':'Removed from your watchlist')}})};
 r.appendChild(s);r.onclick=function(){openTitle(it.ref)};r.onkeydown=function(e){if(e.key==='Enter')openTitle(it.ref)};return r}
function fill(box,items,empty){box.textContent='';if(!items||!items.length){box.appendChild(el('p','empty',empty));return}items.forEach(function(it){box.appendChild(row(it))})}
function loadLists(){api('lists').then(function(j){fill(q('list'),j[listKind],listKind==='progress'?'Nothing in progress.':'Your watchlist is empty.')})}
document.querySelectorAll('.tabs button').forEach(function(b){b.onclick=function(){document.querySelectorAll('.tabs button').forEach(function(x){x.classList.toggle('on',x===b)});listKind=b.dataset.l;loadLists()}});
q('bl').onclick=function(){show('lists');loadLists()};q('bs').onclick=function(){show('search')};q('tb').onclick=function(){show(prevView||'lists')};
var prevView='lists';
q('sf').addEventListener('submit',function(e){e.preventDefault();var v=q('sq').value.trim();if(v.length<2)return;q('sn').textContent='Searching on the TV…';q('sq').blur();
 api('search',{q:v}).then(function(j){q('sn').textContent=j.note||(j.items.length?'':'Nothing found.');fill(q('results'),j.items,'')})});
var dt;function openTitle(ref){prevView=view==='title'?prevView:view;show('title');q('title').textContent='';q('title').appendChild(el('p','m','Opening on the TV…'));
 api('open',{ref:ref},1).then(function(){clearTimeout(dt);dt=setTimeout(loadTitle,700)})}
function loadTitle(){api('details').then(function(d){if(view!=='title')return;drawTitle(d);clearTimeout(dt);if(d.open&&(d.loading||!d.sources.length&&!d.needsEpisode))dt=setTimeout(loadTitle,1500)})}
function drawTitle(d){var box=q('title');box.textContent='';if(!d.open){box.appendChild(el('p','empty','Nothing is open on the TV.'));return}
 var h=el('div','hero');if(d.background)h.style.backgroundImage='url("'+d.background.replace(/["\\\\]/g,'')+'")';box.appendChild(h);
 box.appendChild(el('h2',null,d.item.title));box.appendChild(el('p','m',d.item.sub));
 var a=el('div','acts'),w=el('button',d.watched?'on':'',d.watched?'✓ Watched':'Mark watched'),s=el('button',d.saved?'on':'',d.saved?'★ In watchlist':'☆ Add to watchlist');
 w.onclick=function(){api('watched',{},1).then(function(){setTimeout(loadTitle,400)})};s.onclick=function(){api('watchlist',{ref:d.item.ref},1).then(function(){setTimeout(loadTitle,300)})};
 a.appendChild(w);a.appendChild(s);box.appendChild(a);
 if(d.description)box.appendChild(el('p','m',d.description));
 if(d.seasons&&d.seasons.length>1){box.appendChild(el('h3',null,'Season'));var c=el('div','chips');d.seasons.forEach(function(n){var b=el('button',n===d.season?'on':'',n===0?'Specials':'Season '+n);b.onclick=function(){api('season',{season:n},1).then(function(){setTimeout(loadTitle,500)})};c.appendChild(b)});box.appendChild(c)}
 if(d.episodes&&d.episodes.length){box.appendChild(el('h3',null,'Episodes'));var e=el('div','rows');d.episodes.forEach(function(x){var r=el('div','row'+(x.chosen?' live':''));r.setAttribute('role','button');r.tabIndex=0;
  r.appendChild(thumb({image:x.image}));var t=el('span','rt');t.appendChild(el('b',null,x.episode+'. '+x.title));t.appendChild(el('small',null,'S'+x.season+':E'+x.episode));r.appendChild(t);
  r.onclick=function(){api('episode',{season:x.season,episode:x.episode},1).then(function(){setTimeout(loadTitle,600)})};e.appendChild(r)});box.appendChild(e)}
 box.appendChild(el('h3',null,'Sources'));
 if(d.needsEpisode&&!d.sources.length)box.appendChild(el('p','m','Pick an episode to see its sources.'));
 else if(d.loading&&!d.sources.length)box.appendChild(el('p','m','Finding sources…'));
 else if(!d.sources.length)box.appendChild(el('p','m',d.note||'No sources found.'));
 d.sources.forEach(function(s){var b=el('button','src');b.appendChild(el('b',null,s.title));if(s.desc)b.appendChild(el('small',null,s.desc));(s.badges||[]).forEach(function(n){b.appendChild(el('span','bd',n))});
  b.onclick=function(){toast('Starting on the TV…');api('source',{index:s.index},1)};box.appendChild(b)});
 if(d.loading&&d.sources.length)box.appendChild(el('p','m','Still finding more…'))}
var dragging=false,volDrag=false;
q('seek').addEventListener('input',function(){dragging=true;q('n0').textContent=time(q('seek').value*1000)});
q('seek').addEventListener('change',function(){api('player',{action:'seek',value:q('seek').value*1000},1);setTimeout(function(){dragging=false},800)});
q('vol').addEventListener('input',function(){volDrag=true});q('vol').addEventListener('change',function(){api('volume',{action:'set',level:q('vol').value},1);setTimeout(function(){volDrag=false},800)});
q('vu').onclick=function(){api('volume',{action:'up'},1)};q('vd').onclick=function(){api('volume',{action:'down'},1)};
q('np').onclick=function(){api('player',{action:'toggle'},1).then(poll)};
document.querySelectorAll('[data-p]').forEach(function(b){b.onclick=function(){api('player',{action:b.dataset.p,value:b.dataset.v||''},1).then(poll)}});
q('nw').onclick=function(){var n=state.now;if(n&&n.ref)api('watchlist',{ref:n.ref},1).then(function(j){if(j.ok)toast(n.saved?'Removed from your watchlist':'Added to your watchlist');poll()})};
q('nwd').onclick=function(){var n=state.now;if(n&&n.ref)api('watched',{ref:n.ref},1).then(function(j){if(j.ok)toast(n.watched?'Marked unwatched':'Marked watched');poll()})};
var askKey='';
function ask(p){var box=q('ask');if(!p){box.hidden=true;askKey='';return}
 q('at').textContent=p.title;q('ax').textContent=p.text||'';q('ax').hidden=!p.text;
 var key=p.kind+'|'+p.choices.map(function(c){return c.id+':'+c.label}).join(',');
 if(key!==askKey){askKey=key;var row=q('ac');row.textContent='';
  p.choices.forEach(function(c,i){var b=el('button',i===0&&c.id!=='cancel'?'go':'',c.label);b.onclick=function(){box.hidden=true;askKey='';api('prompt',{kind:p.kind,choice:c.id},1).then(poll)};row.appendChild(b)});
  if(box.hidden&&navigator.vibrate)navigator.vibrate([20,40,20])}
 box.hidden=false}
function draw(s){state=s;ask(s.prompt);var n=s.now,f=q('now');q('kn').textContent=s.typing?'A text box is open on the TV: typing goes there.':'No text box is open on the TV: what you type is searched for.';
 if(!n){f.hidden=true;if(liveRef){liveRef=null;mark()}return}f.hidden=false;q('nn').textContent=n.title;q('ns').textContent=n.sub||(n.live?'Live':'');
 if(n.image&&q('ni').getAttribute('src')!==n.image)q('ni').src=n.image;q('ni').hidden=!n.image;
 q('np').innerHTML=n.playing?'&#10074;&#10074;':'&#9654;';q('np').setAttribute('aria-label',n.playing?'Pause':'Play');
 q('nw').hidden=!n.ref;q('nwd').hidden=!n.ref;q('nw').className='ic'+(n.saved?' on':'');q('nw').innerHTML=n.saved?'&#9733;':'&#9734;';q('nwd').textContent=n.watched?'✓ Watched':'Watched';
 if(!dragging){q('seek').max=Math.max(1,Math.round(n.duration/1000));q('seek').value=Math.round(n.position/1000);q('n0').textContent=time(n.position)}q('n1').textContent=n.live?'Live':time(n.duration);
 q('seek').disabled=n.live||!n.duration;
 if(liveRef!==(n.tref||null)){liveRef=n.tref||null;mark()}}
function mark(){document.querySelectorAll('.row[data-tref]').forEach(function(r){r.classList.toggle('live',!!liveRef&&r.dataset.tref===liveRef)})}
function vol(v){if(!v)return;q('vol').hidden=v.fixed;if(!volDrag){q('vol').max=v.max;q('vol').value=v.level}}
var busy=false;function poll(){if(busy)return;busy=true;api('state').then(function(s){q('bk').disabled=false;document.querySelector('.dot').classList.remove('off');draw(s);vol(s.volume)}).catch(function(){document.querySelector('.dot').classList.add('off')}).then(function(){busy=false})}
setInterval(function(){if(!document.hidden)poll()},1000);poll();loadLists();
// Keyboard and arrows.
var sheet=q('sheet'),kt=q('kt'),kd;q('bk').onclick=function(){sheet.hidden=false;kt.value='';poll()};q('kc').onclick=function(){sheet.hidden=true};
sheet.addEventListener('click',function(e){if(e.target===sheet)sheet.hidden=true});
function send(submit){var v=kt.value;if(!v&&!submit)return;api('text',{text:v,replace:'1',submit:submit?'1':''},1).then(function(j){if(j.searched){toast('Searching for “'+v+'” on the TV');sheet.hidden=true}})}
kt.addEventListener('input',function(){if(!state.typing)return;clearTimeout(kd);kd=setTimeout(function(){send(false)},250)});
q('kf').addEventListener('submit',function(e){e.preventDefault();send(true)});q('kgo').onclick=function(){send(true)};
q('kbs').onclick=function(){if(state.typing){kt.value=kt.value.slice(0,-1);api('backspace',{},1)}else kt.value=kt.value.slice(0,-1)};
document.querySelectorAll('[data-k]').forEach(function(b){b.onclick=function(){api('key',{key:b.dataset.k},1);if(navigator.vibrate)navigator.vibrate(8)}});
})();
"""
}
