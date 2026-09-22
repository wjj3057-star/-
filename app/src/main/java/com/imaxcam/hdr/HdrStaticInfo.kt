package com.imaxcam.hdr

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * CTA-861.3 "Static Metadata Type 1" blob for
 * [android.media.MediaFormat.KEY_HDR_STATIC_INFO].
 *
 * Layout (25 bytes, little-endian, matching the platform's HDRStaticInfo struct):
 *   [0]      descriptor id, always 0
 *   [1..16]  display primaries R, G, B and the white point, x then y, in 0.00002 units
 *   [17..18] max display mastering luminance, cd/m2
 *   [19..20] min display mastering luminance, 0.0001 cd/m2
 *   [21..22] MaxCLL, cd/m2
 *   [23..24] MaxFALL, cd/m2
 */
object HdrStaticInfo {

    private const val PRIMARY_UNITS = 50_000.0 // 1 / 0.00002

    // ITU-R BT.2020 primaries and the D65 white point.
    private val BT2020 = doubleArrayOf(
        0.708, 0.292, // R
        0.170, 0.797, // G
        0.131, 0.046, // B
        0.3127, 0.3290 // W (D65)
    )

    fun build(
        maxDisplayNits: Int = 1000,
        minDisplayMilliNits: Int = 50, // 0.005 cd/m2
        maxCll: Int = 1000,
        maxFall: Int = 400
    ): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(25).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0.toByte()) // Static Metadata Type 1
        BT2020.forEach { buf.putShort(u16(it * PRIMARY_UNITS)) }
        buf.putShort(u16(maxDisplayNits.toDouble()))
        buf.putShort(u16(minDisplayMilliNits.toDouble()))
        buf.putShort(u16(maxCll.toDouble()))
        buf.putShort(u16(maxFall.toDouble()))
        buf.rewind()
        return buf
    }

    private fun u16(value: Double): Short =
        (Math.round(value).coerceIn(0L, 65535L)).toInt().toShort()
}
