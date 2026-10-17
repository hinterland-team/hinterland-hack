package com.hinterland.clinic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.URL

// several team-inbox servers (docs/journeys.md 6.1): teamInbox.urls in local.properties, first = preferred.
// nova checks GET /api/health on start and every 60 s and uses the first that answers. both servers
// have the same api and the same robot token.

// one server as the staff menu shows it. up = null: not checked yet
data class ServerState(
    val url: String,
    val up: Boolean? = null,
    val ms: Long? = null,
    val checkedAt: Long = 0,
    val note: String? = null,
) {
    val host: String get() = ServerList.host(url)
}

// what one health check found (Backend.health)
data class HealthResult(val up: Boolean, val ms: Long, val note: String? = null)

object ServerList {

    // "https://a.app, http://10.0.0.2:3000/" -> both, cleaned; falls back to the single url
    fun parse(urls: String?, single: String?): List<String> {
        val all = (urls.orEmpty().split(',', ';', ' ', '\n') + single.orEmpty())
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        return all.distinct()
    }

    // the first server that answered, in the preferred order. nobody answered -> keep the current one
    fun pick(states: List<ServerState>, current: String): String =
        states.firstOrNull { it.up == true }?.url ?: current.ifBlank { states.firstOrNull()?.url.orEmpty() }

    fun host(url: String): String = runCatching {
        val u = URL(url)
        if (u.port > 0) "${u.host}:${u.port}" else u.host
    }.getOrDefault(url)

    // the health answer: 2xx with ok true = up. a 404 = an older server without /api/health, still ours.
    fun judge(code: Int, body: String?): HealthResult? = when {
        code in 200..299 -> {
            val ok = body?.contains("\"ok\"") != true || body.replace(" ", "").contains("\"ok\":true")
            HealthResult(ok, 0, if (ok) null else "health says not ok")
        }
        code == 404 -> HealthResult(true, 0, "no /api/health (older server)")
        code == 401 || code == 403 -> HealthResult(true, 0, "answers, but asks for a login")
        else -> HealthResult(false, 0, "server answered $code")
    }
}

object Servers {

    private const val EVERY_MS = 60_000L
    // a failed call asks for a new check, but not more often than this
    private const val EARLY_MIN_MS = 10_000L

    val list: List<String> = ServerList.parse(BuildConfig.BACKEND_URLS, BuildConfig.BACKEND_URL)

    @Volatile var current: String = list.firstOrNull().orEmpty()
        private set

    private val _states = MutableStateFlow(list.map { ServerState(it) })
    val states: StateFlow<List<ServerState>> = _states.asStateFlow()

    @Volatile private var checkedAt = 0L
    @Volatile private var recheckWanted = false

    val configured: Boolean get() = current.isNotEmpty()

    // a call to the current server failed with a network error
    fun reportFailure() {
        recheckWanted = true
    }

    fun recheckNow() {
        recheckWanted = true
        checkedAt = 0L
    }

    // runs with the brain
    suspend fun loop() {
        while (true) {
            checkAll()
            val until = System.currentTimeMillis() + EVERY_MS
            while (System.currentTimeMillis() < until) {
                delay(1_000)
                if (recheckWanted && System.currentTimeMillis() - checkedAt >= EARLY_MIN_MS) break
            }
        }
    }

    suspend fun checkAll() {
        if (list.isEmpty()) return
        recheckWanted = false
        checkedAt = System.currentTimeMillis()
        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                list.map { url -> async { url to runCatching { Backend.health(url) }.getOrElse { e -> HealthResult(false, 0, Backend.friendlyFor(url, e)) } } }
                    .awaitAll()
            }
        }
        val now = System.currentTimeMillis()
        val old = _states.value.associateBy { it.url }
        val states = results.map { (url, r) -> ServerState(url, r.up, r.ms.takeIf { r.up }, now, r.note) }
        states.forEach { s ->
            val before = old[s.url]?.up
            if (before != s.up) {
                if (s.up == true) RobotLog.i(LogSource.NET, "Server ${s.host} answers (${s.ms} ms)${s.note?.let { ", $it" }.orEmpty()}")
                else RobotLog.w(LogSource.NET, "Server ${s.host} not reachable: ${s.note ?: "no answer"}")
            }
        }
        _states.value = states
        val next = ServerList.pick(states, current)
        if (next != current) {
            RobotLog.w(LogSource.NET, "Switched server from ${ServerList.host(current)} to ${ServerList.host(next)}")
            current = next
        } else if (states.none { it.up == true }) {
            RobotLog.w(LogSource.NET, "No server answers, staying with ${ServerList.host(current)}")
        }
    }
}
