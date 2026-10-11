package com.laundrycare

import android.app.Application
import android.content.Context
import com.laundrycare.ai.AiException
import com.laundrycare.ai.ClaudeModel
import com.laundrycare.ai.GeminiModel
import com.laundrycare.ai.VisionModel
import com.laundrycare.data.Backup
import com.laundrycare.data.GarmentRepository
import com.laundrycare.data.PhotoStore
import com.laundrycare.data.Provider
import com.laundrycare.data.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class LaundryCareApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Hand-wired dependencies: small enough that a framework would only hide them. */
class AppContainer(context: Context) {
    val settings = Settings(context)
    val photos = PhotoStore(context)
    val repo = GarmentRepository(File(context.filesDir, "closet.json"), photos)
    val backup = Backup(repo, photos)

    /** Things the model layer wants the person to know ("read with X because Y"); the UI shows them as snackbars. */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** The model for whichever provider is selected right now. */
    fun vision(): VisionModel {
        val s = settings.state.value
        val key = s.key(s.provider)
        if (key.isBlank()) throw AiException("Add your ${s.provider.label} API key in Settings first.", retryable = false)
        return when (s.provider) {
            Provider.GEMINI -> GeminiModel(
                apiKey = key,
                preferredModel = s.model(Provider.GEMINI),
                knownModels = s.knownModels(Provider.GEMINI),
                onModelSwitched = {
                    settings.setModel(Provider.GEMINI, it)
                    notices.tryEmit("${s.model(Provider.GEMINI)} is gone; switched to $it")
                },
                onFallback = { used, reason -> notices.tryEmit("Answered by $used. $reason") },
                onModelsListed = { settings.setKnownModels(Provider.GEMINI, it.map { m -> m.id }) },
            )
            Provider.CLAUDE -> ClaudeModel(key, s.model(Provider.CLAUDE)) {
                settings.setModel(Provider.CLAUDE, it)
                notices.tryEmit("${s.model(Provider.CLAUDE)} is gone; switched to $it")
            }
        }
    }

    fun vision(provider: Provider, key: String, model: String): VisionModel = when (provider) {
        Provider.GEMINI -> GeminiModel(key, model, onModelsListed = { settings.setKnownModels(Provider.GEMINI, it.map { m -> m.id }) })
        Provider.CLAUDE -> ClaudeModel(key, model)
    }
}
