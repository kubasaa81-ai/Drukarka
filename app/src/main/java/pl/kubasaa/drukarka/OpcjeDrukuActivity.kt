package pl.kubasaa.drukarka

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintJobInfo
import android.print.PrinterInfo
import android.printservice.PrintService
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * Ekran "Więcej opcji" z systemowego okna drukowania (gdy wybrana jest drukarka z tej aplikacji).
 * Kolorowe przyciski zamiast list Androida; wynik wraca do okna drukowania.
 */
class OpcjeDrukuActivity : AppCompatActivity() {

    private var info: PrintJobInfo? = null
    private var kopie = 1
    private var kolor = true
    private var duplex = PrintAttributes.DUPLEX_MODE_NONE
    private var media: PrintAttributes.MediaSize? = null
    private var wysoka = false
    private var reczny = false
    private var mediaLista: List<PrintAttributes.MediaSize> = emptyList()
    private var maKolor = true
    private var maDuplex = true
    private var maReczny = false
    private var typ = ""
    private var typy: List<String> = emptyList()
    private lateinit var tresc: LinearLayout

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        info = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(PrintService.EXTRA_PRINT_JOB_INFO, PrintJobInfo::class.java)
        else intent.getParcelableExtra<PrintJobInfo>(PrintService.EXTRA_PRINT_JOB_INFO)
        val drukarka: PrinterInfo? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(PrintService.EXTRA_PRINTER_INFO, PrinterInfo::class.java)
        else intent.getParcelableExtra<PrinterInfo>(PrintService.EXTRA_PRINTER_INFO)

        val i = info
        if (i != null) {
            kopie = i.copies.coerceAtLeast(1)
            val a = i.attributes
            kolor = a.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME
            duplex = if (a.duplexMode == 0) PrintAttributes.DUPLEX_MODE_NONE else a.duplexMode
            media = a.mediaSize
        }
        drukarka?.capabilities?.let { c ->
            mediaLista = c.mediaSizes
            maKolor = (c.colorModes and PrintAttributes.COLOR_MODE_COLOR) != 0
            maDuplex = (c.duplexModes and PrintAttributes.DUPLEX_MODE_LONG_EDGE) != 0
        }
        if (!maKolor) kolor = false

        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.addView(Ui.pasekTytulu(this, "Opcje: " + (drukarka?.name ?: "Drukarka")) { anuluj() })
        val sc = ScrollView(this)
        tresc = LinearLayout(this)
        tresc.orientation = LinearLayout.VERTICAL
        tresc.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 20))
        sc.addView(tresc)
        gl.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        val dol = LinearLayout(this)
        dol.orientation = LinearLayout.HORIZONTAL
        dol.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 12))
        dol.setBackgroundColor(Color.WHITE)
        dol.elevation = Ui.dp(this, 8).toFloat()
        dol.addView(Ui.przycisk(this, "Anuluj", Ui.czerwony) { anuluj() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@OpcjeDrukuActivity, 8) })
        dol.addView(Ui.przycisk(this, "✔  Gotowe", Ui.zielony) { gotowe() }, LinearLayout.LayoutParams(0, -2, 2f))
        gl.addView(dol, LinearLayout.LayoutParams(-1, -2))
        setContentView(gl)
        buduj()

        // szczelina ręczna - sprawdzamy w drukarce
        val ip = drukarka?.id?.localId
        val d = Drukarki.lista(this).firstOrNull { it.ip == ip }
        if (d != null) thread {
            val m = try { Mozliwosci(Ipp.atrybuty(this, d)) } catch (e: Exception) { null }
            if (m != null) runOnUiThread { maReczny = m.recznyPodajnik; typy = m.typyPapieru; buduj() }
        }
    }

    private fun buduj() {
        tresc.removeAllViews()
        tresc.addView(Ui.naglowek(this, "Liczba kopii"))
        val k = LinearLayout(this)
        k.orientation = LinearLayout.HORIZONTAL
        k.gravity = Gravity.CENTER_VERTICAL
        k.addView(Ui.przycisk(this, "−", Ui.czerwony) { if (kopie > 1) kopie--; buduj() }, LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        val t = TextView(this)
        t.text = "$kopie"; t.gravity = Gravity.CENTER; t.setTextColor(Ui.granat)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        k.addView(t, LinearLayout.LayoutParams(Ui.dp(this, 70), -2))
        k.addView(Ui.przycisk(this, "+", Ui.zielony) { if (kopie < 99) kopie++; buduj() }, LinearLayout.LayoutParams(Ui.dp(this, 64), -2))
        tresc.addView(k)

        if (maKolor) {
            tresc.addView(Ui.naglowek(this, "Kolor"))
            tresc.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Kolor", Ui.fiolet, kolor) { kolor = true; buduj() },
                Ui.chip(this, "Czarno-biały", Ui.fiolet, !kolor) { kolor = false; buduj() }
            ), 2))
        }

        if (maDuplex && !reczny) {
            tresc.addView(Ui.naglowek(this, "Strony kartki"))
            tresc.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Jednostronnie", Ui.turkus, duplex == PrintAttributes.DUPLEX_MODE_NONE) { duplex = PrintAttributes.DUPLEX_MODE_NONE; buduj() },
                Ui.chip(this, "Dwustronnie\n(jak książka)", Ui.turkus, duplex == PrintAttributes.DUPLEX_MODE_LONG_EDGE) { duplex = PrintAttributes.DUPLEX_MODE_LONG_EDGE; buduj() },
                Ui.chip(this, "Dwustronnie\n(jak kalendarz)", Ui.turkus, duplex == PrintAttributes.DUPLEX_MODE_SHORT_EDGE) { duplex = PrintAttributes.DUPLEX_MODE_SHORT_EDGE; buduj() }
            ), 3))
        }

        tresc.addView(Ui.naglowek(this, "Jakość"))
        tresc.addView(Ui.siatkaChipow(this, listOf(
            Ui.chip(this, "Normalna\n(szybciej, mniej tonera)", Ui.niebieski, !wysoka) { wysoka = false; buduj() },
            Ui.chip(this, "Wysoka\n(zdjęcia, grafika)", Ui.niebieski, wysoka) { wysoka = true; buduj() }
        ), 2))

        if (mediaLista.isNotEmpty()) {
            tresc.addView(Ui.naglowek(this, "Papier"))
            // najpierw popularne rozmiary
            val wazne = listOf("iso_a4", "iso_a5", "iso_a6", "na_index-4x6", "oe_photo-l", "na_5x7", "na_letter", "iso_b5", "jis_b5")
            val posortowane = mediaLista.sortedBy { m -> wazne.indexOfFirst { m.id.startsWith(it) }.let { if (it < 0) 99 else it } }
            tresc.addView(Ui.siatkaChipow(this, posortowane.map { m ->
                Ui.chip(this, m.getLabel(packageManager), Ui.pomarancz, media?.id == m.id) { media = m; buduj() }
            }, 3))
        }

        tresc.addView(Ui.wyborTypuPapieru(this, typy, typ) { typ = it; buduj() })

        if (maReczny) {
            tresc.addView(Ui.naglowek(this, "Skąd papier"))
            tresc.addView(Ui.siatkaChipow(this, listOf(
                Ui.chip(this, "Taca (automatycznie)", Ui.granat, !reczny) { reczny = false; buduj() },
                Ui.chip(this, "Szczelina ręczna\n(koperty, karton, zdjęcia)", Ui.granat, reczny) { reczny = true; buduj() }
            ), 2))
        }

        val uw = TextView(this)
        uw.text = "Po „Gotowe” wrócisz do okna drukowania - naciśnij tam żółty przycisk drukowania."
        uw.setTextColor(Ui.szaryTekst)
        uw.setPadding(0, Ui.dp(this, 16), 0, 0)
        tresc.addView(uw)
    }

    private fun gotowe() {
        val i = info
        if (i == null) { anuluj(); return }
        val stare = i.attributes
        val ab = PrintAttributes.Builder()
        (media ?: stare.mediaSize)?.let { m ->
            // zachowujemy orientację wybraną w oknie drukowania
            val pion = stare.mediaSize?.isPortrait ?: true
            ab.setMediaSize(if (pion) m.asPortrait() else m.asLandscape())
        }
        stare.resolution?.let { ab.setResolution(it) }
        stare.minMargins?.let { ab.setMinMargins(it) }
        ab.setColorMode(if (kolor) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
        ab.setDuplexMode(if (reczny) PrintAttributes.DUPLEX_MODE_NONE else duplex)
        val nowe = PrintJobInfo.Builder(i)
        nowe.setCopies(kopie)
        nowe.setAttributes(ab.build())
        nowe.putAdvancedOption("jakosc", if (wysoka) 5 else 4)
        nowe.putAdvancedOption("reczny", if (reczny) 1 else 0)
        nowe.putAdvancedOption("typ", typ)
        setResult(RESULT_OK, Intent().putExtra(PrintService.EXTRA_PRINT_JOB_INFO, nowe.build()))
        finish()
    }

    private fun anuluj() {
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { anuluj() }
}
