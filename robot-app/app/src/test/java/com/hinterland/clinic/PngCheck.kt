package com.hinterland.clinic

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream

// test helper: reads an 8-bit grayscale png by the spec (signature, chunk crcs, IHDR, zlib IDAT, all five row
// filters), independent of Png.gray. and the JDK's own ImageIO (via reflection: android unit tests compile
// against android.jar, which has no javax.imageio) as a second, real-world decoder
data class GrayImage(val width: Int, val height: Int, val pixels: IntArray) {
    fun at(x: Int, y: Int) = pixels[y * width + x]
}

object PngCheck {

    fun read(png: ByteArray): GrayImage {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(png.copyOfRange(0, 8).contentEquals(sig)) { "no png signature" }
        var at = 8
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        var ended = false
        while (at < png.size) {
            val len = int(png, at)
            val type = String(png, at + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(at + 8, at + 8 + len)
            val crc = CRC32().apply { update(png, at + 4, 4 + len) }.value.toInt()
            require(crc == int(png, at + 8 + len)) { "bad crc in $type" }
            when (type) {
                "IHDR" -> {
                    width = int(data, 0)
                    height = int(data, 4)
                    require(data[8].toInt() == 8) { "bit depth ${data[8]}" }
                    require(data[9].toInt() == 0) { "colour type ${data[9]}" }
                    require(data[12].toInt() == 0) { "interlaced" }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> ended = true
            }
            at += 12 + len
        }
        require(ended) { "no IEND" }
        val raw = InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).readBytes()
        require(raw.size == height * (width + 1)) { "image data is ${raw.size} bytes, not ${height * (width + 1)}" }
        val out = IntArray(width * height)
        val prev = IntArray(width)
        for (y in 0 until height) {
            val filter = raw[y * (width + 1)].toInt()
            val row = IntArray(width)
            for (x in 0 until width) {
                val v = raw[y * (width + 1) + 1 + x].toInt() and 0xff
                val a = if (x > 0) row[x - 1] else 0
                val b = prev[x]
                val c = if (x > 0) prev[x - 1] else 0
                row[x] = (v + when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> error("filter $filter")
                }) and 0xff
            }
            row.copyInto(out, y * width)
            row.copyInto(prev)
        }
        return GrayImage(width, height, out)
    }

    // the JDK's ImageIO, when it is there (it is on a desktop JVM): width, height and the gray samples
    fun readWithImageIo(png: ByteArray): GrayImage? {
        val io = runCatching { Class.forName("javax.imageio.ImageIO") }.getOrNull() ?: return null
        val img = io.getMethod("read", InputStream::class.java).invoke(null, ByteArrayInputStream(png)) ?: return null
        // the public types: the runtime classes are internal to the jdk
        val buffered = Class.forName("java.awt.image.BufferedImage")
        val w = buffered.getMethod("getWidth").invoke(img) as Int
        val h = buffered.getMethod("getHeight").invoke(img) as Int
        val raster = buffered.getMethod("getRaster").invoke(img)
        val sample = Class.forName("java.awt.image.Raster")
            .getMethod("getSample", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val px = IntArray(w * h) { i -> sample.invoke(raster, i % w, i / w, 0) as Int }
        return GrayImage(w, h, px)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun int(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
}
