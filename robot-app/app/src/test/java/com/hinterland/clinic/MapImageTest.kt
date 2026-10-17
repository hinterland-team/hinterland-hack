package com.hinterland.clinic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// the robot's floor plan (docs/robot-setup.md 1): map.pgm from ShareMemoryApi.getMapPgmPFD in the layout of
// OrionStar's RoverMap (RobotSample MapppUtils.loadPFD2RoverMap) -> a grayscale png and map meters -> pixels.
// the sample map is made up here, 5 x 4 cells
class MapImageTest {

    private val u = 0x96 // not explored
    private val f = 0xff // free
    private val n = 0x00 // no-go line
    private val o = 0x05 // obstacle

    // the top row first, as in the file
    private val rows = listOf(
        intArrayOf(u, u, u, u, u),
        intArrayOf(n, f, f, f, o),
        intArrayOf(n, f, f, f, o),
        intArrayOf(n, n, f, o, o),
    )

    private fun pgm(
        cells: List<IntArray> = rows,
        res: Double = 0.05,
        ox: Float = -1.5f,
        oy: Float = 2.0f,
        header: String = "P5\n# OrionStar map\n${cells[0].size} ${cells.size}\n255\n",
        extraBytes: Int = 16,
        tail: Int = 32,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        cells.forEach { r -> r.forEach { out.write(it) } }
        val extra = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putDouble(res).putFloat(ox).putFloat(oy).array()
        out.write(extra, 0, extraBytes)
        // a shared-memory file can be longer than the map: never read
        repeat(tail) { out.write(0x7f) }
        return out.toByteArray()
    }

    @Test
    fun theOrionStarLayoutIsRead() {
        val m = Pgm.read(pgm())
        assertEquals(5, m.width)
        assertEquals(4, m.height)
        assertEquals(0.05, m.resolution, 1e-12)
        assertEquals(-1.5, m.originX, 1e-6)
        assertEquals(2.0, m.originY, 1e-6)
        assertEquals(o, m.value(4, 1))
        assertEquals(u, m.value(0, 0))
        // the comment line is kept for the log, the values are counted
        assertTrue(m.header.contains("# OrionStar map"))
        assertEquals(listOf(f to 7, u to 5, n to 4, o to 4), m.histogram())
        assertTrue(MapImages.describe(m).contains("5 x 4 cells, 0.050 m per cell, origin -1.50 / 2.00 m"))
        assertEquals("9a 99 99 99 99 99 a9 3f 00 00 c0 bf 00 00 00 40", Pgm.hex(m.extra))
        // the header may also come in other whitespace
        val other = Pgm.read(pgm(header = "P5 5\n4 # a comment\n255\n"))
        assertArrayEquals(m.cells, other.cells)
    }

    @Test
    fun brokenFilesAreRefusedWithAReason() {
        fun refused(bytes: ByteArray, why: String) {
            try {
                Pgm.read(bytes)
                fail("read a broken map")
            } catch (e: MapFormatException) {
                assertTrue("${e.message}", e.message.orEmpty().contains(why))
            }
        }
        refused(pgm(header = "P2\n5 4\n255\n"), "not a binary pgm")
        refused(pgm(header = "P5\n5 4\nxyz\n"), "no max value")
        // the header stops early: the cells are no header values
        refused(pgm(header = "P5\n5\n"), "no pgm header")
        refused(pgm(header = "P5\n0 4\n255\n"), "odd size")
        refused(pgm(header = "P5\n5 4\n65535\n"), "only 8 bit")
        refused(pgm(extraBytes = 6, tail = 0), "no resolution and origin")
        refused(pgm(res = -1.0), "odd resolution")
        refused(pgm(ox = Float.NaN), "odd origin")
        refused("P5\n100 100\n255\n".toByteArray() + ByteArray(50), "ends before")
        refused(ByteArray(0), "ends in its header")
    }

    @Test
    fun cellsBecomeGrayValues() {
        assertEquals(MapCell.FREE, MapCell.of(0xff))
        assertEquals(MapCell.UNKNOWN, MapCell.of(0x96))
        assertEquals(MapCell.NO_GO, MapCell.of(0x00))
        assertEquals(MapCell.OBSTACLE, MapCell.of(0x05))
        // other values by brightness, as ROS does
        assertEquals(MapCell.FREE, MapCell.of(0xfe))
        assertEquals(MapCell.OBSTACLE, MapCell.of(0x10))
        assertEquals(MapCell.UNKNOWN, MapCell.of(0x80))
        val r = MapScale.raster(Pgm.read(pgm()))
        assertEquals(1, r.factor)
        assertEquals(5, r.width)
        assertEquals(4, r.height)
        assertEquals(0.05, r.resolution, 1e-12)
        val gray = r.gray.map { it.toInt() and 0xff }
        assertEquals(listOf(205, 205, 205, 205, 205, 128, 255, 255, 255, 0, 128, 255, 255, 255, 0, 128, 128, 255, 0, 0), gray)
    }

    @Test
    fun thePngDecodesToTheSameGrayValues() {
        val m = Pgm.read(pgm())
        val (meta, png) = MapImages.build("Floor 2", m)
        assertEquals("Floor 2", meta.mapName)
        assertEquals(5, meta.width)
        assertEquals(4, meta.height)
        assertEquals(0.05, meta.resolution, 1e-12)
        assertEquals(-1.5, meta.originX, 1e-6)
        assertEquals(m.hash(), meta.hash)
        // read back by the spec (crcs, zlib, row filters), 8-bit gray
        val img = PngCheck.read(png)
        assertEquals(5, img.width)
        assertEquals(4, img.height)
        val r = MapScale.raster(m)
        for (y in 0 until 4) for (x in 0 until 5) {
            assertEquals("pixel $x,$y", r.gray[y * 5 + x].toInt() and 0xff, img.at(x, y))
        }
        // and by the JDK's own png reader
        val jdk = PngCheck.readWithImageIo(png)!!
        assertEquals(5, jdk.width)
        assertEquals(img.pixels.toList(), jdk.pixels.toList())
    }

    @Test
    fun aRealSizedMapIsSmallAsPng() {
        // 1200 x 900 cells (60 m x 45 m at 5 cm): mostly free and unknown with walls, as a floor plan is
        val w = 1200
        val h = 900
        val cells = List(h) { y -> IntArray(w) { x -> if (x == 100 || y == 80 || x == 1100 || y == 820) o else if (x in 100..1100 && y in 80..820) f else u } }
        val m = Pgm.read(pgm(cells = cells))
        val (meta, png) = MapImages.build("Big", m)
        assertEquals(1200, meta.width)
        // a few kB, not megabytes (the base64 goes in one json body)
        assertTrue("${png.size} bytes", png.size < 60_000)
        val back = PngCheck.read(png)
        assertEquals(0, back.at(100, 300))
        assertEquals(255, back.at(600, 450))
        assertEquals(205, back.at(10, 10))
    }

    @Test
    fun aBigMapIsMadeSmallerAndKeepsItsWalls() {
        // 5 x 4 at most 3 px: 2 x 2 cells per pixel, blocks from the lower left corner (where the origin is)
        val r = MapScale.raster(Pgm.read(pgm()), maxSide = 3)
        assertEquals(2, r.factor)
        assertEquals(3, r.width)
        assertEquals(2, r.height)
        assertEquals(0.1, r.resolution, 1e-12)
        assertEquals(-1.5, r.originX, 1e-6)
        val gray = r.gray.map { it.toInt() and 0xff }
        // top: [no-go+unknown+free] [free] [obstacle]; bottom: [no-go] [obstacle] [obstacle]
        assertEquals(listOf(128, 255, 0, 128, 0, 0), gray)
        // one obstacle cell in a free 8 x 8 map is still there at a quarter of the size
        val wall = List(8) { y -> IntArray(8) { x -> if (x == 5 && y == 2) o else f } }
        val small = MapScale.raster(Pgm.read(pgm(cells = wall)), maxSide = 4)
        assertEquals(4, small.width)
        assertEquals(1, small.gray.count { it.toInt() and 0xff == 0 })
        assertEquals(2, MapScale.factorFor(4000, 1000))
        assertEquals(1, MapScale.factorFor(2048, 2048))
        assertEquals(3, MapScale.factorFor(4100, 10))
        // the png of the small one is still readable
        val png = Png.gray(r.width, r.height, r.gray)
        assertEquals(listOf(128, 255, 0, 128, 0, 0), PngCheck.read(png).pixels.toList())
    }

    @Test
    fun mapMetersLandOnTheRightPixels() {
        val m = Pgm.read(pgm())
        // the origin is the lower left corner: x = originX, y = originY -> u 0, v = height
        assertEquals(0.0, MapPixel.u(-1.5, m.originX, m.resolution), 1e-9)
        assertEquals(4.0, MapPixel.v(2.0, m.originY, m.resolution, m.height), 1e-9)
        // the middle of the cell in column 2, row 1 (from the top)
        val x = -1.5 + 2.5 * 0.05
        val y = 2.0 + (4 - 1.5) * 0.05
        assertEquals(2.5, MapPixel.u(x, m.originX, m.resolution), 1e-9)
        assertEquals(1.5, MapPixel.v(y, m.originY, m.resolution, m.height), 1e-9)
        assertEquals(2 to 1, MapPixel.cell(m, x, y))
        assertEquals(f, m.value(2, 1))
        // y up on the map = up on the image (smaller v)
        assertTrue(MapPixel.v(y + 0.1, m.originY, m.resolution, m.height) < 1.5)
        // and back
        assertEquals(x, MapPixel.x(2.5, m.originX, m.resolution), 1e-9)
        assertEquals(y, MapPixel.y(1.5, m.originY, m.resolution, m.height), 1e-9)
        // outside the map
        assertNull(MapPixel.cell(m, -2.0, 2.0))
        assertNull(MapPixel.cell(m, 0.0, 2.0 + 0.21))
        // on the smaller image the same point is in the block that holds that cell
        val r = MapScale.raster(m, maxSide = 3)
        val p = MapPixel.of(Point(x, y), r)
        assertEquals(1.25, p.x, 1e-9)
        assertEquals(0.75, p.y, 1e-9)
        assertEquals(255, r.gray[p.y.toInt() * r.width + p.x.toInt()].toInt() and 0xff)
    }

    @Test
    fun theHashOnlyChangesWithTheMap() {
        val a = Pgm.read(pgm()).hash()
        assertEquals(a, Pgm.read(pgm(tail = 99)).hash())
        assertEquals(64, a.length)
        val changed = rows.map { it.clone() }.also { it[1][1] = o }
        assertNotEquals(a, Pgm.read(pgm(cells = changed)).hash())
        assertNotEquals(a, Pgm.read(pgm(ox = -1.0f)).hash())
    }
}
