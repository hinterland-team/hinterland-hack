package com.novaremote.agent

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException

// the live channel. screen frames go out as binary jpeg; taps, swipes, keys and drive commands
// come in as small json text messages. a socket only opens for a client that already paired
// (holds a valid token) and sits on the robot's wifi - same gates as the http side.
class WsServer(private val service: RemoteService, port: Int) : NanoWSD(port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val token = handshake.parms["t"]
        val clientIp = handshake.remoteIpAddress
        val ok = Net.sameSubnet(service.robotIp, clientIp) && service.pairing.valid(token)
        return RemoteSocket(handshake, service, allowed = ok)
    }
}

class RemoteSocket(
    handshake: NanoHTTPD.IHTTPSession,
    private val service: RemoteService,
    private val allowed: Boolean,
) : NanoWSD.WebSocket(handshake) {

    override fun onOpen() {
        if (!allowed) {
            runCatching { close(NanoWSD.WebSocketFrame.CloseCode.PolicyViolation, "not paired", false) }
            return
        }
        service.addSocket(this)
    }

    override fun onClose(
        code: NanoWSD.WebSocketFrame.CloseCode?,
        reason: String?,
        initiatedByRemote: Boolean,
    ) {
        service.removeSocket(this)
    }

    override fun onMessage(message: NanoWSD.WebSocketFrame) {
        if (!allowed) return
        runCatching { handle(JSONObject(message.textPayload)) }
            .onFailure { Log.w(TAG, "bad ws message", it) }
    }

    override fun onPong(pong: NanoWSD.WebSocketFrame) {}
    override fun onException(exception: IOException) {}

    fun sendFrame(frame: ByteArray) {
        runCatching { send(frame) }.onFailure { service.removeSocket(this) }
    }

    // route one control message. coordinates arrive normalised (0..1) and map onto the real screen.
    private fun handle(m: JSONObject) {
        val a11y = RemoteAccessibilityService.instance
        when (m.optString("t")) {
            "tap" -> a11y?.tap(px(m.optDouble("x")), py(m.optDouble("y")))
            "swipe" -> a11y?.swipe(
                px(m.optDouble("x1")), py(m.optDouble("y1")),
                px(m.optDouble("x2")), py(m.optDouble("y2")),
                m.optLong("ms", 200),
            )
            "key" -> when (m.optString("k")) {
                "back" -> a11y?.back()
                "home" -> a11y?.home()
                "recents" -> a11y?.recents()
            }
            "type" -> a11y?.typeText(m.optString("text"))
            "drive" -> Driving.drive(m.optString("dir"))
            "stop" -> Driving.stop()
        }
    }

    private fun px(norm: Double) = (norm.coerceIn(0.0, 1.0) * service.screenW).toFloat()
    private fun py(norm: Double) = (norm.coerceIn(0.0, 1.0) * service.screenH).toFloat()

    companion object {
        private const val TAG = "NovaRemoteSocket"
    }
}
