package com.pal9000.robot

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.*;
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var bridge: PalBridge? = null
    private var server: PalServer? = null
    private val PERMISSION_REQUEST_CODE = 1001
    private val NOTIF_REQUEST_CODE = 1002

    // Load from assets (no server needed on phone)
    

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (application as? PalApp)?.bridge = null   // rebuilt after webView exists

        // Keep screen on, fullscreen, immersive - hide nav bar
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        // Hide navigation bar (immersive sticky)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        // Debug builds expose the WebView on chrome://inspect (adb forward),
        // which is how the page is driven and verified from the laptop.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        @SuppressLint("SetJavaScriptEnabled")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.setSupportMultipleWindows(false)
            settings.setAllowFileAccessFromFileURLs(true)
            settings.setAllowUniversalAccessFromFileURLs(true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    // Before any page script runs: the page reads window.isNativeApp
                    // while parsing, so injecting at onPageFinished is too late.
                    view?.evaluateJavascript("window.isNativeApp = true;", null)
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Re-assert flag so the page knows it's running in the native app
                    webView.evaluateJavascript("window.isNativeApp = true;", null)
                }
            }

            webChromeClient = object : WebChromeClient() {
                // Bridge the page's console.* to logcat so JS runtime state is observable
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    Log.d(
                        "PAL9000",
                        "${consoleMessage?.message()} @ ${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()}"
                    )
                    return super.onConsoleMessage(consoleMessage)
                }

                // Handle camera/mic permission requests from the web page
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        request.grant(
                            arrayOf(
                                android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE,
                                android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE
                            )
                        )
                    }
                }
            }
        }

        setContentView(webView)

        // Native bridge: HTTP (Hermes), STT, TTS, phone controls.
        // addJavascriptInterface must happen before the page loads.
        bridge = PalBridge(applicationContext) { js ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) webView.evaluateJavascript(js, null)
            }
        }
        (application as? PalApp)?.bridge = bridge
        webView.addJavascriptInterface(bridge!!, "PalBridge")

        // The page can be read and driven from the LAN panel.
        bridge!!.jsEval = { js, cb ->
            runOnUiThread {
                if (isFinishing || isDestroyed) cb(null)
                else webView.evaluateJavascript(js) { r -> cb(r) }
            }
        }

        // Web panel: http://<phone-ip>:8899/ — config, permissions, tools.
        // Temporarily disabled — causes ANR on Android 14. Re-enable once
        // foreground service restrictions are resolved.
        // PanelService.ensureRunning(this)

        // Keep Loona's wake-word loop alive while the app is backgrounded.
        BrainService.ensureRunning(this)
        val srv = (application as? PalApp)?.server
        if (srv != null) {
            srv.bridge = bridge!!
            bridge!!.panelUrl = srv.url()
        }

        // Start TTS shortly after launch: first-init costs seconds, so the
        // first answer shouldn't pay for it.
        webView.postDelayed({ bridge?.warmUp() }, 2500)

        // Panel-driven display controls (dim, keep awake, kiosk lock).
        bridge!!.displayHook = { p -> applyDisplay(p) }
        bridge!!.screenHook = frame@{ cb ->
            try {
                val w = webView.width
                val h = webView.height
                if (w <= 0 || h <= 0) { cb(null); return@frame }
                // Downscale: a 520 px wide frame is plenty for a preview and
                // costs a fraction of a full-resolution draw + JPEG encode.
                val scale = (520f / w).coerceAtMost(1f)
                val bmp = Bitmap.createBitmap(
                    (w * scale).toInt().coerceAtLeast(1),
                    (h * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.RGB_565)
                val canvas = android.graphics.Canvas(bmp)
                canvas.scale(scale, scale)
                webView.draw(canvas)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 55, out)
                bmp.recycle()
                cb(out.toByteArray())
            } catch (e: Exception) {
                Log.w("PAL9000", "[Panel] screenshot failed: ${e.message}")
                cb(null)
            }
        }

        // Request Android runtime permissions first
        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            loadServer()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                loadServer()
            } else {
                Toast.makeText(this, "Camera and microphone permissions are required for PAL9000", Toast.LENGTH_LONG).show()
                loadServer() // Load anyway; web page will handle missing permissions gracefully
            }
        }
    }

    private fun applyDisplay(p: org.json.JSONObject) {
        if (p.has("dim")) {
            val dim = p.optDouble("dim", -1.0).toFloat()
            runOnUiThread {
                val lp = window.attributes
                lp.screenBrightness = if (dim <= 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else dim.coerceIn(0.01f, 1f)
                window.attributes = lp
            }
        }
        if (p.has("keepAwake")) {
            val on = p.optBoolean("keepAwake", true)
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        if (p.has("locked")) {
            val locked = p.optBoolean("locked", true)
            runOnUiThread { if (locked) goImmersive() else showSystemBars() }
        }
        Log.d("PAL9000", "[Display] $p")
    }

    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    private fun showSystemBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun loadServer() {
        webView.loadUrl("file:///android_asset/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Kiosk: back must not dismiss Loona — the settings sheet and cards
        // carry their own close buttons, so there is nothing to go back to.
        Log.d("PAL9000", "[Kiosk] back ignored")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep the WebView (and the wake-word loop) alive while another app is
        // in the foreground, so "Loona" can still be heard and acted on.
        // webView.onPause()   // disabled: would kill listening in the background
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        bridge?.haltListening()
        // The panel server deliberately keeps running: it belongs to the
        // app process, and reopening Loona re-attaches it to the new WebView.
        webView.destroy()
        super.onDestroy()
    }
}
