package app.ember.tv.mediaserver

import app.ember.tv.tracker.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

data class ServerUser(val id: String, val name: String, val protected: Boolean = false)
@Serializable
internal data class PlexHomeSession(val id: String, val name: String, val token: String)

/** Home APIs can return JSON or XML. Never resolve entities supplied by a response. */
internal object PlexUsers {
    private fun xml(text: String): Element {
        require(text.length <= 512_000 && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance()
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(InputSource(StringReader(text))).documentElement
    }
    fun users(text: String): List<ServerUser> {
        require(text.length <= 512_000)
        if (text.trimStart().startsWith("<")) {
            val root = xml(text)
            return (0 until root.childNodes.length).mapNotNull { n ->
                val item = root.childNodes.item(n) as? Element ?: return@mapNotNull null
                val id = item.getAttribute("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ServerUser(id, item.getAttribute("title").ifBlank { item.getAttribute("username") }.ifBlank { "Plex user" },
                    item.getAttribute("protected") in setOf("1", "true"))
            }
        }
        val body = Json.parseToJsonElement(text)
        val items = body.arr("users") ?: body.obj("MediaContainer").arr("User") ?: body.arr("User")
        return items.list().mapNotNull { item -> item.str("id")?.let { id ->
            ServerUser(id, item.str("title") ?: item.str("username") ?: "Plex user", item.str("protected") in setOf("1", "true"))
        } }
    }
    fun token(text: String): String {
        val value = if (text.trimStart().startsWith("<")) xml(text).getAttribute("authenticationToken") else {
            val root = Json.parseToJsonElement(text)
            val user = root.obj("user") ?: root
            user.str("authenticationToken") ?: user.str("authToken")
        }
        return value?.takeIf { it.isNotBlank() } ?: error("Plex did not authorize this user.")
    }
}
