package com.hinterland.clinic

import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

class BackendException(message: String) : Exception(message)

// team-inbox api from docs/api.md. all calls block - run them off the main thread.
object Backend {

    private val base get() = BuildConfig.BACKEND_URL.trim().trimEnd('/')
    private val token get() = BuildConfig.BACKEND_TOKEN
    val robot: String get() = BuildConfig.ROBOT_NAME

    val configured get() = base.isNotEmpty()

    fun checkIn(patientName: String, doctor: String, appointmentTime: String?, language: String?): CheckInResult {
        val body = JSONObject()
            .put("patientName", patientName)
            .put("doctor", doctor)
            .put("robot", robot)
        appointmentTime?.let { body.put("appointmentTime", it) }
        language?.let { body.put("language", it) }
        return Json.checkIn(call("POST", "/api/visits", body).body)
    }

    fun visit(id: String): Visit = Json.visit(call("GET", "/api/visits/${enc(id)}").body)

    fun activeVisits(): List<Visit> = Json.visits(call("GET", "/api/visits?active=1").body)

    fun updateVisit(id: String, status: String, detail: String? = null) {
        val body = JSONObject().put("status", status)
        detail?.let { body.put("detail", it) }
        call("PATCH", "/api/visits/${enc(id)}", body)
    }

    // null when the backend answers 204 (nothing to do)
    fun nextCommand(): RobotCommand? {
        val reply = call("GET", "/api/robot/next?robot=${enc(robot)}")
        return if (reply.code == 204) null else Json.command(reply.body)
    }

    fun commandDone(id: String) {
        call("POST", "/api/robot/commands/${enc(id)}/done", JSONObject())
    }

    // returns the help request id (null if the backend did not send one)
    fun help(summary: String, urgency: String): String? {
        val body = JSONObject()
            .put("robot", robot)
            .put("summary", summary)
            .put("urgency", urgency)
        return Json.helpId(call("POST", "/api/help", body).body)
    }

    // ids of help requests nobody took yet
    fun openHelpIds(): Set<String> = Json.openHelpIds(call("GET", "/api/help?open=1").body)

    // battery, state, position and goal for the board (204)
    fun heartbeat(body: JSONObject) {
        call("POST", "/api/robot/heartbeat", body)
    }

    fun doctors(): List<Doctor> = Json.doctors(call("GET", "/api/doctors").body)

    // short text for the screen, never contains the token
    fun friendly(e: Throwable): String = when (e) {
        is BackendException -> e.message ?: "error"
        is SocketTimeoutException -> "no answer from ${host()} (timeout)"
        is ConnectException -> "can't reach ${host()}"
        is UnknownHostException -> "unknown host ${host()}"
        else -> e.message?.take(120) ?: e.javaClass.simpleName
    }

    private fun host() = runCatching { URL(base).let { "${it.host}:${it.port}" } }.getOrDefault(base)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private class Reply(val code: Int, val body: String)

    private fun call(method: String, path: String, body: JSONObject? = null): Reply {
        if (base.isEmpty()) throw BackendException("teamInbox.url is missing in local.properties")

        val conn = URL(base + path).openConnection() as HttpURLConnection
        try {
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
                throw BackendException("server answered $code$why")
            }
            return Reply(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
