package com.vidx.core.url

import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertNull
import com.vidx.testlib.Tests

object UrlValidatorTest {
    init {
        Tests.test("validate", "accepts valid supported urls") {
            assertNull(UrlValidator.validate("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
            assertNull(UrlValidator.validate("https://cdn.example.com/clip.mp4"))
            assertNull(UrlValidator.validate("https://framatube.org/w/abcDEF1234567890xyz"))
        }

        Tests.test("validate", "rejects empty") {
            assertEquals("empty", (UrlValidator.validate("   ") as UrlValidator.ValidationError).code)
        }

        Tests.test("validate", "rejects bad schemes") {
            assertEquals("no_scheme", (UrlValidator.validate("www.youtube.com/watch?v=x") as UrlValidator.ValidationError).code)
            assertEquals("bad_scheme", (UrlValidator.validate("file:///etc/passwd") as UrlValidator.ValidationError).code)
            assertEquals("bad_scheme", (UrlValidator.validate("javascript:alert(1)") as UrlValidator.ValidationError).code)
        }

        Tests.test("validate", "rejects malformed hosts") {
            assertEquals("host_invalid", (UrlValidator.validate("https://bad host.com/x") as UrlValidator.ValidationError).code)
            assertEquals("host_invalid", (UrlValidator.validate("https://.com/x") as UrlValidator.ValidationError).code)
        }

        Tests.test("validate", "rejects unknown platforms") {
            assertEquals("unsupported", (UrlValidator.validate("https://example.com/video") as UrlValidator.ValidationError).code)
        }

        Tests.test("validate", "overlong urls rejected") {
            val long = "https://youtube.com/watch?v=x&p=" + "a".repeat(5000)
            assertEquals("too_long", (UrlValidator.validate(long) as UrlValidator.ValidationError).code)
        }
    }
}
