package ru.eventpass.core.platform

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader

/** Y plane only. Accounts for plane padding/pixel stride and CameraX crop. No ticket semantics. */
object QrFrameDecoder {
    fun decode(bytes: ByteArray, width: Int, height: Int, rowStride: Int = width, pixelStride: Int = 1,
        left: Int = 0, top: Int = 0, cropWidth: Int = width, cropHeight: Int = height): List<String> {
        require(width > 0 && height > 0 && cropWidth > 0 && cropHeight > 0)
        require(left >= 0 && top >= 0 && left + cropWidth <= width && top + cropHeight <= height)
        require(rowStride > 0 && pixelStride > 0 && cropWidth.toLong() * cropHeight <= 16_000_000)
        require((top + cropHeight - 1L) * rowStride + (left + cropWidth - 1L) * pixelStride < bytes.size)
        val packed = ByteArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) for (x in 0 until cropWidth)
            packed[y * cropWidth + x] = bytes[(top + y) * rowStride + (left + x) * pixelStride]
        val source = PlanarYUVLuminanceSource(packed, cropWidth, cropHeight, 0, 0, cropWidth, cropHeight, false)
        return try {
            QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true)).map { it.text }.filter(String::isNotBlank).distinct().sorted()
        } catch (_: ReaderException) { emptyList() }
    }
}
