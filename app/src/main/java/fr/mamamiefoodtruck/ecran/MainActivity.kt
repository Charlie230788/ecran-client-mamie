package fr.mamamiefoodtruck.ecran

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import org.json.JSONObject

class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pending: String? = null
    private var awaitingActivation = false
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        setContentView(web)
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.cacheMode = WebSettings.LOAD_NO_CACHE
        s.mediaPlaybackRequiresUserGesture = false
        web.addJavascriptInterface(Bridge(), "Android")
        web.setOnLongClickListener { menu(); true }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                if (u.startsWith("intent:")) {
                    try {
                        startActivity(Intent.parseUri(u, Intent.URI_INTENT_SCHEME))
                    } catch (e: Exception) {
                    }
                    return true
                }
                if (u.startsWith("mmfecran:")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView, url: String) {
                val p = pending
                if (p != null && url.startsWith("http")) {
                    pending = null
                    deliver(p)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    view.loadData("<html><body style='font-family:sans-serif;text-align:center;padding-top:30vh'><h2>Connexion en cours...</h2></body></html>", "text/html", "utf-8")
                    handler.postDelayed({ load() }, 5000)
                }
            }

            // Si le processus de rendu de la WebView plante (manque de memoire, etc.),
            // Android ferme toute l'appli par defaut. On recree l'ecran a la place,
            // pour que l'ecran client ne se ferme plus tout seul.
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                try {
                    web.destroy()
                } catch (e: Exception) {
                }
                handler.post { recreate() }
                return true
            }
        }
        handleIntent(intent)
        load()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        val p = pending
        val cur = web.url
        if (p != null && cur != null && cur.startsWith("http")) {
            pending = null
            deliver(p)
        }
    }

    private var overlay: View? = null
    private var asked = false

    override fun onResume() {
        super.onResume()
        if (Settings.canDrawOverlays(this)) {
            ensureOverlay()
        } else {
            askOverlay()
        }
    }

    private fun askOverlay() {
        if (asked) return
        asked = true
        AlertDialog.Builder(this)
            .setTitle("Autorisation")
            .setMessage("Pour pouvoir annuler un paiement depuis la caisse, autorise Ecran client a s'afficher par-dessus les autres applications.")
            .setPositiveButton("Autoriser") { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            }
            .setNegativeButton("Plus tard", null)
            .show()
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val v = View(applicationContext)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp)
            overlay = v
        } catch (e: Exception) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme != "mmfecran") return
        val o = JSONObject()
        for (k in d.queryParameterNames) {
            o.put(k, d.getQueryParameter(k) ?: "")
        }
        i.setData(null)
        if (awaitingActivation) {
            awaitingActivation = false
            showActivationResult(o)
        } else {
            reportResultDirect(o)
            pending = o.toString()
        }
    }

    // Rapporte le resultat du paiement directement au serveur (sans dependre de la WebView / JS),
    // en plus de la livraison normale via mmfPayResult. Filet de securite si la page est bloquee,
    // en cours de rechargement, ou si l'appli a ete relancee a froid au moment du callback Viva.
    private fun reportResultDirect(o: JSONObject) {
        if (o.optString("action", "").equals("abort", ignoreCase = true)) return
        Thread {
            try {
                val cfgUrl = getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("url", null) ?: return@Thread
                val m = Regex("(https://[^/]+)/\\?mmf_ecran=([0-9A-Za-z]+)").find(cfgUrl) ?: return@Thread
                val host = m.groupValues[1]
                val tok = m.groupValues[2]
                val sb = StringBuilder()
                fun add(k: String, v: String) {
                    if (sb.isNotEmpty()) sb.append('&')
                    sb.append(java.net.URLEncoder.encode(k, "UTF-8")).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
                }
                add("action", "mmf_ecl_result")
                add("k", tok)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k == "action" || k == "k") continue
                    add(k, o.optString(k, ""))
                }
                add("id", o.optString("clientTransactionId", ""))
                val body = sb.toString()
                val url = java.net.URL(host + "/wp-admin/admin-ajax.php")
                for (attempt in 0 until 6) {
                    try {
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.doOutput = true
                        conn.connectTimeout = 8000
                        conn.readTimeout = 8000
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        conn.outputStream.use { it.write(body.toByteArray()) }
                        conn.inputStream.use { it.readBytes() }
                        conn.disconnect()
                        break
                    } catch (e: Exception) {
                        if (attempt < 5) Thread.sleep(2500)
                    }
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    private fun deliver(j: String) {
        ticking = false
        web.evaluateJavascript("window.mmfPayResult && window.mmfPayResult(" + JSONObject.quote(j) + ")", null)
    }

    private fun load() {
        val u = getSharedPreferences("cfg", Context.MODE_PRIVATE).getString("url", null)
        if (u == null) {
            ask()
        } else {
            web.loadUrl(u)
        }
    }

    private fun ask() {
        val et = EditText(this)
        et.hint = "Lien ou code de l'ecran client"
        et.inputType = InputType.TYPE_TEXT_VARIATION_URI
        AlertDialog.Builder(this)
            .setTitle("Ecran client")
            .setMessage("Colle ici le lien de l'ecran client.")
            .setView(et)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val v = et.text.toString().trim()
                var host = "https://mamamiefoodtruck.com"
                var tok: String? = null
                val m = Regex("(https://[^/ ]+)/[?]mmf_ecran=([0-9A-Za-z]+)").find(v)
                if (m != null) {
                    host = m.groupValues[1]
                    tok = m.groupValues[2]
                } else if (Regex("[0-9A-Za-z]{16,}").matches(v)) {
                    tok = v
                }
                if (tok == null) {
                    ask()
                } else {
                    val url = host + "/?mmf_ecran=" + tok + "&app=1"
                    getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString("url", url).apply()
                    web.loadUrl(url)
                }
            }
            .show()
    }

    private fun menu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Recharger", "Changer le lien", "Activer la caisse (Viva)", "Reinitialiser Viva (POS)", "Quitter")) { _, w ->
                when (w) {
                    0 -> web.reload()
                    1 -> ask()
                    2 -> askActivation()
                    3 -> resetPos()
                    else -> finishAffinity()
                }
            }
            .show()
    }

    private var ticking = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            web.evaluateJavascript("window.mmfPollNow && window.mmfPollNow()", null)
            handler.postDelayed(this, 1500)
        }
    }

    private fun startTicker() {
        if (!ticking) {
            ticking = true
            handler.postDelayed(ticker, 1500)
        }
    }

    private fun abortPay() {
        val uri = "vivapayclient://pay/v1?appId=fr.mamamiefoodtruck.caisse&action=abort&callback=" + Uri.encode("mmfecran://result")
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            startActivity(i)
        } catch (e: ActivityNotFoundException) {
        }
    }

    private fun launchPay(cents: Int, id: String) {
        startTicker()
        val uri = "vivapayclient://pay/v1?appId=fr.mamamiefoodtruck.caisse&action=sale&amount=" + cents +
            "&clientTransactionId=" + Uri.encode(id) +
            "&customerTrns=" + Uri.encode("Ma Mamie") +
            "&protocol=int_default&paymentMethod=CardPresent&show_transaction_result=false&callback=" + Uri.encode("mmfecran://result")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            val o = JSONObject()
            o.put("status", "fail")
            o.put("message", "Viva Terminal introuvable")
            o.put("clientTransactionId", id)
            deliver(o.toString())
        }
    }

    private fun askActivation() {
        val etKey = EditText(this)
        etKey.hint = "Api Key (portail Viva)"
        AlertDialog.Builder(this)
            .setTitle("Activer la caisse")
            .setMessage("Colle ta cle API Viva (apikey), celle du compte MARCHAND, pas celle du partenaire.")
            .setView(etKey)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Suivant") { _, _ ->
                val apikey = etKey.text.toString().trim()
                if (apikey.isNotEmpty()) askActivationSecret(apikey)
            }
            .show()
    }

    private fun askActivationSecret(apikey: String) {
        val etSecret = EditText(this)
        etSecret.hint = "Api Secret (portail Viva)"
        AlertDialog.Builder(this)
            .setTitle("Activer la caisse")
            .setMessage("Colle ton secret API Viva (apiSecret).")
            .setView(etSecret)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Activer") { _, _ ->
                val secret = etSecret.text.toString().trim()
                if (secret.isNotEmpty()) activatePos(apikey, secret)
            }
            .show()
    }

    private fun activatePos(apikey: String, apiSecret: String) {
        awaitingActivation = true
        val uri = "vivapayclient://pay/v1?appId=fr.mamamiefoodtruck.caisse&action=activatePos" +
            "&apikey=" + Uri.encode(apikey) +
            "&apiSecret=" + Uri.encode(apiSecret) +
            "&callback=" + Uri.encode("mmfecran://result")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            awaitingActivation = false
            AlertDialog.Builder(this)
                .setTitle("Erreur")
                .setMessage("Viva Terminal introuvable sur cet appareil.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

        private fun resetPos() {
        awaitingActivation = true
        val uri = "vivapayclient://pay/v1?appId=fr.mamamiefoodtruck.caisse&action=reset&softReset=false" +
            "&callback=" + Uri.encode("mmfecran://result")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            awaitingActivation = false
            AlertDialog.Builder(this)
                .setTitle("Erreur")
                .setMessage("Viva Terminal introuvable sur cet appareil.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

private fun showActivationResult(o: JSONObject) {
        val status = if (o.has("status")) o.getString("status") else "inconnu"
        val msg = if (status == "success") {
            "Caisse activee avec succes sur cet appareil.\n" + o.toString()
        } else {
            "Echec de l'activation.\n" + o.toString()
        }
        AlertDialog.Builder(this)
            .setTitle("Resultat Viva (" + (if (o.has("action")) o.getString("action") else "?") + ")")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    inner class Bridge {
        @JavascriptInterface
        fun pay(cents: Int, id: String) {
            runOnUiThread { launchPay(cents, id) }
        }

        @JavascriptInterface
        fun abort() {
            runOnUiThread { abortPay() }
        }
    }
}

