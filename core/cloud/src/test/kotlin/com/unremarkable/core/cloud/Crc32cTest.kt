package com.unremarkable.core.cloud

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Crc32cTest {

    @Test
    fun `computeChecksum matches standard test vector`() {
        // Standard CRC32C test vector for ASCII "123456789" is 0xE3069283
        val testInput = "123456789".toByteArray(Charsets.US_ASCII)
        val checksum = Crc32c.computeChecksum(testInput)
        assertEquals(0xE3069283.toInt(), checksum)
    }

    @Test
    fun `computeBase64 formats properly for GCS header`() {
        val testInput = "123456789".toByteArray(Charsets.US_ASCII)
        val base64 = Crc32c.computeBase64(testInput)
        // 0xE3069283 in big-endian bytes is [0xE3, 0x06, 0x92, 0x83] -> Base64: "4waSgw=="
        assertEquals("4waSgw==", base64)
    }

    @Test
    fun `empty input computes zero checksum`() {
        val checksum = Crc32c.computeChecksum(ByteArray(0))
        assertEquals(0, checksum)
        assertEquals("AAAAAA==", Crc32c.computeBase64(ByteArray(0)))
    }
}
