package xyz.szyyw.livetv.logic

import org.junit.Assert.*
import org.junit.Test

class FailureTest {
    @Test fun httpStatusWinsAndIsInMessage() {
        val f = Failure.classify(2004, 404)
        assertEquals(FailureKind.HTTP, f.kind)
        assertTrue(f.describe().contains("404"))
        assertTrue(Failure.classify(2004, 403).describe().contains("403"))
        assertTrue(Failure.classify(2004, 502).describe().contains("502"))
        assertTrue(Failure(FailureKind.HTTP, 418).describe().contains("418"))
    }
    @Test fun timeoutAndNetwork() {
        assertEquals(FailureKind.TIMEOUT, Failure.classify(2002).kind)
        assertEquals("连接超时", Failure.classify(2002).describe())
        assertEquals(FailureKind.NETWORK, Failure.classify(2001).kind)
        assertTrue(Failure.classify(2001).describe().contains("网络"))
    }
    @Test fun formatAndDecoder() {
        for (c in listOf(2003, 3001, 3002, 3003, 3004)) assertEquals(FailureKind.FORMAT, Failure.classify(c).kind)
        assertEquals(FailureKind.DECODER, Failure.classify(4001).kind)
        assertEquals(FailureKind.DECODER, Failure.classify(4005).kind)
    }
    @Test fun unknownIsOtherAndNeverShowsCode() {
        val f = Failure.classify(1000)
        assertEquals(FailureKind.OTHER, f.kind)
        assertFalse(f.describe().contains("ERROR_CODE"))
    }
    @Test fun allKindsHaveChineseText() {
        for (k in FailureKind.values()) {
            val d = Failure(k).describe()
            assertTrue(d.isNotBlank())
            assertFalse(d.contains("ERROR"))
        }
        assertTrue(Failure(FailureKind.STALL).describe().contains("缓冲"))
    }
}
