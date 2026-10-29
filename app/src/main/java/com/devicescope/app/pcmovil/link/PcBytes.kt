package com.devicescope.app.pcmovil.link

import java.util.Base64

/**
 * Utilidades de bytes del enlace. `java.util.Base64` existe en API 26, así que
 * no hace falta `android.util.Base64` y el código sigue siendo unit-testeable.
 */
object PcBytes {

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(text: String): ByteArray = Base64.getDecoder().decode(text)
}

/** Hash estable para nombres de dispositivo (no criptográfico). */
internal fun stableHash(input: String): String {
    var hash = 0x811C9DC5.toInt()
    for (byte in input.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (byte.toInt() and 0xFF)
        hash *= 16777619
    }
    return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}
