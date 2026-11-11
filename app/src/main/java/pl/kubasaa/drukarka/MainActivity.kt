package pl.kubasaa.drukarka

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.concurrent.thread

/**
 * Drukarka - drukowanie zdjęć i dokumentów oraz skanowanie na drukarkach sieciowych
 * (Brother i inne zgodne z Mopria / AirPrint), z kontrolą sieci Wi-Fi.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var baner: LinearLayout
    private lateinit var banerTekst: TextView
    private lateinit var karta: LinearLayout
    private val glowny = Handler(Looper.getMainLooper())
    private var sprawdzanie = 0
    private var siecCb: ConnectivityManager.NetworkCallback? = null
    private var mozliwosci: Mozliwosci? = null

    private val zgody = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { sprawdz() }

    private val wyborDokumentu = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) dialogDokumentu(uri)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.setBackgroundColor(Color.rgb(246, 248, 251))
        gl.addView(Ui.pasekTytulu(this, "Drukarka", null))

        val sc = ScrollView(this)
        val tresc = LinearLayout(this)
        tresc.orientation = LinearLayout.VERTICAL
        tresc.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 24))
        sc.addView(tresc)
        gl.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- czerwony pasek: zła sieć ---
        baner = LinearLayout(this)
        baner.orientation = LinearLayout.VERTICAL
        baner.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12))
        baner.background = Ui.tlo(Color.rgb(255, 235, 238), Ui.dp(this, 14).toFloat(), Ui.dp(this, 2), Ui.czerwony)
        banerTekst = TextView(this)
        banerTekst.setTextColor(Color.rgb(140, 20, 20))
        banerTekst.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        baner.addView(banerTekst)
        baner.addView(Ui.przycisk(this, "📶  Przełącz Wi-Fi", Ui.czerwony) {
            try { startActivity(Siec.intencjaPrzelaczWifi()) } catch (e: Exception) {
                startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 10) })
        baner.visibility = View.GONE
        tresc.addView(baner, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 12) })

        // --- karta aktywnej drukarki ---
        karta = LinearLayout(this)
        karta.orientation = LinearLayout.VERTICAL
        karta.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14))
        karta.background = Ui.tlo(Color.WHITE, Ui.dp(this, 18).toFloat(), Ui.dp(this, 1), Color.rgb(220, 225, 232))
        karta.elevation = Ui.dp(this, 3).toFloat()
        tresc.addView(karta, LinearLayout.LayoutParams(-1, -2))

        // --- kafle ---
        val kafle = listOf(
            Ui.kafel(this, "🖼️", "Drukuj zdjęcie", Ui.niebieski) { otworzZdjecie(null) },
            Ui.kafel(this, "📄", "Drukuj dokument", Ui.zielony) {
                wyborDokumentu.launch(arrayOf("application/pdf", "image/*"))
            },
            Ui.kafel(this, "📠", "Skanuj", Ui.pomarancz) {
                if (Drukarki.aktywna(this) == null) brakDrukarki() else startActivity(Intent(this, SkanActivity::class.java))
            },
            Ui.kafel(this, "⚙️", "Ustawienia\ndrukarki", Ui.fiolet) { ustawieniaDrukarki() },
            Ui.kafel(this, "🔎", "Drukarki\n(dodaj / wybierz)", Ui.turkus) { ekranDrukarek() },
            Ui.kafel(this, "📶", "Sieć Wi-Fi", Ui.granat) {
                try { startActivity(Siec.intencjaPrzelaczWifi()) } catch (e: Exception) {}
            }
        )
        for (i in kafle.indices step 2) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.addView(kafle[i], LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
            r.addView(kafle[i + 1], LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) })
            tresc.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 12) })
        }

        val stopka = TextView(this)
        stopka.text = "Wersja " + try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
        stopka.setTextColor(Ui.szaryTekst)
        stopka.gravity = Gravity.CENTER
        stopka.setPadding(0, Ui.dp(this, 16), 0, 0)
        tresc.addView(stopka)

        setContentView(gl)
        pokazKarte(null, "")

        // zgoda na lokalizację = Android pokazuje nazwę sieci Wi-Fi
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("ust", MODE_PRIVATE).getBoolean("pytano", false)) {
            getSharedPreferences("ust", MODE_PRIVATE).edit().putBoolean("pytano", true).apply()
            AlertDialog.Builder(this)
                .setTitle("Nazwa sieci Wi-Fi")
                .setMessage("Żeby aplikacja wiedziała, w jakiej sieci Wi-Fi jesteś i w jakiej jest drukarka, Android wymaga zgody na lokalizację. Aplikacja nie zapisuje ani nie wysyła Twojej lokalizacji.")
                .setPositiveButton("Dalej") { _, _ ->
                    zgody.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                .setNegativeButton("Pomiń", null)
                .show()
        }

        obsluzUdostepnienie(intent)
        if (Drukarki.lista(this).isEmpty()) glowny.postDelayed({ szukajDrukarek(true) }, 600)
        sluchajSieci()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        obsluzUdostepnienie(i)
    }

    override fun onResume() {
        super.onResume()
        sprawdz()
    }

    override fun onDestroy() {
        siecCb?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } catch (e: Exception) {}
        }
        super.onDestroy()
    }

    /** Po przełączeniu Wi-Fi sprawdzamy drukarkę ponownie, automatycznie. */
    private fun sluchajSieci() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { glowny.postDelayed({ sprawdz() }, 1500) }
            override fun onLost(network: Network) { glowny.postDelayed({ sprawdz() }, 500) }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            siecCb = cb
        } catch (e: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun obsluzUdostepnienie(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri: Uri = (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)) ?: return
        i.action = null
        val typ = i.type ?: contentResolver.getType(uri) ?: ""
        if (typ.startsWith("image/")) {
            AlertDialog.Builder(this).setTitle("Udostępniony obraz")
                .setItems(arrayOf("Drukuj jako zdjęcie (10x15, 9x13…)", "Drukuj jako dokument (A4)")) { _, w ->
                    if (w == 0) otworzZdjecie(uri) else dialogDokumentu(uri)
                }.show()
        } else dialogDokumentu(uri)
    }

    private fun otworzZdjecie(uri: Uri?) {
        val i = Intent(this, ZdjecieActivity::class.java)
        if (uri != null) { i.data = uri; i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(i)
    }

    private fun brakDrukarki() {
        AlertDialog.Builder(this).setMessage("Nie masz jeszcze dodanej drukarki.")
            .setPositiveButton("Szukaj drukarek") { _, _ -> szukajDrukarek(false) }
            .setNegativeButton("Anuluj", null).show()
    }

    // ---------------- Sprawdzanie sieci i stanu drukarki ----------------

    private fun sprawdz() {
        val d = Drukarki.aktywna(this)
        if (d == null) { baner.visibility = View.GONE; pokazKarte(null, ""); return }
        val numer = ++sprawdzanie
        pokazKarte(null, "Sprawdzam…")
        thread {
            val wifi = Siec.maWifi(this)
            val ssid = Siec.aktualneSsid(this)
            val jest = wifi && Siec.osiagalna(this, d.ip, d.ippPort)
            var a: Ipp.Atrybuty? = null
            if (jest) a = try { Ipp.atrybuty(this, d) } catch (e: Exception) { null }
            runOnUiThread {
                if (numer != sprawdzanie) return@runOnUiThread
                if (jest) {
                    baner.visibility = View.GONE
                    if (ssid.isNotBlank() && d.ssid != ssid) { d.ssid = ssid; Drukarki.dodajLubZaktualizuj(this, d) }
                    mozliwosci = a?.let { Mozliwosci(it) }
                    pokazKarte(a, if (a == null) "Odpowiada (brak szczegółów)" else "")
                } else {
                    mozliwosci = null
                    val siecDrukarki = if (d.ssid.isNotBlank()) "„${d.ssid}”" else "(nieznana - zapisze się, gdy raz się połączysz)"
                    banerTekst.text = when {
                        !wifi -> "Nie jesteś połączony z Wi-Fi.\nDrukarka ${d.nazwa} jest w sieci $siecDrukarki."
                        d.ssid.isNotBlank() && ssid.isNotBlank() && ssid != d.ssid ->
                            "Jesteś w złej sieci: „$ssid”.\nDrukarka ${d.nazwa} jest w sieci $siecDrukarki."
                        else -> "Drukarka ${d.nazwa} (${d.ip}) nie odpowiada.\n" +
                                (if (ssid.isNotBlank()) "Jesteś w sieci „$ssid”. " else "") +
                                "Sprawdź, czy drukarka jest włączona, albo przełącz sieć na $siecDrukarki."
                    }
                    baner.visibility = View.VISIBLE
                    pokazKarte(null, "Niedostępna")
                }
            }
        }
    }

    private fun pokazKarte(a: Ipp.Atrybuty?, komunikat: String) {
        karta.removeAllViews()
        val lista = Drukarki.lista(this)
        val d = Drukarki.aktywna(this)
        if (d == null) {
            val t = TextView(this)
            t.text = "Brak drukarki.\nNaciśnij „Drukarki (dodaj / wybierz)”."
            t.setTextColor(Ui.granat); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            karta.addView(t)
            return
        }
        val gora = LinearLayout(this)
        gora.orientation = LinearLayout.HORIZONTAL
        gora.gravity = Gravity.CENTER_VERTICAL
        if (lista.size > 1) gora.addView(strzalka("‹") { zmienDrukarke(-1) })
        val opis = LinearLayout(this)
        opis.orientation = LinearLayout.VERTICAL
        val n = TextView(this)
        n.text = "🖨  " + d.nazwa
        n.setTextColor(Ui.granat); n.typeface = Typeface.DEFAULT_BOLD
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
        opis.addView(n)
        val ip = TextView(this)
        ip.text = d.ip + (if (d.ssid.isNotBlank()) "   ·   Wi-Fi: ${d.ssid}" else "")
        ip.setTextColor(Ui.szaryTekst); ip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        opis.addView(ip)
        val st = TextView(this)
        st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        st.typeface = Typeface.DEFAULT_BOLD
        st.setPadding(0, Ui.dp(this, 4), 0, 0)
        if (a != null) {
            val (tekst, kolor) = stanPoPolsku(a)
            st.text = tekst; st.setTextColor(kolor)
        } else {
            st.text = if (komunikat == "Niedostępna") "✖  Niedostępna" else komunikat
            st.setTextColor(if (komunikat == "Niedostępna") Ui.czerwony else Ui.szaryTekst)
        }
        opis.addView(st)
        gora.addView(opis, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) })
        if (lista.size > 1) gora.addView(strzalka("›") { zmienDrukarke(1) })
        karta.addView(gora)

        if (lista.size > 1) {
            val kropki = TextView(this)
            val akt = Drukarki.aktywnyIndeks(this).coerceIn(0, lista.size - 1)
            kropki.text = lista.indices.joinToString("  ") { if (it == akt) "●" else "○" }
            kropki.setTextColor(Ui.niebieski)
            kropki.gravity = Gravity.CENTER
            kropki.setPadding(0, Ui.dp(this, 6), 0, 0)
            karta.addView(kropki)
        }

        // tonery
        if (a != null) {
            val nazwy = a.teksty("marker-names")
            val poziomy = a.liczby("marker-levels")
            val kolory = a.teksty("marker-colors")
            if (poziomy.isNotEmpty()) {
                val tl = LinearLayout(this)
                tl.orientation = LinearLayout.VERTICAL
                tl.setPadding(0, Ui.dp(this, 10), 0, 0)
                poziomy.forEachIndexed { i, p ->
                    tl.addView(pasekTonera(nazwaTonera(nazwy.getOrElse(i) { "Toner ${i + 1}" }), p, kolorTonera(kolory.getOrElse(i) { "" }, nazwy.getOrElse(i) { "" })))
                }
                karta.addView(tl)
            }
        }
        karta.setOnClickListener { sprawdz() }
    }

    private fun strzalka(t: String, akcja: () -> Unit): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(Color.WHITE)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        v.gravity = Gravity.CENTER
        v.background = Ui.tlo(Ui.niebieski, Ui.dp(this, 22).toFloat())
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44))
        v.setPadding(0, 0, 0, Ui.dp(this, 4))
        v.setOnClickListener { akcja() }
        return v
    }

    private fun zmienDrukarke(o: Int) {
        val l = Drukarki.lista(this)
        if (l.isEmpty()) return
        val n = (Drukarki.aktywnyIndeks(this) + o + l.size) % l.size
        Drukarki.ustawAktywna(this, n)
        sprawdz()
    }

    private fun stanPoPolsku(a: Ipp.Atrybuty): Pair<String, Int> {
        val powody = a.teksty("printer-state-reasons")
        val uwagi = mutableListOf<String>()
        for (p in powody) {
            when {
                p.startsWith("media-empty") || p.startsWith("media-needed") -> uwagi.add("brak papieru")
                p.startsWith("media-jam") -> uwagi.add("zacięty papier")
                p.startsWith("door-open") || p.startsWith("cover-open") -> uwagi.add("otwarta pokrywa")
                p.startsWith("toner-empty") || p.startsWith("marker-supply-empty") -> uwagi.add("brak tonera")
                p.startsWith("toner-low") || p.startsWith("marker-supply-low") -> uwagi.add("mało tonera")
                p.startsWith("offline") -> uwagi.add("offline")
            }
        }
        val (tekst, kolor) = when (a.liczba("printer-state")) {
            3 -> "✔  Oczekiwanie (gotowa)" to Ui.zielony
            4 -> "⏳  Drukuje…" to Ui.niebieski
            5 -> "⛔  Zatrzymana" to Ui.czerwony
            else -> "✔  Połączona" to Ui.zielony
        }
        return if (uwagi.isEmpty()) tekst to kolor
        else (tekst + " – " + uwagi.distinct().joinToString(", ")) to (if (kolor == Ui.zielony) Ui.pomarancz else kolor)
    }

    private fun nazwaTonera(n: String): String {
        val m = n.lowercase()
        return when {
            m.contains("black") -> "Czarny"
            m.contains("cyan") -> "Cyjan"
            m.contains("magenta") -> "Magenta"
            m.contains("yellow") -> "Żółty"
            m.contains("drum") -> "Bęben"
            m.contains("belt") -> "Pas"
            m.contains("waste") -> "Pojemnik na zużyty toner"
            else -> n
        }
    }

    private fun kolorTonera(k: String, n: String): Int {
        try { if (k.startsWith("#") && k.length >= 7) return Color.parseColor(k.substring(0, 7)) } catch (e: Exception) {}
        val m = n.lowercase()
        return when {
            m.contains("black") -> Color.rgb(30, 30, 30)
            m.contains("cyan") -> Color.rgb(0, 170, 230)
            m.contains("magenta") -> Color.rgb(220, 0, 120)
            m.contains("yellow") -> Color.rgb(245, 200, 0)
            else -> Color.GRAY
        }
    }

    private fun pasekTonera(nazwa: String, poziom: Int, kolor: Int): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3))
        val t = TextView(this)
        t.text = nazwa
        t.setTextColor(Ui.granat); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        r.addView(t, LinearLayout.LayoutParams(Ui.dp(this, 92), -2))
        val tor = LinearLayout(this)
        tor.background = Ui.tlo(Ui.szaryTlo, Ui.dp(this, 6).toFloat())
        val proc = if (poziom in 0..100) poziom else 0
        val wyp = View(this)
        val kolorPaska = if (kolor == Color.WHITE || Color.red(kolor) > 240 && Color.green(kolor) > 240 && Color.blue(kolor) > 240) Color.GRAY else kolor
        wyp.background = Ui.tlo(kolorPaska, Ui.dp(this, 6).toFloat())
        tor.weightSum = 100f
        tor.addView(wyp, LinearLayout.LayoutParams(0, -1, proc.toFloat().coerceAtLeast(if (proc > 0) 2f else 0f)))
        r.addView(tor, LinearLayout.LayoutParams(0, Ui.dp(this, 12), 1f))
        val pr = TextView(this)
        pr.text = if (poziom in 0..100) "$poziom%" else if (poziom == -3) "ok" else "?"
        pr.setTextColor(Ui.szaryTekst); pr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        pr.gravity = Gravity.END
        r.addView(pr, LinearLayout.LayoutParams(Ui.dp(this, 44), -2))
        return r
    }

    // ---------------- Ustawienia drukarki ----------------

    private fun ustawieniaDrukarki() {
        val d = Drukarki.aktywna(this) ?: return brakDrukarki()
        val opcje = mutableListOf("Pełne ustawienia drukarki (strona WWW)", "Zmień nazwę / adres IP w aplikacji")
        val brother = listOf("com.brother.mfc.mobileconnect", "com.brother.mfc.brprint")
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
        if (brother != null) opcje.add("Otwórz aplikację Brother")
        opcje.add("Ustawienia drukowania Androida")
        AlertDialog.Builder(this).setTitle(d.nazwa)
            .setItems(opcje.toTypedArray()) { _, w ->
                when (opcje[w]) {
                    opcje[0] -> startActivity(Intent(this, WwwActivity::class.java)
                        .putExtra("adres", d.stronaUstawien()).putExtra("nazwa", d.nazwa))
                    opcje[1] -> edytujDrukarke(Drukarki.aktywnyIndeks(this))
                    "Otwórz aplikację Brother" -> startActivity(packageManager.getLaunchIntentForPackage(brother!!))
                    else -> try { startActivity(Intent(android.provider.Settings.ACTION_PRINT_SETTINGS)) } catch (e: Exception) {}
                }
            }.show()
    }

    // ---------------- Lista drukarek ----------------

    private fun ekranDrukarek() {
        val lista = Drukarki.lista(this)
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0)
        var dialog: AlertDialog? = null
        if (lista.isEmpty()) {
            val t = TextView(this); t.text = "Brak zapisanych drukarek."; t.setTextColor(Ui.szaryTekst)
            l.addView(t)
        }
        val akt = Drukarki.aktywnyIndeks(this)
        lista.forEachIndexed { i, d ->
            val w = LinearLayout(this)
            w.orientation = LinearLayout.VERTICAL
            w.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
            w.background = Ui.tlo(if (i == akt) Color.rgb(232, 240, 255) else Color.WHITE, Ui.dp(this, 12).toFloat(),
                Ui.dp(this, 2), if (i == akt) Ui.niebieski else Color.rgb(220, 225, 232))
            val t = TextView(this)
            t.text = (if (i == akt) "● " else "○ ") + d.nazwa + "\n" + d.ip + (if (d.ssid.isNotBlank()) "  ·  Wi-Fi: ${d.ssid}" else "")
            t.setTextColor(Ui.granat); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            w.addView(t)
            val rz = LinearLayout(this)
            rz.orientation = LinearLayout.HORIZONTAL
            rz.addView(Ui.przycisk(this, "Wybierz", Ui.niebieski) { Drukarki.ustawAktywna(this, i); dialog?.dismiss(); sprawdz() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
            rz.addView(Ui.przycisk(this, "Zmień", Ui.fiolet) { dialog?.dismiss(); edytujDrukarke(i) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@MainActivity, 6) })
            rz.addView(Ui.przycisk(this, "Usuń", Ui.czerwony) {
                AlertDialog.Builder(this).setMessage("Usunąć ${d.nazwa}?")
                    .setPositiveButton("Usuń") { _, _ -> Drukarki.usun(this, i); dialog?.dismiss(); sprawdz(); ekranDrukarek() }
                    .setNegativeButton("Anuluj", null).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            w.addView(rz, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8) })
            l.addView(w, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(this@MainActivity, 10) })
        }
        l.addView(Ui.przycisk(this, "🔎  Szukaj drukarek w sieci", Ui.turkus) { dialog?.dismiss(); szukajDrukarek(false) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 4) })
        l.addView(Ui.przycisk(this, "✚  Dodaj ręcznie (adres IP)", Ui.granat) { dialog?.dismiss(); edytujDrukarke(-1) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@MainActivity, 8); bottomMargin = Ui.dp(this@MainActivity, 8) })
        val sc = ScrollView(this); sc.addView(l)
        dialog = AlertDialog.Builder(this).setTitle("Drukarki").setView(sc).setNegativeButton("Zamknij", null).show()
    }

    private fun edytujDrukarke(i: Int) {
        val lista = Drukarki.lista(this)
        val d = if (i in lista.indices) lista[i] else Drukarka("", "")
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0)
        fun pole(etykieta: String, wartosc: String, typ: Int): EditText {
            val t = TextView(this); t.text = etykieta; t.setTextColor(Ui.granat)
            l.addView(t)
            val e = EditText(this); e.setText(wartosc); e.inputType = typ
            l.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }
        val nazwa = pole("Nazwa (np. Dom, Praca)", d.nazwa, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val ip = pole("Adres IP (np. 192.168.50.5)", d.ip, InputType.TYPE_CLASS_TEXT)
        val ssid = pole("Sieć Wi-Fi drukarki (puste = zapisze się sama)", d.ssid.ifBlank { Siec.aktualneSsid(this) }, InputType.TYPE_CLASS_TEXT)
        AlertDialog.Builder(this).setTitle(if (i < 0) "Nowa drukarka" else "Zmień drukarkę").setView(l)
            .setPositiveButton("Zapisz") { _, _ ->
                val adres = ip.text.toString().trim()
                if (!Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(adres)) {
                    Toast.makeText(this, "Niepoprawny adres IP", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val nowa = Drukarka(nazwa.text.toString().trim().ifBlank { "Drukarka $adres" }, adres, ssid.text.toString().trim(),
                    d.ippPort, d.ippSciezka, if (d.ip == adres) d.esclUrl else "")
                val lst = Drukarki.lista(this)
                val idx = if (i in lst.indices) { lst[i] = nowa; Drukarki.zapisz(this, lst); i } else Drukarki.dodajLubZaktualizuj(this, nowa)
                Drukarki.ustawAktywna(this, idx)
                sprawdz()
            }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun szukajDrukarek(cicho: Boolean) {
        if (!Siec.maWifi(this)) {
            if (!cicho) AlertDialog.Builder(this).setMessage("Połącz się najpierw z Wi-Fi, w którym są drukarki.")
                .setPositiveButton("Przełącz Wi-Fi") { _, _ -> try { startActivity(Siec.intencjaPrzelaczWifi()) } catch (e: Exception) {} }
                .setNegativeButton("Anuluj", null).show()
            return
        }
        val czekaj = AlertDialog.Builder(this).setMessage("Szukam drukarek w sieci Wi-Fi…").setCancelable(false).show()
        Siec.szukaj(this, 6000) { znalezione ->
            czekaj.dismiss()
            if (znalezione.isEmpty()) {
                AlertDialog.Builder(this).setMessage("Nie znaleziono drukarek. Sprawdź, czy są włączone i w tej samej sieci, albo dodaj ręcznie po adresie IP (np. 192.168.50.5).")
                    .setPositiveButton("Dodaj ręcznie") { _, _ -> edytujDrukarke(-1) }
                    .setNegativeButton("Zamknij", null).show()
                return@szukaj
            }
            val ssid = Siec.aktualneSsid(this)
            val zapisane = Drukarki.lista(this).map { it.ip }.toSet()
            val nazwy = znalezione.map { (if (it.ip in zapisane) "✔ " else "") + it.nazwa.ifBlank { "Drukarka" } + "\n" + it.ip }.toTypedArray()
            val zazn = BooleanArray(znalezione.size) { true }
            AlertDialog.Builder(this).setTitle("Znalezione (zaznacz, które zapisać)")
                .setMultiChoiceItems(nazwy, zazn) { _, w, z -> zazn[w] = z }
                .setPositiveButton("Zapisz") { _, _ ->
                    val bylyPuste = Drukarki.lista(this).isEmpty()
                    var ostatni = -1
                    znalezione.forEachIndexed { i, z ->
                        if (zazn[i]) ostatni = Drukarki.dodajLubZaktualizuj(this,
                            Drukarka(z.nazwa.ifBlank { "Drukarka ${z.ip}" }, z.ip, ssid, z.ippPort, z.ippSciezka, z.escl))
                    }
                    if (ostatni >= 0 && bylyPuste) Drukarki.ustawAktywna(this, 0)
                    sprawdz()
                }
                .setNegativeButton("Anuluj", null).show()
        }
    }

    // ---------------- Drukowanie dokumentu ----------------

    private fun dialogDokumentu(uri: Uri) {
        val typ = contentResolver.getType(uri) ?: ""
        var kopie = 1
        var kolor = true
        var dwustronnie = false
        val m = mozliwosci
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        l.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), 0)
        lateinit var odswiez: () -> Unit
        val kontener = LinearLayout(this); kontener.orientation = LinearLayout.VERTICAL
        l.addView(kontener)
        odswiez = {
            kontener.removeAllViews()
            kontener.addView(Ui.naglowek(this, "Kopie: $kopie"))
            kontener.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "−", Ui.czerwony, false) { if (kopie > 1) kopie--; odswiez() },
                Ui.chip(this, "+", Ui.zielony, false) { if (kopie < 99) kopie++; odswiez() }
            ), 2))
            kontener.addView(Ui.naglowek(this, "Kolor"))
            kontener.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Kolor", Ui.fiolet, kolor) { kolor = true; odswiez() },
                Ui.chip(this, "Czarno-biały", Ui.fiolet, !kolor) { kolor = false; odswiez() }
            ), 2))
            if (m == null || m.dwustronnie) {
                kontener.addView(Ui.naglowek(this, "Strony"))
                kontener.addView(Ui.siatkaChipow(this, listOf(
                    Ui.chip(this, "Jednostronnie", Ui.turkus, !dwustronnie) { dwustronnie = false; odswiez() },
                    Ui.chip(this, "Dwustronnie", Ui.turkus, dwustronnie) { dwustronnie = true; odswiez() }
                ), 2))
            }
        }
        odswiez()
        val d = Drukarki.aktywna(this)
        AlertDialog.Builder(this).setTitle("Drukuj dokument" + (d?.let { " na: ${it.nazwa}" } ?: "")).setView(l)
            .setPositiveButton("Drukuj") { _, _ -> drukujDokument(uri, typ, Ipp.Opcje(kopie = kopie, kolor = kolor, dwustronnie = dwustronnie), false) }
            .setNeutralButton("Okno Androida") { _, _ -> drukujDokument(uri, typ, Ipp.Opcje(), true) }
            .setNegativeButton("Anuluj", null).show()
    }

    private fun drukujDokument(uri: Uri, typ: String, o: Ipp.Opcje, systemowo: Boolean) {
        val d = Drukarki.aktywna(this)
        if (!systemowo && d == null) { brakDrukarki(); return }
        val czekaj = AlertDialog.Builder(this).setMessage(if (systemowo) "Przygotowuję…" else "Wysyłam do drukarki ${d?.nazwa}…").setCancelable(false).show()
        thread {
            var wynik = ""
            var plikSystemowy: File? = null
            try {
                val pdf: File = if (typ.startsWith("image/")) Wydruk.obrazDoPdf(this, uri) ?: throw Exception("Nie udało się otworzyć obrazu")
                else Wydruk.skopiuj(this, uri, "dokument.pdf")
                if (systemowo) plikSystemowy = pdf
                else {
                    val dr = d!!
                    if (!Siec.osiagalna(this, dr.ip, dr.ippPort)) throw Exception("Drukarka ${dr.nazwa} nie odpowiada. Sprawdź sieć Wi-Fi.")
                    val m = mozliwosci ?: Mozliwosci(Ipp.atrybuty(this, dr))
                    if (!m.bezposrednio) throw Exception("Ta drukarka nie przyjmuje druku wprost - użyj „Okno Androida”.")
                    val (mn, mm) = Wydruk.mediaA4(m)
                    o.media = mn
                    wynik = Wydruk.drukujPdf(this, dr, m, pdf, o, "Dokument", mn, mm)
                    if (wynik.isEmpty()) wynik = "Wysłano do drukarki ${dr.nazwa} ✔"
                }
            } catch (e: OutOfMemoryError) {
                wynik = "Za mało pamięci telefonu na ten dokument - użyj „Okno Androida”."
            } catch (e: Exception) {
                wynik = e.message ?: "Błąd"
            }
            runOnUiThread {
                czekaj.dismiss()
                val ps = plikSystemowy
                if (ps != null) Wydruk.systemowoPlikPdf(this, ps, "Dokument")
                else AlertDialog.Builder(this).setMessage(wynik).setPositiveButton("OK", null).show()
            }
        }
    }
}
