package com.novaremote.agent

import android.content.Context
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File

// the plain http side: serves the control page and handles pairing, the app list, launch/uninstall
// and apk upload. the live screen + input run over the websocket (WsServer). everything except the
// page itself and /pair needs a valid token, and /pair needs the right wifi + 9-digit code (+ MAC
// when the dev set an allow list).
class WebServer(private val context: Context, port: Int) : NanoHTTPD(port) {

    private val service get() = RemoteService.instance

    override fun serve(session: IHTTPSession): Response {
        return runCatching { route(session) }.getOrElse {
            Log.w(TAG, "serve failed", it)
            json(Response.Status.INTERNAL_ERROR, JSONObject().put("error", "server"))
        }
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri
        val svc = service ?: return json(Response.Status.INTERNAL_ERROR, err("not ready"))

        // body has to be parsed before parms are readable on a POST
        val files = HashMap<String, String>()
        if (session.method == Method.POST || session.method == Method.PUT) {
            runCatching { session.parseBody(files) }
        }

        when (uri) {
            "/", "/index.html" -> return asset("web/index.html", "text/html")
            "/app.js" -> return asset("web/app.js", "application/javascript")
            "/style.css" -> return asset("web/style.css", "text/css")
            "/pair" -> return pair(session, svc)
        }

        // from here on a token is required
        val token = session.parms["t"] ?: session.headers["x-token"]
        if (!svc.pairing.valid(token)) return json(Response.Status.UNAUTHORIZED, err("paired?"))

        return when (uri) {
            "/api/state" -> json(Response.Status.OK, state(svc))
            "/api/apps" -> json(Response.Status.OK, JSONObject().put("apps", AppsInfo.installed(context)))
            "/api/launch" -> {
                val ok = AppsInfo.launch(context, session.parms["pkg"].orEmpty())
                json(Response.Status.OK, JSONObject().put("ok", ok))
            }
            "/api/uninstall" -> {
                AppsInfo.uninstall(context, session.parms["pkg"].orEmpty())
                json(Response.Status.OK, JSONObject().put("ok", true))
            }
            "/api/install" -> install(session, files)
            else -> json(Response.Status.NOT_FOUND, err("no route"))
        }
    }

    private fun pair(session: IHTTPSession, svc: RemoteService): Response {
        val clientIp = session.remoteIpAddress
        if (!Net.sameSubnet(svc.robotIp, clientIp)) {
            return json(Response.Status.FORBIDDEN, err("wrong network"))
        }
        val locked = svc.pairing.lockedUntil(clientIp)
        if (locked > System.currentTimeMillis()) {
            return json(Response.Status.FORBIDDEN, err("locked").put("wait", (locked - System.currentTimeMillis()) / 1000))
        }
        val mac = Net.macForIp(clientIp)
        val res = svc.pairing.pair(clientIp, mac, session.parms["code"].orEmpty())
        return if (res.ok) {
            json(Response.Status.OK, JSONObject().put("ok", true).put("token", res.token).put("wsPort", svc.wsPort))
        } else {
            json(Response.Status.FORBIDDEN, err(res.reason ?: "no"))
        }
    }

    private fun install(session: IHTTPSession, files: Map<String, String>): Response {
        val temp = files["apk"] ?: return json(Response.Status.BAD_REQUEST, err("no file"))
        val name = session.parms["apk"] ?: "upload.apk"
        val apk = Installer.save(context, name, File(temp).inputStream())
        Installer.install(context, apk)
        return json(Response.Status.OK, JSONObject().put("ok", true).put("name", apk.name))
    }

    private fun state(svc: RemoteService): JSONObject = JSONObject()
        .put("robotIp", svc.robotIp)
        .put("screen", JSONObject().put("w", svc.screenW).put("h", svc.screenH))
        .put("wsPort", svc.wsPort)
        .put("a11y", RemoteAccessibilityService.enabled)
        .put("capturing", svc.capturing)
        .put("drive", Driving.connected)
        .put("battery", AppsInfo.battery(context))

    private fun asset(path: String, mime: String): Response {
        val stream = context.assets.open(path)
        return newChunkedResponse(Response.Status.OK, mime, stream)
    }

    private fun json(status: Response.IStatus, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    companion object {
        private const val TAG = "NovaRemoteWeb"
    }
}
