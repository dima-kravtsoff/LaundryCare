package com.laundrycare.data

import com.laundrycare.ai.arr
import com.laundrycare.ai.int
import com.laundrycare.ai.obj
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

enum class ImportMode { MERGE, REPLACE }

data class ImportReport(val added: Int, val skipped: Int, val photos: Int, val errors: Int, val replaced: Boolean) {
    fun summary(): String = buildString {
        append(if (replaced) "Closet replaced: " else "Imported ")
        append("$added garment${if (added == 1) "" else "s"}")
        if (photos > 0) append(", $photos photo${if (photos == 1) "" else "s"}")
        if (skipped > 0) append("; $skipped already here, left alone")
        if (errors > 0) append("; $errors unreadable entr${if (errors == 1) "y" else "ies"} skipped")
        append(".")
    }
}

/**
 * Export and import of the whole closet.
 *
 * A full backup is a zip: `closet.json` plus `photos/<name>.jpg` for every
 * photo. A care-only backup is the bare JSON with the photo lists emptied,
 * small enough to take often. Either reads back; a photo listed but not
 * present is simply dropped from that garment's record.
 */
class Backup(private val repo: GarmentRepository, private val photos: PhotoStore) {

    private val json: Json get() = GarmentRepository.json

    suspend fun export(out: OutputStream, includePhotos: Boolean): Int = withContext(Dispatchers.IO) {
        val garments = repo.garments.value
        val now = System.currentTimeMillis()
        if (!includePhotos) {
            val careOnly = garments.map { it.copy(garmentPhotos = emptyList(), tagPhotos = emptyList()) }
            out.bufferedWriter().use { it.write(json.encodeToString(ClosetFile.serializer(), ClosetFile(exportedAt = now, garments = careOnly))) }
            return@withContext garments.size
        }
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("closet.json"))
            zip.write(json.encodeToString(ClosetFile.serializer(), ClosetFile(exportedAt = now, garments = garments)).toByteArray())
            zip.closeEntry()
            garments.flatMap { it.allPhotos }.distinct().forEach { name ->
                val f = photos.file(name)
                if (f.exists()) {
                    zip.putNextEntry(ZipEntry("photos/$name"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        garments.size
    }

    suspend fun import(input: InputStream, mode: ImportMode): ImportReport = withContext(Dispatchers.IO) {
        val buffered = BufferedInputStream(input)
        buffered.mark(8)
        val head = ByteArray(2)
        val read = buffered.read(head)
        buffered.reset()
        val isZip = read == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

        val stageDir = File(photos.photosDir.parentFile, "import-stage").apply { deleteRecursively(); mkdirs() }
        val staged = HashMap<String, File>()
        var closetText: String? = null
        try {
            if (isZip) {
                ZipInputStream(buffered).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        val name = entry.name
                        if (!entry.isDirectory) {
                            if (name == "closet.json" || name.endsWith("/closet.json")) {
                                closetText = zip.readBytes().decodeToString()
                            } else if (name.contains("photos/")) {
                                val base = name.substringAfterLast('/')
                                if (base.isNotBlank() && !base.contains("..") && base.endsWith(".jpg")) {
                                    val f = File(stageDir, base)
                                    f.outputStream().use { zip.copyTo(it) }
                                    staged[base] = f
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } else {
                closetText = buffered.readBytes().decodeToString()
            }
            val text = closetText ?: throw IllegalStateException("There is no closet.json inside that file.")
            val root = runCatching { json.parseToJsonElement(text).obj() }.getOrNull()
                ?: throw IllegalStateException("That file is not a Closet Care backup.")
            val version = root["schemaVersion"].int() ?: 1
            if (version > ClosetFile.SCHEMA_VERSION) {
                throw IllegalStateException("That backup was made by a newer version of the app (schema $version). Update the app first.")
            }
            var errors = 0
            val incoming = root["garments"].arr().orEmpty().mapNotNull { el ->
                try {
                    json.decodeFromJsonElement(Garment.serializer(), el)
                } catch (e: Exception) {
                    errors++
                    null
                }
            }

            val existing = repo.garments.value.associateBy { it.id }
            if (mode == ImportMode.REPLACE) {
                photos.deleteAll()
                repo.replaceAll(emptyList())
            }
            val result = (if (mode == ImportMode.REPLACE) emptyList() else repo.garments.value).toMutableList()
            var added = 0
            var skipped = 0
            var restored = 0
            fun place(name: String): Boolean {
                val source = staged[name] ?: return photos.file(name).exists()
                photos.restore(name, source)
                restored++
                return true
            }
            for (g in incoming) {
                if (mode == ImportMode.MERGE && existing.containsKey(g.id)) {
                    skipped++
                    continue
                }
                result += g.copy(garmentPhotos = g.garmentPhotos.filter(::place), tagPhotos = g.tagPhotos.filter(::place))
                added++
            }
            repo.replaceAll(result)
            ImportReport(added, skipped, restored, errors, mode == ImportMode.REPLACE)
        } finally {
            stageDir.deleteRecursively()
        }
    }
}
