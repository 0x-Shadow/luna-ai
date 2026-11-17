package com.pal9000.robot

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Tiny web panel served by the phone.
 *
 * Open http://<phone-ip>:8899/ on any device on the same Wi-Fi to see and edit
 * Loona's connections, voice settings, permissions and every phone tool.
 *
 *   GET  /                → panel page (assets/panel.html)
 *   GET  /api/state       → live config + permissions + reachability
 *   POST /api/config      → {hermes:{url,key,model}, voice:{lang,onDevice,whisper,whisperOn}}
 *   POST /api/call        → {method, payload, timeout} run any PalBridge tool
 *   POST /api/test        → {target:"hermes"|"whisper"} connectivity check
 */
class PalServer(private val ctx: Context, var bridge: PalBridge) {

    companion object {
        private const val TAG = "PAL9000"
        private const val FIRST_PORT = 8899
        private const val LAST_PORT = 8909
    }

    // Bounded: a burst of tabs must not spawn unbounded threads and starve
    // the WebView thread that produces frames.
    private val pool = Executors.newFixedThreadPool(12)
    private val frameLock = java.util.concurrent.Semaphore(1)
    private var serverSocket: ServerSocket? = null
    private var running = false
    var port = 0
        private set

    // ── access control ──
    private fun prefs() = ctx.getSharedPreferences("pal_panel", Context.MODE_PRIVATE)
    private fun panelToken(): String = prefs().getString("token", "") ?: ""
    private fun setPanelToken(t: String) { prefs().edit().putString("token", t).apply() }
    private fun blockedIps(): MutableSet<String> =
        (prefs().getStringSet("blocked", emptySet()) ?: emptySet()).toMutableSet()
    private fun blockIp(ip: String) { val s = blockedIps(); s.add(ip); prefs().edit().putStringSet("blocked", s).apply() }
    private val connectedClients = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private var hermesCheckedAt = 0L
    private var hermesOk = false
    private var hermesModels: String = "[]"
    // Last state that could be read while the WebView was alive: the panel
    // keeps showing it when Loona is in the background.
    private var cachedState: String? = null

    fun start(): Int {
        if (running) return port
        for (p in FIRST_PORT..LAST_PORT) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p), 64)
                serverSocket = ss
                running = true
                port = p
                thread(name = "pal-panel", isDaemon = true) { acceptLoop(ss) }
                Log.d(TAG, "[Panel] listening on http://${localIp()}:$p")
                return p
            } catch (e: Exception) {
                Log.w(TAG, "[Panel] port $p unavailable: ${e.message}")
            }
        }
        Log.e(TAG, "[Panel] no free port in $FIRST_PORT..$LAST_PORT")
        return 0
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) { /* ignore */ }
        serverSocket = null
    }

    fun url(): String = if (port == 0) "" else "http://${localIp()}:$port/"

    private fun acceptLoop(ss: ServerSocket) {
        try { ss.soTimeout = 1000 } catch (e: Exception) { /* ignore */ }
        while (running) {
            var client: Socket? = null
            try {
                client = ss.accept()
            } catch (e: java.net.SocketTimeoutException) {
                continue                      // just poll the running flag
            } catch (e: Throwable) {
                if (!running) break
                Log.w(TAG, "[Panel] accept failed: ${e.message}")
                Thread.sleep(50)
                continue
            }
            val c = client ?: continue
            try {
                pool.execute { handle(c) }
            } catch (e: Throwable) {
                Log.w(TAG, "[Panel] dispatch failed: ${e.message}")
                try { c.close() } catch (e2: Exception) { /* ignore */ }
            }
        }
        Log.d(TAG, "[Panel] accept loop stopped")
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            try {
                sock.soTimeout = 5000
                val input = BufferedInputStream(sock.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawTarget = parts[1]
                val path = rawTarget.substringBefore('?').ifBlank { "/" }
                val query = rawTarget.substringAfter('?', "")

                val headers = LinkedHashMap<String, String>()
                var contentLength = 0
                while (true) {
                    val header = readLine(input) ?: break
                    if (header.isEmpty()) break
                    val idx = header.indexOf(':')
                    if (idx > 0) {
                        val k = header.substring(0, idx).trim().lowercase()
                        val v = header.substring(idx + 1).trim()
                        headers[k] = v
                        if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength > 0) String(readN(input, contentLength), Charsets.UTF_8) else ""

                val clientIp = sock.inetAddress?.hostAddress ?: "?"

                // ── access control ──
                val token = panelToken()
                if (token.isNotBlank()) {
                    if (clientIp in blockedIps()) {
                        val out = sock.getOutputStream()
                        respond(out, 403, "text/html; charset=utf-8",
                            "<h1>403</h1><p>This device is blocked.</p>")
                        return
                    }
                    val provided = queryParam(query, "t") ?: headers["x-panel-token"]
                    if (provided != token) {
                        val out = sock.getOutputStream()
                        respond(out, 401, "text/html; charset=utf-8", unauthorizedPage())
                        return
                    }
                }
                // track connected clients
                connectedClients[clientIp] = System.currentTimeMillis()

                val out = sock.getOutputStream()
                when {
                    method == "GET" && (path == "/" || path == "/index.html") ->
                        respond(out, 200, "text/html; charset=utf-8", panelHtml())
                    method == "GET" && path == "/api/state" ->
                        respond(out, 200, "application/json", stateJson())
                    method == "POST" && path == "/api/config" ->
                        respond(out, 200, "application/json", applyConfig(body))
                    method == "POST" && path == "/api/call" ->
                        respond(out, 200, "application/json", callTool(body))
                    method == "POST" && path == "/api/test" ->
                        respond(out, 200, "application/json", testTarget(body))

                    method == "GET" && path == "/api/frame.jpg" -> {
                        // Single flight: grabbing a frame costs a WebView draw, so
                        // parallel requests just queue up and starve the socket.
                        if (!frameLock.tryAcquire(60, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            respondRaw(out, 204, "image/jpeg", ByteArray(0))
                        } else {
                            try {
                                val bytes = frame()
                                if (bytes == null || bytes.isEmpty()) respondRaw(out, 204, "image/jpeg", ByteArray(0))
                                else respondRaw(out, 200, "image/jpeg", bytes)
                            } finally {
                                frameLock.release()
                            }
                        }
                    }
                    else -> respond(out, 404, "application/json", "{\"error\":\"not found\"}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Panel] ${e.message}")
            }
        }
    }

    private fun queryParam(q: String, key: String): String? {
        for (part in q.split('&')) {
            val i = part.indexOf('=')
            if (i > 0 && part.substring(0, i) == key) return part.substring(i + 1)
        }
        return null
    }

    private fun unauthorizedPage(): String = """
        <html><body style="background:#0a0c10;color:#e9eef6;font-family:system-ui;padding:40px;text-align:center">
        <h2>Loona panel</h2><p>Enter the access token to continue.</p>
        <form onsubmit="location.href='/?t='+encodeURIComponent(document.getElementById('t').value);return false">
        <input id="t" style="padding:10px;width:260px" placeholder="token" autofocus>
        <button style="padding:10px 16px">Unlock</button></form></body></html>
    """.trimIndent()

    private fun accessJson(): JSONObject {
        val arr = org.json.JSONArray()
        connectedClients.forEach { (ip, ts) ->
            arr.put(org.json.JSONObject().put("ip", ip).put("last", ts))
        }
        return org.json.JSONObject()
            .put("token", panelToken())
            .put("enabled", panelToken().isNotBlank())
            .put("clients", arr)
            .put("blocked", org.json.JSONArray(blockedIps().toList()))
    }

    /* ═══════════ endpoints ═══════════ */

    private fun panelHtml(): String = try {
        ctx.assets.open("panel.html").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        "<h1>PAL9000</h1><p>panel.html missing: ${e.message}</p>"
    }

    private fun stateJson(): String {
        val cached = cachedState?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val liveJson = evalJson(
            "(function(){try{return JSON.stringify({" +
                "hermes:hermesCfg(),voice:voiceCfg(),ui:uiCfg(),state:appState,turn:turnActive,loop:wakeLoopOn,latency:lastHermesLatency," +
                "address:(document.getElementById('cfg-url')||{}).value||''});}catch(e){return JSON.stringify({error:String(e)})}})()"
        )
        val live = liveJson != null && liveJson.length() > 0
        // Read-only cache when Loona is in the background: the panel keeps
        // showing the real settings instead of blanks.
        val page = if (live) liveJson!! else (cached ?: JSONObject())

        val hermes = page.optJSONObject("hermes") ?: JSONObject()
        val voice = page.optJSONObject("voice") ?: JSONObject()

        if (System.currentTimeMillis() - hermesCheckedAt > 15000) {
            var r = checkHttp(hermes.optString("url"), hermes.optString("key"))
            if (!r.first) {
                // One retry: a cold app start sometimes loses the first probe.
                Thread.sleep(350)
                r = checkHttp(hermes.optString("url"), hermes.optString("key"))
            }
            hermesOk = r.first
            hermesModels = r.second
            hermesCheckedAt = System.currentTimeMillis()
        }

        val status = JSONObject(bridge.statusSnapshot())
        val voices = JSONObject(bridge.voicesSnapshot())
        val whisper = voice.optString("whisper")
        val whisperOn = voice.optBoolean("whisperOn", true)

        val json = JSONObject()
            .put("device", JSONObject()
                .put("model", android.os.Build.MODEL)
                .put("manufacturer", android.os.Build.MANUFACTURER)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("app", "PAL9000")
                .put("ip", localIp())
                .put("port", port)
                .put("panel", url()))
            .put("hermes", JSONObject(hermes.toString())
                .put("reachable", hermesOk)
                .put("models", hermesModels))
            .put("voice", JSONObject(voice.toString())
                .put("ttsEngine", voices.optString("engine"))
                .put("ttsLang", voices.optString("current"))
                .put("greekTts", voices.optInt("el", -2) >= 0)
                .put("ttsVoices", voices.optInt("voiceCount", 0))
                .put("whisperReachable", if (whisperOn && whisper.isNotBlank()) canConnect(whisper) else false))
            .put("permissions", status)
            .put("ui", page.optJSONObject("ui") ?: JSONObject())
            .put("runtime", JSONObject()
                .put("state", page.optString("state"))
                .put("turn", page.optBoolean("turn"))
                .put("loop", page.optBoolean("loop")))
            .put("battery", JSONObject(bridge.batterySnapshot()))
            .put("access", accessJson())

        if (!live) {
            json.put("offline", true)
            listOf("hermes", "voice", "ui", "runtime").forEach { k ->
                cached?.opt(k)?.let { json.put(k, it) }
            }
        }
        val out = json.toString()
        if (live) cachedState = out
        return out
    }

    private fun applyConfig(body: String): String {
        val cfg = try { JSONObject(body) } catch (e: Exception) { return "{\"error\":\"bad json\"}" }
        // ── access control (server-side) ──
        val access = cfg.optJSONObject("access")
        if (access != null) {
            if (access.has("token")) setPanelToken(access.optString("token", ""))
            val block = access.optString("block", "")
            if (block.isNotBlank()) blockIp(block)
            val unblock = access.optString("unblock", "")
            if (unblock.isNotBlank()) {
                val s = blockedIps(); s.remove(unblock); prefs().edit().putStringSet("blocked", s).apply()
            }
        }
        val hermes = cfg.optJSONObject("hermes")
        val voice = cfg.optJSONObject("voice")
        val js = StringBuilder("(function(){try{")
        if (hermes != null && hermes.length() > 0) {
            js.append("var h=hermesCfg();Object.assign(h,").append(hermes.toString()).append(");")
            js.append("localStorage.setItem('pal.hermes',JSON.stringify(h));")
        }
        if (voice != null && voice.length() > 0) {
            js.append("var v=voiceCfg();Object.assign(v,").append(voice.toString()).append(");")
            js.append("localStorage.setItem('pal.voice',JSON.stringify(v));")
        }
        val ui = cfg.optJSONObject("ui")
        if (ui != null && ui.length() > 0) {
            js.append("var u=uiCfg();Object.assign(u,").append(ui.toString()).append(");")
            js.append("localStorage.setItem('pal.ui',JSON.stringify(u));applyUi();")
        }
        js.append("applyVoiceConfig();")
        js.append("if(typeof socket!=='undefined'&&socket)socket.disconnect();")
        js.append("return 'saved';}catch(e){return 'error: '+e}})()")
        val result = evalString(js.toString())
        hermesCheckedAt = 0
        return JSONObject().put("result", result ?: "no page").toString()
    }

    private fun callTool(body: String): String {
        val req = try { JSONObject(body) } catch (e: Exception) { return "{\"ok\":false,\"error\":\"bad json\"}" }
        val method = req.optString("method")
        if (method.isBlank()) return "{\"ok\":false,\"error\":\"method required\"}"
        val payload = req.optJSONObject("payload")?.toString() ?: "{}"
        val timeout = req.optLong("timeout", 15000)
        val (ok, result) = bridge.invokeBlocking(method, payload, timeout)
        return JSONObject().put("ok", ok).put("method", method).put("result", result).toString()
    }

    private fun testTarget(body: String): String {
        val target = try { JSONObject(body).optString("target", "hermes") } catch (e: Exception) { "hermes" }
        return when (target) {
            "whisper" -> {
                val page = evalJson("JSON.stringify(voiceCfg())") ?: JSONObject()
                val url = page.optString("whisper")
                val ok = canConnect(url)
                hermesCheckedAt = 0
                JSONObject().put("target", "whisper").put("url", url).put("ok", ok).toString()
            }
            else -> {
                val page = evalJson("JSON.stringify(hermesCfg())") ?: JSONObject()
                val r = checkHttp(page.optString("url"), page.optString("key"))
                hermesOk = r.first
                hermesModels = r.second
                hermesCheckedAt = System.currentTimeMillis()
                JSONObject()
                    .put("target", "hermes")
                    .put("url", page.optString("url"))
                    .put("ok", r.first)
                    .put("models", r.second)
                    .toString()
            }
        }
    }

    /* ═══════════ helpers ═══════════ */

    /** Run JS in the WebView and decode its (JSON-encoded) string result. */
    private fun evalString(js: String): String? {
        val quoted = bridge.evalInPage(js) ?: return null
        if (quoted == "null" || quoted.isBlank()) return null
        return try {
            JSONObject("{\"v\":$quoted}").optString("v", quoted)
        } catch (e: Exception) {
            quoted.trim('"')
        }
    }

    private fun evalJson(js: String): JSONObject? {
        val raw = evalString(js) ?: return null
        return try { JSONObject(raw) } catch (e: Exception) { null }
    }

    private fun checkHttp(urlRaw: String, key: String): Pair<Boolean, String> {
        if (urlRaw.isBlank()) return false to "[]"
        return try {
            val url = URL(urlRaw.trimEnd('/') + "/v1/models")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream else conn.errorStream ?: return false to "[]"
            val text = stream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return false to "HTTP $code"
            val models = try {
                val arr = JSONObject(text).optJSONArray("data")
                val names = ArrayList<String>()
                if (arr != null) for (i in 0 until arr.length()) {
                    names.add(arr.optJSONObject(i)?.optString("id") ?: "")
                }
                names.toString()
            } catch (e: Exception) { "[]" }
            true to models
        } catch (e: Exception) {
            false to (e.message ?: "unreachable")
        }
    }

    private fun canConnect(urlRaw: String): Boolean {
        if (urlRaw.isBlank()) return false
        return try {
            val uri = java.net.URI(urlRaw)
            val host = uri.host ?: return false
            val p = if (uri.port > 0) uri.port else 80
            Socket().use { it.connect(InetSocketAddress(host, p), 1500) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun localIp(): String {
        return try {
            var fallback = "127.0.0.1"
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val a = addr.hostAddress ?: continue
                        if (a.startsWith("192.168.") || a.startsWith("10.")) {
                            return a
                        }
                        if (fallback == "127.0.0.1") fallback = a
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            "127.0.0.1"
        }
    }

    /** One WebView frame as JPEG, for the live preview in the panel. */
    private fun frame(): ByteArray? {
        // A frame is shared for 350 ms: several open tabs cost one WebView
        // draw instead of one each.
        val now = System.currentTimeMillis()
        cachedFrame?.let { if (now - cachedFrameAt < 350) return it }
        val latch = java.util.concurrent.CountDownLatch(1)
        var bytes: ByteArray? = null
        bridge.captureScreen { b -> bytes = b; latch.countDown() }
        if (!latch.await(2500, java.util.concurrent.TimeUnit.MILLISECONDS)) return cachedFrame
        val b = bytes
        if (b != null && b.isNotEmpty()) {
            cachedFrame = b
            cachedFrameAt = now
            return b
        }
        // Backgrounded app can't be drawn — keep showing the last frame.
        return cachedFrame
    }

    private var cachedFrame: ByteArray? = null
    private var cachedFrameAt = 0L

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        respondRaw(out, code, type, body.toByteArray(Charsets.UTF_8))
    }

    private fun respondRaw(out: OutputStream, code: Int, type: String, bytes: ByteArray) {
        val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return if (off == n) b else b.copyOf(off)
    }
}
