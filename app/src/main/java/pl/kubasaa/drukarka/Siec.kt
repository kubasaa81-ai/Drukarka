package pl.kubasaa.drukarka

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Sieć: wszystkie połączenia z drukarką idą przez Wi-Fi (nawet gdy Wi-Fi "nie ma internetu"
 * i Android domyślnie wysyła ruch przez dane komórkowe).
 */
object Siec {

    fun wifi(c: Context): Network? {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val cap = cm.getNetworkCapabilities(n) ?: continue
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    fun maWifi(c: Context) = wifi(c) != null

    /** Nazwa aktualnej sieci Wi-Fi (wymaga zgody na lokalizację i włączonej lokalizacji). */
    @SuppressLint("MissingPermission")
    fun aktualneSsid(c: Context): String {
        return try {
            val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val s = wm.connectionInfo?.ssid ?: ""
            val czysta = s.trim('"')
            if (czysta.isBlank() || czysta == "<unknown ssid>" || czysta == "0x") "" else czysta
        } catch (e: Exception) { "" }
    }

    /** Czy drukarka odpowiada (krótka próba połączenia TCP przez Wi-Fi). Wywoływać poza wątkiem UI. */
    fun osiagalna(c: Context, ip: String, port: Int = 631, czasMs: Int = 1500): Boolean {
        val n = wifi(c) ?: return false
        return try {
            val s: Socket = n.socketFactory.createSocket()
            s.connect(InetSocketAddress(ip, port), czasMs)
            s.close()
            true
        } catch (e: Exception) {
            // drugi port (www) - niektóre drukarki w trybie uśpienia wolniej otwierają 631
            try {
                val s: Socket = n.socketFactory.createSocket()
                s.connect(InetSocketAddress(ip, 80), czasMs)
                s.close(); true
            } catch (e2: Exception) { false }
        }
    }

    /** Otwiera połączenie HTTP(S) przez Wi-Fi. Certyfikat drukarki (samopodpisany) jest akceptowany. */
    fun otworz(c: Context, adres: String): HttpURLConnection {
        val url = URL(adres)
        val n = wifi(c)
        val con = (if (n != null) n.openConnection(url) else url.openConnection()) as HttpURLConnection
        if (con is HttpsURLConnection) {
            con.sslSocketFactory = ufajWszystkim()
            con.setHostnameVerifier { _, _ -> true }
        }
        con.connectTimeout = 8000
        con.readTimeout = 60000
        return con
    }

    private var sslFactory: javax.net.ssl.SSLSocketFactory? = null
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private fun ufajWszystkim(): javax.net.ssl.SSLSocketFactory {
        sslFactory?.let { return it }
        val tm = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, tm, SecureRandom())
        sslFactory = ctx.socketFactory
        return ctx.socketFactory
    }

    /** Intencja do szybkiego przełączenia Wi-Fi (okienko nad aplikacją na Androidzie 10+). */
    fun intencjaPrzelaczWifi(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
        else Intent(Settings.ACTION_WIFI_SETTINGS)

    // ---------------- Wyszukiwanie drukarek w sieci (mDNS / Bonjour) ----------------

    class Znaleziona(val ip: String) {
        var nazwa = ""
        var ippPort = 631
        var ippSciezka = "/ipp/print"
        var escl = ""
    }

    /**
     * Szuka drukarek (_ipp._tcp) i skanerów (_uscan._tcp) przez [czasMs], potem woła [gotowe]
     * na wątku UI z listą znalezionych.
     */
    fun szukaj(c: Context, czasMs: Long, gotowe: (List<Znaleziona>) -> Unit) {
        val app = c.applicationContext
        val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("drukarka")
        try { lock.setReferenceCounted(false); lock.acquire() } catch (e: Exception) {}

        val wyniki = LinkedHashMap<String, Znaleziona>()
        val kolejka = ArrayDeque<NsdServiceInfo>()
        var rozwiazuje = false
        val glowny = Handler(Looper.getMainLooper())
        val sluchacze = mutableListOf<NsdManager.DiscoveryListener>()

        fun nastepny() {
            if (rozwiazuje) return
            val info = kolejka.removeFirstOrNull() ?: return
            rozwiazuje = true
            try {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(si: NsdServiceInfo, kod: Int) {
                        glowny.post { rozwiazuje = false; nastepny() }
                    }
                    override fun onServiceResolved(si: NsdServiceInfo) {
                        glowny.post {
                            try {
                                @Suppress("DEPRECATION")
                                val ip = si.host?.hostAddress ?: ""
                                if (ip.isNotBlank() && !ip.contains(":")) {
                                    val z = wyniki.getOrPut(ip) { Znaleziona(ip) }
                                    val txt = si.attributes
                                    fun t(k: String) = txt[k]?.let { String(it) } ?: ""
                                    val typ = si.serviceType ?: ""
                                    if (typ.contains("uscan")) {
                                        val rs = t("rs").trim('/')
                                        val schemat = if (typ.contains("uscans")) "https" else "http"
                                        z.escl = "$schemat://$ip:${si.port}/" + (if (rs.isBlank()) "eSCL" else rs)
                                        if (z.nazwa.isBlank()) z.nazwa = t("ty").ifBlank { si.serviceName }
                                    } else {
                                        z.ippPort = si.port
                                        val rp = t("rp").trim('/')
                                        z.ippSciezka = "/" + (if (rp.isBlank()) "ipp/print" else rp)
                                        z.nazwa = t("ty").ifBlank { si.serviceName }
                                    }
                                }
                            } catch (e: Exception) {}
                            rozwiazuje = false
                            nastepny()
                        }
                    }
                })
            } catch (e: Exception) {
                rozwiazuje = false
                glowny.post { nastepny() }
            }
        }

        for (typ in listOf("_ipp._tcp", "_uscan._tcp")) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo != null) glowny.post { kolejka.addLast(serviceInfo); nastepny() }
                }
            }
            try {
                nsd.discoverServices(typ, NsdManager.PROTOCOL_DNS_SD, l)
                sluchacze.add(l)
            } catch (e: Exception) {}
        }

        glowny.postDelayed({
            for (l in sluchacze) try { nsd.stopServiceDiscovery(l) } catch (e: Exception) {}
            try { lock.release() } catch (e: Exception) {}
            gotowe(wyniki.values.toList())
        }, czasMs)
    }
}
