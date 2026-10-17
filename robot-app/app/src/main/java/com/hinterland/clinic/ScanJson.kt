package com.hinterland.clinic

// the radar frame without org.json (v3.3): the robot sends STATUS_LINE_DATA as a list of Pose objects
// ({"px":..,"py":..,"theta":..,...}, ~370 per frame, several frames a second). reading only px / py (or x / y)
// straight from the text is several times faster than building a JSONObject per point, and makes far less
// garbage for the collector. any other shape, or text it does not understand, goes to Json.scan as before.
object ScanJson {

    fun parse(data: String?): List<Point>? {
        val t = data ?: return null
        val start = skipSpace(t, 0)
        if (start >= t.length || t[start] != '[') return Json.scan(data)
        val first = skipSpace(t, start + 1)
        if (first < t.length && t[first] == ']') return emptyList()
        // only a list of objects is read here; [[x, y], ...] and the rest go the slow way
        if (first >= t.length || t[first] != '{') return Json.scan(data)
        val out = ArrayList<Point>(t.length / 60 + 4)
        var objects = 0
        var i = first
        var depth = 0
        var x = Double.NaN
        var y = Double.NaN
        var px = Double.NaN
        var py = Double.NaN
        while (i < t.length) {
            when (t[i]) {
                '"' -> {
                    val end = stringEnd(t, i) ?: return Json.scan(data)
                    if (depth == 1) {
                        // a key in a point object: px / py / x / y followed by a number
                        val keyLen = end - i - 1
                        val colon = skipSpace(t, end + 1)
                        if (colon < t.length && t[colon] == ':') {
                            val v = skipSpace(t, colon + 1)
                            val which = if (keyLen in 1..2) t.substring(i + 1, end) else null
                            if (which == "px" || which == "py" || which == "x" || which == "y") {
                                val numEnd = numberEnd(t, v)
                                val n = if (numEnd > v) t.substring(v, numEnd).toDoubleOrNull() else null
                                when (which) {
                                    "px" -> px = n ?: Double.NaN
                                    "py" -> py = n ?: Double.NaN
                                    "x" -> x = n ?: Double.NaN
                                    "y" -> y = n ?: Double.NaN
                                }
                                i = numEnd
                                continue
                            }
                        }
                    }
                    i = end + 1
                    continue
                }
                '{' -> {
                    depth++
                    if (depth == 1) {
                        x = Double.NaN
                        y = Double.NaN
                        px = Double.NaN
                        py = Double.NaN
                    }
                }
                '}' -> {
                    if (depth == 1) {
                        objects++
                        val fx = if (!px.isNaN()) px else x
                        val fy = if (!py.isNaN()) py else y
                        if (!fx.isNaN() && !fy.isNaN() && fx.isFinite() && fy.isFinite()) out += Point(fx, fy)
                    }
                    depth--
                    if (depth < 0) return Json.scan(data)
                }
                '[' -> if (depth >= 1) depth++
                // the end of the list. a point it could not read (numbers as text, ...): the slow way decides
                ']' -> if (depth >= 1) depth-- else return if (out.size == objects) out else Json.scan(data)
            }
            i++
        }
        // no closing bracket: not something to trust
        return Json.scan(data)
    }

    private fun skipSpace(t: String, from: Int): Int {
        var i = from
        while (i < t.length && t[i].isWhitespace()) i++
        return i
    }

    // index of the closing quote of the string that starts at from, null when it never ends
    private fun stringEnd(t: String, from: Int): Int? {
        var i = from + 1
        while (i < t.length) {
            when (t[i]) {
                '\\' -> i += 2
                '"' -> return i
                else -> i++
            }
        }
        return null
    }

    private fun numberEnd(t: String, from: Int): Int {
        var i = from
        while (i < t.length) {
            val c = t[i]
            if (c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++ else break
        }
        return i
    }
}
