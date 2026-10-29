package com.devicescope.app.pcmovil.link

/**
 * JSON mínimo, puro Kotlin, sin dependencias de Android.
 *
 * El enlace PC-Móvil usa mensajes JSON de una sola línea por TCP. Se implementa
 * aquí para que el códec sea unit-testeable en la JVM y no dependa de `org.json`
 * (cuyos stubs de Android lanzan excepciones en pruebas locales). Solo soporta
 * objetos, arrays, cadenas, números, booleanos y `null`, que es todo lo que el
 * protocolo necesita.
 */
object MiniJson {

    fun encode(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        require(parser.atEnd()) { "Caracteres sobrantes en el JSON" }
        return value
    }

    private fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> sb.append(value.toString())
            is Float -> writeNumber(sb, value.toDouble())
            is Double -> writeNumber(sb, value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((key, item) in value) {
                    if (key == null) continue
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, key.toString())
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, item)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.toList())
            is ByteArray -> writeString(sb, PcBytes.toBase64(value))
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeNumber(sb: StringBuilder, value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            sb.append("null")
            return
        }
        val asLong = value.toLong()
        if (asLong.toDouble() == value && kotlin.math.abs(value) < 1e15) {
            sb.append(asLong)
        } else {
            sb.append(value)
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (char in value) {
            when (char) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (char < ' ' || char.code > 0x7E) {
                    sb.append("\\u")
                    val hex = char.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(char)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "JSON vacío" }
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(char == '-' || char.isDigit()) { "Valor JSON inesperado en $index" }
                    readNumber()
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "Clave JSON inválida en $index" }
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "Falta ':' en $index" }
                index++
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Objeto JSON inválido en $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("Array JSON inválido en $index")
                }
            }
        }

        private fun readString(): String {
            require(peek() == '"') { "Cadena JSON inválida en $index" }
            index++
            val sb = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                when {
                    char == '"' -> return sb.toString()
                    char == '\\' -> {
                        require(index < text.length) { "Escape incompleto" }
                        when (val escape = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "Escape unicode incompleto" }
                                sb.append(text.substring(index, index + 4).toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Escape inválido: \\$escape")
                        }
                    }
                    else -> sb.append(char)
                }
            }
            throw IllegalArgumentException("Cadena sin terminar")
        }

        private fun readNumber(): Double {
            val start = index
            if (peek() == '-') index++
            while (index < text.length && (text[index].isDigit() || text[index] in ".eE+-")) index++
            return text.substring(start, index).toDoubleOrNull()
                ?: throw IllegalArgumentException("Número inválido: ${text.substring(start, index)}")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Literal inválido en $index" }
            index += literal.length
            return value
        }

        private fun peek(): Char {
            require(index < text.length) { "JSON truncado" }
            return text[index]
        }
    }
}
