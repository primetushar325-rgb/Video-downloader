package com.vidx.core.url

import com.vidx.core.model.Platform
import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertNull
import com.vidx.testlib.Tests

object UrlNormalizerTest {
    init {
        Tests.test("normalize", "strips tracking params") {
            val n = UrlNormalizer.normalize(
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_source=share&fbclid=abc&t=30"
            )
            assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30", n.display)
            assertEquals(Platform.YOUTUBE, n.platform)
            assertEquals("dQw4w9WgXcQ", n.videoId)
        }

        Tests.test("normalize", "canonical keys identify the same video across urls") {
            val a = UrlNormalizer.normalize("https://www.youtube.com/watch?v=dQw4w9WgXcQ&utm_source=share")
            val b = UrlNormalizer.normalize("https://youtu.be/dQw4w9WgXcQ?si=whatever&t=10")
            val c = UrlNormalizer.normalize("https://www.youtube.com/shorts/dQw4w9WgXcQ")
            assertEquals(a.canonicalKey, b.canonicalKey)
            assertEquals(a.canonicalKey, c.canonicalKey)

            val d = UrlNormalizer.normalize("https://www.youtube.com/watch?v=DIFFERENT123")
            assertEquals(false, a.canonicalKey == d.canonicalKey)
        }

        Tests.test("normalize", "canonical keys per platform") {
            assertEquals("tiktok:7300000000000000000",
                UrlNormalizer.normalize("https://www.tiktok.com/@u/video/7300000000000000000").canonicalKey)
            assertEquals("instagram:CxYzAbCdEf",
                UrlNormalizer.normalize("https://www.instagram.com/reel/CxYzAbCdEf/?igsh=abc").canonicalKey)
            assertEquals("x:1234567890123456789",
                UrlNormalizer.normalize("https://x.com/u/status/1234567890123456789").canonicalKey)
            assertEquals("facebook:123456789012345",
                UrlNormalizer.normalize("https://www.facebook.com/watch/?v=123456789012345").canonicalKey)
            assertEquals("pinterest:123456789012345678",
                UrlNormalizer.normalize("https://pinterest.com/pin/123456789012345678/").canonicalKey)
        }

        Tests.test("normalize", "does not collapse genuinely different videos") {
            // Same user page, different Instagram posts must stay distinct.
            val p1 = UrlNormalizer.normalize("https://www.instagram.com/p/AAAAABBBBB/")
            val p2 = UrlNormalizer.normalize("https://www.instagram.com/p/CCCCCDDDDD/")
            assertEquals(false, p1.canonicalKey == p2.canonicalKey)

            // Unknown-identity URLs keep their full normalized form.
            val x1 = UrlNormalizer.normalize("https://cdn.example.com/a.mp4?token=1")
            val x2 = UrlNormalizer.normalize("https://cdn.example.com/a.mp4?token=2")
            assertEquals(false, x1.canonicalKey == x2.canonicalKey)
        }

        Tests.test("normalize", "direct files keep tracking-stripped display url") {
            val n = UrlNormalizer.normalize("https://cdn.example.com/clip.mp4?token=x&utm_source=app")
            assertEquals("https://cdn.example.com/clip.mp4?token=x", n.display)
            assertNull(n.videoId)
        }
    }
}
