package pl.kubasaa.drukarka

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pełne ustawienia drukarki - jej wbudowana strona (Brother: "Zarządzanie przez przeglądarkę WWW").
 * Hasło logowania jest na naklejce drukarki (oznaczenie "Pwd").
 */
class WwwActivity : AppCompatActivity() {

    private lateinit var www: WebView
    private var powiazano = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val adres = intent.getStringExtra("adres") ?: "http://192.168.1.1/"
        val nazwa = intent.getStringExtra("nazwa") ?: "Drukarka"

        // cały ruch tej strony przez Wi-Fi (nawet gdy Wi-Fi nie ma internetu)
        Siec.wifi(this)?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            powiazano = cm.bindProcessToNetwork(it)
        }

        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.addView(Ui.pasekTytulu(this, "Ustawienia: $nazwa") { finish() })

        val pomoc = TextView(this)
        pomoc.text = "Hasło do logowania jest na naklejce z tyłu drukarki (\"Pwd\")."
        pomoc.setTextColor(Ui.szaryTekst)
        pomoc.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 6))
        gl.addView(pomoc)

        val pasek = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        pasek.max = 100
        gl.addView(pasek, LinearLayout.LayoutParams(-1, Ui.dp(this, 4)))

        www = WebView(this)
        www.settings.javaScriptEnabled = true
        www.settings.domStorageEnabled = true
        www.settings.loadWithOverviewMode = true
        www.settings.useWideViewPort = true
        www.settings.builtInZoomControls = true
        www.settings.displayZoomControls = false
        www.webViewClient = object : WebViewClient() {
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed() // drukarka ma własny (samopodpisany) certyfikat
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
        }
        www.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, p: Int) {
                pasek.progress = p
                pasek.visibility = if (p >= 100) View.GONE else View.VISIBLE
            }
        }
        gl.addView(www, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(gl)
        www.loadUrl(adres)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (www.canGoBack()) www.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        if (powiazano) {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
        }
        www.destroy()
        super.onDestroy()
    }
}
