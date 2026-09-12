package com.vidx.testlib

import org.junit.Test

/**
 * Gradle/JUnit entry point: runs the exact same suite as `tools/run_tests.sh`.
 * Gradle's unit-test task is JUnit-based; this single test funnels the whole
 * suite through it so the two environments can never drift apart.
 */
class JUnitBridge {
    @Test
    fun vidxFullSuite() {
        val results = Tests.runAll()
        val failures = results.filter { !it.passed }
        if (failures.isNotEmpty()) {
            val sb = StringBuilder("${failures.size} test(s) failed:\n")
            for (r in failures) sb.append("  - ").append(r.case.suite).append(" :: ").append(r.case.name)
                .append(" -> ").append(r.error?.message).append('\n')
            throw AssertionError(sb.toString())
        }
    }
}
