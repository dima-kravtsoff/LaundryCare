package com.laundrycare.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The closet: one JSON file, rewritten whole on every change.
 *
 * A closet is a few hundred records at most, so a database would be more
 * machinery than data. Writes go to a temp file first and are renamed into
 * place, so a crash mid-write leaves the old closet rather than half a new one.
 */
class GarmentRepository(private val file: File, private val photos: PhotoStore) {

    private val mutex = Mutex()
    private val _garments = MutableStateFlow<List<Garment>>(emptyList())
    val garments: StateFlow<List<Garment>> = _garments.asStateFlow()

    @Volatile
    private var loaded = false

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (loaded) return@withLock
            _garments.value = sort(readFile())
            loaded = true
        }
    }

    private fun readFile(): List<Garment> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(ClosetFile.serializer(), file.readText()).garments
        } catch (e: Exception) {
            // Keep the unreadable file beside the store rather than overwrite it
            // on the next save; the photos it refers to are still on disk.
            runCatching { file.copyTo(File(file.parentFile, "closet-corrupt-${System.currentTimeMillis()}.json"), overwrite = true) }
            emptyList()
        }
    }

    fun get(id: String): Garment? = _garments.value.firstOrNull { it.id == id }

    suspend fun upsert(garment: Garment) {
        val old = get(garment.id)
        mutate { list -> list.filter { it.id != garment.id } + garment }
        if (old != null) {
            val dropped = old.allPhotos.toSet() - garment.allPhotos.toSet()
            if (dropped.isNotEmpty()) photos.delete(dropped)
        }
    }

    suspend fun delete(id: String) {
        val old = get(id) ?: return
        mutate { list -> list.filter { it.id != id } }
        photos.delete(old.allPhotos)
    }

    suspend fun replaceAll(list: List<Garment>) = mutate { list }

    private suspend fun mutate(transform: (List<Garment>) -> List<Garment>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = sort(transform(_garments.value))
            persist(next)
            _garments.value = next
        }
    }

    private fun persist(list: List<Garment>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(ClosetFile.serializer(), ClosetFile(garments = list)))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) tmp.copyTo(file, overwrite = true)
        }
    }

    private fun sort(list: List<Garment>) = list.sortedByDescending { it.createdAt }

    companion object {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
