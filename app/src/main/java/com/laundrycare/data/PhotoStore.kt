package com.laundrycare.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Where photos live.
 *
 *   photos/   the stored copies, upright and at most [MAX_EDGE] px on the long side
 *   thumbs/   small copies for grids and for the matcher's reference set
 *   cache/draft    photos added to a garment that is still being edited
 *   cache/capture  the camera's hand-off file, deleted as soon as it is read
 */
class PhotoStore(filesDir: File, cacheDir: File, private val context: Context?) {

    constructor(context: Context) : this(context.filesDir, context.cacheDir, context)

    val photosDir: File = File(filesDir, "photos").apply { mkdirs() }
    val thumbsDir: File = File(filesDir, "thumbs").apply { mkdirs() }
    private val draftDir = File(cacheDir, "draft").apply { mkdirs() }
    private val captureDir = File(cacheDir, "capture").apply { mkdirs() }

    private val android: Context get() = checkNotNull(context) { "This PhotoStore has no Android context (test instance)." }

    fun newCaptureFile(): File {
        captureDir.mkdirs()
        return File(captureDir, "cap-${System.currentTimeMillis()}.jpg")
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(android, "${android.packageName}.files", file)

    /** A photo from the camera or the gallery, stood upright and shrunk to what the reader needs, as a draft file. */
    suspend fun importToDraft(uri: Uri, maxEdge: Int = MAX_EDGE): File = withContext(Dispatchers.IO) {
        val original = readBytes(uri)
        val bitmap = Images.decodeUpright(original, maxEdge)
        val out = File(draftDir.apply { mkdirs() }, "d-${UUID.randomUUID()}.jpg")
        out.writeBytes(Images.jpeg(bitmap, QUALITY))
        bitmap.recycle()
        if (uri.scheme == "file") {
            uri.path?.let { p -> File(p).takeIf { it.parentFile?.absolutePath == captureDir.absolutePath }?.delete() }
        }
        out
    }

    private fun readBytes(uri: Uri): ByteArray =
        if (uri.scheme == "file") File(requireNotNull(uri.path)).readBytes()
        else android.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("That photo could not be opened.")

    fun isStored(file: File) = file.parentFile?.absolutePath == photosDir.absolutePath

    /** Moves a draft into the store under the garment's id and returns the stored name. A stored file is kept as it is. */
    fun commit(file: File, garmentId: String): String {
        if (isStored(file)) return file.name
        val name = "$garmentId-${UUID.randomUUID()}.jpg"
        val dest = File(photosDir, name)
        if (!file.renameTo(dest)) {
            file.copyTo(dest, overwrite = true)
            file.delete()
        }
        thumb(name)
        return name
    }

    fun file(name: String) = File(photosDir, name)

    /** The small copy, made on first use if it is missing. */
    fun thumb(name: String): File {
        val t = File(thumbsDir, name)
        if (!t.exists()) {
            runCatching {
                val src = file(name)
                if (src.exists()) {
                    val bitmap = Images.decodeUpright(src.readBytes(), THUMB_EDGE)
                    t.writeBytes(Images.jpeg(bitmap, THUMB_QUALITY))
                    bitmap.recycle()
                }
            }
        }
        return t
    }

    fun bytes(name: String): ByteArray? = file(name).takeIf { it.exists() }?.readBytes()
    fun thumbBytes(name: String): ByteArray? = thumb(name).takeIf { it.exists() }?.readBytes()

    /** Writes a restored photo into the store under its original name. */
    fun restore(name: String, source: File) {
        val dest = file(name)
        if (!source.renameTo(dest)) source.copyTo(dest, overwrite = true)
        File(thumbsDir, name).delete()
    }

    fun delete(names: Collection<String>) {
        names.forEach {
            file(it).delete()
            File(thumbsDir, it).delete()
        }
    }

    fun deleteAll() {
        photosDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
    }

    /**
     * Leftovers from an edit that never finished. Only safe at start-up.
     *
     * @param keep absolute paths still referenced by a restored draft. Anything
     *        written in the last few minutes is also left alone: the camera app
     *        may have just produced it for a result that has not been delivered yet.
     */
    fun cleanupDrafts(keep: Set<String> = emptySet()) {
        val cutoff = System.currentTimeMillis() - RECENT_MS
        val files = draftDir.listFiles()?.toList().orEmpty() + captureDir.listFiles()?.toList().orEmpty()
        files.forEach { f ->
            if (f.isFile && f.absolutePath !in keep && f.lastModified() < cutoff) f.delete()
        }
    }

    companion object {
        const val MAX_EDGE = 1600
        /** Tags keep more pixels: the symbol row is cut out of them and enlarged for the reader. */
        const val TAG_MAX_EDGE = 3200
        const val QUALITY = 85
        const val THUMB_EDGE = 384
        const val THUMB_QUALITY = 72
        private const val RECENT_MS = 10 * 60 * 1000L
    }
}

object Images {

    /** Decoded sampled-down and stood upright, so the full-size bitmap never exists in memory. */
    fun decodeUpright(original: ByteArray, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("That image could not be decoded.")

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) sample *= 2

        val decoded = BitmapFactory.decodeByteArray(
            original, 0, original.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw IllegalStateException("That image could not be decoded.")

        val scale = maxEdge.toFloat() / maxOf(decoded.width, decoded.height)
        val sized = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val degrees = exifDegrees(original)
        if (degrees == 0f) return sized
        return Bitmap.createBitmap(sized, 0, 0, sized.width, sized.height, Matrix().apply { postRotate(degrees) }, true)
            .also { if (it !== sized) sized.recycle() }
    }

    fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** The stored photo shrunk for sending, when it is kept at more than the reader needs for an overview. */
    fun resized(original: ByteArray, maxEdge: Int, quality: Int = 85): ByteArray {
        val bitmap = decodeUpright(original, maxEdge)
        return jpeg(bitmap, quality).also { bitmap.recycle() }
    }

    /**
     * When nobody could say where the symbols are: the photo in four
     * overlapping quarters, each enlarged, so whichever quarter holds the
     * symbol row shows it at roughly twice the size.
     */
    fun quadrants(original: ByteArray, targetWidth: Int = 1600, quality: Int = 88): List<ByteArray> {
        val full = decodeUpright(original, 6000)
        val overlap = 0.15
        val halfW = (full.width * (0.5 + overlap)).toInt().coerceAtMost(full.width)
        val halfH = (full.height * (0.5 + overlap)).toInt().coerceAtMost(full.height)
        val lefts = listOf(0, full.width - halfW)
        val tops = listOf(0, full.height - halfH)
        val out = mutableListOf<ByteArray>()
        for (top in tops) for (left in lefts) {
            val crop = Bitmap.createBitmap(full, left, top, halfW, halfH)
            val scale = targetWidth.toFloat() / crop.width
            val sized = if (scale > 1f) {
                Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt().coerceAtLeast(1), true)
                    .also { if (it !== crop) crop.recycle() }
            } else crop
            out += jpeg(sized, quality)
            sized.recycle()
        }
        full.recycle()
        return out
    }

    /**
     * The region [box] (ymin, xmin, ymax, xmax on a 0–1000 scale) cut out of
     * the full-resolution photo, with a margin, and enlarged to at least
     * [targetWidth] px wide so the model sees each dot as a real blob.
     */
    fun cropZoom(original: ByteArray, box: IntArray, targetWidth: Int = 2400, quality: Int = 92): ByteArray {
        val full = decodeUpright(original, 6000)
        val margin = 0.04
        val top = ((box[0] / 1000.0 - margin) * full.height).toInt().coerceIn(0, full.height - 1)
        val left = ((box[1] / 1000.0 - margin) * full.width).toInt().coerceIn(0, full.width - 1)
        val bottom = ((box[2] / 1000.0 + margin) * full.height).toInt().coerceIn(top + 1, full.height)
        val right = ((box[3] / 1000.0 + margin) * full.width).toInt().coerceIn(left + 1, full.width)
        val crop = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
        if (crop !== full) full.recycle()
        val scale = targetWidth.toFloat() / crop.width
        val sized = if (scale > 1f) {
            Bitmap.createScaledBitmap(crop, (crop.width * scale).toInt(), (crop.height * scale).toInt().coerceAtLeast(1), true)
                .also { if (it !== crop) crop.recycle() }
        } else crop
        return jpeg(sized, quality).also { sized.recycle() }
    }

    private fun exifDegrees(original: ByteArray): Float = runCatching {
        ExifInterface(original.inputStream()).rotationDegrees.toFloat()
    }.getOrDefault(0f)
}
