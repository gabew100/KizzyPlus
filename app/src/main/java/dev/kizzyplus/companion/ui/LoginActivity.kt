package dev.kizzyplus.companion.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import dev.kizzyplus.companion.R
import org.json.JSONException
import org.json.JSONTokener

/**
 * Discord's web login in a WebView. Once signed in, the web app keeps the token in
 * localStorage under "token" but deletes window.localStorage, so the token is read through a
 * fresh same-origin iframe, the technique Kizzy uses in DiscordLoginWebView.kt. Kizzy reads it
 * once after a redirect; this polls instead, so it works however the page navigates.
 */
class LoginActivity : Activity() {

    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private var done = false

    private val poll = object : Runnable {
        override fun run() {
            if (done) return
            web.evaluateJavascript(PROBE) { onProbe(it) }
            handler.postDelayed(this, POLL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Start from a clean web session so a previous account can't be picked up by mistake.
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val note = TextView(this).apply {
            text = getString(R.string.login_note)
            textSize = 14f
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            loadUrl("https://discord.com/login")
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(note, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        handler.postDelayed(poll, POLL_MS)
    }

    private fun onProbe(result: String?) {
        if (done || result == null || result == "null") return
        // evaluateJavascript hands back a JSON-encoded string; the stored token is JSON-encoded too.
        val raw = try {
            JSONTokener(result).nextValue() as? String
        } catch (e: JSONException) {
            null
        } ?: return
        val token = raw.trim().removeSurrounding("\"")
        if (token.length < 50) return
        done = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TOKEN, token))
        finish()
    }

    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        // The token is all Kizzy+ keeps; the web session is thrown away.
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TOKEN = "token"
        private const val POLL_MS = 1_500L
        private const val PROBE = "(function(){try{" +
            "var f=document.getElementById('kzp-probe');" +
            "if(!f){f=document.createElement('iframe');f.id='kzp-probe';f.style.display='none';document.body.appendChild(f);}" +
            "return f.contentWindow.localStorage.getItem('token')||'';" +
            "}catch(e){return '';}})()"
    }
}
