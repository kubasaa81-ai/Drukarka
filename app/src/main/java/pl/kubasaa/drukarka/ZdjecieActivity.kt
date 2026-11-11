package pl.kubasaa.drukarka

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Edytor odbitek: jedno lub wiele zdjęć, format, orientacja, wypełnienie, przesuwanie kadru,
 * "Kilka na kartce A4" (np. 4 x 9x13) z liniami cięcia, drukowanie.
 */
class ZdjecieActivity : AppCompatActivity() {

    /** Jedno zdjęcie na liście: adres, mała kopia do podglądu i własne ustawienia kadru. */
    class Pozycja(val uri: Uri, val male: Bitmap, val stan: Kompozycja.Stan) {
        private var obrMale: Bitmap? = null
        private var obrKat = 0
        fun maleObrocone(): Bitmap {
            if (stan.obrot == 0) return male
            if (obrMale == null || obrKat != stan.obrot) {
                obrMale?.recycle()
                obrMale = obroc(male, stan.obrot)
                obrKat = stan.obrot
            }
            return obrMale!!
        }
    }

    private val pozycje = mutableListOf<Pozycja>()
    private var aktualny = 0
    private val wspolny = Kompozycja.Stan()   // format / ułożenie / wypełnienie dla wszystkich
    private var naKartce = false               // false = każde zdjęcie na swojej odbitce
    private var powtarzaj = true
    private var linie = true
    private var kopie = 1
    private var reczny = false
    private var mozliwosci: Mozliwosci? = null
    private var drukarka: Drukarka? = null

    private lateinit var podglad: Podglad
    private lateinit var info: TextView
    private lateinit var opcje: LinearLayout
    private lateinit var miniatury: LinearLayout
    private lateinit var wskazowka: TextView
    private lateinit var tekstKopii: TextView

    private val wybor = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
        if (uris.isNotEmpty()) dodaj(uris) else if (pozycje.isEmpty()) finish()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        drukarka = Drukarki.aktywna(this)

        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.addView(Ui.pasekTytulu(this, "Drukuj zdjęcia") { finish() })

        val sc = ScrollView(this)
        val tresc = LinearLayout(this)
        tresc.orientation = LinearLayout.VERTICAL
        tresc.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 24))
        sc.addView(tresc)
        gl.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        podglad = Podglad(this)
        tresc.addView(podglad, LinearLayout.LayoutParams(-1, Ui.dp(this, 360)))

        wskazowka = TextView(this)
        wskazowka.setTextColor(Ui.szaryTekst)
        wskazowka.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        wskazowka.gravity = Gravity.CENTER
        wskazowka.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4))
        tresc.addView(wskazowka)

        val hs = HorizontalScrollView(this)
        miniatury = LinearLayout(this)
        miniatury.orientation = LinearLayout.HORIZONTAL
        hs.addView(miniatury)
        tresc.addView(hs, LinearLayout.LayoutParams(-1, Ui.dp(this, 76)))

        opcje = LinearLayout(this)
        opcje.orientation = LinearLayout.VERTICAL
        tresc.addView(opcje)

        info = TextView(this)
        info.setTextColor(Ui.granat)
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        info.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        info.background = Ui.tlo(Ui.szaryTlo, Ui.dp(this, 12).toFloat())
        tresc.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ZdjecieActivity, 12) })

        // dolny pasek - zawsze widoczny
        val dol = LinearLayout(this)
        dol.orientation = LinearLayout.VERTICAL
        dol.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 12))
        dol.setBackgroundColor(Color.WHITE)
        dol.elevation = Ui.dp(this, 8).toFloat()
        dol.addView(Ui.przycisk(this, "🖨  Drukuj" + (drukarka?.let { " na: ${it.nazwa}" } ?: ""), Ui.zielony) { drukuj() },
            LinearLayout.LayoutParams(-1, -2))
        val rzad = LinearLayout(this)
        rzad.orientation = LinearLayout.HORIZONTAL
        rzad.addView(Ui.przycisk(this, "＋ Dodaj zdjęcia", Ui.niebieski) { wyborZdjec() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@ZdjecieActivity, 8) })
        rzad.addView(Ui.przycisk(this, "Okno Androida", Ui.granat) { drukujSystemowo() },
            LinearLayout.LayoutParams(0, -2, 1f))
        dol.addView(rzad, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@ZdjecieActivity, 8) })
        gl.addView(dol, LinearLayout.LayoutParams(-1, -2))

        setContentView(gl)
        zbudujOpcje()
        pokazMiniatury()

        val startowe = mutableListOf<Uri>()
        intent.data?.let { startowe.add(it) }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { if (it !in startowe) startowe.add(it) } }
        if (startowe.isNotEmpty()) dodaj(startowe) else wyborZdjec()

        drukarka?.let { d ->
            thread {
                val m = try { Mozliwosci(Ipp.atrybuty(this, d)) } catch (e: Exception) { null }
                runOnUiThread { mozliwosci = m; zbudujOpcje(); odswiezInfo() }
            }
        }
    }

    private fun wyborZdjec() {
        wybor.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun dodaj(uris: List<Uri>) {
        info.text = "Wczytuję zdjęcia…"
        thread {
            val nowe = mutableListOf<Pozycja>()
            for (u in uris) {
                val b = try { Wydruk.wczytajObraz(this, u, 1200) } catch (e: Throwable) { null } ?: continue
                nowe.add(Pozycja(u, b, wspolny.kopia().also { it.obrot = 0; it.przesX = 0f; it.przesY = 0f; it.zoom = 1f }))
            }
            runOnUiThread {
                if (nowe.isEmpty()) {
                    Toast.makeText(this, "Nie udało się otworzyć zdjęć", Toast.LENGTH_LONG).show()
                    if (pozycje.isEmpty()) finish()
                    return@runOnUiThread
                }
                pozycje.addAll(nowe)
                aktualny = pozycje.size - nowe.size
                if (pozycje.size > 1 && !naKartce && wspolny.format.dluzszyMm <= 152) {
                    // kilka małych zdjęć - od razu proponujemy kartkę A4
                    naKartce = true
                }
                zbudujOpcje(); pokazMiniatury(); odswiez()
            }
        }
    }

    // ---------------- Opcje ----------------

    private fun ustawWszystkim(blok: (Kompozycja.Stan) -> Unit) {
        blok(wspolny)
        pozycje.forEach { blok(it.stan) }
    }

    private fun biezacy(): Pozycja? = pozycje.getOrNull(aktualny.coerceIn(0, maxOf(0, pozycje.size - 1)))

    private fun zbudujOpcje() {
        opcje.removeAllViews()
        opcje.addView(Ui.naglowek(this, "Papier"))
        opcje.addView(Ui.siatkaChipow(this, listOf(
            Ui.chip(this, "Jedna odbitka\nna kartce", Ui.czerwony, !naKartce) { naKartce = false; zbudujOpcje(); odswiez() },
            Ui.chip(this, "Kilka zdjęć\nna kartce A4", Ui.czerwony, naKartce) { naKartce = true; zbudujOpcje(); odswiez() }
        ), 2))

        opcje.addView(Ui.naglowek(this, "Rozmiar odbitki"))
        opcje.addView(Ui.siatkaChipow(this, Kompozycja.formaty.map { f ->
            Ui.chip(this, f.nazwa, Ui.niebieski, wspolny.format == f) {
                ustawWszystkim { it.format = f; it.przesX = 0f; it.przesY = 0f }
                zbudujOpcje(); odswiez()
            }
        }, 4))

        if (naKartce) {
            val dodatki = Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Powtarzaj, by zapełnić kartkę", Ui.czerwony, powtarzaj) { powtarzaj = !powtarzaj; zbudujOpcje(); odswiez() },
                Ui.chip(this, "Linie cięcia", Ui.czerwony, linie) { linie = !linie; zbudujOpcje(); odswiez() }
            ), 2)
            dodatki.setPadding(0, Ui.dp(this, 8), 0, 0)
            opcje.addView(dodatki)
        }

        opcje.addView(Ui.naglowek(this, "Ułożenie"))
        val ul = listOf("Jak zdjęcie", "Pionowo", "Poziomo")
        val rzadUl = mutableListOf<View>()
        ul.forEachIndexed { i, t ->
            rzadUl.add(Ui.chip(this, t, Ui.fiolet, wspolny.orientacja == i) {
                ustawWszystkim { it.orientacja = i; it.przesX = 0f; it.przesY = 0f }
                zbudujOpcje(); odswiez()
            })
        }
        rzadUl.add(Ui.chip(this, "⟳ 90°", Ui.pomarancz, false) {
            biezacy()?.stan?.let { it.obrot = (it.obrot + 90) % 360; it.przesX = 0f; it.przesY = 0f }
            pokazMiniatury(); odswiez()
        })
        opcje.addView(Ui.siatkaChipow(this, rzadUl, 4))

        opcje.addView(Ui.naglowek(this, "Wypełnienie odbitki"))
        val tryby = listOf(
            Kompozycja.Tryb.WYPELNIJ to "Na całość\n(przytnij brzegi)",
            Kompozycja.Tryb.CALE_ROZMYTE to "Całe zdjęcie\n+ rozmyte tło",
            Kompozycja.Tryb.CALE_BIALE to "Całe zdjęcie\n+ białe tło"
        )
        opcje.addView(Ui.siatkaChipow(this, tryby.map { (t, n) ->
            Ui.chip(this, n, Ui.turkus, wspolny.tryb == t) { ustawWszystkim { it.tryb = t }; zbudujOpcje(); odswiez() }
        }, 3))

        opcje.addView(Ui.naglowek(this, if (naKartce) "Liczba kopii (kartek)" else "Liczba odbitek każdego zdjęcia"))
        val k = LinearLayout(this)
        k.orientation = LinearLayout.HORIZONTAL
        k.gravity = Gravity.CENTER_VERTICAL
        k.addView(Ui.przycisk(this, "−", Ui.czerwony) { if (kopie > 1) kopie--; tekstKopii.text = "$kopie"; odswiezInfo() },
            LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        tekstKopii = TextView(this)
        tekstKopii.text = "$kopie"
        tekstKopii.gravity = Gravity.CENTER
        tekstKopii.setTextColor(Ui.granat)
        tekstKopii.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        k.addView(tekstKopii, LinearLayout.LayoutParams(Ui.dp(this, 70), -2))
        k.addView(Ui.przycisk(this, "+", Ui.zielony) { if (kopie < 50) kopie++; tekstKopii.text = "$kopie"; odswiezInfo() },
            LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        opcje.addView(k)

        if (!naKartce && mozliwosci?.recznyPodajnik == true) {
            opcje.addView(Ui.naglowek(this, "Skąd papier"))
            opcje.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Taca (automatycznie)", Ui.granat, !reczny) { reczny = false; zbudujOpcje(); odswiezInfo() },
                Ui.chip(this, "Szczelina ręczna", Ui.granat, reczny) { reczny = true; zbudujOpcje(); odswiezInfo() }
            ), 2))
        }
        wskazowka.text = if (naKartce) "Dotknij zdjęcia na kartce i przesuń palcem, żeby wybrać kadr. Dwa palce = przybliżenie."
        else "Przesuń palcem, żeby wybrać kadr. Rozsuń dwa palce, żeby przybliżyć."
    }

    private fun pokazMiniatury() {
        miniatury.removeAllViews()
        pozycje.forEachIndexed { i, p ->
            val iv = ImageView(this)
            iv.setImageBitmap(p.maleObrocone())
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val wybrany = i == aktualny
            iv.background = Ui.tlo(Color.WHITE, Ui.dp(this, 8).toFloat(), Ui.dp(this, if (wybrany) 3 else 1), if (wybrany) Ui.niebieski else Color.LTGRAY)
            iv.setPadding(Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3), Ui.dp(this, 3))
            iv.setOnClickListener { aktualny = i; pokazMiniatury(); odswiez() }
            iv.setOnLongClickListener {
                AlertDialog.Builder(this).setMessage("Usunąć to zdjęcie z wydruku?")
                    .setPositiveButton("Usuń") { _, _ ->
                        pozycje.removeAt(i)
                        if (aktualny >= pozycje.size) aktualny = maxOf(0, pozycje.size - 1)
                        pokazMiniatury(); odswiez()
                        if (pozycje.isEmpty()) wyborZdjec()
                    }.setNegativeButton("Zostaw", null).show()
                true
            }
            val lp = LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68))
            lp.marginEnd = Ui.dp(this, 8)
            miniatury.addView(iv, lp)
        }
        val plus = Ui.chip(this, "＋", Ui.niebieski, true) { wyborZdjec() }
        plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        miniatury.addView(plus, LinearLayout.LayoutParams(Ui.dp(this, 68), Ui.dp(this, 68)))
    }

    // ---------------- Układ stron ----------------

    private fun preferujPoziome(): Boolean {
        if (wspolny.orientacja == 2) return true
        if (wspolny.orientacja == 1) return false
        val poz = pozycje.count { it.maleObrocone().width > it.maleObrocone().height }
        return poz * 2 > pozycje.size
    }

    private fun naKartke(): Int = Kompozycja.uklad(wspolny.format, 210.0, 297.0, preferujPoziome()).ile

    private fun ileStron(): Int {
        if (pozycje.isEmpty()) return 0
        if (!naKartce) return pozycje.size
        val n = maxOf(1, naKartke())
        return (pozycje.size + n - 1) / n
    }

    /** Które zdjęcie trafia na stronę [s] do komórki [k] (null = pusta). */
    private fun zdjecieKomorki(s: Int, k: Int, n: Int): Int? {
        val i = s * n + k
        if (i < pozycje.size) return i
        return if (powtarzaj && pozycje.isNotEmpty()) i % pozycje.size else null
    }

    private fun stronaBiezacego(): Int = if (naKartce) aktualny / maxOf(1, naKartke()) else aktualny

    private fun odswiez() {
        if (pozycje.isEmpty()) return
        if (naKartce) {
            podglad.ustaw(210.0, 297.0)
        } else {
            val p = biezacy() ?: return
            val z = p.maleObrocone()
            val (wMm, hMm) = Kompozycja.wymiary(p.stan, z.width, z.height)
            podglad.ustaw(wMm, hMm)
        }
        odswiezInfo()
    }

    /** Rysuje podgląd (w pikselach podglądu). */
    private fun rysujPodglad(w: Int, h: Int): Pair<Bitmap, List<Kompozycja.Komorka>>? {
        if (pozycje.isEmpty()) return null
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        if (naKartce) {
            val n = naKartke()
            val s = stronaBiezacego()
            val kom = Kompozycja.rysujArkusz(cv, w.toFloat(), h.toFloat(), (w / 210.0).toFloat(), wspolny.format, preferujPoziome(), linie) { k ->
                zdjecieKomorki(s, k, n)?.let { i -> Triple(i, pozycje[i].maleObrocone(), pozycje[i].stan) }
            }
            return Pair(b, kom)
        }
        val p = biezacy() ?: return null
        val o = Kompozycja.renderuj(p.maleObrocone(), w, h, p.stan)
        cv.drawBitmap(o, 0f, 0f, null)
        o.recycle()
        return Pair(b, listOf(Kompozycja.Komorka(RectF(0f, 0f, w.toFloat(), h.toFloat()), aktualny, false, w, h)))
    }

    private fun odswiezInfo() {
        if (pozycje.isEmpty()) { info.text = "Wybierz zdjęcia."; return }
        val f = wspolny.format
        val sb = StringBuilder()
        if (naKartce) {
            val n = naKartke()
            val st = ileStron()
            sb.append("Na kartce A4 mieści się $n × ${f.nazwa}. Zdjęć: ${pozycje.size}, kartek: $st")
            if (kopie > 1) sb.append(" (× $kopie kopie)")
            if (st > 1) sb.append("\nPodgląd pokazuje kartkę ${stronaBiezacego() + 1} - wybierz miniaturę, żeby zobaczyć inną.")
            if (pozycje.size < n && !powtarzaj) sb.append("\nPuste miejsca zostaną białe - dodaj zdjęcia albo włącz „Powtarzaj”.")
            sb.append("\nOdbitki mają prawdziwy rozmiar ${f.nazwa} - wytnij po liniach.")
        } else {
            val p = biezacy()!!
            val z = p.maleObrocone()
            val (wMm, hMm) = Kompozycja.wymiary(p.stan, z.width, z.height)
            sb.append("Odbitka ${f.nazwa}, ").append(if (wMm > hMm) "poziomo" else if (wMm < hMm) "pionowo" else "kwadrat")
            if (pozycje.size > 1) sb.append(" · zdjęć: ${pozycje.size} (każde na osobnej kartce)")
            val m = mozliwosci
            if (m != null && m.bezposrednio && !reczny) {
                if (Ipp.dopasujMedia(m.media, wMm, hMm) != null) sb.append("\nDrukarka zna papier ${f.nazwa} - włóż go do tacy lub szczeliny.")
                else sb.append("\nDrukarka nie ma papieru ${f.nazwa} - wydruk na A4 w prawdziwym rozmiarze. Kilka zdjęć? Wybierz „Kilka zdjęć na kartce A4”.")
            } else if (reczny) sb.append("\nPapier ${f.nazwa} włóż do szczeliny ręcznej.")
        }
        val d = drukarka
        val m = mozliwosci
        if (d == null) sb.append("\nBrak wybranej drukarki - dodaj ją na ekranie głównym.")
        else if (m == null) sb.append("\nDrukarka ${d.nazwa}: sprawdzam…")
        else if (!m.bezposrednio) sb.append("\nTa drukarka nie przyjmuje druku wprost - użyj „Okno Androida”.")
        sb.append("\nDrukarka laserowa zostawia ok. 4 mm marginesu przy krawędziach kartki.")
        info.text = sb.toString()
    }

    // ---------------- Druk ----------------

    /** Pełne zdjęcie (do wydruku) - już obrócone. */
    private fun duze(p: Pozycja, maks: Int): Bitmap {
        val b = Wydruk.wczytajObraz(this, p.uri, maks) ?: throw Exception("Nie mogę otworzyć zdjęcia")
        return Wydruk.obroc(b, p.stan.obrot)
    }

    /** Rysuje stronę [s] wydruku na płótnie (jednostki: [naMm] na mm). */
    private fun rysujStrone(cv: Canvas, w: Float, h: Float, naMm: Float, s: Int) {
        if (naKartce) {
            val n = Kompozycja.uklad(wspolny.format, w / naMm.toDouble(), h / naMm.toDouble(), preferujPoziome()).ile
            val zaladowane = HashMap<Int, Bitmap>()
            Kompozycja.rysujArkusz(cv, w, h, naMm, wspolny.format, preferujPoziome(), linie) { k ->
                zdjecieKomorki(s, k, n)?.let { i ->
                    val b = zaladowane.getOrPut(i) { duze(pozycje[i], 2000) }
                    Triple(i, b, pozycje[i].stan)
                }
            }
            zaladowane.values.forEach { it.recycle() }
        } else {
            val p = pozycje[s]
            val z = duze(p, 3000)
            val (wMm, hMm) = Kompozycja.wymiary(p.stan, z.width, z.height)
            val dpi = if (maxOf(wMm, hMm) > 220) 250 else 300
            val o = Kompozycja.renderuj(z, Math.round(wMm / 25.4 * dpi).toInt(), Math.round(hMm / 25.4 * dpi).toInt(), p.stan)
            z.recycle()
            Wydruk.narysujZdjecieNaStronie(cv, w, h, o, wMm, hMm, naMm)
            o.recycle()
        }
    }

    private fun drukuj() {
        val d = drukarka
        if (d == null) { Toast.makeText(this, "Najpierw dodaj drukarkę", Toast.LENGTH_LONG).show(); return }
        if (pozycje.isEmpty()) return
        val czekaj = AlertDialog.Builder(this).setMessage("Przygotowuję i wysyłam do ${d.nazwa}…").setCancelable(false).show()
        thread {
            var wynik: String
            try {
                if (!Siec.osiagalna(this, d.ip, d.ippPort)) throw Exception("Drukarka ${d.nazwa} nie odpowiada. Sprawdź, czy jesteś w jej sieci Wi-Fi i czy jest włączona.")
                val m = mozliwosci ?: Mozliwosci(Ipp.atrybuty(this, d)).also { mozliwosci = it }
                if (!m.bezposrednio) throw Exception("Ta drukarka nie przyjmuje druku wprost - użyj przycisku „Okno Androida”.")
                val mediaNazwa: String
                val mediaMm: Pair<Double, Double>
                val f = wspolny.format
                val media = if (naKartce || reczny) null else Ipp.dopasujMedia(m.media, f.krotszyMm, f.dluzszyMm)
                if (naKartce) {
                    val a4 = Wydruk.mediaA4(m); mediaNazwa = a4.first; mediaMm = a4.second
                } else if (reczny) {
                    mediaNazwa = ""; mediaMm = Pair(f.krotszyMm, f.dluzszyMm)
                } else if (media != null) {
                    mediaNazwa = media
                    val wym = Ipp.wymiaryMm(media)!!
                    mediaMm = Pair(minOf(wym.first, wym.second), maxOf(wym.first, wym.second))
                } else {
                    val a4 = Wydruk.mediaA4(m); mediaNazwa = a4.first; mediaMm = a4.second
                }
                val o = Ipp.Opcje(kopie = kopie, kolor = true, media = mediaNazwa, zdjecie = true,
                    recznyPodajnik = reczny && !naKartce,
                    mediaSzerSetneMm = Math.round(mediaMm.first * 100).toInt(),
                    mediaWysSetneMm = Math.round(mediaMm.second * 100).toInt())
                wynik = Wydruk.drukujBezposrednio(this, d, m, ileStron(), mediaNazwa, mediaMm, o,
                    if (naKartce) "Zdjecia ${f.nazwa} na A4" else "Zdjecie ${f.nazwa}") { cv, w, h, naMm, s ->
                    rysujStrone(cv, w, h, naMm, s)
                }
                if (wynik.isEmpty()) wynik = "Wysłano do drukarki ${d.nazwa} ✔"
            } catch (e: OutOfMemoryError) {
                wynik = "Za mało pamięci telefonu - spróbuj mniej zdjęć naraz"
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
        if (pozycje.isEmpty()) return
        try {
            if (!naKartce && pozycje.size == 1) {
                val p = pozycje[0]
                val z = duze(p, 3000)
                val (wMm, hMm) = Kompozycja.wymiary(p.stan, z.width, z.height)
                val o = Kompozycja.renderuj(z, Math.round(wMm / 25.4 * 300).toInt(), Math.round(hMm / 25.4 * 300).toInt(), p.stan)
                z.recycle()
                Wydruk.systemowoZdjecie(this, o, wMm, hMm, "Zdjęcie ${wspolny.format.nazwa}")
            } else {
                Wydruk.systemowoStrony(this, ileStron(), "Zdjęcia ${wspolny.format.nazwa}") { cv, w, h, naMm, s ->
                    rysujStrone(cv, w, h, naMm, s)
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Błąd: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        podglad.zwolnij()
    }

    companion object {
        fun obroc(b: Bitmap, st: Int): Bitmap {
            val m = Matrix().apply { postRotate(st.toFloat()) }
            return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        }
    }

    // ---------------- Podgląd z przesuwaniem i przybliżaniem ----------------

    @SuppressLint("ViewConstructor")
    inner class Podglad(c: Context) : View(c) {
        private var wMm = 100.0
        private var hMm = 150.0
        private var gotowy: Bitmap? = null
        private var komorki: List<Kompozycja.Komorka> = emptyList()
        private var przesuwana: Kompozycja.Komorka? = null
        private val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private val cien = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) }
        private val ramka = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(200, 205, 215); style = Paint.Style.STROKE; strokeWidth = 2f }
        private val zaznacz = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.niebieski; style = Paint.Style.STROKE; strokeWidth = 5f }
        private var ostX = 0f
        private var ostY = 0f
        private val skalowanie = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val poz = przesuwana?.let { pozycje.getOrNull(it.indeks) } ?: biezacy() ?: return false
                if (poz.stan.tryb != Kompozycja.Tryb.WYPELNIJ) return false
                poz.stan.zoom = (poz.stan.zoom * det.scaleFactor).coerceIn(1f, 4f)
                przerysuj(); return true
            }
        })

        init { setBackgroundColor(Ui.szaryTlo) }

        fun ustaw(w: Double, h: Double) { wMm = w; hMm = h; przerysuj() }

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
            val r = prostokat()
            if (r.width() < 2 || r.height() < 2) { if (pozycje.isNotEmpty()) post { przerysuj() }; return }
            val wynik = rysujPodglad(r.width().toInt(), r.height().toInt()) ?: return
            gotowy?.recycle()
            gotowy = wynik.first
            komorki = wynik.second
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { przerysuj() }

        override fun onDraw(cv: Canvas) {
            val g = gotowy ?: return
            val r = prostokat()
            cv.drawRect(r.left + 6, r.top + 8, r.right + 6, r.bottom + 8, cien)
            cv.drawBitmap(g, null, r, p)
            cv.drawRect(r, ramka)
            if (naKartce) {
                komorki.firstOrNull { it.indeks == aktualny }?.let { k ->
                    cv.drawRect(r.left + k.prost.left, r.top + k.prost.top, r.left + k.prost.right, r.top + k.prost.bottom, zaznacz)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            skalowanie.onTouchEvent(e)
            val r = prostokat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ostX = e.x; ostY = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val x = e.x - r.left; val y = e.y - r.top
                    przesuwana = komorki.firstOrNull { it.prost.contains(x, y) }
                    przesuwana?.let { k ->
                        if (k.indeks != aktualny) { aktualny = k.indeks; pokazMiniatury(); invalidate() }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> { ostX = e.x; ostY = e.y }
                MotionEvent.ACTION_MOVE -> {
                    val k = przesuwana
                    val poz = k?.let { pozycje.getOrNull(it.indeks) }
                    if (k != null && poz != null && e.pointerCount == 1 && !skalowanie.isInProgress &&
                        poz.stan.tryb == Kompozycja.Tryb.WYPELNIJ) {
                        val ruchX = e.x - ostX; val ruchY = e.y - ostY
                        // w obróconej komórce osie zdjęcia są zamienione
                        val zx = if (k.obrocona) ruchY else ruchX
                        val zy = if (k.obrocona) -ruchX else ruchY
                        val (dx, dy) = Kompozycja.nadmiar(poz.maleObrocone(), k.zdjW, k.zdjH, poz.stan.zoom)
                        if (dx > 1) poz.stan.przesX = (poz.stan.przesX - 2f * zx / dx).coerceIn(-1f, 1f)
                        if (dy > 1) poz.stan.przesY = (poz.stan.przesY - 2f * zy / dy).coerceIn(-1f, 1f)
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
