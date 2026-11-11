package pl.kubasaa.drukarka

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Rozmiar odbitki (krótszy x dłuższy bok w mm). */
data class FormatZdjecia(val nazwa: String, val krotszyMm: Double, val dluzszyMm: Double)

object Kompozycja {

    val formaty = listOf(
        FormatZdjecia("9x13", 89.0, 127.0),
        FormatZdjecia("10x15", 102.0, 152.0),
        FormatZdjecia("13x18", 127.0, 178.0),
        FormatZdjecia("15x21", 152.0, 216.0),
        FormatZdjecia("10x10", 100.0, 100.0),
        FormatZdjecia("A6", 105.0, 148.0),
        FormatZdjecia("A5", 148.0, 210.0),
        FormatZdjecia("A4", 210.0, 297.0)
    )

    enum class Tryb { WYPELNIJ, CALE_ROZMYTE, CALE_BIALE }

    class Stan {
        var format = formaty[1]
        var orientacja = 0        // 0 = auto (jak zdjęcie), 1 = pion, 2 = poziom
        var tryb = Tryb.WYPELNIJ
        var obrot = 0             // dodatkowy obrót zdjęcia: 0/90/180/270
        var przesX = 0f           // -1..1 - które miejsce zdjęcia zostaje przy przycinaniu
        var przesY = 0f
        var zoom = 1f             // 1..4 (tylko tryb WYPELNIJ)

        fun kopia(): Stan = Stan().also {
            it.format = format; it.orientacja = orientacja; it.tryb = tryb; it.obrot = obrot
            it.przesX = przesX; it.przesY = przesY; it.zoom = zoom
        }
    }

    // ---------------- Kilka zdjęć na jednej kartce ----------------

    /** Siatka odbitek na kartce (wszystko w mm). Komórki mają prawdziwy rozmiar odbitki. */
    class Uklad(val kol: Int, val wier: Int, val komW: Double, val komH: Double, val x0: Double, val y0: Double, val odstep: Double) {
        val ile get() = kol * wier
        fun x(k: Int) = x0 + (k % kol) * (komW + odstep)
        fun y(k: Int) = y0 + (k / kol) * (komH + odstep)
    }

    private const val MARGINES = 4.0
    private const val ODSTEP = 2.0

    fun uklad(f: FormatZdjecia, kartkaW: Double, kartkaH: Double, preferujPoziome: Boolean): Uklad {
        fun policz(w: Double, h: Double): Uklad {
            val k = Math.floor((kartkaW - 2 * MARGINES + ODSTEP) / (w + ODSTEP)).toInt()
            val r = Math.floor((kartkaH - 2 * MARGINES + ODSTEP) / (h + ODSTEP)).toInt()
            val x0 = (kartkaW - (k * w + (k - 1) * ODSTEP)) / 2
            val y0 = (kartkaH - (r * h + (r - 1) * ODSTEP)) / 2
            return Uklad(maxOf(k, 0), maxOf(r, 0), w, h, x0, y0, ODSTEP)
        }
        val pion = policz(f.krotszyMm, f.dluzszyMm)
        val poz = policz(f.dluzszyMm, f.krotszyMm)
        if (pion.ile == 0 && poz.ile == 0) {
            // odbitka większa niż kartka - jedna, pomniejszona
            val s = minOf((kartkaW - 2 * MARGINES) / f.krotszyMm, (kartkaH - 2 * MARGINES) / f.dluzszyMm)
            val w = f.krotszyMm * s; val h = f.dluzszyMm * s
            return Uklad(1, 1, w, h, (kartkaW - w) / 2, (kartkaH - h) / 2, 0.0)
        }
        return when {
            pion.ile > poz.ile -> pion
            poz.ile > pion.ile -> poz
            else -> if (preferujPoziome) poz else pion
        }
    }

    /** Komórka narysowana na kartce - do przesuwania kadru palcem w podglądzie. */
    class Komorka(val prost: RectF, val indeks: Int, val obrocona: Boolean, val zdjW: Int, val zdjH: Int)

    /**
     * Rysuje kartkę z wieloma odbitkami. [zdjecie](k) zwraca dla k-tej komórki: (indeks zdjęcia,
     * obrócone zdjęcie, stan) albo null (pusta komórka).
     */
    fun rysujArkusz(
        cv: Canvas, stronaW: Float, stronaH: Float, naMm: Float, f: FormatZdjecia, preferujPoziome: Boolean,
        linie: Boolean, zdjecie: (Int) -> Triple<Int, Bitmap, Stan>?
    ): List<Komorka> {
        cv.drawColor(Color.WHITE)
        val u = uklad(f, stronaW / naMm.toDouble(), stronaH / naMm.toDouble(), preferujPoziome)
        val wynik = mutableListOf<Komorka>()
        val kreska = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(185, 185, 185); style = Paint.Style.STROKE; strokeWidth = maxOf(1f, 0.15f * naMm)
        }
        for (k in 0 until u.ile) {
            val l = (u.x(k) * naMm).toFloat(); val t = (u.y(k) * naMm).toFloat()
            val r = RectF(l, t, l + (u.komW * naMm).toFloat(), t + (u.komH * naMm).toFloat())
            val z = zdjecie(k)
            if (z != null) {
                val (idx, zr, st) = z
                val (wMm, hMm) = wymiary(st, zr.width, zr.height)
                val zdjPoziome = wMm > hMm
                val komPozioma = u.komW > u.komH
                val obrot = zdjPoziome != komPozioma && Math.abs(wMm - hMm) > 0.5
                val pw = (if (obrot) r.height() else r.width()).toInt().coerceAtLeast(1)
                val ph = (if (obrot) r.width() else r.height()).toInt().coerceAtLeast(1)
                val b = renderuj(zr, pw, ph, st)
                cv.save()
                cv.translate(r.centerX(), r.centerY())
                if (obrot) cv.rotate(90f)
                cv.drawBitmap(b, null, RectF(-pw / 2f, -ph / 2f, pw / 2f, ph / 2f), farba)
                cv.restore()
                b.recycle()
                wynik.add(Komorka(r, idx, obrot, pw, ph))
            }
            if (linie) cv.drawRect(r, kreska)
        }
        return wynik
    }

    /** Wymiary odbitki w mm (szer, wys) po uwzględnieniu orientacji. */
    fun wymiary(s: Stan, zdjW: Int, zdjH: Int): Pair<Double, Double> {
        val f = s.format
        val poziomo = when (s.orientacja) {
            1 -> false
            2 -> true
            else -> zdjW > zdjH
        }
        return if (poziomo) Pair(f.dluzszyMm, f.krotszyMm) else Pair(f.krotszyMm, f.dluzszyMm)
    }

    private val farba = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Ile "nadmiaru" (w px wyniku) ma zdjęcie przy wypełnianiu - do przeliczania przesuwania palcem. */
    fun nadmiar(zr: Bitmap, outW: Int, outH: Int, zoom: Float): Pair<Float, Float> {
        val s = maxOf(outW.toFloat() / zr.width, outH.toFloat() / zr.height) * zoom
        return Pair(zr.width * s - outW, zr.height * s - outH)
    }

    /** Maluje gotową odbitkę [outW] x [outH] px. [zr] = zdjęcie już obrócone. */
    fun renderuj(zr: Bitmap, outW: Int, outH: Int, s: Stan): Bitmap {
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.WHITE)
        when (s.tryb) {
            Tryb.WYPELNIJ -> rysujWypelnij(cv, zr, outW, outH, s.przesX, s.przesY, s.zoom)
            Tryb.CALE_ROZMYTE -> {
                val tlo = rozmyteTlo(zr, outW, outH)
                cv.drawBitmap(tlo, null, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), farba)
                tlo.recycle()
                cv.drawColor(Color.argb(45, 0, 0, 0))
                rysujCale(cv, zr, outW, outH)
            }
            Tryb.CALE_BIALE -> rysujCale(cv, zr, outW, outH)
        }
        return out
    }

    private fun rysujWypelnij(cv: Canvas, zr: Bitmap, outW: Int, outH: Int, px: Float, py: Float, zoom: Float) {
        val s = maxOf(outW.toFloat() / zr.width, outH.toFloat() / zr.height) * zoom
        val w = zr.width * s; val h = zr.height * s
        val dx = w - outW; val dy = h - outH
        val l = -dx * (px + 1f) / 2f
        val t = -dy * (py + 1f) / 2f
        cv.drawBitmap(zr, null, RectF(l, t, l + w, t + h), farba)
    }

    private fun rysujCale(cv: Canvas, zr: Bitmap, outW: Int, outH: Int) {
        val s = minOf(outW.toFloat() / zr.width, outH.toFloat() / zr.height)
        val w = zr.width * s; val h = zr.height * s
        val l = (outW - w) / 2f; val t = (outH - h) / 2f
        cv.drawBitmap(zr, null, RectF(l, t, l + w, t + h), farba)
    }

    /** Powiększone i mocno rozmyte zdjęcie jako tło (zamiast białych pasów). */
    private fun rozmyteTlo(zr: Bitmap, outW: Int, outH: Int): Bitmap {
        val mw = maxOf(6, outW / 28); val mh = maxOf(6, outH / 28)
        val male = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
        rysujWypelnij(Canvas(male), zr, mw, mh, 0f, 0f, 1.15f)
        // kilka kroków pomniejsz/powiększ = gładkie rozmycie bez dodatkowych bibliotek
        var b = male
        repeat(2) {
            val wiekszy = Bitmap.createScaledBitmap(b, b.width * 3, b.height * 3, true)
            val mniejszy = Bitmap.createScaledBitmap(wiekszy, b.width, b.height, true)
            wiekszy.recycle()
            if (b != male) b.recycle()
            b = mniejszy
        }
        val sredni = Bitmap.createScaledBitmap(b, mw * 6, mh * 6, true)
        if (b != male) b.recycle()
        male.recycle()
        return sredni
    }
}
