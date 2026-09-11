package com.xempastissimo.lightnovelreader.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    @Test
    fun `round trips a string list`() {
        val document = Json.obj { put("tags", listOf("a", "b")) }.toJson()
        val parsed = Json.parseObject(document)!!
        assertEquals(listOf("a", "b"), parsed.strings("tags"))
    }

    @Test
    fun `round trips an explicit array`() {
        val document = Json.obj { put("items", Json.array { add("x"); add("y") }) }.toJson()
        val parsed = Json.parseObject(document)!!
        assertEquals(listOf("x", "y"), parsed.strings("items"))
        assertEquals(2, parsed.array("items").size())
    }

    @Test
    fun `parses a raw array document`() {
        val parsed = Json.parseArray("""["1","2","3"]""")!!
        assertEquals(listOf("1", "2", "3"), parsed.strings())
        assertEquals(3, parsed.size())
    }

    @Test
    fun `round trips nested objects`() {
        val document = Json.obj {
            put(
                "progress",
                Json.obj {
                    put("chapterId", 176596)
                    put("percent", 0.5)
                },
            )
        }.toJson()
        val parsed = Json.parseObject(document)!!
        val progress = parsed.entries["progress"] as Json.Obj
        assertEquals(176596, progress.int("chapterId"))
        assertEquals(0.5, progress.double("percent"), 0.0001)
    }

    @Test
    fun `round trips an array of objects`() {
        val document = Json.obj {
            put(
                "blocks",
                Json.array {
                    add(Json.obj { put("t", "第一段") })
                    add(Json.obj { put("i", "http://x/y.jpg") })
                },
            )
        }.toJson()
        val parsed = Json.parseObject(document)!!
        val blocks = parsed.array("blocks").objects()
        assertEquals(2, blocks.size)
        assertEquals("第一段", blocks[0].string("t"))
        assertEquals("http://x/y.jpg", blocks[1].string("i"))
    }

    @Test
    fun `escapes special characters`() {
        val tricky = "引号\" 反斜杠\\ 换行\n 制表\t"
        val document = Json.obj { put("text", tricky) }.toJson()
        assertEquals(tricky, Json.parseObject(document)!!.string("text"))
    }

    @Test
    fun `parses a full document with all value kinds`() {
        val document = """
            {"title":"书名","count":3,"ratio":0.25,"flag":true,"nothing":null,
             "nested":{"inner":"值"},"list":["a","b"],"numbers":[1,2]}
        """.trimIndent()
        val parsed = Json.parseObject(document)!!
        assertEquals("书名", parsed.string("title"))
        assertEquals(3, parsed.int("count"))
        assertEquals(0.25, parsed.double("ratio"), 0.0001)
        assertTrue(parsed.boolean("flag"))
        assertEquals("值", (parsed.entries["nested"] as Json.Obj).string("inner"))
        assertEquals(listOf("a", "b"), parsed.strings("list"))
        assertEquals(2, parsed.array("numbers").size())
    }

    @Test
    fun `malformed input returns null instead of throwing`() {
        assertEquals(null, Json.parseObject("{ not json"))
        assertEquals(null, Json.parseArray("[1,2"))
        assertEquals(null, Json.parseObject(""))
    }
}
