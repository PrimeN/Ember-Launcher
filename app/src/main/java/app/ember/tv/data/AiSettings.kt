package app.ember.tv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

/**
 * #438: the AI services Ember can use. Most take OpenAI's request format; Claude has its own.
 * [free] says what a free account gets, when there is a free tier.
 */
enum class AiProvider(
    val id: String, val label: String, val base: String, val keyId: String,
    val anthropic: Boolean = false,
    /** Asks for a JSON reply directly (others are told to answer with JSON only). */
    val jsonMode: Boolean = true,
    val defaultModel: String,
    /** Words that mark a good, inexpensive everyday model in the service's list. */
    val prefer: List<String> = emptyList(),
    val free: String? = null,
    val keyHelp: String,
) {
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai/", "GEMINI_API_KEY",
        defaultModel = "gemini-flash-latest", prefer = listOf("flash-latest", "flash"),
        free = "Free tier with daily limits, no card needed. Google may use free-tier requests to improve its products.",
        keyHelp = "aistudio.google.com › Get API key."),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1/", "GROQ_API_KEY",
        defaultModel = "llama-3.3-70b-versatile", prefer = listOf("llama-3.3-70b", "gpt-oss-120b", "llama"),
        free = "Free tier, no card: about 1,000 requests a day on the larger models.",
        keyHelp = "console.groq.com › API Keys."),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1/", "OPENROUTER_API_KEY", jsonMode = false,
        defaultModel = "openrouter/auto", prefer = listOf(":free"),
        free = "Many models are free (their names end in :free): about 50 requests a day, more after a small top-up.",
        keyHelp = "openrouter.ai › Keys. One key reaches hundreds of models from many companies."),
    MISTRAL("mistral", "Mistral", "https://api.mistral.ai/v1/", "MISTRAL_API_KEY",
        defaultModel = "mistral-small-latest", prefer = listOf("mistral-small-latest", "small"),
        free = "Free Experiment plan with account limits.",
        keyHelp = "console.mistral.ai › API keys."),
    CEREBRAS("cerebras", "Cerebras", "https://api.cerebras.ai/v1/", "CEREBRAS_API_KEY", jsonMode = false,
        defaultModel = "llama-3.3-70b", prefer = listOf("llama-3.3", "gpt-oss", "llama"),
        free = "Free tier, no card: about a million tokens a day.",
        keyHelp = "cloud.cerebras.ai › API Keys."),
    OPENAI("openai", "OpenAI", "https://api.openai.com/v1/", "OPENAI_API_KEY",
        defaultModel = "gpt-4o-mini", prefer = listOf("-mini", "gpt-4o-mini"),
        keyHelp = "platform.openai.com › API keys. Requests are charged to your account."),
    CLAUDE("claude", "Claude (Anthropic)", "https://api.anthropic.com/v1/", "ANTHROPIC_API_KEY", anthropic = true, jsonMode = false,
        defaultModel = "claude-haiku-4-5", prefer = listOf("haiku"),
        keyHelp = "console.anthropic.com › API keys. Requests are charged to your account."),
    LOCAL("local", "Your own server (Ollama, LM Studio…)", "", "LOCAL_AI_KEY", jsonMode = false,
        defaultModel = "", free = "Runs on your own computer: free, and nothing leaves your home.",
        keyHelp = "The address of an OpenAI-style server on your network, e.g. http://192.168.1.20:11434/v1 for Ollama. A key only if your server asks for one.");

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
        /** The everyday model to start with from a service's list: the preferred kind, else the first. */
        fun pick(p: AiProvider, models: List<String>): String? =
            p.prefer.firstNotNullOfOrNull { want -> models.firstOrNull { it.contains(want, ignoreCase = true) } } ?: models.firstOrNull()
    }
}

/** #438: which AI service Ember uses, and the model for each. Keys live with the other API keys. */
object AiSettings {
    private var sp: SharedPreferences? = null
    var providerId by mutableStateOf("")
        private set
    var localUrl by mutableStateOf("")
        private set
    var revision by mutableStateOf(0)
        private set

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("ai", Context.MODE_PRIVATE)
        providerId = sp?.getString("provider", null).orEmpty()
        localUrl = sp?.getString("localUrl", null).orEmpty()
    }

    /** The chosen service; with none chosen yet, Mistral (the one Ember used before). */
    val provider: AiProvider get() = AiProvider.byId(providerId) ?: AiProvider.MISTRAL

    fun choose(p: AiProvider) { providerId = p.id; sp?.edit { putString("provider", p.id) }; revision++ }
    fun model(p: AiProvider = provider): String = sp?.getString("model.${p.id}", null)?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun setModel(p: AiProvider, model: String) { sp?.edit { putString("model.${p.id}", model.trim()) }; revision++ }
    fun saveLocalUrl(url: String) { localUrl = url.trim(); sp?.edit { putString("localUrl", localUrl) }; revision++ }

    fun key(p: AiProvider = provider): String = ApiKeys.get(p.keyId)
    /** Where requests go: the service's address, or your own server's. */
    fun base(p: AiProvider = provider): String = if (p == AiProvider.LOCAL) localUrl.trimEnd('/') + "/" else p.base
    /** Ready to send a request: a key (or, for your own server, its address and a model). */
    fun ready(p: AiProvider = provider): Boolean =
        if (p == AiProvider.LOCAL) localUrl.startsWith("http") && model(p).isNotBlank() else key(p).isNotBlank()
}
