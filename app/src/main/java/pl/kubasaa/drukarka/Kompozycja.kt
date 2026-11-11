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
