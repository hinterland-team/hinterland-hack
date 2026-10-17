package com.hinterland.clinic

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

// QR code for the phone companion link (docs/journeys.md 6.4), made on the robot: no network image.
// size x size modules, true = dark. no quiet zone: the screen draws its own white margin.
class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    companion object {
        // null when the text can't be encoded (empty or far too long)
        fun encode(text: String): QrMatrix? {
            if (text.isBlank()) return null
            return runCatching {
                val m = QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(
                        EncodeHintType.MARGIN to 0,
                        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                        EncodeHintType.CHARACTER_SET to "UTF-8",
                    ),
                )
                // zxing gives one pixel per module when no size is asked for
                val n = m.width
                QrMatrix(n, BooleanArray(n * n) { i -> m.get(i % n, i / n) })
            }.getOrNull()
        }
    }
}
