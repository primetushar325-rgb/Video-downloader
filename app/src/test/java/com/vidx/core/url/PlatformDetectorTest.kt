package com.vidx.core.url

import com.vidx.core.model.Platform
import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Tests

object PlatformDetectorTest {
    init {
        Tests.test("platform", "detects youtube variants") {
            assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://youtu.be/dQw4w9WgXcQ?t=3"))
            assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://m.youtube.com/shorts/abc123XYZ_-"))
            assertEquals(Platform.YOUTUBE, PlatformDetector.detect("https://youtube.com/live/abc123XYZ_-"))
            assertEquals(Platform.YOUTUBE, PlatformDetector.detect("youtu.be/xyz1234567_")) // no scheme
        }

        Tests.test("platform", "detects social platforms") {
            assertEquals(Platform.TIKTOK, PlatformDetector.detect("https://www.tiktok.com/@user/video/7300000000000000000"))
            assertEquals(Platform.TIKTOK, PlatformDetector.detect("https://vm.tiktok.com/ABC123/"))
            assertEquals(Platform.INSTAGRAM, PlatformDetector.detect("https://www.instagram.com/reel/CxYzAbCdEf/"))
            assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://www.facebook.com/watch/?v=123456789012345"))
            assertEquals(Platform.FACEBOOK, PlatformDetector.detect("https://fb.watch/abC123/"))
            assertEquals(Platform.TWITTER_X, PlatformDetector.detect("https://x.com/someuser/status/1234567890123456789"))
            assertEquals(Platform.TWITTER_X, PlatformDetector.detect("https://twitter.com/someuser/status/1234567890123456789"))
            assertEquals(Platform.REDNOTE, PlatformDetector.detect("https://www.xiaohongshu.com/explore/64ab1234567890123?xsec_token=abc"))
            assertEquals(Platform.REDNOTE, PlatformDetector.detect("https://xhslink.com/abc"))
            assertEquals(Platform.KUAISHOU, PlatformDetector.detect("https://www.kuaishou.com/short-video/3x1234567890"))
            assertEquals(Platform.PINTEREST, PlatformDetector.detect("https://www.pinterest.com/pin/123456789012345678/"))
            assertEquals(Platform.VIMEO, PlatformDetector.detect("https://vimeo.com/123456789"))
        }

        Tests.test("platform", "detects peertube instances by path") {
            assertEquals(Platform.PEERTUBE, PlatformDetector.detect("https://framatube.org/w/abcDEF1234567890xyz"))
            assertEquals(Platform.PEERTUBE, PlatformDetector.detect("https://video.example.com/videos/watch/9b2c1d3e-1111-2222-3333-444455556666"))
            assertEquals(Platform.UNKNOWN, PlatformDetector.detect("https://video.example.com/random/page"))
        }

        Tests.test("platform", "detects archive.org and direct media") {
            assertEquals(Platform.ARCHIVE_ORG, PlatformDetector.detect("https://archive.org/details/SomeCollection"))
            assertEquals(Platform.DIRECT, PlatformDetector.detect("https://cdn.example.com/videos/clip.mp4"))
            assertEquals(Platform.DIRECT, PlatformDetector.detect("https://files.example.com/a.mp4?token=xyz"))
            assertEquals(Platform.UNKNOWN, PlatformDetector.detect("https://example.com/page.html"))
        }

        Tests.test("platform", "unknown hosts and junk are rejected") {
            assertEquals(Platform.UNKNOWN, PlatformDetector.detect("https://notavideosite.example.net/watch?v=1"))
            assertEquals(Platform.UNKNOWN, PlatformDetector.detect("not a url at all"))
            assertEquals(Platform.UNKNOWN, PlatformDetector.detect("javascript:alert(1)"))
        }
    }
}
