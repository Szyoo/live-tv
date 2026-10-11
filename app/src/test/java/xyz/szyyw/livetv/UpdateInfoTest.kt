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

class UpdateEvaluateTest {
    private val json = """{"versionCode":5,"versionName":"0.5.0","apk":"https://x/a.apk"}"""
    @org.junit.Test fun newer() = org.junit.Assert.assertTrue(
        xyz.szyyw.livetv.update.UpdateInfo.evaluate(json, 4) is xyz.szyyw.livetv.update.CheckResult.Newer)
    @org.junit.Test fun upToDate() = org.junit.Assert.assertEquals(
        xyz.szyyw.livetv.update.CheckResult.UpToDate, xyz.szyyw.livetv.update.UpdateInfo.evaluate(json, 5))
    @org.junit.Test fun badFormat() = org.junit.Assert.assertTrue(
        xyz.szyyw.livetv.update.UpdateInfo.evaluate("<html>", 1) is xyz.szyyw.livetv.update.CheckResult.Failed)
    @org.junit.Test fun sizeFormat() {
        org.junit.Assert.assertEquals("512 B", xyz.szyyw.livetv.data.CacheTools.format(512))
        org.junit.Assert.assertEquals("1.5 MB", xyz.szyyw.livetv.data.CacheTools.format(1572864))
    }
}
