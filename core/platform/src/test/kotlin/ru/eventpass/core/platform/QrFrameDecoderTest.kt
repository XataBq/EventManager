package ru.eventpass.core.platform

import org.junit.Assert.*
import org.junit.Test

class QrFrameDecoderTest {
    @Test fun decodesCroppedPaddedStridedAndRotatedLuminance() {
        val qr = TicketQrEncoder.encode("controlled-demo-qr", 256)
        val width = 276; val stride = width * 2 + 16
        val bytes = ByteArray(stride * width) { 0xff.toByte() }
        for (y in 0 until qr.size) for (x in 0 until qr.size)
            bytes[(y + 10) * stride + (x + 10) * 2] = qr.pixels[(qr.size - 1 - x) * qr.size + y].toByte()
        assertEquals(listOf("controlled-demo-qr"), QrFrameDecoder.decode(bytes, width, width, stride, 2, 10, 10, 256, 256))
    }
    @Test fun multipleQrHaveDeterministicCandidatesAndBlankFrameHasNone() {
        val a = TicketQrEncoder.encode("second-code", 256); val b = TicketQrEncoder.encode("first-code", 256)
        val bytes = ByteArray(544 * 288) { 0xff.toByte() }
        for (y in 0 until 256) for (x in 0 until 256) {
            bytes[(y + 16) * 544 + x + 8] = a.pixels[y * 256 + x].toByte()
            bytes[(y + 16) * 544 + x + 280] = b.pixels[y * 256 + x].toByte()
        }
        assertEquals(listOf("first-code", "second-code"), QrFrameDecoder.decode(bytes, 544, 288))
        assertTrue(QrFrameDecoder.decode(ByteArray(256 * 256) { 0xff.toByte() }, 256, 256).isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedPlaneBeforeReadingIt() {
        QrFrameDecoder.decode(ByteArray(10), 256, 256)
    }
}
