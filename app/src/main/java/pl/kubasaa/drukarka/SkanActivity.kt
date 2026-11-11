package pl.kubasaa.drukarka

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Skanowanie z szyby lub podajnika, zapis do PDF / JPG, udostępnianie. */
class SkanActivity : AppCompatActivity() {

    private val u = Escl.Ustawienia()
    private var pdf = true
    private var rozmiar = 0 // 0 = A4, 1 = A5, 2 = 10x15
    private var adres: String? = null
    private var moz: Escl.Mozliwosci? = null
    private val strony = mutableListOf<File>()
    private var drukarka: Drukarka? = null

    private lateinit var opcje: LinearLayout
    private lateinit var miniatury: LinearLayout
    private lateinit var info: TextView
    private lateinit var katalog: File

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        drukarka = Drukarki.aktywna(this)
        katalog = File(cacheDir, "skany").apply { mkdirs() }
        katalog.listFiles()?.forEach { it.delete() }

        val gl = LinearLayout(this)
        gl.orientation = LinearLayout.VERTICAL
        gl.addView(Ui.pasekTytulu(this, "Skanuj") { finish() })

        val sc = ScrollView(this)
        val tresc = LinearLayout(this)
        tresc.orientation = LinearLayout.VERTICAL
        tresc.setPadding(Ui.dp(this, 14), Ui.dp(this, 6), Ui.dp(this, 14), Ui.dp(this, 20))
        sc.addView(tresc)
        gl.addView(sc, LinearLayout.LayoutParams(-1, 0, 1f))

        info = TextView(this)
        info.setTextColor(Ui.granat)
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        info.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10))
        info.background = Ui.tlo(Ui.szaryTlo, Ui.dp(this, 12).toFloat())
        tresc.addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SkanActivity, 8) })

        opcje = LinearLayout(this)
        opcje.orientation = LinearLayout.VERTICAL
        tresc.addView(opcje)

        tresc.addView(Ui.naglowek(this, "Zeskanowane strony"))
        val hs = HorizontalScrollView(this)
        miniatury = LinearLayout(this)
        miniatury.orientation = LinearLayout.HORIZONTAL
        hs.addView(miniatury)
        tresc.addView(hs, LinearLayout.LayoutParams(-1, Ui.dp(this, 170)))

        val dol = LinearLayout(this)
        dol.orientation = LinearLayout.VERTICAL
        dol.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 12))
        dol.elevation = Ui.dp(this, 8).toFloat()
        dol.setBackgroundColor(android.graphics.Color.WHITE)
        dol.addView(Ui.przycisk(this, "📠  Skanuj", Ui.pomarancz) { skanuj() }, LinearLayout.LayoutParams(-1, -2))
        val rz = LinearLayout(this)
        rz.orientation = LinearLayout.HORIZONTAL
        rz.addView(Ui.przycisk(this, "Zapisz", Ui.zielony) { zapisz(false) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@SkanActivity, 6) })
        rz.addView(Ui.przycisk(this, "Udostępnij", Ui.niebieski) { zapisz(true) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(this@SkanActivity, 6) })
        rz.addView(Ui.przycisk(this, "Wyczyść", Ui.czerwony) { strony.clear(); katalog.listFiles()?.forEach { it.delete() }; pokazMiniatury() },
            LinearLayout.LayoutParams(0, -2, 1f))
        dol.addView(rz, LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(this@SkanActivity, 8) })
        gl.addView(dol, LinearLayout.LayoutParams(-1, -2))
        setContentView(gl)

        zbudujOpcje()
        pokazMiniatury()
        val d = drukarka
        if (d == null) { info.text = "Brak drukarki - dodaj ją na ekranie głównym."; return }
        info.text = "Łączę ze skanerem ${d.nazwa}…"
        thread {
            var tekst: String
            try {
                val a = Escl.adres(this, d)
                val m = Escl.mozliwosci(this, a)
                adres = a; moz = m
                if (d.esclUrl != a) { d.esclUrl = a; Drukarki.dodajLubZaktualizuj(this, d) }
                tekst = "Skaner: " + m.model.ifBlank { d.nazwa } + " ✔"
            } catch (e: Exception) {
                tekst = (e.message ?: "Skaner nie odpowiada") + "\nSprawdź Wi-Fi i czy urządzenie jest włączone."
            }
            runOnUiThread { info.text = tekst; zbudujOpcje() }
        }
    }

    private fun zbudujOpcje() {
        opcje.removeAllViews()
        opcje.addView(Ui.naglowek(this, "Skąd skanować"))
        val zr = mutableListOf(Escl.Zrodlo.SZYBA to "Szyba")
        if (moz == null || moz!!.podajnik) zr.add(Escl.Zrodlo.PODAJNIK to "Podajnik")
        if (moz == null || moz!!.podajnikDwustronny) zr.add(Escl.Zrodlo.PODAJNIK_2STR to "Podajnik\n2-stronnie")
        opcje.addView(Ui.siatkaChipow(this, zr.map { (z, n) ->
            Ui.chip(this, n, Ui.pomarancz, u.zrodlo == z) { u.zrodlo = z; zbudujOpcje() }
        }, 3))

        opcje.addView(Ui.naglowek(this, "Kolor"))
        opcje.addView(Ui.siatkaChipow(this, listOf(
            Ui.chip(this, "Kolor", Ui.fiolet, u.kolor) { u.kolor = true; zbudujOpcje() },
            Ui.chip(this, "Czarno-białe (szarość)", Ui.fiolet, !u.kolor) { u.kolor = false; zbudujOpcje() }
        ), 2))

        opcje.addView(Ui.naglowek(this, "Jakość"))
        opcje.addView(Ui.siatkaChipow(this, listOf(150 to "150 dpi\n(mały plik)", 300 to "300 dpi\n(zwykle)", 600 to "600 dpi\n(najlepsza)").map { (d, n) ->
            Ui.chip(this, n, Ui.turkus, u.dpi == d) { u.dpi = d; zbudujOpcje() }
        }, 3))

        opcje.addView(Ui.naglowek(this, "Rozmiar"))
        opcje.addView(Ui.siatkaChipow(this, listOf("A4", "A5", "10x15").mapIndexed { i, n ->
            Ui.chip(this, n, Ui.niebieski, rozmiar == i) {
                rozmiar = i
                when (i) {
                    0 -> { u.szerMm = 210.0; u.wysMm = 297.0 }
                    1 -> { u.szerMm = 148.0; u.wysMm = 210.0 }
                    else -> { u.szerMm = 102.0; u.wysMm = 152.0 }
                }
                zbudujOpcje()
            }
        }, 3))

        opcje.addView(Ui.naglowek(this, "Zapisz jako"))
        opcje.addView(Ui.siatkaChipow(this, listOf(
            Ui.chip(this, "PDF (wszystkie strony w 1 pliku)", Ui.zielony, pdf) { pdf = true; zbudujOpcje() },
            Ui.chip(this, "JPG (zdjęcia)", Ui.zielony, !pdf) { pdf = false; zbudujOpcje() }
        ), 2))
    }

    private fun skanuj() {
        val d = drukarka ?: return
        val czekaj = AlertDialog.Builder(this).setMessage("Skanuję…").setCancelable(false).show()
        thread {
            var blad: String? = null
            try {
                val a = adres ?: Escl.adres(this, d).also { adres = it }
                val nowe = Escl.skanuj(this, a, u, katalog) { n -> runOnUiThread { czekaj.setMessage("Skanuję… strona $n") } }
                strony.addAll(nowe)
            } catch (e: Exception) { blad = e.message ?: "Błąd skanowania" }
            runOnUiThread {
                czekaj.dismiss()
                pokazMiniatury()
                if (blad != null) AlertDialog.Builder(this).setMessage(blad).setPositiveButton("OK", null).show()
                else if (u.zrodlo == Escl.Zrodlo.SZYBA) Toast.makeText(this, "Strona ${strony.size} gotowa. Połóż następną i naciśnij Skanuj albo Zapisz.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pokazMiniatury() {
        miniatury.removeAllViews()
        if (strony.isEmpty()) {
            val t = TextView(this)
            t.text = "Jeszcze nic. Połóż dokument na szybie (albo w podajniku) i naciśnij Skanuj."
            t.setTextColor(Ui.szaryTekst)
            t.setPadding(0, Ui.dp(this, 10), 0, 0)
            miniatury.addView(t)
            return
        }
        strony.forEachIndexed { i, f ->
            val iv = ImageView(this)
            val o = BitmapFactory.Options().apply { inSampleSize = 8 }
            iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, o))
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.background = Ui.tlo(Ui.szaryTlo, Ui.dp(this, 8).toFloat())
            iv.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4))
            iv.setOnClickListener {
                AlertDialog.Builder(this).setMessage("Strona ${i + 1}")
                    .setPositiveButton("Usuń stronę") { _, _ -> f.delete(); strony.remove(f); pokazMiniatury() }
                    .setNegativeButton("Zostaw", null).show()
            }
            val lp = LinearLayout.LayoutParams(Ui.dp(this, 115), -1)
            lp.marginEnd = Ui.dp(this, 8)
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.addView(iv, LinearLayout.LayoutParams(-1, 0, 1f))
            val t = TextView(this); t.text = "${i + 1}"; t.gravity = Gravity.CENTER; t.setTextColor(Ui.granat)
            box.addView(t)
            miniatury.addView(box, lp)
        }
    }

    private fun zapisz(udostepnij: Boolean) {
        if (strony.isEmpty()) { Toast.makeText(this, "Najpierw coś zeskanuj", Toast.LENGTH_SHORT).show(); return }
        val czekaj = AlertDialog.Builder(this).setMessage("Zapisuję…").setCancelable(false).show()
        thread {
            var komunikat: String
            val uris = ArrayList<Uri>()
            try {
                val nazwa = "Skan_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
                val wyjscie = File(cacheDir, "wyjscie").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val pliki = mutableListOf<File>()
                if (pdf) {
                    val f = File(wyjscie, "$nazwa.pdf")
                    zrobPdf(f); pliki.add(f)
                } else {
                    strony.forEachIndexed { i, s ->
                        val f = File(wyjscie, if (strony.size == 1) "$nazwa.jpg" else "${nazwa}_${i + 1}.jpg")
                        s.copyTo(f, true); pliki.add(f)
                    }
                }
                if (udostepnij) {
                    pliki.forEach { uris.add(FileProvider.getUriForFile(this, "pl.kubasaa.drukarka.pliki", it)) }
                    komunikat = ""
                } else {
                    val gdzie = zapiszPublicznie(pliki.first())
                    komunikat = "Zapisano: $gdzie"
                }
            } catch (e: Exception) { komunikat = "Nie udało się zapisać: ${e.message}" }
            runOnUiThread {
                czekaj.dismiss()
                if (udostepnij && uris.isNotEmpty()) {
                    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
                    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
                    i.type = if (pdf) "application/pdf" else "image/jpeg"
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(i, "Wyślij skan"))
                } else if (komunikat.isNotEmpty()) {
                    AlertDialog.Builder(this).setMessage(komunikat).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun zrobPdf(f: File) {
        val doc = PdfDocument()
        val wPt = (u.szerMm / 25.4 * 72).toInt(); val hPt = (u.wysMm / 25.4 * 72).toInt()
        strony.forEachIndexed { i, s ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(s.absolutePath, o)
            var prob = 1
            while (maxOf(o.outWidth, o.outHeight) / (prob * 2) >= 3500) prob *= 2
            val b: Bitmap = BitmapFactory.decodeFile(s.absolutePath, BitmapFactory.Options().apply { inSampleSize = prob }) ?: return@forEachIndexed
            val p = doc.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, i + 1).create())
            Wydruk.narysujDopasowane(p.canvas, wPt.toFloat(), hPt.toFloat(), b)
            doc.finishPage(p)
            b.recycle()
        }
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
    }

    /** Zapis do Pobrane/Skany (PDF) albo Obrazy/Skany (JPG). Zwraca opis miejsca. */
    private fun zapiszPublicznie(f: File): String {
        val jpg = f.name.endsWith(".jpg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (jpg) "image/jpeg" else "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (jpg) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Skany")
            }
            val kolekcja = if (jpg) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(kolekcja, cv) ?: throw Exception("brak miejsca")
            contentResolver.openOutputStream(uri)?.use { o: OutputStream -> FileInputStream(f).use { it.copyTo(o) } }
            // przy JPG zapisujemy wszystkie strony
            if (jpg) {
                f.parentFile?.listFiles()?.filter { it != f && it.name.endsWith(".jpg") }?.forEach { inny ->
                    val cv2 = ContentValues(cv).apply { put(MediaStore.MediaColumns.DISPLAY_NAME, inny.name) }
                    contentResolver.insert(kolekcja, cv2)?.let { u2 ->
                        contentResolver.openOutputStream(u2)?.use { o -> FileInputStream(inny).use { it.copyTo(o) } }
                    }
                }
            }
            return if (jpg) "Galeria → Obrazy/Skany" else "Pobrane/Skany/${f.name}"
        } else {
            val kat = File(getExternalFilesDir(null), "Skany").apply { mkdirs() }
            f.parentFile?.listFiles()?.forEach { it.copyTo(File(kat, it.name), true) }
            return kat.absolutePath
        }
    }
}
