package com.laundrycare

import com.laundrycare.data.Backup
import com.laundrycare.data.CareProfile
import com.laundrycare.data.ClosetFile
import com.laundrycare.data.Garment
import com.laundrycare.data.GarmentRepository
import com.laundrycare.data.ImportMode
import com.laundrycare.data.PhotoStore
import com.laundrycare.data.WashAction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export and import on a real file system, without an Android device. */
class BackupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class Device(root: File) {
        val photos = PhotoStore(File(root, "files").apply { mkdirs() }, File(root, "cache").apply { mkdirs() }, context = null)
        val repo = GarmentRepository(File(root, "files/closet.json"), photos)
        val backup = Backup(repo, photos)

        fun photo(name: String, content: String = name) = photos.file(name).also { it.writeText(content) }
    }

    private fun garment(id: String, name: String, vararg photos: String) = Garment(
        id = id, name = name, care = CareProfile(fabric = "cotton", washAction = WashAction.GENTLE, washTempC = 30),
        garmentPhotos = photos.toList(), createdAt = id.hashCode().toLong(),
    )

    @Test
    fun `a zip backup round-trips garments and photos onto a second device`() = runBlocking {
        val a = Device(tmp.newFolder("a"))
        a.repo.load()
        a.photo("g1-x.jpg", "PHOTO-1"); a.photo("g2-y.jpg", "PHOTO-2")
        a.repo.upsert(garment("g1", "Tee", "g1-x.jpg"))
        a.repo.upsert(garment("g2", "Jeans", "g2-y.jpg", "g2-missing.jpg"))   // one listed photo is not on disk

        val zip = ByteArrayOutputStream()
        assertEquals(2, a.backup.export(zip, includePhotos = true))

        val b = Device(tmp.newFolder("b"))
        b.repo.load()
        val report = b.backup.import(ByteArrayInputStream(zip.toByteArray()), ImportMode.MERGE)
        assertEquals(2, report.added)
        assertEquals(2, report.photos)
        assertEquals(0, report.errors)
        assertEquals("PHOTO-2", b.photos.file("g2-y.jpg").readText())
        val jeans = b.repo.get("g2")!!
        assertEquals(listOf("g2-y.jpg"), jeans.garmentPhotos)   // the missing one was dropped from the record
        assertEquals(WashAction.GENTLE, jeans.care.washAction)
        assertTrue(report.summary(), report.summary().startsWith("Imported 2 garments, 2 photos"))
    }

    @Test
    fun `merge keeps what is already here, replace wipes photos and records first`() = runBlocking {
        val a = Device(tmp.newFolder("a"))
        a.repo.load()
        a.photo("g1-x.jpg")
        a.repo.upsert(garment("g1", "Tee", "g1-x.jpg"))
        val careOnly = ByteArrayOutputStream()
        a.backup.export(careOnly, includePhotos = false)
        val text = careOnly.toString("UTF-8")
        assertTrue(text.contains("\"schemaVersion\": 1"))
        assertFalse("care-only export carries no photo names", text.contains("g1-x.jpg"))

        // Same id already present: left alone, counted as skipped.
        val merge = a.backup.import(ByteArrayInputStream(careOnly.toByteArray()), ImportMode.MERGE)
        assertEquals(0, merge.added); assertEquals(1, merge.skipped)
        assertEquals(listOf("g1-x.jpg"), a.repo.get("g1")!!.garmentPhotos)

        // Replace: the old photo file goes, the record comes back from the file without photos.
        val replace = a.backup.import(ByteArrayInputStream(careOnly.toByteArray()), ImportMode.REPLACE)
        assertTrue(replace.replaced)
        assertEquals(1, replace.added)
        assertFalse(a.photos.file("g1-x.jpg").exists())
        assertTrue(a.repo.get("g1")!!.garmentPhotos.isEmpty())
    }

    @Test
    fun `hostile or broken files are refused or trimmed, never crash or escape the photo folder`() = runBlocking {
        val d = Device(tmp.newFolder("d"))
        d.repo.load()

        // Not a backup at all.
        try { d.backup.import(ByteArrayInputStream("hello".toByteArray()), ImportMode.MERGE); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not a Closet Care backup")) }

        // A zip without closet.json.
        val noCloset = zipOf("readme.txt" to "x")
        try { d.backup.import(ByteArrayInputStream(noCloset), ImportMode.MERGE); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("no closet.json")) }

        // From the future.
        try { d.backup.import(ByteArrayInputStream("""{"schemaVersion":99,"garments":[]}""".toByteArray()), ImportMode.MERGE); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("newer version")) }

        // One garment record is garbage, a photo entry tries to climb out, another is not a jpg.
        val closet = """{"schemaVersion":1,"garments":[
            {"id":"ok","name":"Fine","createdAt":1,"garmentPhotos":["ok-a.jpg"]},
            {"id":"bad","name":"Broken","createdAt":"not a number"},
            {"id":"nophoto","name":"Listed photo never arrives","createdAt":2,"garmentPhotos":["gone.jpg"]}
        ]}"""
        val zip = zipOf("closet.json" to closet, "photos/ok-a.jpg" to "A", "photos/../../escape.jpg" to "E", "photos/x.jpg/..jpg" to "D", "photos/script.sh" to "S")
        val report = d.backup.import(ByteArrayInputStream(zip), ImportMode.MERGE)
        assertEquals(2, report.added)
        assertEquals(1, report.errors)
        assertEquals(1, report.photos)
        assertEquals("A", d.photos.file("ok-a.jpg").readText())
        assertTrue(d.repo.get("nophoto")!!.garmentPhotos.isEmpty())
        assertFalse(File(tmp.root, "escape.jpg").exists())
        assertFalse(File(tmp.root, "d/escape.jpg").exists())
        assertFalse(d.photos.file("script.sh").exists())
        assertFalse(File(d.photos.photosDir.parentFile, "import-stage").exists())
        assertTrue(report.summary().contains("1 unreadable entry skipped"))
    }

    @Test
    fun `the closet file is written atomically and a corrupt one is set aside, not overwritten`() = runBlocking {
        val root = tmp.newFolder("r")
        val d = Device(root)
        d.repo.load()
        d.repo.upsert(garment("g1", "Tee"))
        val file = File(root, "files/closet.json")
        assertTrue(file.exists())
        assertFalse(File(root, "files/closet.json.tmp").exists())
        assertEquals(1, GarmentRepository.json.decodeFromString(ClosetFile.serializer(), file.readText()).garments.size)

        file.writeText("{ this is not json")
        val again = Device(root)
        again.repo.load()
        assertTrue(again.repo.garments.value.isEmpty())
        val kept = File(root, "files").listFiles()!!.filter { it.name.startsWith("closet-corrupt-") }
        assertEquals(1, kept.size)
        assertEquals("{ this is not json", kept.single().readText())

        // Deleting drops the photo files as well as the record.
        again.photo("g9-a.jpg"); again.photo("g9-b.jpg")
        again.repo.upsert(garment("g9", "Towel", "g9-a.jpg", "g9-b.jpg"))
        again.repo.upsert(garment("g9", "Towel", "g9-a.jpg"))     // an edit that dropped one photo
        assertFalse(again.photos.file("g9-b.jpg").exists())
        assertTrue(again.photos.file("g9-a.jpg").exists())
        again.repo.delete("g9")
        assertFalse(again.photos.file("g9-a.jpg").exists())
        assertTrue(again.repo.garments.value.isEmpty())
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
