package com.hinterland.clinic

import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class BackendException(message: String, val code: Int = 0) : Exception(message)

// team-inbox api from docs/journeys.md. all calls block - run them off the main thread.
// never sends a patient name: journeys only know a ticket. the server is the one Servers picked.
object Backend {

    private val base get() = Servers.current
    private val token get() = BuildConfig.BACKEND_TOKEN
    val robot: String get() = BuildConfig.ROBOT_NAME

    val configured get() = Servers.configured

    fun places(): List<Place> = Json.places(call("GET", "/api/places").body)

    // robot may only create journeys to public places and self check-in rooms (403 otherwise)
    fun createJourney(to: String, forPlaceId: String? = null, pace: Pace? = null, wheelchair: Boolean? = null): JourneyCreated {
        val body = JSONObject().put("to", to).put("robot", robot)
        forPlaceId?.let { body.put("forPlaceId", it) }
        pace?.let { body.put("pace", it.wire) }
        wheelchair?.let { body.put("wheelchair", it) }
        return Json.journeyCreated(call("POST", "/api/journeys", body).body)
    }

    fun journey(id: String): Journey = Json.journey(call("GET", "/api/journeys/${enc(id)}").body)

    // today's open journey with that ticket, null on 404
    fun journeyByTicket(ticket: String): Journey? = try {
        Json.journey(call("GET", "/api/journeys/by-ticket/${enc(ticket)}").body)
    } catch (e: BackendException) {
        if (e.code == 404) null else throw e
    }

    // leg status and/or one event (detail = short english, never patient info)
    fun patchJourney(id: String, legId: String?, status: String?, event: String?, detail: String? = null) {
        call("PATCH", "/api/journeys/${enc(id)}", Json.journeyPatch(legId, status, event, detail))
    }

    // null when the backend answers 204 (nothing to do)
    fun nextCommand(): RobotCommand? {
        val reply = call("GET", "/api/robot/next?robot=${enc(robot)}")
        return if (reply.code == 204) null else Json.command(reply.body)
    }

    fun commandDone(id: String) {
        call("POST", "/api/robot/commands/${enc(id)}/done", JSONObject())
    }

    // returns the help request id (null if the backend did not send one). summary has no health details.
    fun help(summary: String, urgency: String, journeyId: String? = null, ticket: String? = null): String? {
        val body = JSONObject()
            .put("robot", robot)
            .put("summary", summary)
            .put("urgency", urgency)
        journeyId?.let { body.put("journeyId", it) }
        ticket?.let { body.put("ticket", it) }
        return Json.helpId(call("POST", "/api/help", body).body)
    }

    // ids of help requests nobody took yet
    fun openHelpIds(): Set<String> = Json.openHelpIds(call("GET", "/api/help?open=1").body)

    // battery, state, position, goal and the screen mirror for the board (204)
    fun heartbeat(body: JSONObject) {
        call("POST", "/api/robot/heartbeat", body)
    }

    // live conversation feed; the answer says if patient words may be sent
    fun feed(body: JSONObject): Boolean? = Json.showPatientText(call("POST", "/api/robot/feed", body).body)

    // a batch of robot logs (docs/journeys.md 6.2)
    fun logs(body: JSONObject) {
        call("POST", "/api/robot/logs", body)
    }

    // pose, places, scan, follower and target for the live map (docs/journeys.md 6.5)
    fun map(body: JSONObject) {
        call("POST", "/api/robot/map", body)
    }

    // GET <url>/api/health without the token, short timeouts (docs/journeys.md 6.1)
    fun health(url: String): HealthResult {
        val started = System.nanoTime()
        val conn = URL(url.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(2_000) }
            val ms = (System.nanoTime() - started) / 1_000_000
            val r = ServerList.judge(code, text) ?: HealthResult(false, ms)
            if (!r.up || token.isBlank()) return r.copy(ms = ms)
            // it answers, but does it take our robot key? 17.10: a server with another key looked
            // "up" and every real call got 401, so nova never tried the next one
            return when (val key = runCatching { keyCheck(url) }.getOrNull()) {
                401, 403 -> HealthResult(false, ms, "robot key not accepted ($key)")
                else -> r.copy(ms = ms)
            }
        } finally {
            conn.disconnect()
        }
    }

    // GET /api/places with the bearer: the robot may read it, so 401/403 means the key is wrong
    private fun keyCheck(url: String): Int {
        val conn = URL(url.trimEnd('/') + "/api/places").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 3_000
            conn.readTimeout = 3_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            return conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    // short text for the screen, never contains the token
    fun friendly(e: Throwable): String = friendlyFor(base, e)

    fun friendlyFor(url: String, e: Throwable): String {
        val host = ServerList.host(url)
        return when (e) {
            is BackendException -> e.message ?: "error"
            is SocketTimeoutException -> "no answer from $host (timeout)"
            is ConnectException -> "can't reach $host"
            is UnknownHostException -> "unknown host $host"
            else -> e.message?.take(120) ?: e.javaClass.simpleName
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private class Reply(val code: Int, val body: String)

    private fun call(method: String, path: String, body: JSONObject? = null): Reply {
        if (base.isEmpty()) throw BackendException("teamInbox.url is missing in local.properties")
        try {
            return send(method, path, body)
        } catch (e: IOException) {
            // the network is gone or this server is down: look for another one soon
            Servers.reportFailure()
            throw e
        }
    }

    private fun send(method: String, path: String, body: JSONObject?): Reply {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
            // android's HttpURLConnection (okhttp inside) accepts PATCH, the plain jvm one would not
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val why = Json.errorMessage(text)?.let { ": ${it.take(120)}" }.orEmpty()
                throw BackendException("server answered $code$why", code)
            }
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
