package com.vidx.core.transcript

import com.vidx.core.util.Outcome
import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.Tests

object TranscriptParserTest {
    init {
        Tests.test("transcript", "parses vtt") {
            val vtt = """
            WEBVTT

            00:00.000 --> 00:03.500
            First <i>line</i> &amp; more

            00:03.500 --> 00:07.000
            Second line
            continues here
            """.trimIndent()
            val res = TranscriptParsers.parse(vtt, "vtt")
            assertTrue(res.isOk())
            val segs = (res as Outcome.Ok).value
            assertEquals(2, segs.size)
            assertEquals("First line & more", segs[0].text)
            assertEquals(3500L, segs[0].endMs)
            assertEquals("Second line continues here", segs[1].text)
        }

        Tests.test("transcript", "parses srt with comma timestamps") {
            val srt = """
            1
            00:00:00,000 --> 00:00:04,000
            Hello there

            2
            00:00:04,000 --> 00:00:08,250
            General Kenobi
            """.trimIndent()
            val res = TranscriptParsers.parse(srt, "srt")
            assertTrue(res.isOk())
            val segs = (res as Outcome.Ok).value
            assertEquals(2, segs.size)
            assertEquals(0L, segs[0].startMs)
            assertEquals(4000L, segs[0].endMs)
            assertEquals(8250L, segs[1].endMs)
            assertEquals("General Kenobi", segs[1].text)
        }

        Tests.test("transcript", "parses json3") {
            val json = """{"events":[{"tStartMs":0,"dDurationMs":2000,"segs":[{"utf8":"A"},{"utf8":"B"}]},{"tStartMs":2000,"dDurationMs":1500,"segs":[{"utf8":"&amp;C"}]}]}"""
            val res = TranscriptParsers.parse(json, "json3")
            assertTrue(res.isOk())
            val segs = (res as Outcome.Ok).value
            assertEquals(2, segs.size)
            assertEquals("AB", segs[0].text)
            assertEquals("&C", segs[1].text)
        }

        Tests.test("transcript", "garbage input yields a clean error, never a crash") {
            val res = TranscriptParsers.parse("!!!not a caption file!!!", null)
            assertTrue(!res.isOk())
            // VTT parsing succeeds but finds no cues → reported as no captions.
            assertEquals("captions_unavailable", res.errorOrNull()?.code)
        }

        Tests.test("transcript", "result text helpers") {
            val r = TranscriptResult(
                track = TranscriptTrack("t", "en", "English", false, "http://x"),
                segments = listOf(
                    TranscriptSegment(0, 2000, " Hello "),
                    TranscriptSegment(2000, 4000, "world"),
                ),
                source = "captions",
            )
            assertEquals("Hello world", r.plainText)
            assertTrue(r.withTimestamps().contains("00:00  Hello"))
            assertEquals("00:02  world", r.withTimestamps().lines()[1])
        }

        Tests.test("transcript", "language labels") {
            assertEquals("বাংলা (Bengali)", TranscriptLanguages.label("bn"))
            assertEquals("English", TranscriptLanguages.label("en-US"))
            assertEquals("Deutsch (German)", TranscriptLanguages.label("de-CH"))
        }

        Tests.test("transcript", "translation is honestly unavailable") {
            val res = UnavailableTranslator.translate("hello", "en", "bn")
            assertTrue(!res.isOk())
            assertEquals("translation_unavailable", res.errorOrNull()?.code)
        }
    }
}
