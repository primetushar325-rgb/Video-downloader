package com.vidx.core.clipboard

import com.vidx.core.model.Platform
import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.Tests

object ClipboardTest {
    init {
        Tests.test("clipboard", "recognizes supported video links") {
            val v = ClipboardClassifier.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
            assertTrue(v is ClipboardClassifier.Verdict.VideoLink)
            assertEquals(Platform.YOUTUBE, (v as ClipboardClassifier.Verdict.VideoLink).platform)

            val t = ClipboardClassifier.classify("https://www.tiktok.com/@u/video/7300000000000000000")
            assertTrue(t is ClipboardClassifier.Verdict.VideoLink)
            assertEquals(Platform.TIKTOK, (t as ClipboardClassifier.Verdict.VideoLink).platform)

            val d = ClipboardClassifier.classify("https://cdn.example.com/v.mp4")
            assertTrue(d is ClipboardClassifier.Verdict.VideoLink)
        }

        Tests.test("clipboard", "rejects sensitive content") {
            val card = ClipboardClassifier.classify("4111 1111 1111 1111")
            assertTrue(card is ClipboardClassifier.Verdict.Sensitive, "card must be rejected")

            val otp = ClipboardClassifier.classify("123456")
            assertTrue(otp is ClipboardClassifier.Verdict.Sensitive, "OTP must be rejected")

            val pw = ClipboardClassifier.classify("mypassword123")
            assertTrue(pw is ClipboardClassifier.Verdict.Sensitive, "credential-like must be rejected")

            val phone = ClipboardClassifier.classify("12345678901")
            assertTrue(phone is ClipboardClassifier.Verdict.Sensitive)
        }

        Tests.test("clipboard", "ignores random text") {
            assertEquals(ClipboardClassifier.Verdict.NotVideo, ClipboardClassifier.classify("hello world"))
            assertEquals(ClipboardClassifier.Verdict.NotVideo, ClipboardClassifier.classify(""))
            assertEquals(ClipboardClassifier.Verdict.NotVideo, ClipboardClassifier.classify(null))
            assertEquals(ClipboardClassifier.Verdict.NotVideo, ClipboardClassifier.classify("https://example.com/not-a-video"))
        }
    }
}
