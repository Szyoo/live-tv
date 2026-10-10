package xyz.szyyw.livetv

import org.junit.Assert.*
import org.junit.Test
import xyz.szyyw.livetv.update.UpdateInfo

class UpdateInfoTest {
    private val ok = """{"versionCode": 7, "versionName": "0.3.0", "apk": "https://x/live-tv.apk", "notes": "修复若干问题"}"""

    @Test fun parsesValid() {
        val i = UpdateInfo.parse(ok)!!
        assertEquals(7, i.versionCode)
        assertEquals("0.3.0", i.versionName)
        assertEquals("https://x/live-tv.apk", i.apk)
        assertEquals("修复若干问题", i.notes)
    }
    @Test fun notesAndNameOptional() {
        val i = UpdateInfo.parse("""{"versionCode":3,"apk":"http://a/b.apk"}""")!!
        assertEquals("", i.notes)
        assertEquals("3", i.versionName)
    }
    @Test fun invalidReturnsNull() {
        assertNull(UpdateInfo.parse(null))
        assertNull(UpdateInfo.parse(""))
        assertNull(UpdateInfo.parse("<html>404</html>"))
        assertNull(UpdateInfo.parse("[1,2]"))
        assertNull(UpdateInfo.parse("""{"versionName":"1","apk":"https://a"}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":5}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":5,"apk":"ftp://a/b.apk"}"""))
        assertNull(UpdateInfo.parse("""{"versionCode":0,"apk":"https://a/b.apk"}"""))
    }
    @Test fun versionComparisonStrictlyGreater() {
        val i = UpdateInfo.parse(ok)!!
        assertTrue(i.isNewerThan(6))
        assertFalse(i.isNewerThan(7))
        assertFalse(i.isNewerThan(8))
    }
}
