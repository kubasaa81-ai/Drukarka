package pl.kubasaa.drukarka

import android.content.Context
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimalny klient IPP (Internet Printing Protocol) - ten sam protokół, którego używa
 * Mopria / AirPrint. Pozwala odczytać stan drukarki i tonery oraz wysłać wydruk bez
 * systemowego okna drukowania.
 */
object Ipp {

    // znaczniki wartości
    private const val T_INT = 0x21
    private const val T_BOOL = 0x22
    private const val T_ENUM = 0x23
    private const val T_RES = 0x32
    private const val T_RANGE = 0x33
    private const val T_BEGCOL = 0x34
    private const val T_ENDCOL = 0x37
    private const val T_TEXT = 0x41
    private const val T_NAME = 0x42
    private const val T_KEYWORD = 0x44
    private const val T_URI = 0x45
    private const val T_CHARSET = 0x47
    private const val T_LANG = 0x48
    private const val T_MIME = 0x49
    private const val T_MEMBER = 0x4A

    class Atrybuty(val status: Int, val mapa: Map<String, List<Any>>) {
        fun tekst(n: String): String = mapa[n]?.firstOrNull()?.toString() ?: ""
        fun liczba(n: String): Int = (mapa[n]?.firstOrNull() as? Int) ?: -1
        fun teksty(n: String): List<String> = mapa[n]?.map { it.toString() } ?: emptyList()
        fun liczby(n: String): List<Int> = mapa[n]?.mapNotNull { it as? Int } ?: emptyList()
        fun ok() = status in 0..0xFF
    }

    /** Budowanie żądania IPP. */
    class Zadanie(operacja: Int, private val uri: String) {
        private val bufor = ByteArrayOutputStream()
        private val out = DataOutputStream(bufor)

        init {
            out.writeShort(0x0200)      // IPP 2.0
            out.writeShort(operacja)
            out.writeInt(1)             // request-id
            out.writeByte(0x01)         // operation-attributes
            tekst(T_CHARSET, "attributes-charset", "utf-8")
            tekst(T_LANG, "attributes-natural-language", "pl")
            tekst(T_URI, "printer-uri", uri)
        }

        fun tekst(tag: Int, nazwa: String, wartosc: String): Zadanie {
            val n = nazwa.toByteArray(Charsets.UTF_8)
            val w = wartosc.toByteArray(Charsets.UTF_8)
            out.writeByte(tag); out.writeShort(n.size); out.write(n); out.writeShort(w.size); out.write(w)
            return this
        }

        fun slowo(nazwa: String, wartosc: String) = tekst(T_KEYWORD, nazwa, wartosc)

        fun slowa(nazwa: String, wartosci: List<String>): Zadanie {
            wartosci.forEachIndexed { i, w -> tekst(T_KEYWORD, if (i == 0) nazwa else "", w) }
            return this
        }

        fun liczba(nazwa: String, wartosc: Int, tag: Int = T_INT): Zadanie {
            val n = nazwa.toByteArray(Charsets.UTF_8)
            out.writeByte(tag); out.writeShort(n.size); out.write(n); out.writeShort(4); out.writeInt(wartosc)
            return this
        }

        fun grupa(tag: Int): Zadanie { out.writeByte(tag); return this }

        fun poczatekKolekcji(nazwa: String): Zadanie {
            val n = nazwa.toByteArray(Charsets.UTF_8)
            out.writeByte(T_BEGCOL); out.writeShort(n.size); out.write(n); out.writeShort(0)
            return this
        }

        fun koniecKolekcji(): Zadanie {
            out.writeByte(T_ENDCOL); out.writeShort(0); out.writeShort(0)
            return this
        }

        fun czlonek(nazwa: String): Zadanie = tekst(T_MEMBER, "", nazwa)

        fun bajty(): ByteArray {
            out.writeByte(0x03) // end-of-attributes
            out.flush()
            return bufor.toByteArray()
        }
    }

    private fun wyslij(c: Context, d: Drukarka, naglowek: ByteArray, dane: File?): Atrybuty {
        val con = Siec.otworz(c, d.ippUrl())
        con.requestMethod = "POST"
        con.doOutput = true
        con.setRequestProperty("Content-Type", "application/ipp")
        con.readTimeout = 180000
        val dlugosc = naglowek.size.toLong() + (dane?.length() ?: 0L)
        con.setFixedLengthStreamingMode(dlugosc)
        con.outputStream.use { o ->
            o.write(naglowek)
            if (dane != null) FileInputStream(dane).use { it.copyTo(o, 64 * 1024) }
        }
        val kod = con.responseCode
        if (kod != 200) {
            con.disconnect()
            throw Exception("Drukarka odpowiedziała HTTP $kod")
        }
        val wynik = con.inputStream.use { czytaj(it) }
        con.disconnect()
        return wynik
    }

    private fun czytaj(wej: InputStream): Atrybuty {
        val inp = DataInputStream(wej.buffered())
        inp.readShort()                    // wersja
        val status = inp.readUnsignedShort()
        inp.readInt()                      // request-id
        val mapa = LinkedHashMap<String, MutableList<Any>>()
        var ostatnia = ""
        var glebokosc = 0
        while (true) {
            val tag = inp.read()
            if (tag < 0 || tag == 0x03) break
            if (tag <= 0x0F) continue      // początek grupy
            val dn = inp.readUnsignedShort()
            val nazwa = if (dn > 0) String(ByteArray(dn).also { inp.readFully(it) }, Charsets.UTF_8) else ""
            val dw = inp.readUnsignedShort()
            val w = ByteArray(dw).also { inp.readFully(it) }
            if (tag == T_BEGCOL) { glebokosc++; continue }
            if (tag == T_ENDCOL) { if (glebokosc > 0) glebokosc--; continue }
            if (glebokosc > 0) continue    // pomijamy wnętrza kolekcji
            if (nazwa.isNotEmpty()) ostatnia = nazwa
            val wartosc: Any = when (tag) {
                T_INT, T_ENUM -> if (dw == 4) java.nio.ByteBuffer.wrap(w).int else 0
                T_BOOL -> (dw > 0 && w[0].toInt() != 0)
                T_RES -> if (dw == 9) {
                    val b = java.nio.ByteBuffer.wrap(w); "${b.int}x${b.int}"
                } else ""
                T_RANGE -> if (dw == 8) {
                    val b = java.nio.ByteBuffer.wrap(w); "${b.int}-${b.int}"
                } else ""
                else -> String(w, Charsets.UTF_8)
            }
            mapa.getOrPut(ostatnia) { mutableListOf() }.add(wartosc)
        }
        return Atrybuty(status, mapa)
    }

    /** Stan drukarki, tonery i możliwości. Wywoływać poza wątkiem UI. */
    fun atrybuty(c: Context, d: Drukarka): Atrybuty {
        val z = Zadanie(0x000B, d.ippUri())
            .slowa(
                "requested-attributes", listOf(
                    "printer-state", "printer-state-message", "printer-state-reasons",
                    "printer-make-and-model", "printer-name", "printer-info",
                    "marker-names", "marker-levels", "marker-colors", "marker-types",
                    "document-format-supported", "media-supported", "media-ready",
                    "sides-supported", "print-color-mode-supported", "color-supported",
                    "pwg-raster-document-resolution-supported", "pwg-raster-document-type-supported",
                    "pwg-raster-document-sheet-back", "media-source-supported"
                )
            )
        return wyslij(c, d, z.bajty(), null)
    }

    class Opcje(
        var kopie: Int = 1,
        var kolor: Boolean = true,
        var dwustronnie: Boolean = false,
        var media: String = "",               // np. iso_a4_210x297mm
        var recznyPodajnik: Boolean = false,
        var mediaSzerSetneMm: Int = 0,        // do media-col (ręczny podajnik)
        var mediaWysSetneMm: Int = 0,
        var zdjecie: Boolean = false
    )

    /** Wysyła gotowy plik (PDF albo PWG-raster) jako zadanie drukowania. */
    fun drukuj(c: Context, d: Drukarka, plik: File, format: String, nazwaZadania: String, o: Opcje): Atrybuty {
        val z = Zadanie(0x0002, d.ippUri())
            .tekst(T_NAME, "requesting-user-name", "Telefon")
            .tekst(T_NAME, "job-name", nazwaZadania.take(60))
            .tekst(T_MIME, "document-format", format)
            .grupa(0x02) // job-attributes
            .liczba("copies", o.kopie.coerceIn(1, 99))
            .slowo("sides", if (o.dwustronnie) "two-sided-long-edge" else "one-sided")
            .slowo("print-color-mode", if (o.kolor) "color" else "monochrome")
            .slowo("print-scaling", if (o.zdjecie) "none" else "fit")
        if (o.zdjecie) z.liczba("print-quality", 5, T_ENUM) // wysoka
        if (o.recznyPodajnik && o.mediaSzerSetneMm > 0) {
            z.poczatekKolekcji("media-col")
                .czlonek("media-size").poczatekKolekcji("")
                .czlonek("x-dimension").liczba("", o.mediaSzerSetneMm)
                .czlonek("y-dimension").liczba("", o.mediaWysSetneMm)
                .koniecKolekcji()
                .czlonek("media-source").slowo("", "manual")
                .koniecKolekcji()
        } else if (o.media.isNotBlank()) {
            z.slowo("media", o.media)
        }
        return wyslij(c, d, z.bajty(), plik)
    }

    // ---------------- Rozmiary papieru z nazw PWG ----------------

    /** Zwraca (szer, wys) w mm z nazwy PWG, np. "iso_a4_210x297mm" -> 210x297. */
    fun wymiaryMm(nazwa: String): Pair<Double, Double>? {
        val r = Regex("_([0-9.]+)x([0-9.]+)(mm|in)$").find(nazwa) ?: return null
        val a = r.groupValues[1].toDoubleOrNull() ?: return null
        val b = r.groupValues[2].toDoubleOrNull() ?: return null
        val k = if (r.groupValues[3] == "in") 25.4 else 1.0
        return Pair(a * k, b * k)
    }

    /** Szuka w liście drukarki papieru o wymiarach ok. [szerMm] x [wysMm] (orientacja bez znaczenia). */
    fun dopasujMedia(lista: List<String>, szerMm: Double, wysMm: Double, tolMm: Double = 4.0): String? {
        val a = minOf(szerMm, wysMm); val b = maxOf(szerMm, wysMm)
        var najlepsza: String? = null
        var blad = Double.MAX_VALUE
        for (m in lista) {
            if (m.startsWith("custom_")) continue
            val w = wymiaryMm(m) ?: continue
            val x = minOf(w.first, w.second); val y = maxOf(w.first, w.second)
            val e = Math.abs(x - a) + Math.abs(y - b)
            if (Math.abs(x - a) <= tolMm && Math.abs(y - b) <= tolMm && e < blad) { blad = e; najlepsza = m }
        }
        return najlepsza
    }

    // ---------------- PWG-raster (format, który rozumie każda drukarka Mopria/AirPrint) ----------------

    class Raster(private val out: OutputStream, private val dpi: Int, private val kolor: Boolean) {
        private val d = DataOutputStream(out)
        init { d.write("RaS2".toByteArray(Charsets.US_ASCII)) }

        private fun str(s: String, dl: Int) {
            val b = ByteArray(dl)
            val z = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(z, 0, b, 0, minOf(z.size, dl - 1))
            d.write(b)
        }

        /** Dopisuje stronę. Bitmapa musi mieć wymiary strony w pikselach przy [dpi]. */
        fun strona(bmp: Bitmap, nazwaMedia: String, stronyRazem: Int) {
            val w = bmp.width; val h = bmp.height
            val bpp = if (kolor) 3 else 1
            // --- nagłówek 1796 bajtów ---
            str("", 64); str("", 64); str("", 64); str("", 64)     // MediaClass, MediaColor, MediaType, OutputType
            d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0) // 256..275
            d.writeInt(dpi); d.writeInt(dpi)                         // 276 HWResolution
            repeat(4) { d.writeInt(0) }                              // 284 ImagingBoundingBox
            d.writeInt(0); d.writeInt(0); d.writeInt(0)              // 300 InsertSheet, Jog, LeadingEdge
            d.writeInt(0); d.writeInt(0)                             // 312 Margins
            d.writeInt(0); d.writeInt(0); d.writeInt(0)              // 320 ManualFeed, MediaPosition, MediaWeight
            d.writeInt(0); d.writeInt(0)                             // 332 MirrorPrint, NegativePrint
            d.writeInt(1)                                            // 340 NumCopies
            d.writeInt(0); d.writeInt(0)                             // 344 Orientation, OutputFaceUp
            d.writeInt(w * 72 / dpi); d.writeInt(h * 72 / dpi) // 352 PageSize (pt)
            d.writeInt(0); d.writeInt(0); d.writeInt(0)              // 360 Separations, TraySwitch, Tumble
            d.writeInt(w); d.writeInt(h)                             // 372 Width, Height
            d.writeInt(0)                                            // 380 MediaType
            d.writeInt(8); d.writeInt(8 * bpp); d.writeInt(w * bpp)  // 384 BitsPerColor, BitsPerPixel, BytesPerLine
            d.writeInt(0)                                            // 396 ColorOrder (chunky)
            d.writeInt(if (kolor) 19 else 18)                        // 400 ColorSpace sRGB / sGray
            d.writeInt(0); d.writeInt(0); d.writeInt(0); d.writeInt(0) // 404 Compression, RowCount, RowFeed, RowStep
            d.writeInt(bpp)                                          // 420 NumColors
            d.writeInt(0)                                            // 424 BorderlessScalingFactor
            d.writeInt(0); d.writeInt(0)                             // 428 PageSize (float) - nieużywane w PWG
            repeat(4) { d.writeInt(0) }                              // 436 ImagingBBox (float)
            // 452 cupsInteger[16]: TotalPageCount, CrossFeedTransform, FeedTransform, ImageBox L/T/R/B, AlternatePrimary, PrintQuality ...
            d.writeInt(stronyRazem); d.writeInt(1); d.writeInt(1)
            d.writeInt(0); d.writeInt(0); d.writeInt(w); d.writeInt(h)
            d.writeInt(0xFFFFFF); d.writeInt(0)
            repeat(7) { d.writeInt(0) }
            repeat(16) { d.writeInt(0) }                             // 516 cupsReal[16]
            repeat(16) { str("", 64) }                               // 580 cupsString[16]
            str("", 64)                                              // 1604 MarkerType
            str("", 64)                                              // 1668 RenderingIntent
            str(nazwaMedia, 64)                                      // 1732 PageSizeName
            // --- dane z kompresją PackBits (PWG) ---
            val wiersz = IntArray(w)
            val poprz = IntArray(w)
            var maPoprz = false
            var powtorzenia = 0
            val linia = ByteArrayOutputStream(w * bpp + 64)

            fun zapiszLinie(px: IntArray) {
                linia.reset()
                var i = 0
                while (i < w) {
                    // ile kolejnych takich samych pikseli
                    var j = i + 1
                    while (j < w && px[j] == px[i] && j - i < 128) j++
                    val bieg = j - i
                    if (bieg >= 2) {
                        linia.write(bieg - 1); piksel(linia, px[i]); i = j
                    } else {
                        var k = i
                        while (k < w && k - i < 128 && (k + 1 >= w || px[k] != px[k + 1])) k++
                        if (k == i) k = i + 1
                        val dl = k - i
                        if (dl == 1) { linia.write(0); piksel(linia, px[i]) }
                        else { linia.write(257 - dl); for (q in i until k) piksel(linia, px[q]) }
                        i = k
                    }
                }
            }

            fun wypisz() {
                d.writeByte(powtorzenia)
                zapiszLinie(poprz)
                linia.writeTo(d)
            }

            for (y in 0 until h) {
                bmp.getPixels(wiersz, 0, w, 0, y, w, 1)
                if (!kolor) for (x in 0 until w) {
                    val p = wiersz[x]
                    val g = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    wiersz[x] = g
                } else for (x in 0 until w) wiersz[x] = wiersz[x] and 0xFFFFFF
                if (maPoprz && powtorzenia < 255 && wiersz.contentEquals(poprz)) {
                    powtorzenia++
                } else {
                    if (maPoprz) wypisz()
                    System.arraycopy(wiersz, 0, poprz, 0, w)
                    maPoprz = true
                    powtorzenia = 0
                }
            }
            if (maPoprz) wypisz()
            d.flush()
        }

        private fun piksel(o: ByteArrayOutputStream, p: Int) {
            if (kolor) { o.write(p shr 16 and 0xFF); o.write(p shr 8 and 0xFF); o.write(p and 0xFF) }
            else o.write(p and 0xFF)
        }
    }
}
