package app.ember.tv.data

/** Built-in stream result templates (Settings > Stremio). Same syntax as AIOStreams' custom formatter. */
object StreamFormatDefaults {
    val NAME = """
{service.cached::istrue["▶ "||""]}{service.cached::isfalse["▷ "||""]} {stream.resolution::exists["{stream.resolution::replace('2160p','4K')::replace('1080p','FHD')::replace('720p','HD')::replace('480p','SD')}"||"Unknown"]} || {service.shortName::exists["{service.shortName} || "||""]} {addon.name}
{stream.visualTags::exists["{stream.visualTags::join(' | ')} "||""]}
""".trim('\n')

    val DESCRIPTION = """
{stream.edition::exists::and::stream.visualTags::~IMAX::isfalse["※ ▸{stream.edition::replace('Anniversary Edition','ᴀɴɴɪᴠᴇʀsᴀʀʏ ᴇᴅɪᴛɪᴏɴ')::replace('Ultimate Edition','ᴜʟᴛɪᴍᴀᴛᴇ ᴇᴅɪᴛɪᴏɴ')::replace('Director's Cut','ᴅɪʀᴇᴄᴛᴏʀ's ᴄᴜᴛ')::replace('Extended Edition','ᴇxᴛᴇɴᴅᴇᴅ ᴇᴅɪᴛɪᴏɴ')::replace('Collector's Edition','ᴄᴏʟʟᴇᴄᴛᴏʀ's ᴇᴅɪᴛɪᴏɴ')::replace('Theatrical','ᴛʜᴇᴀᴛʀɪᴄᴀʟ ᴄᴜᴛ')::replace('Uncut','ᴜɴᴄᴜᴛ')::replace('IMAX','ɪᴍᴀx')::replace('Diamond Edition','ᴅɪᴀᴍᴏɴᴅ ᴇᴅɪᴛɪᴏɴ')::replace('Remastered','ʀᴇᴍᴀsᴛᴇʀᴇᴅ')}◂"||""]}
{stream.network::exists["※ ▸{stream.network::replace('Netflix','ɴᴇᴛғʟɪx')::replace('Disney','ᴅɪsɴᴇʏ')::replace('Apple TV','ᴀᴘᴘʟᴇ ᴛᴠ')::replace('Amazon','ᴀᴍᴀᴢᴏɴ')::replace('PBS','ᴘʙs')::replace('Nickelodeon','ɴɪᴄᴋᴇʟᴏᴅᴇᴏɴ')::replace('HBO','ʜʙᴏ')::replace('Hulu','ʜᴜʟᴜ')::replace('CBS','ᴄʙs')::replace('NBC','ɴʙᴄ')::replace('AMC','ᴀᴍᴄ')::replace('Crunchyroll','ᴄʀᴜɴᴄʜʏʀᴏʟʟ')::replace('VICE','ᴠɪᴄᴇ')::replace('Sony','sᴏɴʏ')::replace('Hallmark','ʜᴀʟʟᴍᴀʀᴋ')::replace('Adult Swim','ᴀᴅᴜʟᴛ sᴡɪᴍ')::replace('Animal Planet','ᴀɴɪᴍᴀʟ ᴘʟᴀɴᴇᴛ')::replace('Cartoon Network','ᴄᴀʀᴛᴏᴏɴ ɴᴇᴛᴡᴏʀᴋ')}◂ "||""]}{stream.upscaled::istrue["⏣ ▸ᴜᴘsᴄᴀʟᴇᴅ◂ "||""]}{stream.remastered::istrue::and::stream.edition::~Remastered::isfalse["⏣ ▸ʀᴇᴍᴀsᴛᴇʀᴇᴅ◂ "||""]}{stream.uncensored::istrue["⏣ ▸ᴜɴᴄᴇɴsᴏʀᴇᴅ◂ "||""]}{stream.repack::istrue["⏣ ▸ʀᴇᴘᴀᴄᴋᴇᴅ◂ "||""]}{stream.unrated::istrue["⏣ ▸ᴜɴʀᴀᴛᴇᴅ◂ "||""]}
{service.cached::istrue["▶ "||""]}{service.cached::isfalse["▷ "||""]}{stream.title::exists["{stream.title}"||""]} {stream.year::exists["({stream.year})"||""]}{stream.seasonEpisode::exists["☲ {stream.seasonEpisode::join(' · ')}"||""]}
{stream.quality::exists["▣ {stream.quality} "||""]}{stream.encode::exists["• {stream.encode} "||""]}
{stream.audioTags::exists["♬ {stream.audioTags::join(' • ')} "||""]}{stream.audioChannels::exists["• {stream.audioChannels::join(' • ')} "||""]}{stream.languages::exists["☶ {stream.languages::join(' | ')::replace('Multi','ᴍᴜʟᴛɪ')::replace('Dual Audio','ᴅᴜᴏ')::replace('Dubbed','ᴅᴜʙ')::replace('English','ᴇɴɢ')::replace('Japanese','ᴊᴀᴘ')::replace('Korean','ᴋᴏʀ')::replace('Russian','ʀᴜs')::replace('Arabic','ᴀʀᴀ')::replace('Portuguese','ᴘᴏʀ')::replace('Spanish','sᴘᴀ')::replace('Latino','ʟᴀᴛ')::replace('French','ғʀᴀ')::replace('German','ᴅᴇᴜ')::replace('Italian','ɪᴛᴀ')::replace('Hindi','ʜɪɴ')::replace('Bengali','ʙᴇɴ')::replace('Punjabi','ᴘᴀɴ')::replace('Marathi','ᴍᴀʀ')::replace('Gujarati','ɢᴜᴊ')::replace('Tamil','ᴛᴀᴍ')::replace('Telugu','ᴛᴇʟ')::replace('Kannada','ᴋᴀɴ')::replace('Malayalam','ᴍʟʏ')::replace('Thai','ᴛʜᴀ')::replace('Vietnamese','ᴠɪᴇ')::replace('Indonesian','ɪɴᴅ')::replace('Turkish','ᴛᴜʀ')::replace('Hebrew','ʜᴇʙ')::replace('Persian','ᴘᴇʀ')::replace('Ukrainian','ᴜᴋʀ')::replace('Greek','ɢʀᴇ')::replace('Lithuanian','ʟɪᴛ')::replace('Latvian','ʟᴀᴛ')::replace('Estonian','ᴇsᴛ')::replace('Polish','ᴘᴏʟ')::replace('Czech','ᴄᴢᴇ')::replace('Slovak','sʟᴋ')::replace('Hungarian','ʜᴜɴ')::replace('Romanian','ʀᴏᴍ')::replace('Bulgarian','ʙᴜʟ')::replace('Serbian','sᴇʀ')::replace('Croatian','ᴄʀᴏ')::replace('Slovenian','sʟᴠ')::replace('Dutch','ᴅᴜᴛ')::replace('Danish','ᴅᴀɴ')::replace('Finnish','ғɪɴ')::replace('Swedish','sᴡᴇ')::replace('Norwegian','ɴᴏʀ')::replace('Malay','ᴍᴀʟ')::replace('Chinese','ᴄʜɪ')}"||""]}
{stream.size::>0["⛁ {stream.size::bytes}"||""]} {stream.bitrate::exists["• {stream.bitrate::sbitrate::replace('Mbps','ᴹᵇᵖˢ')::replace('Kbps','ᴷᵇᵖˢ')} "||""]}
{stream.message::exists["⌗ {stream.message}"||""]}
""".trim('\n')

    /** A made-up source for the Settings preview when no title is open. */
    fun example() = StreamInfo(
        addonName = "Torrentio",
        name = "[RD+] Torrentio\n4k DV | HDR",
        description = "The.Matrix.1999.Remastered.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FGT",
        filename = "The.Matrix.1999.Remastered.2160p.UHD.BluRay.REMUX.DV.HDR.HEVC.TrueHD.7.1.Atmos-FGT.mkv",
        title = "The Matrix",
        year = "1999",
        seasonEpisode = emptyList(),
        resolution = "2160p",
        quality = "BluRay REMUX",
        encode = "HEVC",
        visualTags = listOf("DV", "HDR"),
        audioTags = listOf("Atmos", "TrueHD"),
        audioChannels = listOf("7.1"),
        languages = listOf("English", "Italian"),
        sizeBytes = 62_600_000_000L,
        seeders = 214,
        indexer = "ThePirateBay",
        releaseGroup = "FGT",
        edition = "Remastered",
        network = null,
        upscaled = false,
        remastered = true,
        uncensored = false,
        repack = false,
        unrated = false,
        proxied = false,
        message = null,
        service = StreamService("realdebrid", "Real-Debrid", "RD"),
        cached = true,
        type = "debrid",
    )
}
