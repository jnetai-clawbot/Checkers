package com.jnetai.checkers.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.Hashtable

/**
 * QR code generation/reading helpers used for online P2P pairing.
 */
object QRCodeUtils {

    private const val DEFAULT_SIZE = 512

    /**
     * Render [content] into a QR bitmap that can be scanned by the opponent.
     * Returns null (with a logged diagnostic) on failure.
     */
    fun generateQrBitmap(content: String, sizePx: Int = DEFAULT_SIZE): Bitmap? {
        return try {
            val hints = Hashtable<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.MARGIN] = 1

            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = matrix.width
            val height = matrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix.get(x, y)) {
                        Color.BLACK
                    } else {
                        Color.WHITE
                    }
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565)
        } catch (e: Exception) {
            ErrorLogger.logf(ErrorLogger.Codes.QR_GENERATE_FAILED,
                "Failed to generate QR for content of length %d", e, content.length)
            null
        }
    }
}