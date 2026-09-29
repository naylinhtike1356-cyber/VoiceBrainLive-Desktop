package com.example.voicebrainlive.desktop.platform.phonemic

import com.example.voicebrainlive.desktop.platform.DesktopLogger
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.extensions.HashAlgorithm
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Phone-as-mic server: embeds an HTTPS + WebSocket server on the LAN so a
 * phone browser can stream its microphone as a wireless mic for the desktop
 * voice assistant. No app install needed on the phone.
 *
 * Security model (LAN treated as untrusted):
 * - TLS with a per-launch self-signed cert (secure context required for
 *   getUserMedia on the phone).
 * - Pairing via QR code (URL + one-time token) or manual 6-digit PIN.
 * - After PIN verification, a random 256-bit bearer token is issued;
 *   only its SHA-256 hash is stored server-side.
 * - Per-IP brute-force lockout: 5 failed PIN attempts -> 30s lockout.
 * - Single active streamer enforced.
 *
 * Wire format (phone -> desktop): binary WS frames, each 644 bytes:
 *   [0..3]   sequence number (uint32 LE)
 *   [4..643] 320 samples of 16 kHz mono PCM16 LE (20 ms)
 */
class PhoneMicServer(
    private val onAudioChunk: (seq: Long, pcm16: ByteArray) -> Unit,
    private val onStreamerConnected: (clientIp: String) -> Unit,
    private val onStreamerDisconnected: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null

    /** Current 6-digit pairing PIN (regenerated per server start). */
    @Volatile var pairingPin: String = generatePin()
        private set

    /** One-time tokens from QR codes: token -> expiry millis. */
    private val oneTimeTokens = ConcurrentHashMap<String, Long>()

    /** Active bearer token hashes: sha256(token) -> expiry millis. */
    private val bearerTokens = ConcurrentHashMap<String, Long>()

    /** Failed PIN attempts per IP for brute-force lockout. */
    private val pinFailures = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** Currently streaming session (single streamer enforced). */
    @Volatile private var activeSession: DefaultWebSocketServerSession? = null

    val port: Int = 8443

    /** LAN IPv4 address chosen for the QR code / pairing UI. */
    @Volatile var lanIp: String = discoverLanIp()
        private set

    fun isRunning(): Boolean = engine != null

    fun activeStreamerIp(): String? = activeSession?.call?.request?.local?.remoteHost

    /**
     * Starts the HTTPS+WSS server. Safe to call repeatedly (no-op if running).
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun start() {
        if (engine != null) return
        pairingPin = generatePin()
        lanIp = discoverLanIp()
        val keyStore = buildKeyStore {
            certificate("nilar-ai") {
                hash = HashAlgorithm.SHA256
                // SANs cover all detected LAN IPs so the phone browser accepts the host.
                domains = discoverAllLanIps()
                password = "nilar-ai-phonemic"
            }
        }
        val environment = io.ktor.server.engine.applicationEngineEnvironment {
            module {
                install(WebSockets) {
                    pingPeriod = Duration.ofSeconds(5)
                    timeout = Duration.ofSeconds(15)
                    maxFrameSize = Long.MAX_VALUE
                }
                routing {
                    get("/") { context.respondText(PHONE_MIC_HTML, io.ktor.http.ContentType.Text.Html) }
                    post("/pair") { context.handlePair() }
                    webSocket("/mic") { handleMicSocket() }
                }
            }
            // TLS-only: no plain-HTTP connector. The phone needs HTTPS for
            // getUserMedia (secure-context rule). Bound to all interfaces so
            // the phone can reach it over LAN; PIN + bearer tokens are the
            // real security boundary.
            sslConnector(
                keyStore = keyStore,
                keyAlias = "nilar-ai",
                keyStorePassword = { "nilar-ai-phonemic".toCharArray() },
                privateKeyPassword = { "nilar-ai-phonemic".toCharArray() },
            ) {
                port = this@PhoneMicServer.port
                host = "0.0.0.0"
            }
        }
        engine = embeddedServer(CIO, environment)
        scope.launch {
            try {
                engine?.start(wait = true)
            } catch (e: Exception) {
                DesktopLogger.warn("PhoneMicServer failed: ${e.message}")
            }
        }
        DesktopLogger.info("PhoneMicServer started on https://$lanIp:$port (PIN $pairingPin)")
    }

    fun stop() {
        try {
            engine?.stop(500, 1000)
        } catch (_: Exception) { }
        engine = null
        activeSession = null
        scope.cancel()
        DesktopLogger.info("PhoneMicServer stopped")
    }

    /**
     * Generates a QR-code payload URL for the phone to scan:
     * https://<lan-ip>:<port>/?token=<one-time-token>
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun generatePairingQr(sizePx: Int = 280): Pair<String, Array<IntArray>> {
        val token = randomTokenBase64()
        oneTimeTokens[token] = System.currentTimeMillis() + ONE_TIME_TOKEN_TTL_MS
        val url = "https://$lanIp:$port/?token=$token"
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val pixels = Array(sizePx) { y -> IntArray(sizePx) { x -> if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() } }
        return url to pixels
    }

    /** Regenerates the pairing PIN (invalidates the old one). */
    fun regeneratePin() {
        pairingPin = generatePin()
    }

    // ---- HTTP handlers ----

    private suspend fun io.ktor.server.application.ApplicationCall.handlePair() {
        val body = runCatching { receive<String>() }.getOrNull() ?: ""
        val pin = runCatching { JSONObject(body).optString("pin", "") }.getOrNull() ?: ""
        val ip = request.local.remoteHost
        if (isPinLockedOut(ip)) {
            respondJson(JSONObject().put("ok", false).put("error", "locked_out"))
            return
        }
        if (!constantTimeEquals(pin, pairingPin)) {
            recordPinFailure(ip)
            respondJson(JSONObject().put("ok", false).put("error", "bad_pin"))
            return
        }
        clearPinFailures(ip)
        val token = issueBearerToken()
        respondJson(JSONObject().put("ok", true).put("token", token))
    }

    private suspend fun io.ktor.server.application.ApplicationCall.respondJson(obj: JSONObject) {
        respondText(obj.toString(), io.ktor.http.ContentType.Application.Json)
    }

    private suspend fun DefaultWebSocketServerSession.handleMicSocket() {
        val token = call.request.queryParameters["token"] ?: ""
        val isBearer = validateBearerToken(token)
        // QR one-time tokens are exchanged for a bearer the phone keeps for reconnects.
        val newBearer: String? = if (!isBearer) consumeOneTimeTokenReturningBearer(token) else null
        if (!isBearer && newBearer == null) {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        // Hand the (new) bearer to the phone so reconnects don't need re-pairing.
        val pairedMsg = JSONObject().put("type", "paired")
        if (newBearer != null) pairedMsg.put("token", newBearer)
        try { send(Frame.Text(pairedMsg.toString())) } catch (_: Exception) { }
        // Single active streamer: reject a second phone.
        if (activeSession != null) {
            close(io.ktor.websocket.CloseReason(io.ktor.websocket.CloseReason.Codes.TRY_AGAIN_LATER, "busy"))
            return
        }
        activeSession = this
        val ip = call.request.local.remoteHost
        onStreamerConnected(ip)
        DesktopLogger.info("Phone mic streamer connected from $ip")
        try {
            incoming.consumeEach { frame ->
                if (frame is Frame.Binary) {
                    val data = frame.data
                    if (data.size >= 6) {
                        // Header: seq uint32 LE + 16kHz PCM16 LE payload.
                        val seq = (data[0].toLong() and 0xFF) or
                                ((data[1].toLong() and 0xFF) shl 8) or
                                ((data[2].toLong() and 0xFF) shl 16) or
                                ((data[3].toLong() and 0xFF) shl 32)
                        val pcm = data.copyOfRange(4, data.size)
                        onAudioChunk(seq, pcm)
                    }
                }
            }
        } catch (e: Exception) {
            DesktopLogger.info("Phone mic streamer error: ${e.message}")
        } finally {
            if (activeSession == this) activeSession = null
            onStreamerDisconnected()
            DesktopLogger.info("Phone mic streamer disconnected")
        }
    }

    // ---- Token / PIN helpers ----

    @OptIn(ExperimentalEncodingApi::class)
    private fun issueBearerToken(): String {
        val token = randomTokenBase64()
        val hash = sha256Hex(token)
        bearerTokens[hash] = System.currentTimeMillis() + BEARER_TOKEN_TTL_MS
        // Prune expired tokens opportunistically.
        val now = System.currentTimeMillis()
        bearerTokens.entries.removeIf { it.value < now }
        oneTimeTokens.entries.removeIf { it.value < now }
        return token
    }

    private fun validateBearerToken(token: String): Boolean {
        if (token.isBlank()) return false
        val hash = sha256Hex(token)
        val expiry = bearerTokens[hash] ?: return false
        if (expiry < System.currentTimeMillis()) {
            bearerTokens.remove(hash)
            return false
        }
        // Sliding expiry: keep the token alive while streaming.
        bearerTokens[hash] = System.currentTimeMillis() + BEARER_TOKEN_TTL_MS
        return true
    }

    /**
     * Consumes a one-time QR token; returns a fresh bearer token for the
     * phone to use on reconnects, or null if the token is invalid/expired.
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun consumeOneTimeTokenReturningBearer(token: String): String? {
        if (token.isBlank()) return null
        val expiry = oneTimeTokens.remove(token) ?: return null
        if (expiry < System.currentTimeMillis()) return null
        val bearer = randomTokenBase64()
        bearerTokens[sha256Hex(bearer)] = System.currentTimeMillis() + BEARER_TOKEN_TTL_MS
        return bearer
    }

    private fun isPinLockedOut(ip: String): Boolean {
        val (fails, lastMs) = pinFailures[ip] ?: return false
        if (fails >= MAX_PIN_FAILURES && System.currentTimeMillis() - lastMs < PIN_LOCKOUT_MS) return true
        if (System.currentTimeMillis() - lastMs >= PIN_LOCKOUT_MS) pinFailures.remove(ip)
        return false
    }

    private fun recordPinFailure(ip: String) {
        val (fails, _) = pinFailures[ip] ?: (0 to 0L)
        pinFailures[ip] = (fails + 1) to System.currentTimeMillis()
    }

    private fun clearPinFailures(ip: String) {
        pinFailures.remove(ip)
    }

    companion object {
        private const val ONE_TIME_TOKEN_TTL_MS = 5 * 60 * 1000L
        private const val BEARER_TOKEN_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PIN_FAILURES = 5
        private const val PIN_LOCKOUT_MS = 30 * 1000L

        private val secureRandom = SecureRandom()

        fun generatePin(): String = "%06d".format(secureRandom.nextInt(1_000_000))

        @OptIn(ExperimentalEncodingApi::class)
        fun randomTokenBase64(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return Base64.UrlSafe.encode(bytes).trimEnd('=')
        }

        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        /**
         * Best LAN IPv4 for the phone to reach. Filters loopback, down,
         * virtual, and VPN/TUN interfaces (e.g. 198.18.0.0/15).
         */
        fun discoverLanIp(): String = discoverAllLanIps().firstOrNull() ?: "127.0.0.1"

        fun discoverAllLanIps(): List<String> {
            val out = mutableListOf<String>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return out
                for (ni in ifaces) {
                    if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
                    val name = ni.name.lowercase()
                    if (name.contains("tun") || name.contains("tap") || name.contains("vpn") || name.contains("wg")) continue
                    for (addr in ni.inetAddresses.toList()) {
                        if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                        val ip = addr.hostAddress ?: continue
                        // Skip VPN fake ranges.
                        if (ip.startsWith("198.18.") || ip.startsWith("198.19.")) continue
                        if (addr.isSiteLocalAddress) out.add(ip)
                    }
                }
            } catch (_: Exception) { }
            return out
        }

        /** Phone client HTML, loaded from resources. */
        val PHONE_MIC_HTML: String by lazy {
            PhoneMicServer::class.java.getResourceAsStream("/phone-mic.html")
                ?.bufferedReader()?.readText()
                ?: "<html><body>phone-mic.html missing</body></html>"
        }
    }
}
