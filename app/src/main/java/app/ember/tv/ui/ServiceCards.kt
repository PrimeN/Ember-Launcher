package app.ember.tv.ui

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.ember.tv.R

/**
 * #447: a service's look in settings: its logo (Simple Icons path data, drawn white) on its brand
 * colour, and the colours a focused row fills with. Services without a logo show their initials.
 */
internal data class ServiceBrand(@DrawableRes val logo: Int?, val tile: Color, val from: Color, val to: Color = from)

internal object ServiceBrands {
    private fun b(logo: Int?, hex: Long, to: Long? = null) = Color(hex).let { c -> ServiceBrand(logo, c, c, to?.let(::Color) ?: c) }
    private val brands: Map<String, ServiceBrand> = mapOf(
        "trakt" to b(R.drawable.ic_brand_trakt, 0xFF9F42C6, 0xFFED2224),
        "simkl" to ServiceBrand(R.drawable.ic_brand_simkl, Color(0xFF000000), Color(0xFF2A2A2E), Color(0xFF0B0B0C)),
        "anilist" to b(R.drawable.ic_brand_anilist, 0xFF02A9FF, 0xFF1A6FB0),
        "mal" to b(R.drawable.ic_brand_myanimelist, 0xFF2E51A2, 0xFF1D3570),
        "myanimelist" to b(R.drawable.ic_brand_myanimelist, 0xFF2E51A2, 0xFF1D3570),
        "wetrakr" to b(null, 0xFF3D7BF7, 0xFF2348A8),
        "plex" to ServiceBrand(R.drawable.ic_brand_plex, Color(0xFF1F1F1F), Color(0xFFEBAF00), Color(0xFFC07F00)),
        "jellyfin" to b(R.drawable.ic_brand_jellyfin, 0xFF00A4DC, 0xFFAA5CC3),
        "emby" to b(R.drawable.ic_brand_emby, 0xFF52B54B, 0xFF2E7D32),
        "mdblist" to b(R.drawable.ic_brand_mdblist, 0xFF4284CA, 0xFF27548A),
        "imdb" to ServiceBrand(R.drawable.ic_brand_imdb, Color(0xFF000000), Color(0xFFF5C518), Color(0xFFC79A00)),
        "nextdns" to b(R.drawable.ic_brand_nextdns, 0xFF007BFF, 0xFF0052AA),
        "google gemini" to b(R.drawable.ic_brand_googlegemini, 0xFF8E75B2, 0xFF4F6BD8),
        "claude (anthropic)" to b(R.drawable.ic_brand_claude, 0xFFD97757, 0xFFA6492C),
        "mistral ai" to b(R.drawable.ic_brand_mistralai, 0xFFFA520F, 0xFFB8380A),
        "openrouter" to b(R.drawable.ic_brand_openrouter, 0xFF64748B, 0xFF334155),
        "your own ai server" to ServiceBrand(R.drawable.ic_brand_ollama, Color(0xFF000000), Color(0xFF2A2A2E), Color(0xFF0B0B0C)),
        "stremio" to b(R.drawable.ic_brand_stremio, 0xFF685CEE, 0xFF3F35A8),
        "tmdb" to b(null, 0xFF01B4E4, 0xFF0D253F),
        "openai" to b(null, 0xFF10A37F, 0xFF0B6B54),
        "groq" to b(null, 0xFFF55036, 0xFFB5341F),
        "cerebras" to b(null, 0xFFF15A29, 0xFFA83A15),
        "tvdb" to b(null, 0xFF6CD491, 0xFF2E7D4F),
        "fanart.tv" to b(null, 0xFF2E86C1, 0xFF1B4F72),
        "opensubtitles.com" to b(null, 0xFF178600, 0xFF0E5200),
        "subdl" to b(null, 0xFF7E57C2, 0xFF4A2F7A),
        "rpdb" to b(null, 0xFFE53935, 0xFF8E1F1C),
        "omdb" to b(null, 0xFF455A64, 0xFF263238),
        "watchmode" to b(null, 0xFF00B894, 0xFF00775F),
        "streaming availability" to b(null, 0xFF5C6BC0, 0xFF303F9F),
    )

    /** The brand for a service id or name ("trakt", "Plex", "Claude (Anthropic)"), if Ember knows it. */
    fun of(key: String): ServiceBrand? = brands[key.trim().lowercase()]

    /** Up to two initials for a service without a logo: "Fanart.tv" -> "F", "Streaming Availability" -> "SA". */
    fun initials(name: String): String = name.split(' ', '-', '(').filter { it.isNotBlank() && it.first().isLetterOrDigit() }
        .take(2).joinToString("") { it.first().uppercase() }.ifEmpty { name.take(1).uppercase() }
}

/** How a service stands, shown as a coloured dot and a short line at the end of its row. */
internal enum class ServiceState { READY, OFF, ATTENTION, PROBLEM, ADD }

internal data class ServiceCardInfo(
    val id: String,
    val name: String,
    /** What the service does here, in one line. */
    val about: String?,
    /** "Connected as arash", "Not connected", "Needs an API key". */
    val status: String,
    val state: ServiceState,
    val brand: ServiceBrand? = null,
    /** A logo from the web (an addon's manifest) when the service has no built-in one. */
    val image: String? = null,
    val dim: Boolean = false,
)

internal object ServiceCards {
    fun dot(state: ServiceState): Color = when (state) {
        ServiceState.READY -> Color(0xFF3DDC84)
        ServiceState.OFF -> Color(0xFF8A8A93)
        ServiceState.ATTENTION -> Color(0xFFF5A623)
        ServiceState.PROBLEM -> Color(0xFFFF5A5F)
        ServiceState.ADD -> Color(0xFF8A8A93)
    }
    fun spoken(c: ServiceCardInfo): String = listOfNotNull(c.name, c.status, c.about).joinToString(", ")
    /** Text on a brand fill: dark on light brands (Plex yellow, IMDb), white otherwise. */
    fun ink(on: Color): Color = if (on.luminance() > .45f) Color(0xFF141414) else Color.White
    fun fromTile(t: ServiceTileInfo, about: String? = null, brandKey: String = t.id): ServiceCardInfo = ServiceCardInfo(
        t.id, t.name, about ?: t.line,
        status = listOfNotNull(t.tag, if (about != null) t.line else null).joinToString(" · ").ifEmpty {
            when (t.mark) { ServiceMark.OK -> "Ready"; ServiceMark.PROBLEM -> "Needs attention"; ServiceMark.OFF -> "Not set up"; ServiceMark.ADD -> "" }
        },
        state = when (t.mark) {
            ServiceMark.OK -> ServiceState.READY
            ServiceMark.PROBLEM -> ServiceState.PROBLEM
            ServiceMark.OFF -> if (t.tagStrong) ServiceState.ATTENTION else ServiceState.OFF
            ServiceMark.ADD -> ServiceState.ADD
        },
        brand = ServiceBrands.of(brandKey) ?: ServiceBrands.of(t.name),
    )
}

/** A rounded group of service rows with a title and a short line, as on the Trackers page. */
@Composable
internal fun ServiceCardGroup(title: String, subtitle: String?, cards: List<ServiceCardInfo>, onOpen: (ServiceCardInfo) -> Unit) {
    Column(Modifier.width(960.dp).background(Panel, RoundedCornerShape(TvTokens.Shape.Panel.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, fontSize = 13.sp, color = Mute) }
        }
        cards.forEach { c -> ServiceCard(c) { onOpen(c) } }
    }
}

/**
 * One service as a wide row: logo tile, name, what it does, and its status at the end with a
 * coloured dot. The focused row fills with the service's brand colours.
 */
@Composable
internal fun ServiceCard(c: ServiceCardInfo, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val brand = c.brand
    val fill = brand?.to ?: Fg
    val focusInk = ServiceCards.ink(brand?.from ?: fill)
    Focusable(onClick = onClick, modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus }.testTag("service_card")
        .semantics { contentDescription = ServiceCards.spoken(c) }.then(modifier),
        radius = TvTokens.Shape.Pill, container = PanelHi, focusedContainer = fill, focusedContent = focusInk, focusedScale = TvTokens.Focus.Inline) {
        val ink = androidx.tv.material3.LocalContentColor.current
        Box(Modifier.fillMaxWidth().then(if (focused && brand != null) Modifier.background(Brush.horizontalGradient(listOf(brand.from, brand.to))) else Modifier)) {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.alpha(if (c.dim) .5f else 1f)) { ServiceLogo(c, focused) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    c.about?.let { Text(it, fontSize = 13.sp, color = ink.copy(alpha = .75f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (c.status.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.state != ServiceState.ADD) Box(Modifier.size(9.dp).clip(CircleShape).background(ServiceCards.dot(c.state)))
                    Text(c.status, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp),
                        color = when {
                            focused -> ink
                            c.state == ServiceState.PROBLEM -> Color(0xFFFF8A80)
                            c.state == ServiceState.ATTENTION -> Color(0xFFFFC66B)
                            c.state == ServiceState.OFF || c.state == ServiceState.ADD -> ink.copy(alpha = .7f)
                            else -> ink
                        })
                }
                trailing?.invoke()
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = ink.copy(alpha = .75f), modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The service's logo on its brand colour, a web logo (addons), its initials, or a plus to add one. */
@Composable
internal fun ServiceLogo(c: ServiceCardInfo, focused: Boolean, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val brand = c.brand
    val tile = when {
        c.state == ServiceState.ADD -> WashHi
        brand != null -> if (focused && brand.tile == brand.from) Color.White.copy(alpha = .22f) else brand.tile
        else -> WashHi
    }
    Box(Modifier.size(size).clip(RoundedCornerShape(size * .26f)).background(tile), contentAlignment = Alignment.Center) {
        when {
            c.state == ServiceState.ADD -> Icon(Icons.Outlined.Add, contentDescription = null, tint = Fg, modifier = Modifier.size(size * .55f))
            brand?.logo != null -> Icon(painterResource(brand.logo), contentDescription = null, modifier = Modifier.size(size * .56f),
                tint = if (brand.tile.luminance() > .45f) Color(0xFF141414) else Color.White)
            c.image != null -> WebLogo(c.image, c.name)
            else -> Text(ServiceBrands.initials(c.name), fontSize = (size.value * .36f).sp, fontWeight = FontWeight.Bold,
                color = if (brand != null) ServiceCards.ink(tile) else Fg, maxLines = 1)
        }
    }
}

@Composable
private fun WebLogo(url: String, name: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) Text(ServiceBrands.initials(name), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Fg)
    else coil.compose.AsyncImage(
        model = remember(url) { coil.request.ImageRequest.Builder(context).data(url).size(168).decoderFactory(coil.decode.SvgDecoder.Factory()).build() },
        contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        onError = { failed = true }, modifier = Modifier.fillMaxSize().padding(5.dp),
    )
}
