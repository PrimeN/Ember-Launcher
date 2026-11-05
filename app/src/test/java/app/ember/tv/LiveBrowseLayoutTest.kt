package app.ember.tv

import app.ember.tv.iptv.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.lang.reflect.Proxy
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

class LiveBrowseLayoutTest {
    private fun channel(id:Int,source:String="a")=Channel("$source:$id",source,"Channel $id","https://example.test/live/$id")
    @Test fun defaultSelectionNeverCombinesPlaylistsOrChoosesHiddenFavourites() {
        val a=channel(1);val b=channel(2,"b")
        val sources=listOf(IptvSource("a","A","m3u","https://a.test"),IptvSource("b","B","m3u","https://b.test"))
        assertEquals(LiveChoice("favourites"),LivePresentation.initialChoice(listOf(a,b),sources,LiveLibrary(),setOf(b.key)))
        assertEquals(LiveChoice("source","a"),LivePresentation.initialChoice(listOf(a,b),sources,LiveLibrary(hiddenSections=setOf("favourites")),setOf(b.key)))
        assertEquals(LiveChoice("source","b"),LivePresentation.initialChoice(listOf(b),sources.map {if(it.id=="a")it.copy(enabled=false) else it},LiveLibrary(),emptySet()))
        assertEquals(LiveChoice("addons"),LivePresentation.initialChoice(emptyList(),emptyList(),LiveLibrary(),emptySet(),true))
    }
    @Test fun onNowCardsAreBoundedUniqueAndUseOnlyAuthorizedChannels() {
        val channels=(1..100).map {channel(it)}
        val now=Programme(0,1000,"Show")
        val result=LivePresentation.featured(channels,setOf("hidden:1",channels[40].key),listOf(channels[50].key,channels[40].key),channels.associate {it.key to listOf(now)},500)
        assertEquals(20,result.size);assertEquals(20,result.distinctBy {it.key}.size)
        assertEquals(channels[40],result[0]);assertEquals(channels[50],result[1]);assertFalse(result.any {it.key=="hidden:1"})
    }
    @Test fun cardsDoNotInventRemainingTimeForMissingFutureOrExpiredProgrammes() {
        val p=Programme(1000,121_000,"Now")
        assertEquals(.5f,LivePresentation.progress(p,61_000)!!,.001f)
        assertEquals(1L,LivePresentation.remainingMinutes(p,120_000))
        assertNull(LivePresentation.progress(p,0));assertNull(LivePresentation.progress(p,121_000))
        assertNull(LivePresentation.progress(p.copy(stop=1000),1000));assertNull(LivePresentation.remainingMinutes(null,1000))
    }
    @Test fun aKnownProgrammeWithoutDescriptionDoesNotDisplayANoGuideError() {
        val p=Programme(0,1000,"Known title")
        assertNull(LivePresentation.summary(p,"No programme information"))
        assertNull(LivePresentation.summary(p.copy(description=""),"No programme information"))
        assertEquals("Plot",LivePresentation.summary(p.copy(description="Plot"),"No programme information"))
        assertEquals("Guide unavailable",LivePresentation.summary(null,"Guide unavailable"))
    }
    @Test fun markerLabelsStayOutsideChannelRailInBothLayoutDirections() {
        assertEquals(205f,LivePresentation.markerLabelLeft(200f,800f,200f,64f,false),.01f)
        val left=LivePresentation.markerLabelLeft(790f,800f,200f,64f,false)
        assertTrue(left>=200f && left+64<=800f)
        val right=LivePresentation.markerLabelLeft(590f,800f,200f,64f,true)
        assertTrue(right>=0 && right+64<=600f)
    }
    @Test fun programmeArtworkKeepsProviderQueriesButRejectsLocalOrExecutableUris() {
        val image="https://cdn.test/poster.jpg?token=fixture"
        assertEquals(image,EpgMetadata.artwork(image))
        listOf("file:///private/data","content://private/data","javascript:alert(1)","https://user:pass@cdn.test/art","/relative/art").forEach {assertNull(EpgMetadata.artwork(it))}
        assertEquals(4 to 3,EpgMetadata.episode("3.2.0/1","xmltv_ns"))
        assertEquals(4 to 3,EpgMetadata.episode("S4 E3","onscreen"))
    }
    @Test fun oldGuideCacheDecodesAndNewMetadataSurvivesTimeOffsets() {
        val old=Json.decodeFromString<Programme>("""{"start":100,"stop":200,"title":"Old"}""")
        assertNull(old.image);assertNull(old.rating)
        val p=old.copy(image="https://cdn.test/art",subtitle="Brothers",season=4,episode=3,rating="TV-PG")
        val shifted=EpgMatching.shifted(listOf(p),1000,1000,2000).single()
        assertEquals(p.image,shifted.image);assertEquals(4,shifted.season);assertEquals(3,shifted.episode);assertEquals("TV-PG",shifted.rating)
    }
    /** Real JDK XML parsing behind the narrow Android pull-parser boundary; no Android runtime required. */
    private fun parser():XmlPullParser {
        lateinit var reader:XMLStreamReader
        fun type()=when(reader.eventType) {XMLStreamConstants.START_DOCUMENT->XmlPullParser.START_DOCUMENT;XMLStreamConstants.END_DOCUMENT->XmlPullParser.END_DOCUMENT;XMLStreamConstants.START_ELEMENT->XmlPullParser.START_TAG;XMLStreamConstants.END_ELEMENT->XmlPullParser.END_TAG;else->XmlPullParser.TEXT}
        return Proxy.newProxyInstance(javaClass.classLoader,arrayOf(XmlPullParser::class.java)) {_,m,args ->when(m.name) {
            "setFeature"->null
            "setInput"->{reader=XMLInputFactory.newFactory().apply {setProperty(XMLInputFactory.SUPPORT_DTD,false);setProperty("javax.xml.stream.isSupportingExternalEntities",false)}.createXMLStreamReader(args!![0] as java.io.InputStream);null}
            "getEventType"->type()
            "next"->{reader.next();type()}
            "getName"->reader.localName
            "getAttributeValue"->reader.getAttributeValue(args!![0] as String?,args[1] as String)
            "nextText"->reader.elementText
            else->error("Unexpected parser method ${m.name}")
        }} as XmlPullParser
    }
    @Test fun nameIndexKeepsSafeAliasesAndExplicitIdsWhenAnotherAliasIsAmbiguous() {
        val xml="""<tv>
          <channel id="a"><display-name>One</display-name><display-name>Two HD</display-name></channel>
          <channel id="b"><display-name>One</display-name></channel>
          <channel id="c"><display-name>Other</display-name></channel>
          <programme channel="a" start="20261026120000 +0000" stop="20261026130000 +0000"><title>A</title></programme>
          <programme channel="b" start="20261026120000 +0000" stop="20261026130000 +0000"><title>B</title></programme>
          <programme channel="c" start="20261026120000 +0000" stop="20261026130000 +0000"><title>C</title></programme></tv>"""
        val from=Xmltv.time("20261026120000 +0000")
        val result=Xmltv.parseDetailed(xml.byteInputStream(),setOf("b"),setOf("one","two"),from,from+3_600_000,::parser)
        assertEquals(setOf("a","b"),result.programmes.keys)
        assertEquals(mapOf("two" to "a"),result.names)
    }
    @Test fun xmltvLoadsProviderMetadataResetsFieldsAndBoundsFutureArtwork() {
        val xml="""<tv><channel id="a"><display-name>Channel</display-name><icon src="https://cdn.test/channel"/></channel>
          <programme channel="a" start="20261026120000 +0000" stop="20261026130000 +0000"><title>Star Trek</title><sub-title>Brothers</sub-title><desc>Plot</desc><icon src="https://cdn.test/programme"/><star-rating><value>8/10</value></star-rating><rating><value>TV-PG</value></rating><category>Drama</category><episode-num system="xmltv_ns">3.2.</episode-num></programme>
          <programme channel="a" start="20261026130000 +0000" stop="20261026140000 +0000"><title>Next</title></programme>
          <programme channel="a" start="bad" stop="20261026130000 +0000"><title>Invalid start must not span decades</title></programme>
          <programme channel="a" start="20261026120000 +0000" stop="20261026110000 +0000"><title>Backwards interval</title></programme>
          <programme channel="a" start="20261026230000 +0000" stop="20261026235900 +0000"><title>Later</title><desc>Long description</desc><icon src="https://cdn.test/later"/></programme></tv>"""
        val from=Xmltv.time("20261026120000 +0000")
        val result=Xmltv.parseDetailed(xml.byteInputStream(),setOf("a"),emptySet(),from,from+12*3_600_000L,::parser).programmes.getValue("a")
        assertEquals(3,result.size)
        val first=result[0];assertEquals("Brothers",first.subtitle);assertEquals("TV-PG",first.rating);assertEquals(4,first.season);assertEquals(3,first.episode);assertEquals("Drama",first.category);assertEquals("https://cdn.test/programme",first.image)
        assertNull(result[1].image);assertNull(result[1].rating);assertNull(result[1].subtitle);assertNull(result[1].season)
        assertEquals("Later",result[2].title);assertNull(result[2].image);assertNull(result[2].description)
    }
}
