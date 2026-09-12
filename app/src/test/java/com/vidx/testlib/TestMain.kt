package com.vidx.testlib

/** Entry point for the dependency-free local test run (tools/run_tests.sh). */
object TestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // Force class-loading so every test file registers its cases.
        val suites = listOf(
            "com.vidx.core.json.JsonTest",
            "com.vidx.core.url.UrlValidatorTest",
            "com.vidx.core.url.PlatformDetectorTest",
            "com.vidx.core.url.UrlNormalizerTest",
            "com.vidx.core.util.CoreUtilTest",
            "com.vidx.core.download.HttpDownloaderTest",
            "com.vidx.core.download.DownloadQueueTest",
            "com.vidx.core.platforms.AdaptersTest",
            "com.vidx.core.transcript.TranscriptParserTest",
            "com.vidx.core.clipboard.ClipboardTest",
        )
        for (s in suites) Class.forName(s)
        kotlin.system.exitProcess(Runner.main())
    }
}
