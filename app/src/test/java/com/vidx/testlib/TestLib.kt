package com.vidx.testlib

/**
 * VIDX's dependency-free test harness.
 *
 * The same test functions run in TWO environments:
 *  1. Locally on the JVM via `tools/run_tests.sh` (kotlinc, no Gradle/Maven needed).
 *  2. On CI via Gradle, through [JUnitBridge] which funnels everything into one JUnit test.
 *
 * Assertion failures are reported with source file + line; the runner continues past
 * failures so a single pass reports every problem (used by the bug-fix loop).
 */

class AssertionFailed(message: String, val file: String?, val line: Int?) : AssertionError(message)

object Asserts {
    fun fail(message: String): Nothing = throw AssertionFailed(message, callerFile(), callerLine())

    fun assertTrue(cond: Boolean, message: String = "expected true") {
        if (!cond) throw AssertionFailed(message, callerFile(), callerLine())
    }

    fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
        if (expected != actual) {
            throw AssertionFailed(
                "expected <$expected> but was <$actual>${if (message.isEmpty()) "" else " — $message"}",
                callerFile(), callerLine(),
            )
        }
    }

    fun assertNull(actual: Any?, message: String = "expected null") {
        if (actual != null) throw AssertionFailed("$message but was <$actual>", callerFile(), callerLine())
    }

    fun assertNotNull(actual: Any?, message: String = "expected non-null") {
        if (actual == null) throw AssertionFailed(message, callerFile(), callerLine())
    }

    fun assertContains(haystack: String, needle: String, message: String = "") {
        if (!haystack.contains(needle)) {
            throw AssertionFailed(
                "expected <$haystack> to contain <$needle>${if (message.isEmpty()) "" else " — $message"}",
                callerFile(), callerLine(),
            )
        }
    }

    fun assertFailsWith(expectedType: Class<out Throwable>, block: () -> Any?) {
        try {
            block()
        } catch (e: Throwable) {
            if (expectedType.isInstance(e)) return
            throw AssertionFailed("expected ${expectedType.simpleName} but got ${e::class.simpleName}: ${e.message}", callerFile(), callerLine())
        }
        throw AssertionFailed("expected ${expectedType.simpleName} but nothing was thrown", callerFile(), callerLine())
    }

    private fun callerFile(): String? {
        val st = Thread.currentThread().stackTrace.firstOrNull {
            it.className != "com.vidx.testlib.Asserts" && !it.className.contains("TestLib")
        }
        return st?.fileName
    }

    private fun callerLine(): Int? =
        Thread.currentThread().stackTrace.firstOrNull {
            it.className != "com.vidx.testlib.Asserts" && !it.className.contains("TestLib")
        }?.lineNumber
}

object Tests {
    data class Case(val suite: String, val name: String, val fn: () -> Unit)

    private val cases = LinkedHashMap<String, MutableList<Case>>()
    private val suites = LinkedHashMap<String, () -> Unit>()

    /** Canonical suite list — loaded explicitly by BOTH runners (TestMain + JUnitBridge). */
    val suiteClassNames: List<String> = listOf(
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

    /** Forces class loading so every suite registers its cases (Gradle/JUnit only
     *  loads classes that carry @Test methods, so this must be explicit). */
    fun loadAllSuites() {
        for (name in suiteClassNames) Class.forName(name)
    }

    fun suite(name: String, fn: () -> Unit) {
        suites[name] = fn
    }

    fun test(suite: String, name: String, fn: () -> Unit) {
        cases.getOrPut(suite) { mutableListOf() }.add(Case(suite, name, fn))
    }

    fun runAll(): List<CaseResult> {
        val results = mutableListOf<CaseResult>()
        // run suite initializers first (in insertion order)
        for ((_, fn) in suites) fn()
        for ((_, list) in cases) {
            for (case in list) {
                val result = try {
                    case.fn()
                    CaseResult(case, passed = true, error = null)
                } catch (t: Throwable) {
                    CaseResult(case, passed = false, error = t)
                }
                results.add(result)
            }
        }
        return results
    }
}

data class CaseResult(val case: Tests.Case, val passed: Boolean, val error: Throwable?)

object Runner {
    /** Exit code 0 when everything passes, 1 otherwise. Prints a full report. */
    fun main(): Int {
        val results = Tests.runAll()
        var failed = 0
        for (r in results) {
            if (r.passed) {
                println("PASS  ${r.case.suite} :: ${r.case.name}")
            } else {
                failed++
                val loc = if (r.error is AssertionFailed) {
                    val a = r.error
                    val f = a.file ?: "?"
                    val l = a.line ?: "?"
                    " ($f:$l)"
                } else ""
                println("FAIL  ${r.case.suite} :: ${r.case.name}$loc")
                println("      ${r.error?.javaClass?.simpleName}: ${r.error?.message}")
                r.error?.stackTrace?.take(6)?.forEach { println("        at $it") }
            }
        }
        println()
        println("${results.size} tests — ${results.size - failed} passed, $failed failed")
        return if (failed == 0) 0 else 1
    }
}
