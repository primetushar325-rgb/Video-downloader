package com.vidx.core.util

import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.Tests

object CoreUtilTest {
    init {
        Tests.test("util", "file name sanitization blocks traversal") {
            // Dangerous ".." / "." segments are dropped; safe parts are kept.
            assertEquals("etc passwd", FileNames.sanitize("../../etc/passwd", fallback = "video.mp4"))
            assertEquals("a b c d e f g h.mp4", FileNames.sanitize("a\\b/c:d*e?\"f<g>|h.mp4"))
            assertEquals("title", FileNames.sanitize("   ..  ", fallback = "title"))
            assertEquals("title", FileNames.sanitize("../..", fallback = "title"))
            assertEquals("ok bad", FileNames.sanitize("ok\u0000\u001Fbad", fallback = "title"))
            assertEquals("my video.mp4", FileNames.sanitize("../../my/video.mp4"))
        }

        Tests.test("util", "unique names increment") {
            val existing = setOf("clip.mp4", "clip (2).mp4")
            assertEquals("clip (3).mp4", FileNames.unique("clip", "mp4", existing))
            assertEquals("new.mp4", FileNames.unique("new", "mp4", existing))
        }

        Tests.test("util", "byte formatting") {
            assertEquals("512 B", ByteFmt.human(512))
            assertEquals("1.5 KB", ByteFmt.human(1536))
            assertEquals("2.0 MB", ByteFmt.human(2_000_000))
            assertEquals("1.0 GB", ByteFmt.human(1_000_000_000))
        }

        Tests.test("util", "clock formatting") {
            assertEquals("1:15", TimeFmt.clock(75_000))
            assertEquals("1:02:00", TimeFmt.clock(3_720_000))
            assertEquals("00:05", TimeFmt.transcriptStamp(5_000))
            assertEquals("01:02:03", TimeFmt.transcriptStamp(3_723_000))
        }

        Tests.test("util", "eta formatting") {
            assertEquals("—", Eta.human(1000, 0))
            assertEquals("10s", Eta.human(10_000, 1000))
            assertEquals("1m 00s", Eta.human(60_000, 1000))
        }

        Tests.test("util", "sha1 is stable") {
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc"))
        }

        Tests.test("util", "retry delays grow") {
            val d = RetryPolicy.delays(3, 1000)
            assertEquals(listOf(1000L, 2000L), d)
            assertTrue(d[1] > d[0])
        }
    }
}
