package com.laundrycare.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laundrycare.LaundryCareApp
import com.laundrycare.ai.AiException
import com.laundrycare.ai.MatchCandidate
import com.laundrycare.ai.MatchInput
import com.laundrycare.ai.ModelInfo
import com.laundrycare.care.LoadPlan
import com.laundrycare.care.LoadPlanner
import com.laundrycare.care.MachinePair
import com.laundrycare.care.Recommend
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ColorGroup
import com.laundrycare.data.Garment
import com.laundrycare.data.GarmentRepository
import com.laundrycare.data.Images
import com.laundrycare.data.ImportMode
import com.laundrycare.data.PhotoStore
import com.laundrycare.data.Provider
import com.laundrycare.data.SettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

enum class PhotoField { GARMENT, TAG }
enum class DraftStep { PHOTOS, REVIEW }

/** A garment being added or edited. Photos are files: stored ones for an edit, cache drafts for new shots. */
data class Draft(
    val id: String,
    val editing: Boolean,
    val createdAt: Long,
    val name: String = "",
    val care: CareProfile? = null,
    val colorGroup: ColorGroup? = null,
    val description: String = "",
    val garmentPhotos: List<File> = emptyList(),
    val tagPhotos: List<File> = emptyList(),
    val step: DraftStep = DraftStep.PHOTOS,
    /** The tag is being read. */
    val busy: Boolean = false,
    /** Photos are being moved into the store; a second Save or a Back must wait. */
    val saving: Boolean = false,
    val error: String? = null,
)

/** What survives the process being killed behind the camera app. */
@Serializable
private data class DraftSnapshot(
    val id: String,
    val editing: Boolean,
    val createdAt: Long,
    val name: String,
    val care: CareProfile?,
    val colorGroup: ColorGroup?,
    val description: String,
    val garmentPhotos: List<String>,
    val tagPhotos: List<String>,
    val step: String,
)

data class MatchHit(val garment: Garment, val candidate: MatchCandidate)

data class MatchState(
    val query: File,
    /** Picking an item for a load rather than opening its care guide. */
    val forPlan: Boolean,
    val busy: Boolean = true,
    val error: String? = null,
    val retryable: Boolean = true,
    val hits: List<MatchHit> = emptyList(),
    val done: Boolean = false,
    val truncated: Boolean = false,
)

data class ModelCheck(
    val busy: Boolean = false,
    val models: List<ModelInfo> = emptyList(),
    val error: String? = null,
    val autoPicked: String? = null,
)

class ClosetViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as LaundryCareApp).container
    private val draftFile = File(app.cacheDir, "draft/draft.json")

    val garments: StateFlow<List<Garment>> = container.repo.garments
    val settings: StateFlow<SettingsState> = container.settings.state
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    var draft by mutableStateOf<Draft?>(null)
        private set
    var match by mutableStateOf<MatchState?>(null)
        private set
    val planSelection = mutableStateListOf<String>()
    var plan by mutableStateOf<LoadPlan?>(null)
        private set
    var modelCheck by mutableStateOf<ModelCheck?>(null)
        private set
    var transferBusy by mutableStateOf(false)
        private set

    /** Draft snapshots are written in order on one thread, so two quick edits cannot interleave into a corrupt file. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val draftWriter = Dispatchers.IO.limitedParallelism(1)

    init {
        draft = restoreDraft()
        val keep = draft?.let { d -> (d.garmentPhotos + d.tagPhotos).map { it.absolutePath }.toSet() } ?: emptySet()
        viewModelScope.launch {
            container.repo.load()
            withContext(Dispatchers.IO) { container.photos.cleanupDrafts(keep) }
        }
        viewModelScope.launch { container.notices.collect { toast(it) } }
    }

    fun machines(): MachinePair = settings.value.machines
    fun photoFile(name: String): File = container.photos.file(name)
    fun washPlan(g: Garment) = Recommend.wash(g.care, g.name, machines().washer, g.colorGroup)
    fun dryPlan(g: Garment) = Recommend.dry(g.care, g.name, machines().dryer)
    fun toast(message: String) { messages.tryEmit(message) }

    /* ---------------- draft ---------------- */

    private fun replaceDraft(d: Draft?) {
        draft = d
        viewModelScope.launch(draftWriter) { persistDraft(d) }
    }

    private fun persistDraft(d: Draft?) {
        runCatching {
            if (d == null) {
                draftFile.delete()
            } else {
                draftFile.parentFile?.mkdirs()
                val snap = DraftSnapshot(
                    d.id, d.editing, d.createdAt, d.name, d.care, d.colorGroup, d.description,
                    d.garmentPhotos.map { it.absolutePath }, d.tagPhotos.map { it.absolutePath }, d.step.name,
                )
                draftFile.writeText(GarmentRepository.json.encodeToString(DraftSnapshot.serializer(), snap))
            }
        }
    }

    private fun restoreDraft(): Draft? = runCatching {
        if (!draftFile.exists()) return null
        val s = GarmentRepository.json.decodeFromString(DraftSnapshot.serializer(), draftFile.readText())
        Draft(
            id = s.id, editing = s.editing, createdAt = s.createdAt, name = s.name, care = s.care,
            colorGroup = s.colorGroup, description = s.description,
            garmentPhotos = s.garmentPhotos.map(::File).filter { it.exists() },
            tagPhotos = s.tagPhotos.map(::File).filter { it.exists() },
            step = runCatching { DraftStep.valueOf(s.step) }.getOrDefault(DraftStep.PHOTOS),
        )
    }.getOrNull()

    fun startAdd(initialPhoto: File? = null) {
        replaceDraft(Draft(id = "g${System.currentTimeMillis()}", editing = false, createdAt = System.currentTimeMillis(), garmentPhotos = listOfNotNull(initialPhoto)))
    }

    fun startEdit(g: Garment) {
        replaceDraft(
            Draft(
                id = g.id, editing = true, createdAt = g.createdAt, name = g.name, care = g.care,
                colorGroup = g.colorGroup, description = g.description,
                garmentPhotos = g.garmentPhotos.map(::photoFile).filter { it.exists() },
                tagPhotos = g.tagPhotos.map(::photoFile).filter { it.exists() },
                step = DraftStep.REVIEW,
            )
        )
    }

    fun discardDraft() {
        draft?.let { d ->
            (d.garmentPhotos + d.tagPhotos).filterNot { container.photos.isStored(it) }.forEach { it.delete() }
        }
        replaceDraft(null)
    }

    fun addPhotos(field: PhotoField, uris: List<Uri>) {
        val d = draft ?: return
        viewModelScope.launch {
            val max = if (field == PhotoField.GARMENT) MAX_GARMENT_PHOTOS else MAX_TAG_PHOTOS
            val have = if (field == PhotoField.GARMENT) d.garmentPhotos.size else d.tagPhotos.size
            val room = (max - have).coerceAtLeast(0)
            if (room == 0) {
                toast("Up to $max photos here")
                return@launch
            }
            val files = mutableListOf<File>()
            for (uri in uris.take(room)) {
                try {
                    files += container.photos.importToDraft(uri, if (field == PhotoField.TAG) PhotoStore.TAG_MAX_EDGE else PhotoStore.MAX_EDGE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Couldn't read that photo: ${e.message ?: "unknown error"}")
                }
            }
            if (uris.size > room) toast("Only $room more photo${if (room == 1) "" else "s"} fit; the rest were skipped")
            val cur = draft ?: return@launch
            if (cur.id != d.id) return@launch
            replaceDraft(
                if (field == PhotoField.GARMENT) cur.copy(garmentPhotos = cur.garmentPhotos + files, error = null)
                else cur.copy(tagPhotos = cur.tagPhotos + files, error = null)
            )
        }
    }

    fun removePhoto(field: PhotoField, index: Int) {
        val d = draft ?: return
        val list = if (field == PhotoField.GARMENT) d.garmentPhotos else d.tagPhotos
        val file = list.getOrNull(index) ?: return
        if (!container.photos.isStored(file)) file.delete()
        val next = list.filterIndexed { i, _ -> i != index }
        replaceDraft(if (field == PhotoField.GARMENT) d.copy(garmentPhotos = next) else d.copy(tagPhotos = next))
    }

    /** Moves a photo to the front, where it becomes the cover and the matcher's reference. */
    fun makeMain(field: PhotoField, index: Int) {
        val d = draft ?: return
        val list = if (field == PhotoField.GARMENT) d.garmentPhotos else d.tagPhotos
        if (index <= 0 || index >= list.size) return
        val next = listOf(list[index]) + list.filterIndexed { i, _ -> i != index }
        replaceDraft(if (field == PhotoField.GARMENT) d.copy(garmentPhotos = next) else d.copy(tagPhotos = next))
    }

    fun readTag() {
        val d = draft ?: return
        if (d.tagPhotos.isEmpty()) {
            replaceDraft(d.copy(error = "Add a photo of the care tag first."))
            return
        }
        replaceDraft(d.copy(busy = true, error = null))
        viewModelScope.launch {
            try {
                val model = container.vision()
                val read = withContext(Dispatchers.IO) {
                    val tags = d.tagPhotos.map { it.readBytes() }
                    // Overview at reading size; the symbol row found in it is cut
                    // from the full-resolution photo and enlarged. A failed locate
                    // costs nothing but the zoom.
                    val overviews = tags.map { Images.resized(it, PhotoStore.MAX_EDGE) }
                    val debugDir = File(getApplication<Application>().cacheDir, "debug").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    var smallTag = false
                    val zooms = tags.indices.flatMap { i ->
                        val box = try {
                            withRetry { model.locateSymbols(overviews[i]) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "tag $i: locate failed: ${e.message}")
                            null
                        }
                        Log.i(TAG, "tag $i: ${tags[i].size} bytes stored, overview ${overviews[i].size} bytes, box=${box?.toList()}")
                        if (box != null && box[3] - box[1] < 400) smallTag = true
                        val crops = try {
                            if (box != null) listOf(Images.cropZoom(tags[i], box)) else Images.quadrants(tags[i])
                        } catch (e: Exception) {
                            Log.w(TAG, "tag $i: zoom failed: ${e.message}")
                            emptyList()
                        }
                        crops.forEachIndexed { k, z -> File(debugDir, "zoom$i-$k.jpg").writeBytes(z) }
                        Log.i(TAG, "tag $i: ${crops.size} zoom image(s), ${crops.sumOf { it.size }} bytes")
                        crops
                    }
                    if (smallTag) toast("Tip: the tag is small in the photo. Get closer so it fills the frame.")
                    // Only the first three garment photos are sent (see readTagParts), so only those are resized.
                    withRetry { model.readTag(overviews, zooms, d.garmentPhotos.take(3).map { Images.resized(it.readBytes(), 1024) }) }.also {
                        Log.i(TAG, "transcript: ${it.transcript}")
                        Log.i(TAG, "read: action=${it.care.washAction} temp=${it.care.washTempC} heat=${it.care.tumbleHeat} iron=${it.care.iron} bleach=${it.care.bleach} unsure=${it.care.lowConfidence}")
                    }
                }
                val cur = draft
                if (cur?.id != d.id) return@launch
                replaceDraft(
                    cur.copy(
                        care = read.care,
                        name = cur.name.ifBlank { read.garmentGuess },
                        colorGroup = read.colorGroup ?: cur.colorGroup,
                        description = read.description.ifBlank { cur.description },
                        step = DraftStep.REVIEW, busy = false, error = null,
                    )
                )
                toast("Tag decoded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cur = draft
                if (cur?.id == d.id) replaceDraft(cur.copy(busy = false, error = e.message ?: "Something went wrong"))
            }
        }
    }

    /** A busy or rate-limited model is asked again, twice, before the failure is shown. */
    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: AiException) {
                if (!e.retryable || attempt >= 2) throw e
                Log.w(TAG, "retrying after: ${e.message}")
                delay(if (attempt == 0) 2_000L else 6_000L)
                attempt++
            }
        }
    }

    fun enterManually() {
        draft?.let { replaceDraft(it.copy(care = it.care ?: CareProfile.blank(), step = DraftStep.REVIEW, error = null)) }
    }

    fun copyCareFrom(g: Garment) {
        draft?.let {
            replaceDraft(it.copy(care = g.care.copy(lowConfidence = emptyList()), colorGroup = it.colorGroup ?: g.colorGroup, step = DraftStep.REVIEW, error = null))
            toast("Care copied from ${g.name}")
        }
    }

    fun backToPhotos() { draft?.let { replaceDraft(it.copy(step = DraftStep.PHOTOS, error = null)) } }
    fun setName(name: String) { draft?.let { replaceDraft(it.copy(name = name)) } }
    fun setColorGroup(c: ColorGroup?) { draft?.let { replaceDraft(it.copy(colorGroup = c)) } }
    fun setDescription(text: String) { draft?.let { replaceDraft(it.copy(description = text)) } }
    fun updateCare(transform: (CareProfile) -> CareProfile) {
        draft?.let { replaceDraft(it.copy(care = transform(it.care ?: CareProfile.blank()))) }
    }

    fun saveDraft(onSaved: (String) -> Unit) {
        val d = draft ?: return
        // A second tap while the first save is moving files would find them gone and report a failure.
        if (d.saving || d.busy) return
        replaceDraft(d.copy(saving = true, error = null))
        viewModelScope.launch {
            try {
                val (garmentNames, tagNames) = withContext(Dispatchers.IO) {
                    d.garmentPhotos.map { container.photos.commit(it, d.id) } to d.tagPhotos.map { container.photos.commit(it, d.id) }
                }
                val garment = Garment(
                    id = d.id,
                    name = d.name.trim().ifBlank { "Untitled garment" },
                    care = d.care ?: CareProfile.blank(),
                    garmentPhotos = garmentNames,
                    tagPhotos = tagNames,
                    colorGroup = d.colorGroup,
                    description = d.description,
                    createdAt = d.createdAt,
                    updatedAt = System.currentTimeMillis(),
                )
                container.repo.upsert(garment)
                replaceDraft(null)
                toast(if (d.editing) "Updated" else "Saved to closet")
                onSaved(garment.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val cur = draft
                if (cur?.id == d.id) replaceDraft(cur.copy(saving = false))
                toast("Save failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /* ---------------- snap it to find it ---------------- */

    fun snapToFind(uri: Uri, forPlan: Boolean) {
        viewModelScope.launch {
            try {
                val file = container.photos.importToDraft(uri)
                match = MatchState(query = file, forPlan = forPlan)
                runMatch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't read that photo: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun retryMatch() {
        match = match?.copy(busy = true, error = null)
        runMatch()
    }

    private fun runMatch() {
        val m = match ?: return
        viewModelScope.launch {
            val list = garments.value.take(MAX_MATCH_ITEMS)
            try {
                val model = container.vision()
                val (query, inputs) = withContext(Dispatchers.IO) {
                    val q = Images.jpeg(Images.decodeUpright(m.query.readBytes(), 768), 78)
                    val ins = list.mapIndexed { i, g ->
                        MatchInput(
                            index = i, name = g.name, fabric = g.care.fabric, notes = g.care.specialNotes,
                            description = g.description,
                            thumb = if (i < MAX_MATCH_THUMBS) g.coverPhoto?.let { container.photos.thumbBytes(it) } else null,
                        )
                    }
                    q to ins
                }
                val result = model.match(query, inputs)
                val hits = result.candidates.mapNotNull { c -> list.getOrNull(c.index)?.let { MatchHit(it, c) } }
                if (match?.query == m.query) {
                    match = m.copy(busy = false, hits = hits, done = true, truncated = garments.value.size > list.size, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (match?.query == m.query) {
                    match = m.copy(busy = false, error = e.message ?: "Something went wrong", retryable = (e as? AiException)?.retryable ?: true)
                }
            }
        }
    }

    /** The photo that found nothing becomes the first photo of a new garment. */
    fun addQueryAsNewGarment() {
        val q = match?.query ?: return
        match = null
        startAdd(q)
    }

    fun clearMatch() { match = null }

    /* ---------------- plan a load ---------------- */

    fun togglePlan(id: String) {
        if (!planSelection.remove(id)) planSelection.add(id)
        plan = null
    }

    fun addToPlan(id: String) {
        if (id !in planSelection) planSelection.add(id)
        plan = null
    }

    fun clearPlan() {
        planSelection.clear()
        plan = null
    }

    fun checkLoad() {
        val items = planSelection.mapNotNull { id -> garments.value.firstOrNull { it.id == id } }
        plan = LoadPlanner.plan(items, machines())
    }

    fun resetPlanResult() { plan = null }

    /* ---------------- detail actions ---------------- */

    fun delete(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            container.repo.delete(id)
            planSelection.remove(id)
            toast("Removed")
            onDone()
        }
    }

    fun applyCareTo(source: Garment, targetIds: List<String>, onDone: () -> Unit) {
        viewModelScope.launch {
            var n = 0
            for (id in targetIds) {
                val g = container.repo.get(id) ?: continue
                container.repo.upsert(g.copy(care = source.care.copy(lowConfidence = emptyList()), updatedAt = System.currentTimeMillis()))
                n++
            }
            toast("Care applied to $n garment${if (n == 1) "" else "s"}")
            onDone()
        }
    }

    fun rereadTag(g: Garment) {
        startEdit(g)
        readTag()
    }

    /* ---------------- settings ---------------- */

    fun setProvider(p: Provider) = container.settings.setProvider(p)
    fun setMachines(id: String) = container.settings.setMachines(id)

    /** A model id is one short token; anything else is a paste gone wrong and must not be saved. */
    private fun cleanModelId(p: Provider, model: String): String? {
        val m = model.trim()
        if (m.isEmpty()) return p.defaultModel
        if (m.length > 80 || m.any { it.isWhitespace() } || m.contains('<')) {
            toast("That doesn't look like a model id. Tap Check key and pick one from the list.")
            return null
        }
        return m
    }

    fun saveProvider(p: Provider, key: String, model: String) {
        val m = cleanModelId(p, model) ?: return
        container.settings.setKey(p, key)
        container.settings.setModel(p, m)
        toast("Saved")
    }

    fun checkKey(p: Provider, key: String, model: String) {
        val m = cleanModelId(p, model) ?: return
        modelCheck = ModelCheck(busy = true)
        viewModelScope.launch {
            try {
                val models = container.vision(p, key.trim(), m).listModels()
                Log.i(TAG, "${p.label} models for this key: ${models.joinToString { it.id }}")
                container.settings.setKey(p, key)
                val wanted = m
                var auto: String? = null
                if (models.isNotEmpty() && models.none { it.id == wanted }) {
                    auto = models.first().id
                    container.settings.setModel(p, auto)
                } else {
                    container.settings.setModel(p, wanted)
                }
                modelCheck = ModelCheck(models = models, autoPicked = auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                modelCheck = ModelCheck(error = e.message ?: "Something went wrong")
            }
        }
    }

    fun clearModelCheck() { modelCheck = null }

    fun pickModel(p: Provider, id: String) {
        container.settings.setModel(p, id)
        toast("Model set to $id")
    }

    /* ---------------- backup ---------------- */

    fun exportCloset(uri: Uri, includePhotos: Boolean) {
        viewModelScope.launch {
            transferBusy = true
            try {
                val n = withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val stream = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull() ?: resolver.openOutputStream(uri)
                    stream?.use { container.backup.export(it, includePhotos) } ?: throw IllegalStateException("Could not open that location.")
                }
                toast("Exported $n garment${if (n == 1) "" else "s"}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Export failed: ${e.message ?: "unknown error"}")
            } finally {
                transferBusy = false
            }
        }
    }

    fun importCloset(uri: Uri, mode: ImportMode) {
        viewModelScope.launch {
            transferBusy = true
            try {
                val report = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { container.backup.import(it, mode) }
                        ?: throw IllegalStateException("Could not open that file.")
                }
                plan = null
                planSelection.retainAll(garments.value.map { it.id }.toSet())
                toast(report.summary())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Import failed: ${e.message ?: "unknown error"}")
            } finally {
                transferBusy = false
            }
        }
    }

    companion object {
        private const val TAG = "ClosetCare"
        const val MAX_GARMENT_PHOTOS = 5
        const val MAX_TAG_PHOTOS = 4
        /** Items described in words to the matcher; descriptions are cheap. */
        const val MAX_MATCH_ITEMS = 120
        /** Items that also get a reference photo, newest first. */
        const val MAX_MATCH_THUMBS = 30
    }
}
