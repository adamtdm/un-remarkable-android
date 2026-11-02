package com.unremarkable.core.cloud

import java.nio.ByteBuffer
import java.util.Base64

object Crc32c {
    private const val POLYNOMIAL = 0x82F63B78.toInt()
    private val TABLE = IntArray(256) { i ->
        var curr = i
        for (bit in 0 until 8) {
            curr = if ((curr and 1) != 0) {
                POLYNOMIAL xor (curr ushr 1)
            } else {
                curr ushr 1
            }
        }
        curr
    }

    fun computeChecksum(data: ByteArray): Int {
        var crc = -1 // 0xFFFFFFFF
        for (b in data) {
            val idx = (crc xor (b.toInt() and 0xFF)) and 0xFF
            crc = TABLE[idx] xor (crc ushr 8)
        }
        return crc xor -1
    }

    fun computeBase64(data: ByteArray): String {
        val crc = computeChecksum(data)
        val buf = ByteBuffer.allocate(4).putInt(crc).array()
        return Base64.getEncoder().encodeToString(buf)
    }
}
