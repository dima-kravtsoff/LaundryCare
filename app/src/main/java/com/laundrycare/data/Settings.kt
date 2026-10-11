package com.laundrycare.data

import android.content.Context
import com.laundrycare.ai.ClaudeModel
import com.laundrycare.care.MachinePair
import com.laundrycare.care.Machines
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which service reads the tags. Each keeps its own key and model, so switching
 * back and forth never means re-pasting.
 *
 * Deliberately no key-shape validation: a credential's format belongs to the
 * provider, and the API is the only thing that can say whether a key is good.
 */
enum class Provider(val id: String, val label: String, val defaultModel: String, val whereToGet: String) {
    GEMINI("gemini", "Gemini", "gemini-3.8-flash", "aistudio.google.com → Get API key"),
    CLAUDE("claude", "Claude", ClaudeModel.DEFAULT_MODEL, "platform.claude.com → Settings → API keys");

    companion object {
        fun from(id: String?): Provider = entries.firstOrNull { it.id == id } ?: GEMINI
    }
}

data class SettingsState(
    val provider: Provider,
    val keys: Map<Provider, String>,
    val models: Map<Provider, String>,
    /** Model ids each key was last seen to offer (Settings → Check key). Empty until checked. */
    val knownModels: Map<Provider, Set<String>>,
    val machinesId: String,
) {
    fun key(p: Provider): String = keys[p].orEmpty()
    fun model(p: Provider): String = models[p]?.takeIf { it.isNotBlank() } ?: p.defaultModel
    fun knownModels(p: Provider): Set<String> = knownModels[p].orEmpty()
    val hasKey: Boolean get() = key(provider).isNotBlank()
    val machines: MachinePair get() = Machines.byId(machinesId)
}

/**
 * Stored in the app's private preferences. That is fine for a personal
 * build; anything published should put a proxy in front of the provider
 * instead of shipping a key.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun read() = SettingsState(
        provider = Provider.from(prefs.getString("provider", null)),
        keys = Provider.entries.associateWith { prefs.getString("key_${it.id}", "").orEmpty() },
        models = Provider.entries.associateWith { prefs.getString("model_${it.id}", "").orEmpty() },
        knownModels = Provider.entries.associateWith { p ->
            prefs.getString("known_${p.id}", "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        },
        machinesId = prefs.getString("machines", Machines.DEFAULT.id) ?: Machines.DEFAULT.id,
    )

    fun setProvider(p: Provider) = write { putString("provider", p.id) }
    fun setKey(p: Provider, key: String) = write { putString("key_${p.id}", key.trim()) }
    fun setModel(p: Provider, model: String) = write { putString("model_${p.id}", model.trim()) }
    fun setKnownModels(p: Provider, ids: Collection<String>) = write { putString("known_${p.id}", ids.joinToString(",")) }
    fun setMachines(id: String) = write { putString("machines", id) }

    private fun write(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }
}
