package pl.kubasaa.drukarka

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/** Edytor odbitki: format, orientacja, wypełnienie, przesuwanie i drukowanie. */
class ZdjecieActivity : AppCompatActivity() {

    private var zrodlo: Bitmap? = null        // pełne zdjęcie (do wydruku), max 3000 px
    private var maleZrodlo: Bitmap? = null    // pomniejszone (do podglądu)
    private var obroconeDuze: Bitmap? = null
    private var obroconeMale: Bitmap? = null
    private var obrotZrobiony = -1

    private val stan = Kompozycja.Stan()
    private var kopie = 1
    private var reczny = false
    private var mozliwosci: Mozliwosci? = null
    private var drukarka: Drukarka? = null

    private lateinit var podglad: Podglad
    private lateinit var info: TextView
    private lateinit var opcje: LinearLayout
    private lateinit var tekstKopii: TextView

    private val wybor = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) wczytaj(uri) else if (zrodlo == null) finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        drukarka = Drukarki.aktywna(this)

        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.addView(Ui.pasekTytulu(this, "Drukuj zdjęcie") { finish() })

        val sc = ScrollView(this)
        sc.isFillViewport = true
        val tresc = LinearLayout(this)
        tresc.orientation = LinearLayout.VERTICAL
        tresc.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 24))
        sc.addView(tresc)
        gl.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        podglad = Podglad(this)
        tresc.addView(podglad, LinearLayout.LayoutParams(-1, Ui.dp(this, 340)))

        val wskazowka = TextView(this)
        wskazowka.text = "Przesuń palcem, żeby wybrać kadr. Rozsuń dwa palce, żeby przybliżyć."
        wskazowka.setTextColor(Ui.szaryTekst)
        wskazowka.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        wskazowka.gravity = Gravity.CENTER
        wskazowka.setPadding(0, Ui.dp(this, 4), 0, 0)
        tresc.addView(wskazowka)

        opcje = LinearLayout(this)
        opcje.orientation = LinearLayout.VERTICAL
        tresc.addView(opcje)

        info = TextView(this)
        info.setTextColor(Ui.granat)
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        info.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        info.background = Ui.tlo(Ui.szaryTlo, Ui.dp(this, 12).toFloat())
        tresc.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ZdjecieActivity, 12) })

        // dolny pasek z przyciskami - zawsze widoczny, nic go nie zasłania
        val dol = LinearLayout(this)
        dol.orientation = LinearLayout.VERTICAL
        dol.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 12))
        dol.setBackgroundColor(Color.WHITE)
        dol.elevation = Ui.dp(this, 8).toFloat()
        dol.addView(Ui.przycisk(this, "🖨  Drukuj" + (drukarka?.let { " na: ${it.nazwa}" } ?: ""), Ui.zielony) { drukuj() },
            LinearLayout.LayoutParams(-1, -2))
        val rzad = LinearLayout(this)
        rzad.orientation = LinearLayout.HORIZONTAL
        rzad.addView(Ui.przycisk(this, "Inne zdjęcie", Ui.niebieski) { wyborZdjecia() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@ZdjecieActivity, 8) })
        rzad.addView(Ui.przycisk(this, "Okno Androida", Ui.granat) { drukujSystemowo() },
            LinearLayout.LayoutParams(0, -2, 1f))
        dol.addView(rzad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ZdjecieActivity, 8) })
        gl.addView(dol, LinearLayout.LayoutParams(-1, -2))

        setContentView(gl)
        zbudujOpcje()

        val uri = intent.data
        if (uri != null) wczytaj(uri) else wyborZdjecia()

        drukarka?.let { d ->
            thread {
                val m = try { Mozliwosci(Ipp.atrybuty(this, d)) } catch (e: Exception) { null }
                runOnUiThread { mozliwosci = m; zbudujOpcje(); odswiezInfo() }
            }
        }
    }

    private fun wyborZdjecia() {
        wybor.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun wczytaj(uri: Uri) {
        info.text = "Wczytuję zdjęcie…"
        thread {
            val b = try { Wydruk.wczytajObraz(this, uri, 3000) } catch (e: Exception) { null }
            runOnUiThread {
                if (b == null) { Toast.makeText(this, "Nie udało się otworzyć zdjęcia", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                zrodlo?.recycle(); maleZrodlo?.recycle(); obroconeDuze?.recycle(); obroconeMale?.recycle()
                zrodlo = b
                val s = 1200f / maxOf(b.width, b.height)
                maleZrodlo = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b.copy(Bitmap.Config.ARGB_8888, false)
                obroconeDuze = null; obroconeMale = null; obrotZrobiony = -1
                stan.obrot = 0; stan.przesX = 0f; stan.przesY = 0f; stan.zoom = 1f
                odswiez()
            }
        }
    }

    private fun obrocone(duze: Boolean): Bitmap? {
        val z = (if (duze) zrodlo else maleZrodlo) ?: return null
        if (obrotZrobiony != stan.obrot) {
            obroconeDuze?.let { if (it != zrodlo) it.recycle() }
            obroconeMale?.let { if (it != maleZrodlo) it.recycle() }
            obroconeDuze = null; obroconeMale = null
            obrotZrobiony = stan.obrot
        }
        if (stan.obrot == 0) return z
        if (duze) {
            if (obroconeDuze == null) obroconeDuze = obrocKopie(z, stan.obrot)
            return obroconeDuze
        } else {
            if (obroconeMale == null) obroconeMale = obrocKopie(z, stan.obrot)
            return obroconeMale
        }
    }

    private fun obrocKopie(b: Bitmap, st: Int): Bitmap {
        val m = android.graphics.Matrix().apply { postRotate(st.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun zbudujOpcje() {
        opcje.removeAllViews()
        opcje.addView(Ui.naglowek(this, "Rozmiar odbitki"))
        opcje.addView(Ui.siatkaChipow(this, Kompozycja.formaty.map { f ->
            Ui.chip(this, f.nazwa, Ui.niebieski, stan.format == f) { stan.format = f; zbudujOpcje(); odswiez() }
        }, 4))

        opcje.addView(Ui.naglowek(this, "Ułożenie"))
        val ul = listOf("Jak zdjęcie", "Pionowo", "Poziomo")
        val rzadUl = ul.mapIndexed { i, t ->
            Ui.chip(this, t, Ui.fiolet, stan.orientacja == i) { stan.orientacja = i; stan.przesX = 0f; stan.przesY = 0f; zbudujOpcje(); odswiez() } as View
        }.toMutableList()
        rzadUl.add(Ui.chip(this, "⟳ 90°", Ui.pomarancz, false) {
            stan.obrot = (stan.obrot + 90) % 360; stan.przesX = 0f; stan.przesY = 0f; odswiez()
        })
        opcje.addView(Ui.siatkaChipow(this, rzadUl, 4))

        opcje.addView(Ui.naglowek(this, "Wypełnienie kartki"))
        val tryby = listOf(
            Kompozycja.Tryb.WYPELNIJ to "Na całość\n(przytnij brzegi)",
            Kompozycja.Tryb.CALE_ROZMYTE to "Całe zdjęcie\n+ rozmyte tło",
            Kompozycja.Tryb.CALE_BIALE to "Całe zdjęcie\n+ białe tło"
        )
        opcje.addView(Ui.siatkaChipow(this, tryby.map { (t, n) ->
            Ui.chip(this, n, Ui.turkus, stan.tryb == t) { stan.tryb = t; zbudujOpcje(); odswiez() }
        }, 3))

        opcje.addView(Ui.naglowek(this, "Liczba odbitek"))
        val k = LinearLayout(this)
        k.orientation = LinearLayout.HORIZONTAL
        k.gravity = Gravity.CENTER_VERTICAL
        k.addView(Ui.przycisk(this, "−", Ui.czerwony) { if (kopie > 1) kopie--; tekstKopii.text = "$kopie" },
            LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        tekstKopii = TextView(this)
        tekstKopii.text = "$kopie"
        tekstKopii.gravity = Gravity.CENTER
        tekstKopii.setTextColor(Ui.granat)
        tekstKopii.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        k.addView(tekstKopii, LinearLayout.LayoutParams(Ui.dp(this, 70), -2))
        k.addView(Ui.przycisk(this, "+", Ui.zielony) { if (kopie < 50) kopie++; tekstKopii.text = "$kopie" },
            LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        opcje.addView(k)

        if (mozliwosci?.recznyPodajnik == true) {
            opcje.addView(Ui.naglowek(this, "Skąd papier"))
            opcje.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Taca (automatycznie)", Ui.granat, !reczny) { reczny = false; zbudujOpcje(); odswiezInfo() },
                Ui.chip(this, "Szczelina ręczna", Ui.granat, reczny) { reczny = true; zbudujOpcje(); odswiezInfo() }
            ), 2))
        }
    }

    private fun odswiez() {
        val z = obrocone(false) ?: return
        val (wMm, hMm) = Kompozycja.wymiary(stan, z.width, z.height)
        podglad.ustaw(z, wMm, hMm)
        odswiezInfo()
    }

    private fun odswiezInfo() {
        val z = obrocone(false) ?: return
        val (wMm, hMm) = Kompozycja.wymiary(stan, z.width, z.height)
        val sb = StringBuilder()
        sb.append("Odbitka ${stan.format.nazwa} (${Math.round(wMm / 10.0 * 10) / 10.0} × ${Math.round(hMm / 10.0 * 10) / 10.0} cm), ")
        sb.append(if (wMm > hMm) "poziomo" else if (wMm < hMm) "pionowo" else "kwadrat")
        val d = drukarka
        val m = mozliwosci
        if (d == null) sb.append("\nBrak wybranej drukarki - dodaj ją na ekranie głównym.")
        else if (m == null) sb.append("\nSprawdzam drukarkę ${d.nazwa}…")
        else if (!m.bezposrednio) sb.append("\nTa drukarka nie przyjmuje druku wprost - użyj „Okno Androida”.")
        else if (reczny) sb.append("\nPapier ${stan.format.nazwa} włóż do szczeliny ręcznej.")
        else {
            val media = Ipp.dopasujMedia(m.media, wMm, hMm)
            if (media != null) sb.append("\nDrukarka zna papier ${stan.format.nazwa} - włóż go do tacy lub szczeliny.")
            else sb.append("\nDrukarka nie ma papieru ${stan.format.nazwa} - wydruk na A4 w prawdziwym rozmiarze (do wycięcia).")
        }
        sb.append("\nDrukarka laserowa zostawia ok. 4 mm marginesu przy krawędziach.")
        info.text = sb.toString()
    }

    /** Gotowa odbitka w 300 dpi. */
    private fun odbitka(): Triple<Bitmap, Double, Double>? {
        val z = obrocone(true) ?: return null
        val (wMm, hMm) = Kompozycja.wymiary(stan, z.width, z.height)
        val dpi = if (maxOf(wMm, hMm) > 220) 250 else 300
        val w = Math.round(wMm / 25.4 * dpi).toInt()
        val h = Math.round(hMm / 25.4 * dpi).toInt()
        return Triple(Kompozycja.renderuj(z, w, h, stan), wMm, hMm)
    }

    private fun drukuj() {
        val d = drukarka
        if (d == null) { Toast.makeText(this, "Najpierw dodaj drukarkę", Toast.LENGTH_LONG).show(); return }
        if (zrodlo == null) return
        val czekaj = AlertDialog.Builder(this).setMessage("Wysyłam do drukarki ${d.nazwa}…").setCancelable(false).show()
        thread {
            var wynik: String
            try {
                if (!Siec.osiagalna(this, d.ip, d.ippPort)) throw Exception("Drukarka ${d.nazwa} nie odpowiada. Sprawdź, czy jesteś w jej sieci Wi-Fi i czy jest włączona.")
                val m = mozliwosci ?: Mozliwosci(Ipp.atrybuty(this, d)).also { mozliwosci = it }
                if (!m.bezposrednio) throw Exception("Ta drukarka nie przyjmuje druku wprost - użyj przycisku „Okno Androida”.")
                val (bmp, wMm, hMm) = odbitka() ?: throw Exception("Brak zdjęcia")
                val media = if (reczny) null else Ipp.dopasujMedia(m.media, wMm, hMm)
                val mediaNazwa: String
                val mediaMm: Pair<Double, Double>
                if (reczny) {
                    mediaNazwa = ""
                    mediaMm = Pair(minOf(wMm, hMm), maxOf(wMm, hMm))
                } else if (media != null) {
                    mediaNazwa = media
                    val wym = Ipp.wymiaryMm(media)!!
                    mediaMm = Pair(minOf(wym.first, wym.second), maxOf(wym.first, wym.second))
                } else {
                    val a4 = Wydruk.mediaA4(m); mediaNazwa = a4.first; mediaMm = a4.second
                }
                val o = Ipp.Opcje(kopie = kopie, kolor = true, media = mediaNazwa, zdjecie = true,
                    recznyPodajnik = reczny,
                    mediaSzerSetneMm = Math.round(mediaMm.first * 100).toInt(),
                    mediaWysSetneMm = Math.round(mediaMm.second * 100).toInt())
                wynik = Wydruk.drukujBezposrednio(this, d, m, 1, mediaNazwa, mediaMm, o, "Zdjecie ${stan.format.nazwa}") { cv, w, h, naMm, _ ->
                    Wydruk.narysujZdjecieNaStronie(cv, w, h, bmp, wMm, hMm, naMm)
                }
                bmp.recycle()
                if (wynik.isEmpty()) wynik = "Wysłano do drukarki ${d.nazwa} ✔"
            } catch (e: OutOfMemoryError) {
                wynik = "Za mało pamięci telefonu na to zdjęcie"
            } catch (e: Exception) {
                wynik = e.message ?: "Błąd drukowania"
            }
            runOnUiThread {
                czekaj.dismiss()
                AlertDialog.Builder(this).setMessage(wynik).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun drukujSystemowo() {
        if (zrodlo == null) return
        val o = odbitka() ?: return
        Wydruk.systemowoZdjecie(this, o.first, o.second, o.third, "Zdjęcie ${stan.format.nazwa}")
    }

    override fun onDestroy() {
        super.onDestroy()
        podglad.zwolnij()
    }

    // ---------------- Podgląd z przesuwaniem i przybliżaniem ----------------

    @SuppressLint("ViewConstructor")
    inner class Podglad(c: Context) : View(c) {
        private var zr: Bitmap? = null
        private var wMm = 100.0
        private var hMm = 150.0
        private var gotowy: Bitmap? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val cien = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        private val ramka = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 205, 215); style = Paint.Style.STROKE; strokeWidth = 2f }
        private var ostX = 0f
        private var ostY = 0f
        private val skalowanie = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                if (stan.tryb != Kompozycja.Tryb.WYPELNIJ) return false
                stan.zoom = (stan.zoom * det.scaleFactor).coerceIn(1f, 4f)
                przerysuj(); return true
            }
        })

        init { setBackgroundColor(Ui.szaryTlo) }

        fun ustaw(z: Bitmap, w: Double, h: Double) { zr = z; wMm = w; hMm = h; przerysuj() }

        fun zwolnij() { gotowy?.recycle(); gotowy = null }

        private fun prostokat(): RectF {
            val m = Ui.dp(context, 14).toFloat()
            val dw = width - 2 * m; val dh = height - 2 * m
            if (dw <= 0 || dh <= 0) return RectF()
            val s = minOf(dw / wMm.toFloat(), dh / hMm.toFloat())
            val w = wMm.toFloat() * s; val h = hMm.toFloat() * s
            val l = (width - w) / 2f; val t = (height - h) / 2f
            return RectF(l, t, l + w, t + h)
        }

        private fun przerysuj() {
            val z = zr ?: return
            val r = prostokat()
            if (r.width() < 2 || r.height() < 2) { post { przerysuj() }; return }
            val n = Kompozycja.renderuj(z, r.width().toInt(), r.height().toInt(), stan)
            gotowy?.recycle()
            gotowy = n
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { przerysuj() }

        override fun onDraw(cv: Canvas) {
            val g = gotowy ?: return
            val r = prostokat()
            cv.drawRect(r.left + 6, r.top + 8, r.right + 6, r.bottom + 8, cien)
            cv.drawBitmap(g, null, r, p)
            cv.drawRect(r, ramka)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            skalowanie.onTouchEvent(e)
            val z = zr ?: return true
            if (stan.tryb != Kompozycja.Tryb.WYPELNIJ) return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { ostX = e.x; ostY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { ostX = e.x; ostY = e.y }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount == 1 && !skalowanie.isInProgress) {
                        val r = prostokat()
                        val (dx, dy) = Kompozycja.nadmiar(z, r.width().toInt(), r.height().toInt(), stan.zoom)
                        if (dx > 1) stan.przesX = (stan.przesX - 2f * (e.x - ostX) / dx).coerceIn(-1f, 1f)
                        if (dy > 1) stan.przesY = (stan.przesY - 2f * (e.y - ostY) / dy).coerceIn(-1f, 1f)
                        przerysuj()
                    }
                    ostX = e.x; ostY = e.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            return true
        }
    }
}
