package app.ember.tv

import app.ember.tv.data.AiProvider
import app.ember.tv.discover.AiChat
import app.ember.tv.spark.SparkAsk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #438 AI services and Ask Spark. */
class AiServicesTest {
    @Test fun openAiStyleRequests() {
        val r = AiChat.request(AiProvider.GROQ, AiProvider.GROQ.base, "k", "m", "sys", "hi", json = true, maxTokens = 100)
        assertEquals("https://api.groq.com/openai/v1/chat/completions", r.url)
        assertEquals("Bearer k", r.headers["Authorization"])
        assertEquals("json_object", r.body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val or = AiChat.request(AiProvider.OPENROUTER, AiProvider.OPENROUTER.base, "k", "m", "sys", "hi", json = true, maxTokens = 100)
        assertNull(or.body["response_format"]); assertEquals("Ember", or.headers["X-Title"])
        val local = AiChat.request(AiProvider.LOCAL, "http://10.0.0.2:11434/v1/", "", "llama3", "sys", "hi", json = true, maxTokens = 100)
        assertEquals("http://10.0.0.2:11434/v1/chat/completions", local.url); assertNull(local.headers["Authorization"])
    }

    @Test fun claudeRequestsAndRepliesAreConverted() {
        val r = AiChat.request(AiProvider.CLAUDE, AiProvider.CLAUDE.base, "k", "claude-x", "sys", "hi", json = true, maxTokens = 100)
        assertEquals("https://api.anthropic.com/v1/messages", r.url)
        assertEquals("k", r.headers["x-api-key"]); assertEquals("2023-06-01", r.headers["anthropic-version"]); assertNull(r.headers["Authorization"])
        assertEquals("sys", r.body["system"]!!.jsonPrimitive.content)
        assertEquals(1, r.body["messages"]!!.jsonArray.size)
        val reply = AiChat.normalize(AiProvider.CLAUDE, Json.parseToJsonElement("""{"content":[{"type":"text","text":"{\"a\":1}"}],"stop_reason":"end_turn"}"""))
        val choice = reply.jsonObject["choices"]!!.jsonArray[0].jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        assertEquals("{\"a\":1}", choice["message"]!!.jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun modelsAndJsonText() {
        assertEquals("gemini-flash-latest", AiProvider.pick(AiProvider.GEMINI, listOf("gemini-pro", "gemini-flash-latest", "gemini-2.0-flash")))
        assertEquals("meta/llama:free", AiProvider.pick(AiProvider.OPENROUTER, listOf("openai/gpt-x", "meta/llama:free")))
        assertEquals("""{"x":1}""", AiChat.jsonText("Here you go:\n```json\n{\"x\":1}\n```"))
        assertTrue(AiProvider.entries.count { it.free != null } >= 5)
    }

    @Test fun askRepliesAreCheckedAndMatchedToRealTitles() {
        val picks = SparkAsk.parse("""```json
            {"titles":[{"title":"Interstellar","year":2014,"type":"movie","why":"Big ideas, no dreams"},{"title":"Dark","year":2017,"type":"series","why":"Time puzzles"},{"title":"","year":1}]}
            ```""")
        assertEquals(2, picks.size); assertEquals("tv", picks[1].type)
        assertTrue(SparkAsk.matches(picks[0], "Interstellar", null, 2014))
        assertTrue(SparkAsk.matches(picks[0], "Interstellar", null, 2015))
        assertFalse(SparkAsk.matches(picks[0], "Interstellar", null, 2019))
        assertFalse(SparkAsk.matches(picks[0], "Interstella 2", null, 2014))
        assertTrue(SparkAsk.matches(SparkAsk.Pick("Amélie", 2001), "Amelie", "Le Fabuleux Destin d'Amélie Poulain", 2001))
        assertTrue(runCatching { SparkAsk.parse("not json") }.isFailure)
        assertTrue(app.ember.tv.data.HomeWidgets.isSparkList("spark:ask:1a2b3c"))
    }
}
