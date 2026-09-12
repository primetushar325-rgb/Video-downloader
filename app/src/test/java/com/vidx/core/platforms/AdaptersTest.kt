package com.vidx.core.platforms

import com.vidx.core.model.Platform
import com.vidx.core.net.HttpClient
import com.vidx.core.transcript.TranscriptEngine
import com.vidx.core.util.Outcome
import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.MockServer
import com.vidx.testlib.Tests

object AdaptersTest {

    private fun withServer(block: (MockServer) -> Unit) {
        val srv = MockServer().start()
        try { block(srv) } finally {
            srv.stop()
            HttpClient.urlOverrides = emptyMap()
        }
    }

    init {
        Tests.test("adapters", "youtube oembed metadata parses") {
            withServer { srv ->
                srv.json("/oembed", """{"title":"Test Video","author_name":"Channel A","thumbnail_url":"https://i.ytimg.com/vi/x/hqdefault.jpg"}""")
                HttpClient.urlOverrides = mapOf("https://www.youtube.com/oembed" to srv.url("/oembed"))
                val res = YouTubeAdapter.resolveMetadata("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                assertTrue(res.isOk(), "err: ${res.errorOrNull()?.message}")
                val meta = (res as Outcome.Ok).value
                assertEquals("Test Video", meta.title)
                assertEquals("Channel A", meta.author)
                assertEquals(Platform.YOUTUBE, meta.platform)
                assertTrue(meta.downloadNote != null, "honest download note required")
                assertEquals(null, meta.durationMillis, "oEmbed gives no duration — must not invent it")
            }
        }

        Tests.test("adapters", "youtube 404 maps to not_found") {
            withServer { srv ->
                srv.json("/oembed", "{}", 404)
                HttpClient.urlOverrides = mapOf("https://www.youtube.com/oembed" to srv.url("/oembed"))
                val res = YouTubeAdapter.resolveMetadata("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                assertEquals("not_found", res.errorOrNull()?.code)
            }
        }

        Tests.test("adapters", "tiktok + vimeo oembed") {
            withServer { srv ->
                srv.json("/tto", """{"title":"tok","author_name":"tiktoker","thumbnail_url":"https://x/t.jpg"}""")
                srv.json("/vo", """{"title":"vim vid","author_name":"vimer","thumbnail_url":"https://x/v.jpg","duration":95}""")
                HttpClient.urlOverrides = mapOf(
                    "https://www.tiktok.com/oembed" to srv.url("/tto"),
                    "https://vimeo.com/api/oembed.json" to srv.url("/vo"),
                )
                val t = TikTokAdapter.resolveMetadata("https://www.tiktok.com/@u/video/7300000000000000000")
                assertTrue(t.isOk())
                assertEquals(Platform.TIKTOK, (t as Outcome.Ok).value.platform)
                val v = VimeoAdapter.resolveMetadata("https://vimeo.com/123456789")
                assertTrue(v.isOk())
                assertEquals(95_000L, (v as Outcome.Ok).value.durationMillis)
            }
        }

        Tests.test("adapters", "peertube metadata + formats + captions") {
            withServer { srv ->
                srv.json("/api/v1/videos/abc123", """
                {"name":"Peer Video","channel":{"displayName":"PeerChannel"},"thumbnailPath":"/static/t.jpg",
                 "duration":3720,"description":"desc",
                 "files":[{"id":1,"ext":"mp4","size":123456,"mimeType":"video/mp4",
                   "resolution":{"id":3,"label":"720p","width":1280,"height":720},
                   "fileDownloadUrl":"https://mirror.example/v.mp4"}],
                 "streamingPlaylists":[{"type":1,"files":[{"fileUrl":"https://mirror.example/hls/abc123-fragmented.mp4"}]}],
                 "captions":[]}""")
                srv.json("/api/v1/videos/abc123/captions", """{"data":[{"language":{"id":"en","label":"English"},"captionPath":"/captions/en.vtt"}]}""")
                srv.json("/captions/en.vtt", "WEBVTT\n\n00:00.000 --> 00:03.000\nHello world\n", 200)
                val origin = "http://127.0.0.1:${srv.port}"
                HttpClient.urlOverrides = mapOf(
                    "https://framatube.org/api/v1/videos/abc123" to srv.url("/api/v1/videos/abc123"),
                )
                val res = PeertubeAdapter.resolveMetadata("https://framatube.org/w/abc123")
                assertTrue(res.isOk(), "err: ${res.errorOrNull()?.message}")
                val meta = (res as Outcome.Ok).value
                assertEquals("Peer Video", meta.title)
                assertEquals(3_720_000L, meta.durationMillis)
                assertEquals(1, meta.videoFormats.size)
                assertEquals("720p", meta.videoFormats[0].qualityLabel)
                assertEquals("mp4", meta.videoFormats[0].container)
                assertEquals(123456L, meta.videoFormats[0].fileSize)
                // HLS-only entries are not offered as progressive downloads.
                assertTrue(meta.videoFormats.none { it.url.contains("hls") })

                // captions list + fetch through the engine
                HttpClient.urlOverrides = mapOf(
                    "https://framatube.org/api/v1/videos/abc123" to srv.url("/api/v1/videos/abc123"),
                    "https://framatube.org/captions" to srv.url("/captions"),
                )
                val tracks = PeertubeAdapter.listTranscriptTracks("https://framatube.org/w/abc123", null)
                assertTrue(tracks.isOk(), "err: ${tracks.errorOrNull()?.message}")
                val track = (tracks as Outcome.Ok).value.first()
                assertEquals("en", track.langCode)
                val fetched = TranscriptEngine.fetchTrack(track)
                assertTrue(fetched.isOk(), "err: ${fetched.errorOrNull()?.message}")
                assertEquals("Hello world", (fetched as Outcome.Ok).value.segments.first().text)
            }
        }

        Tests.test("adapters", "archive.org metadata + files") {
            withServer { srv ->
                srv.json("/metadata/item1", """
                {"metadata":{"title":"Archive Video","creator":["Some Creator"],"runtime":"1:23:45"},
                 "files":[{"name":"vid.mp4","format":"MPEG4","size":999},{"name":"audio.mp3","format":"VBR MP3","size":111}]}""")
                HttpClient.urlOverrides = mapOf("https://archive.org/metadata/item1" to srv.url("/metadata/item1"))
                val res = ArchiveOrgAdapter.resolveMetadata("https://archive.org/details/item1")
                assertTrue(res.isOk(), "err: ${res.errorOrNull()?.message}")
                val meta = (res as Outcome.Ok).value
                assertEquals("Archive Video", meta.title)
                assertEquals((1 * 3600 + 23 * 60 + 45) * 1000L, meta.durationMillis)
                assertEquals(1, meta.videoFormats.size)
                assertEquals(1, meta.audioFormats.size)
                assertTrue(meta.videoFormats[0].url.startsWith("https://archive.org/download/item1/"))
            }
        }

        Tests.test("adapters", "direct media head probe") {
            withServer { srv ->
                srv.file("/clip.mp4", ByteArray(5000), mime = "video/mp4")
                val res = DirectAdapter.resolveMetadata(srv.url("/clip.mp4"))
                assertTrue(res.isOk(), "err: ${res.errorOrNull()?.message}")
                val meta = (res as Outcome.Ok).value
                assertEquals(Platform.DIRECT, meta.platform)
                assertEquals("clip", meta.title)
                assertEquals(5000L, meta.videoFormats[0].fileSize)
                assertEquals("video/mp4", meta.videoFormats[0].mimeType)
            }
        }

        Tests.test("adapters", "restricted platforms are honest") {
            val meta = RestrictedAdapter(Platform.INSTAGRAM).resolveMetadata("https://www.instagram.com/reel/CxYz/")
            assertTrue(meta.isOk())
            assertTrue((meta as Outcome.Ok).value.downloadNote != null)
            assertEquals(null, meta.value.title)
        }

        Tests.test("adapters", "youtube transcript flow: tracks + json3 parse") {
            withServer { srv ->
                srv.json("/player", """
                {"playabilityStatus":{"status":"OK","reason":""},
                 "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
                    {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc&lang=en&fmt=json3","languageCode":"en",
                     "name":{"simpleText":"English"},"kind":"standard"},
                    {"baseUrl":"https://www.youtube.com/api/timedtext?v=abc&lang=bn&fmt=json3","languageCode":"bn",
                     "name":{"simpleText":"বাংলা"},"kind":"asr"}]}}}""")
                srv.json("/timedtext", """{"events":[{"tStartMs":0,"dDurationMs":3000,"segs":[{"utf8":"Hello "},{"utf8":"world"}]},{"tStartMs":3000,"dDurationMs":2000,"segs":[{"utf8":"Second line"}]}]}""")
                HttpClient.urlOverrides = mapOf(
                    "https://www.youtube.com/youtubei/v1/player" to srv.url("/player"),
                    "https://www.youtube.com/api/timedtext" to srv.url("/timedtext"),
                )
                val tracks = YouTubeAdapter.listTranscriptTracks("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null)
                assertTrue(tracks.isOk(), "err: ${tracks.errorOrNull()?.message}")
                val list = (tracks as Outcome.Ok).value
                assertEquals(2, list.size)
                assertEquals("en", list[0].langCode)
                assertEquals("bn", list[1].langCode)
                assertTrue(list[1].autoGenerated)

                val res = TranscriptEngine.fetchTrack(list[0])
                assertTrue(res.isOk(), "err: ${res.errorOrNull()?.message}")
                val segs = (res as Outcome.Ok).value.segments
                assertEquals(2, segs.size)
                assertEquals("Hello world", segs[0].text)
                assertEquals(0L, segs[0].startMs)
                assertEquals(3000L, segs[1].startMs)
            }
        }

        Tests.test("adapters", "youtube private video is reported, never bypassed") {
            withServer { srv ->
                srv.json("/player", """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in to confirm your age"}}""")
                HttpClient.urlOverrides = mapOf("https://www.youtube.com/youtubei/v1/player" to srv.url("/player"))
                val tracks = YouTubeAdapter.listTranscriptTracks("https://www.youtube.com/watch?v=dQw4w9WgXcQ", null)
                assertTrue(!tracks.isOk())
                assertEquals("age_restricted", tracks.errorOrNull()?.code)
            }
        }
    }
}
