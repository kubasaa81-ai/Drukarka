package pl.kubasaa.drukarka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.print.pdf.PrintedPdfDocument
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Co umie drukarka (z IPP). */
class Mozliwosci(a: Ipp.Atrybuty) {
    val formaty = a.teksty("document-format-supported")
    val pdf = formaty.any { it.equals("application/pdf", true) }
    val raster = formaty.any { it.equals("image/pwg-raster", true) }
    val media = a.teksty("media-supported")
    val mediaWlozone = a.teksty("media-ready")
    val kolor = a.teksty("print-color-mode-supported").any { it.contains("color") } ||
            a.mapa["color-supported"]?.firstOrNull() == true
    val dwustronnie = a.teksty("sides-supported").any { it.startsWith("two-sided") }
    val recznyPodajnik = a.teksty("media-source-supported").any { it == "manual" || it.startsWith("by-pass") }
    val rasterDpi: Int = run {
        val r = a.teksty("pwg-raster-document-resolution-supported").mapNotNull { it.substringBefore("x").toIntOrNull() }
        when {
            r.contains(300) -> 300
            r.isNotEmpty() -> r.filter { it <= 600 }.maxOrNull() ?: r.first()
            else -> 300
        }
    }
    val rasterKolor = a.teksty("pwg-raster-document-type-supported").let { t -> t.isEmpty() || t.any { it.startsWith("srgb") } }
    val bezposrednio = pdf || raster
}

object Wydruk {

    /** Wczytuje obraz z uwzględnieniem obrotu z EXIF, pomniejszony tak, by dłuższy bok <= maks. */
    fun wczytajObraz(c: Context, uri: Uri, maks: Int): Bitmap? {
        val cr = c.contentResolver
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0) return null
        var prob = 1
        while (maxOf(o.outWidth, o.outHeight) / (prob * 2) >= maks) prob *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = prob }
        var b = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        val obrot = try {
            cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) { 0 }
        if (obrot != 0) b = obroc(b, obrot)
        val dl = maxOf(b.width, b.height)
        if (dl > maks) {
            val s = maks.toFloat() / dl
            val n = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
            if (n != b) b.recycle()
            b = n
        }
        return b
    }

    fun obroc(b: Bitmap, stopnie: Int): Bitmap {
        if (stopnie % 360 == 0) return b
        val m = Matrix().apply { postRotate(stopnie.toFloat()) }
        val n = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (n != b) b.recycle()
        return n
    }

    private fun px(mm: Double, dpi: Int) = Math.round(mm / 25.4 * dpi).toInt().coerceAtLeast(1)

    /**
     * Rysuje zdjęcie (gotowe, w docelowych proporcjach) na stronie papieru w rzeczywistym rozmiarze.
     * Jeśli orientacja papieru jest inna niż zdjęcia - obraca o 90°. Gdy zdjęcie większe niż kartka - zmniejsza.
     */
    fun narysujZdjecieNaStronie(cv: Canvas, stronaW: Float, stronaH: Float, zdj: Bitmap, zdjWmm: Double, zdjHmm: Double, jednostkaNaMm: Float) {
        cv.drawColor(Color.WHITE)
        val zdjPoziome = zdjWmm > zdjHmm
        val stronaPozioma = stronaW > stronaH
        var w = (zdjWmm * jednostkaNaMm).toFloat()
        var h = (zdjHmm * jednostkaNaMm).toFloat()
        val obrot = zdjPoziome != stronaPozioma && Math.abs(zdjWmm - zdjHmm) > 0.5
        val (dw, dh) = if (obrot) Pair(h, w) else Pair(w, h)
        val skala = minOf(1f, stronaW / dw, stronaH / dh)
        w *= skala; h *= skala
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        cv.save()
        cv.translate(stronaW / 2f, stronaH / 2f)
        if (obrot) cv.rotate(90f)
        cv.drawBitmap(zdj, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), p)
        cv.restore()
    }

    /** Rysuje stronę dokumentu (bitmapę) na kartce: dopasowanie + obrót, gdy strona pozioma. */
    fun narysujDopasowane(cv: Canvas, stronaW: Float, stronaH: Float, b: Bitmap) {
        cv.drawColor(Color.WHITE)
        val obrot = (b.width > b.height) != (stronaW > stronaH)
        val bw = if (obrot) b.height.toFloat() else b.width.toFloat()
        val bh = if (obrot) b.width.toFloat() else b.height.toFloat()
        val s = minOf(stronaW / bw, stronaH / bh)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        cv.save()
        cv.translate(stronaW / 2f, stronaH / 2f)
        if (obrot) cv.rotate(90f)
        val w = b.width * s; val h = b.height * s
        cv.drawBitmap(b, null, RectF(-w / 2f, -h / 2f, w / 2f, h / 2f), p)
        cv.restore()
    }

    // ---------------- Druk bezpośredni (IPP) ----------------

    /**
     * Drukuje [ileStron] stron. [rysuj] maluje i-tą stronę na płótnie o wymiarach w pikselach (raster)
     * albo w punktach (PDF). [mediaNazwa] / [mediaMm] - papier.
     */
    fun drukujBezposrednio(
        c: Context, d: Drukarka, m: Mozliwosci, ileStron: Int, mediaNazwa: String, mediaMm: Pair<Double, Double>,
        opcje: Ipp.Opcje, nazwa: String, rysuj: (Canvas, Float, Float, Float, Int) -> Unit
    ): String {
        val plik = File(c.cacheDir, "wydruk.bin")
        val format: String
        if (m.raster) {
            format = "image/pwg-raster"
            val dpi = m.rasterDpi
            val kolor = opcje.kolor && m.rasterKolor
            val w = px(mediaMm.first, dpi); val h = px(mediaMm.second, dpi)
            FileOutputStream(plik).buffered(256 * 1024).use { os ->
                val r = Ipp.Raster(os, dpi, kolor)
                for (i in 0 until ileStron) {
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val cv = Canvas(b)
                    rysuj(cv, w.toFloat(), h.toFloat(), (dpi / 25.4).toFloat(), i)
                    r.strona(b, mediaNazwa, ileStron)
                    b.recycle()
                }
            }
        } else {
            format = "application/pdf"
            val pw = (mediaMm.first / 25.4 * 72).toInt(); val ph = (mediaMm.second / 25.4 * 72).toInt()
            val doc = PdfDocument()
            for (i in 0 until ileStron) {
                val s = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                rysuj(s.canvas, pw.toFloat(), ph.toFloat(), (72 / 25.4).toFloat(), i)
                doc.finishPage(s)
            }
            FileOutputStream(plik).use { doc.writeTo(it) }
            doc.close()
        }
        val a = Ipp.drukuj(c, d, plik, format, nazwa, opcje)
        plik.delete()
        return if (a.ok()) "" else "Drukarka odrzuciła zadanie (kod 0x" + Integer.toHexString(a.status) + ")"
    }

    /** Wysyła plik PDF: wprost (jeśli drukarka zna PDF) albo zamienia strony na raster. */
    fun drukujPdf(c: Context, d: Drukarka, m: Mozliwosci, pdf: File, opcje: Ipp.Opcje, nazwa: String, mediaNazwa: String, mediaMm: Pair<Double, Double>): String {
        if (m.pdf) {
            val a = Ipp.drukuj(c, d, pdf, "application/pdf", nazwa, opcje)
            return if (a.ok()) "" else "Drukarka odrzuciła zadanie (kod 0x" + Integer.toHexString(a.status) + ")"
        }
        val fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val r = PdfRenderer(fd)
        try {
            return drukujBezposrednio(c, d, m, r.pageCount, mediaNazwa, mediaMm, opcje, nazwa) { cv, w, h, _, i ->
                val s = r.openPage(i)
                // strona PDF w rozdzielczości kartki
                val skala = minOf(w / s.width, h / s.height).let { if ((s.width > s.height) != (w > h)) minOf(w / s.height, h / s.width) else it }
                val bw = (s.width * skala).toInt().coerceAtLeast(1)
                val bh = (s.height * skala).toInt().coerceAtLeast(1)
                val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
                b.eraseColor(Color.WHITE)
                s.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                s.close()
                narysujDopasowane(cv, w, h, b)
                b.recycle()
            }
        } finally {
            r.close(); fd.close()
        }
    }

    // ---------------- Druk przez systemowe okno Androida (wtyczka Brother / Mopria) ----------------

    fun systemowoPlikPdf(c: Context, pdf: File, nazwa: String) {
        val pm = c.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(nazwa, object : PrintDocumentAdapter() {
            override fun onLayout(stare: PrintAttributes?, nowe: PrintAttributes, cs: CancellationSignal?, cb: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?) {
                if (cs?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(nazwa).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), stare != nowe)
            }
            override fun onWrite(strony: Array<out PageRange>?, cel: ParcelFileDescriptor, cs: CancellationSignal?, cb: PrintDocumentAdapter.WriteResultCallback) {
                try {
                    FileInputStream(pdf).use { i -> FileOutputStream(cel.fileDescriptor).use { o -> i.copyTo(o) } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }

    /** Zdjęcie przez okno systemowe: na wybranym tam papierze w rzeczywistym rozmiarze. */
    fun systemowoZdjecie(c: Context, zdj: Bitmap, wMm: Double, hMm: Double, nazwa: String) {
        val pm = c.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val a = PrintAttributes.Builder()
            .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .setMediaSize(mediaAndroid(minOf(wMm, hMm), maxOf(wMm, hMm)))
            .build()
        pm.print(nazwa, object : PrintDocumentAdapter() {
            var atr: PrintAttributes? = null
            override fun onLayout(stare: PrintAttributes?, nowe: PrintAttributes, cs: CancellationSignal?, cb: PrintDocumentAdapter.LayoutResultCallback, extras: Bundle?) {
                atr = nowe
                cb.onLayoutFinished(PrintDocumentInfo.Builder(nazwa).setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO).setPageCount(1).build(), true)
            }
            override fun onWrite(strony: Array<out PageRange>?, cel: ParcelFileDescriptor, cs: CancellationSignal?, cb: PrintDocumentAdapter.WriteResultCallback) {
                try {
                    val doc = PrintedPdfDocument(c, atr ?: a)
                    val s = doc.startPage(0)
                    val w = s.info.pageWidth.toFloat(); val h = s.info.pageHeight.toFloat()
                    narysujZdjecieNaStronie(s.canvas, w, h, zdj, wMm, hMm, (72 / 25.4).toFloat())
                    doc.finishPage(s)
                    FileOutputStream(cel.fileDescriptor).use { doc.writeTo(it) }
                    doc.close()
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, a)
    }

    private fun mediaAndroid(a: Double, b: Double): PrintAttributes.MediaSize {
        fun blisko(x: Double, y: Double) = Math.abs(a - x) < 3 && Math.abs(b - y) < 3
        return when {
            blisko(101.6, 152.4) || blisko(100.0, 150.0) -> PrintAttributes.MediaSize.NA_INDEX_4X6
            blisko(105.0, 148.0) -> PrintAttributes.MediaSize.ISO_A6
            blisko(148.0, 210.0) -> PrintAttributes.MediaSize.ISO_A5
            blisko(210.0, 297.0) -> PrintAttributes.MediaSize.ISO_A4
            else -> PrintAttributes.MediaSize("zdj_${a.toInt()}x${b.toInt()}", "${(a / 10).toInt()}x${(b / 10).toInt()} cm",
                (a / 25.4 * 1000).toInt(), (b / 25.4 * 1000).toInt())
        }
    }

    /** Zamienia obraz (dokument-zdjęcie) na PDF A4. */
    fun obrazDoPdf(c: Context, uri: Uri): File? {
        val b = wczytajObraz(c, uri, 3000) ?: return null
        val doc = PdfDocument()
        val s = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        narysujDopasowane(s.canvas, 595f, 842f, b)
        doc.finishPage(s)
        val f = File(c.cacheDir, "obraz.pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close(); b.recycle()
        return f
    }

    /** Kopiuje wybrany plik do cache (PdfRenderer i wysyłka potrzebują pliku). */
    fun skopiuj(c: Context, uri: Uri, nazwa: String): File {
        val f = File(c.cacheDir, nazwa)
        c.contentResolver.openInputStream(uri)!!.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
        return f
    }

    /** A4 dla dokumentów - nazwa z listy drukarki (jeśli jest) albo standardowa. */
    fun mediaA4(m: Mozliwosci?): Pair<String, Pair<Double, Double>> {
        val n = m?.let { Ipp.dopasujMedia(it.media, 210.0, 297.0) } ?: "iso_a4_210x297mm"
        return Pair(n, Pair(210.0, 297.0))
    }
}
