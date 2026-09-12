package com.vidx.core.json

import com.vidx.testlib.Asserts.assertEquals
import com.vidx.testlib.Asserts.assertFailsWith
import com.vidx.testlib.Asserts.assertTrue
import com.vidx.testlib.Tests

object JsonTest {
    init {
        Tests.test("json", "parses nested objects") {
            val v = Json.parseObject("""{"a":1,"b":{"c":[true,false,null,"x\u0041"],"d":-2.5e2},"e":"\n\t\"esc\""}""")
            assertEquals(1, v.int("a"))
            assertEquals(JsonValue.Bool(true), v.obj("b")?.arr("c")?.get(0))
            assertEquals("xA", (v.obj("b")?.arr("c")?.get(3) as JsonValue.Str).value)
            assertEquals(-250.0, v.obj("b")?.double("d") ?: 0.0, "scientific notation")
            assertEquals("\n\t\"esc\"", v.str("e"))
        }

        Tests.test("json", "parses arrays and scalars") {
            val a = Json.parse("[1,2,3]") as JsonValue.Arr
            assertEquals(3, a.items.size)
            assertEquals("ok", (Json.parse("\"ok\"") as JsonValue.Str).value)
            assertEquals(true, (Json.parse("true") as JsonValue.Bool).value)
            assertEquals(JsonValue.Null, Json.parse("null"))
        }

        Tests.test("json", "rejects malformed input") {
            assertFailsWith(JsonParseException::class.java) { Json.parse("{") }
            assertFailsWith(JsonParseException::class.java) { Json.parse("[1,]") }
            assertFailsWith(JsonParseException::class.java) { Json.parse("""{"a":}""") }
            assertFailsWith(JsonParseException::class.java) { Json.parse("""{"a":"unterminated}""") }
            assertFailsWith(JsonParseException::class.java) { Json.parse("nul") }
        }

        Tests.test("json", "handles whitespace and unicode") {
            val v = Json.parseObject("  { \"k\" : \"\\u20ACuro \\ud83d\\ude00\" } \n")
            assertEquals("€uro 😀", v.str("k"))
        }

        Tests.test("json", "writer escapes strings") {
            val out = JsonWriter.obj("x" to JsonWriter.string("a\"b\\c\nd"))
            val reparsed = Json.parseObject(out)
            assertEquals("a\"b\\c\nd", reparsed.str("x"))
        }

        Tests.test("json", "path helpers navigate") {
            val v = Json.parseObject("""{"a":{"b":{"c":"deep"}}}""")
            assertEquals("deep", v.pathStr("a", "b", "c"))
            assertEquals(null, v.pathStr("a", "x"))
            assertTrue(v.path("a", "b") is JsonValue.Obj)
        }
    }
}
