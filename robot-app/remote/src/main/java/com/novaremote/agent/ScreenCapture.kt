package com.novaremote.agent

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

// mirrors the robot screen into jpeg frames. the MediaProjection comes from the system consent
// dialog the dev accepts on the robot ("Start capturing?"), so this cannot start on its own.
// frames are downscaled and throttled - this is a dev view, not a video stream.
class ScreenCapture(
    private val projection: MediaProjection,
    private val realW: Int,
    private val realH: Int,
    private val densityDpi: Int,
    private val onFrame: (ByteArray) -> Unit,
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val thread = HandlerThread("nova-remote-capture").apply { start() }
    private val handler = Handler(thread.looper)

    private val scale: Double = (MAX_SIDE.toDouble() / maxOf(realW, realH)).coerceAtMost(1.0)
    private val capW = (realW * scale).toInt().coerceAtLeast(1)
    private val capH = (realH * scale).toInt().coerceAtLeast(1)

    @Volatile private var lastFrameAt = 0L

    fun start() {
        val r = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, handler)
        display = projection.createVirtualDisplay(
            "nova-remote",
            capW, capH, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    private fun onImage(r: ImageReader) {
        var image: Image? = null
        try {
            image = r.acquireLatestImage() ?: return
            val now = System.currentTimeMillis()
            if (now - lastFrameAt < MIN_INTERVAL_MS) return
            lastFrameAt = now

            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * capW
            val bmpW = capW + (if (pixelStride > 0) rowPadding / pixelStride else 0)

            val bmp = Bitmap.createBitmap(bmpW, capH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            val cropped = if (bmpW != capW) Bitmap.createBitmap(bmp, 0, 0, capW, capH) else bmp

            val out = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
            onFrame(out.toByteArray())
        } catch (e: Throwable) {
            Log.w(TAG, "frame failed", e)
        } finally {
            image?.close()
        }
    }

    fun stop() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection.stop() }
        runCatching { thread.quitSafely() }
    }

    companion object {
        private const val TAG = "NovaRemoteCapture"
        private const val MAX_SIDE = 960       // longest frame side in px
        private const val QUALITY = 45         // jpeg quality
        private const val MIN_INTERVAL_MS = 120 // ~8 fps cap
    }
}
