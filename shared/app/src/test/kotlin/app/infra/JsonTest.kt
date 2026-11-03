// Json 单测：转义、大整数、坏输入（这三个点是自研 JSON 唯一会出事的地方）
//
// ★ 2026-11-03（T05-C1）：随 Json.kt 一起上移到 :shared:app，5 条断言**一行未改**，
//   只把包名换成与被测代码同包（因此 import 全部去掉）。
package app.infra

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {

    @Test
    fun `中文与特殊字符往返不乱码`() {
        val value = "照片\\路径\"含引号\"\n换行\t制表 D:/照片/IMG_001.jpg"
        val text = Json.write(json { put("p", value) })
        val back = Json.parse(text)?.asObj()?.strOrNull("p")
        assertEquals(value, back)
    }

    @Test
    fun `长整数不丢精度`() {
        // 2^62 级别：若用 Double 解析会被截断，导致照片 id 变化
        val big = 4_611_686_018_427_387_904L
        val text = Json.write(json { put("id", big) })
        assertEquals(big, Json.parse(text)?.asObj()?.longOrNull("id"))
    }

    @Test
    fun `嵌套对象与数组`() {
        val text = Json.write(
            json {
                put("name", "导出")
                putStrings("dirs", listOf("D:/a", "D:/b"))
                put("meta", json { put("ok", true); put("count", 3) })
            },
            pretty = true,
        )
        val obj = assertNotNull(Json.parse(text)?.asObj())
        assertEquals(listOf("D:/a", "D:/b"), obj.stringList("dirs"))
        assertEquals(true, obj.fields["meta"]?.asObj()?.boolOrNull("ok"))
        assertTrue(text.contains("\n")) // pretty 生效
    }

    @Test
    fun `坏输入返回 null 而不是抛异常`() {
        assertNull(Json.parse("{"))
        assertNull(Json.parse("{\"a\":}"))
        assertNull(Json.parse(""))
        assertNull(Json.parse("{\"a\":1} trailing"))
    }

    @Test
    fun `空对象与空数组`() {
        assertEquals("{}", Json.write(json { }))
        assertEquals("[]", Json.write(JsonValue.Arr(emptyList())))
    }
}
