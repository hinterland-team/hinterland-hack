package com.hinterland.clinic

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.ceil
import kotlin.math.max

// the robot's floor plan (docs/robot-setup.md 1). ShareMemoryApi.getMapPgmPFD(mapName) gives map.pgm in the
// layout OrionStar's own sample reads as a RoverMap (RobotSample MapppUtils.loadPFD2RoverMap; RoverMap and
// MapppUtils are not in the sdk jars, only in the sample): a binary PGM header ("P5", "<w> <h>", "255", maybe
// "#" comment lines), w*h bytes, one per cell, the top row first, then 16 more bytes:
//   0..7   resolution in m per cell (double, little endian)
//   8..11  origin x in map meters (float, little endian)
//   12..15 origin y in map meters (float, little endian)
// cells: 0xff free, 0x96 not explored, 0x00 no-go line, 0x05 obstacle.
// map meters -> pixels as in MapppUtils.pose2PixelByRoverMap: u = (x - originX) / res, v = height - (y - originY) / res
// (the y axis is flipped, as in ROS maps: the origin is the lower left corner).
// plain kotlin without android, unit tested.

enum class MapCell(val gray: Int) {
    FREE(255), UNKNOWN(205), NO_GO(128), OBSTACLE(0);

    companion object {
        // the four values OrionStar writes; anything else by brightness, as ROS map_server does
        fun of(v: Int): MapCell = when (v) {
            0xff -> FREE
            0x96 -> UNKNOWN
            0x00 -> NO_GO
            0x05 -> OBSTACLE
            else -> when {
                v >= 0xc8 -> FREE
                v <= 0x32 -> OBSTACLE
                else -> UNKNOWN
            }
        }

        // when cells are put together for a smaller image, the thin things must not vanish: walls first
        fun strongest(a: MapCell, b: MapCell): MapCell = if (rank(b) > rank(a)) b else a

        private fun rank(c: MapCell) = when (c) {
            OBSTACLE -> 3
            NO_GO -> 2
            FREE -> 1
            UNKNOWN -> 0
        }
    }
}

class PgmMap(
    val width: Int,
    val height: Int,
    val resolution: Double,
    val originX: Double,
    val originY: Double,
    // w*h raw values, the top row first
    val cells: ByteArray,
    // the 16 bytes after the cells, as they came (for the log)
    val extra: ByteArray,
    // the header lines as they came, comments included (for the log)
    val header: List<String>,
) {
    fun value(col: Int, row: Int): Int = cells[row * width + col].toInt() and 0xff

    // how often each raw value occurs, most frequent first (for the log on first use)
    fun histogram(top: Int = 6): List<Pair<Int, Int>> {
        val n = IntArray(256)
        for (b in cells) n[b.toInt() and 0xff]++
        return (0..255).filter { n[it] > 0 }.map { it to n[it] }.sortedByDescending { it.second }.take(top)
    }

    // the same map content gives the same hash, so it is only sent when it changed
    fun hash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("$width x $height".toByteArray())
        md.update(cells)
        md.update(extra)
        return md.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
    }
}

class MapFormatException(message: String) : Exception(message)

object Pgm {
    // a real floor map is far below this (2000 x 2000 cells at 5 cm = 100 m x 100 m)
    const val MAX_CELLS = 40_000_000
    const val EXTRA_BYTES = 16

    // reads exactly the header, the cells and the 16 bytes behind them (a shared-memory file may be longer)
    fun read(input: InputStream): PgmMap {
        val data = DataInputStream(input)
        val head = Header(data)
        val magic = head.token()
        if (magic != "P5") throw MapFormatException("not a binary pgm (magic \"${magic.take(8)}\")")
        // OrionStar writes "P5\n<w> <h>\n255\n"; any whitespace and "#" comments between the values are fine too
        val width = head.token().toIntOrNull() ?: throw MapFormatException("no width")
        val height = head.token().toIntOrNull() ?: throw MapFormatException("no height")
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_CELLS) throw MapFormatException("odd size $width x $height")
        // the one whitespace after the max value ends the header
        val maxVal = head.token().toIntOrNull() ?: throw MapFormatException("no max value")
        if (maxVal !in 1..255) throw MapFormatException("max value $maxVal (only 8 bit maps)")
        val header = head.lines()
        val cells = ByteArray(width * height)
        try {
            data.readFully(cells)
        } catch (e: EOFException) {
            throw MapFormatException("the map ends before its $width x $height cells")
        }
        val extra = ByteArray(EXTRA_BYTES)
        val got = readUpTo(data, extra)
        if (got < EXTRA_BYTES) throw MapFormatException("no resolution and origin after the cells ($got of $EXTRA_BYTES bytes)")
        val res = le64(extra, 0)
        val ox = Float.fromBits(le32(extra, 8)).toDouble()
        val oy = Float.fromBits(le32(extra, 12)).toDouble()
        if (!res.isFinite() || res <= 0.0 || res > 10.0) throw MapFormatException("odd resolution $res")
        if (!ox.isFinite() || !oy.isFinite()) throw MapFormatException("odd origin $ox, $oy")
        return PgmMap(width, height, res, ox, oy, cells, extra, header)
    }

    fun read(bytes: ByteArray): PgmMap = read(bytes.inputStream())

    // the pgm header as whitespace separated values; "#" starts a comment up to the end of the line.
    // the raw text is kept for the log
    private class Header(private val data: InputStream) {
        private val raw = StringBuilder()

        fun token(): String {
            val tok = StringBuilder()
            var comment = false
            while (true) {
                val b = data.read()
                if (b < 0) throw MapFormatException("the map ends in its header")
                if (raw.length > MAX_HEADER) throw MapFormatException("no pgm header")
                val c = b.toChar()
                raw.append(c)
                when {
                    comment -> if (c == '\n' || c == '\r') comment = false
                    c == '#' && tok.isEmpty() -> comment = true
                    c.isWhitespace() -> if (tok.isNotEmpty()) return tok.toString()
                    else -> {
                        tok.append(c)
                        if (tok.length > 16) throw MapFormatException("no pgm header")
                    }
                }
            }
        }

        fun lines(): List<String> = raw.toString().split('\n').map { it.trim('\r', ' ') }.filter { it.isNotEmpty() }
    }

    private const val MAX_HEADER = 4096

    private fun readUpTo(data: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = data.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    fun le64(b: ByteArray, at: Int): Double {
        var v = 0L
        for (i in 0 until 8) v = v or ((b[at + i].toLong() and 0xff) shl (8 * i))
        return Double.fromBits(v)
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
}

// the image that goes to the dashboard: gray values (MapCell.gray), at most maxSide pixels on the long side.
// resolution is per pixel of this image; the origin stays the lower left corner
class MapRaster(
    val width: Int,
    val height: Int,
    val resolution: Double,
    val originX: Double,
    val originY: Double,
    // width*height gray values, the top row first
    val gray: ByteArray,
    // how many map cells one pixel is wide (1 = full size)
    val factor: Int,
)

object MapScale {
    const val MAX_SIDE = 2048

    fun factorFor(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int =
        max(1, ceil(max(width, height).toDouble() / maxSide).toInt())

    // k x k cells become one pixel, the strongest cell wins (walls and no-go lines stay visible). the blocks
    // start at the lower left corner, where the origin is, so u = (x - originX) / (res * k) still holds; a part
    // block at the top or the right is filled up with "not explored"
    fun raster(map: PgmMap, maxSide: Int = MAX_SIDE): MapRaster {
        val k = factorFor(map.width, map.height, maxSide)
        val w = ceil(map.width.toDouble() / k).toInt()
        val h = ceil(map.height.toDouble() / k).toInt()
        val out = ByteArray(w * h)
        for (r in 0 until h) {
            // block index from the bottom
            val j = h - 1 - r
            val rowBottom = map.height - 1 - j * k
            val rowTop = maxOf(0, rowBottom - k + 1)
            for (c in 0 until w) {
                val col0 = c * k
                val col1 = minOf(map.width - 1, col0 + k - 1)
                var best = MapCell.UNKNOWN
                var row = rowTop
                while (row <= rowBottom) {
                    var col = col0
                    while (col <= col1) {
                        best = MapCell.strongest(best, MapCell.of(map.value(col, row)))
                        col++
                    }
                    row++
                }
                out[r * w + c] = best.gray.toByte()
            }
        }
        return MapRaster(w, h, map.resolution * k, map.originX, map.originY, out, k)
    }
}

// map meters <-> image pixels (pixel centres at .5). the dashboard draws with the same formula
object MapPixel {
    fun u(x: Double, originX: Double, resolution: Double): Double = (x - originX) / resolution

    fun v(y: Double, originY: Double, resolution: Double, height: Int): Double = height - (y - originY) / resolution

    fun x(u: Double, originX: Double, resolution: Double): Double = originX + u * resolution

    fun y(v: Double, originY: Double, resolution: Double, height: Int): Double = originY + (height - v) * resolution

    fun of(p: Point, r: MapRaster): Point = Point(u(p.x, r.originX, r.resolution), v(p.y, r.originY, r.resolution, r.height))

    // the raw cell under a map point, null outside the map
    fun cell(map: PgmMap, x: Double, y: Double): Pair<Int, Int>? {
        val col = Math.floor(u(x, map.originX, map.resolution)).toInt()
        val row = Math.floor(v(y, map.originY, map.resolution, map.height)).toInt()
        if (col !in 0 until map.width || row !in 0 until map.height) return null
        return col to row
    }
}

// an 8-bit grayscale png (colour type 0) without android: signature, IHDR, IDAT (zlib), IEND
object Png {
    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

    fun gray(width: Int, height: Int, pixels: ByteArray, level: Int = 6): ByteArray {
        require(width > 0 && height > 0 && pixels.size == width * height) { "pixels do not fit $width x $height" }
        val out = ByteArrayOutputStream(pixels.size / 8 + 1024)
        out.write(SIGNATURE)
        val ihdr = ByteArrayOutputStream(13).apply {
            int32(width)
            int32(height)
            write(8) // bit depth
            write(0) // colour type: gray
            write(0) // deflate
            write(0) // no filter method change
            write(0) // not interlaced
        }
        chunk(out, "IHDR", ihdr.toByteArray())
        // each row starts with its filter: "up" (the difference to the row above) makes a map compress well
        val raw = ByteArrayOutputStream(pixels.size / 4 + 64)
        val deflater = Deflater(level)
        DeflaterOutputStream(raw, deflater, 64 * 1024).use { z ->
            val row = ByteArray(width + 1)
            for (r in 0 until height) {
                val at = r * width
                if (r == 0) {
                    row[0] = 0
                    System.arraycopy(pixels, at, row, 1, width)
                } else {
                    row[0] = 2
                    for (c in 0 until width) row[c + 1] = (pixels[at + c] - pixels[at - width + c]).toByte()
                }
                z.write(row)
            }
        }
        deflater.end()
        chunk(out, "IDAT", raw.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.int32(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        out.int32(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.int32(v: Int) {
        write((v ushr 24) and 0xff)
        write((v ushr 16) and 0xff)
        write((v ushr 8) and 0xff)
        write(v and 0xff)
    }
}

// what goes to POST /api/robot/map/image (the png itself as base64 next to it)
data class MapImageMeta(
    val mapName: String,
    val width: Int,
    val height: Int,
    val resolution: Double,
    val originX: Double,
    val originY: Double,
    val hash: String,
)

object MapImages {
    // the raw map -> the png and what the dashboard needs to place map meters on it
    fun build(mapName: String, map: PgmMap, maxSide: Int = MapScale.MAX_SIDE): Pair<MapImageMeta, ByteArray> {
        val r = MapScale.raster(map, maxSide)
        val png = Png.gray(r.width, r.height, r.gray)
        return MapImageMeta(mapName, r.width, r.height, r.resolution, r.originX, r.originY, map.hash()) to png
    }

    // one log line about what the real data looked like (first use)
    fun describe(map: PgmMap): String = buildString {
        append("${map.width} x ${map.height} cells, ")
        append(String.format(Locale.ROOT, "%.3f m per cell, origin %.2f / %.2f m", map.resolution, map.originX, map.originY))
        append(", header ").append(map.header.joinToString(" | ") { it.take(40) })
        append(", extra bytes ").append(Pgm.hex(map.extra))
        append(", values ").append(map.histogram().joinToString { (v, n) -> String.format(Locale.ROOT, "0x%02x x%d", v, n) })
    }
}
