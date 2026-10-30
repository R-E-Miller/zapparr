package tv.zapparr.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val files = mapOf(
        "look" to mapOf("palette" to "dracula", "clock" to "24"),
        "guide" to mapOf("favorites" to setOf("1001", "1003"), "order" to "1003,1001,1002", "names" to """{"1001":"CNN"}"""),
        "progress" to mapOf("movie:7" to """{"position":42}"""),
        "watch_next" to mapOf("declined" to true),
        "library_view" to mapOf("series" to false, "count" to 3, "seen" to 1_800_000_000_000L, "scale" to 1.5f),
    )

    @Test
    fun everyKindOfValueComesBackAsItWent() {
        val json = Backup.write(files, created = 1234, app = "0.2")
        assertEquals(files, Backup.read(json))
        assertEquals(1234L, Backup.created(json))
        assertFalse(Backup.hasLogins(json))
    }

    @Test
    fun loginsOnlyWhenTheyWereIncluded() {
        val json = Backup.write(files + ("server" to mapOf("address" to "http://10.0.0.2:9191")), created = 1, app = "0.2")
        assertTrue(Backup.hasLogins(json))
        assertEquals("http://10.0.0.2:9191", Backup.read(json).getValue("server")["address"])
    }

    @Test
    fun filesZapparrDoesNotKeepAreLeftOut() {
        val json = Backup.write(files + ("something_else" to mapOf("x" to "y")), created = 1, app = "0.2")
        assertFalse("something_else" in Backup.read(json))
    }

    @Test
    fun anythingElseIsRefused() {
        assertThrows(BackupException::class.java) { Backup.read("not json") }
        assertThrows(BackupException::class.java) { Backup.read("""{"format":"something","prefs":{}}""") }
        assertThrows(BackupException::class.java) { Backup.read("""{"format":"zapparr-backup","version":99,"prefs":{}}""") }
    }
}
