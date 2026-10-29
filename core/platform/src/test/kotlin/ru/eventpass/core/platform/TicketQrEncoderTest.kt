package ru.eventpass.core.platform

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class TicketQrEncoderTest {
    @Test fun renderedPixelsDecodeToTheExactIssuedPayload() {
        for (payload in listOf("eventpass-demo-ticket:0b1eacb7-9f24-4ddc-a4fd-67ad6a39c664", "opaque:李-2026-QR")) {
            val image = TicketQrEncoder.encode(payload)
            val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.size, image.size, image.pixels))))
            assertEquals(payload, decoded.text)
        }
    }
}
