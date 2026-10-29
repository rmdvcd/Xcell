package com.devicescope.app.pcmovil

import com.devicescope.app.pcmovil.link.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun `codifica objetos planos en orden`() {
        val encoded = MiniJson.encode(
            linkedMapOf(
                "v" to 1,
                "type" to "ping",
                "activo" to true,
                "ratio" to 1.5,
            ),
        )
        assertEquals("""{"v":1,"type":"ping","activo":true,"ratio":1.5}""", encoded)
    }

    @Test
    fun `escapa cadenas y acentos`() {
        val encoded = MiniJson.encode(mapOf("texto" to "hola \"mundo\"\nñandú"))
        assertEquals("""{"texto":"hola \"mundo\"\n\u00f1and\u00fa"}""", encoded)
        val decoded = MiniJson.parse(encoded) as Map<*, *>
        assertEquals("hola \"mundo\"\nñandú", decoded["texto"])
    }

    @Test
    fun `redondea enteros que llegan como double`() {
        assertEquals("""{"n":42}""", MiniJson.encode(mapOf("n" to 42.0)))
        assertEquals("""{"n":42.5}""", MiniJson.encode(mapOf("n" to 42.5)))
    }

    @Test
    fun `descarta NaN e infinitos`() {
        assertEquals("""{"n":null}""", MiniJson.encode(mapOf("n" to Double.NaN)))
        assertEquals("""{"n":null}""", MiniJson.encode(mapOf("n" to Double.NEGATIVE_INFINITY)))
    }

    @Test
    fun `convierte bytes a base64`() {
        val encoded = MiniJson.encode(mapOf("datos" to byteArrayOf(1, 2, 3)))
        assertEquals("""{"datos":"AQID"}""", encoded)
    }

    @Test
    fun `parsea anidados listas y null`() {
        val parsed = MiniJson.parse(
            """{"v":1,"data":{"lista":[1,"dos",true,null],"vacio":{}},"ch":"input"}""",
        ) as Map<*, *>
        assertEquals(1.0, parsed["v"])
        val data = parsed["data"] as Map<*, *>
        val list = data["lista"] as List<*>
        assertEquals(1.0, list[0])
        assertEquals("dos", list[1])
        assertEquals(true, list[2])
        assertNull(list[3])
        assertTrue((data["vacio"] as Map<*, *>).isEmpty())
    }

    @Test
    fun `rechaza json invalido`() {
        assertNull(runCatching { MiniJson.parse("{sin cerrar") }.getOrNull())
        assertNull(runCatching { MiniJson.parse("") }.getOrNull())
        assertNull(runCatching { MiniJson.parse("{}basura") }.getOrNull())
        // Un objeto vacío sí es válido.
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
    }

    @Test
    fun `sobrevive a un ida y vuelta complejo`() {
        val original = linkedMapOf<String, Any?>(
            "v" to 1,
            "type" to "file_list",
            "data" to linkedMapOf(
                "path" to "/home/usuario/Ñandú",
                "entries" to listOf(
                    linkedMapOf("name" to "a.txt", "dir" to false, "size" to 1024),
                ),
            ),
        )
        val encoded = MiniJson.encode(original)
        val decoded = MiniJson.parse(encoded) as Map<*, *>
        assertEquals(original["v"], (decoded["v"] as Number).toInt())
        val data = decoded["data"] as Map<*, *>
        assertEquals("/home/usuario/Ñandú", data["path"])
        val entries = data["entries"] as List<*>
        assertEquals("a.txt", (entries[0] as Map<*, *>)["name"])
    }
}
