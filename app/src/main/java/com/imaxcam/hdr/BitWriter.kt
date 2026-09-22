package com.imaxcam.hdr

import java.io.ByteArrayOutputStream

/** Big-endian MSB-first bit writer, as used by ITU-T T.35 / SEI payloads. */
internal class BitWriter(initialCapacity: Int = 128) {
    private val out = ByteArrayOutputStream(initialCapacity)
    private var current = 0
    private var bitsFilled = 0

    fun writeBits(value: Long, bits: Int) {
        require(bits in 1..63) { "bits out of range: $bits" }
        for (i in bits - 1 downTo 0) {
            val bit = ((value ushr i) and 1L).toInt()
            current = (current shl 1) or bit
            bitsFilled++
            if (bitsFilled == 8) {
                out.write(current and 0xFF)
                current = 0
                bitsFilled = 0
            }
        }
    }

    fun writeBits(value: Int, bits: Int) = writeBits(value.toLong(), bits)

    fun writeFlag(flag: Boolean) = writeBits(if (flag) 1L else 0L, 1)

    /** Pads the final partial byte with zeroes and returns the payload. */
    fun toByteArray(): ByteArray {
        if (bitsFilled > 0) {
            out.write((current shl (8 - bitsFilled)) and 0xFF)
            current = 0
            bitsFilled = 0
        }
        return out.toByteArray()
    }
}
