package ru.eventpass.core.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

data class QrPixels(val size: Int, val pixels: IntArray)

/** Call on a worker dispatcher. Includes the standard four-module quiet zone. */
object TicketQrEncoder {
    fun encode(payload: String, size: Int = 768): QrPixels {
        require(payload.isNotEmpty() && size in 128..2048)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size,
            mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
        return QrPixels(matrix.width, IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        })
    }
}
