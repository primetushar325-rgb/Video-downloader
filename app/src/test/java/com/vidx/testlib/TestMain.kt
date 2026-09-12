package com.vidx.testlib

/** Entry point for the dependency-free local test run (tools/run_tests.sh). */
object TestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        // Force class-loading so every test file registers its cases.
        Tests.loadAllSuites()
        kotlin.system.exitProcess(Runner.main())
    }
}
