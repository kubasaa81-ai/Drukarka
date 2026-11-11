package pl.kubasaa.drukarka

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.URL

/**
 * Skanowanie przez eSCL (AirScan / Mopria Scan) - protokół, który obsługują nowe skanery Brother.
 */
object Escl {

    class Mozliwosci(val szyba: Boolean, val podajnik: Boolean, val podajnikDwustronny: Boolean, val model: String)

    /** Działający adres skanera (sprawdza zapisany, potem http i https). */
    fun adres(c: Context, d: Drukarka): String {
        val kandydaci = linkedSetOf(d.esclLubDomyslny(), "http://${d.ip}/eSCL", "https://${d.ip}/eSCL", "http://${d.ip}:8080/eSCL")
        var blad: Exception? = null
        for (a in kandydaci) {
            try {
                val con = Siec.otworz(c, "$a/ScannerCapabilities")
                con.connectTimeout = 4000
                con.readTimeout = 8000
                val kod = con.responseCode
                con.disconnect()
                if (kod == 200) return a
            } catch (e: Exception) { blad = e }
        }
        throw Exception("Skaner nie odpowiada" + (blad?.message?.let { " ($it)" } ?: ""))
    }

    fun mozliwosci(c: Context, adres: String): Mozliwosci {
        val con = Siec.otworz(c, "$adres/ScannerCapabilities")
        val x = con.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        con.disconnect()
        val model = Regex("<pwg:MakeAndModel>([^<]*)<").find(x)?.groupValues?.get(1) ?: ""
        return Mozliwosci(
            x.contains("Platen"),
            x.contains(":Adf>") || x.contains("AdfSimplexInputCaps"),
            x.contains("AdfDuplexInputCaps"),
            model
        )
    }

    enum class Zrodlo { SZYBA, PODAJNIK, PODAJNIK_2STR }

    class Ustawienia(
        var zrodlo: Zrodlo = Zrodlo.SZYBA,
        var kolor: Boolean = true,
        var dpi: Int = 300,
        var szerMm: Double = 210.0,
        var wysMm: Double = 297.0
    )

    private fun xml(u: Ustawienia): String {
        val w = Math.round(u.szerMm / 25.4 * 300).toInt()
        val h = Math.round(u.wysMm / 25.4 * 300).toInt()
        val zrodlo = if (u.zrodlo == Zrodlo.SZYBA) "Platen" else "Feeder"
        val duplex = if (u.zrodlo == Zrodlo.PODAJNIK_2STR) "<scan:Duplex>true</scan:Duplex>" else ""
        val tryb = if (u.kolor) "RGB24" else "Grayscale8"
        return """<?xml version="1.0" encoding="UTF-8"?>
<scan:ScanSettings xmlns:scan="http://schemas.hp.com/imaging/escl/2011/05/03" xmlns:pwg="http://www.pwg.org/schemas/2010/12/sm">
<pwg:Version>2.0</pwg:Version>
<pwg:ScanRegions>
<pwg:ScanRegion>
<pwg:Height>$h</pwg:Height>
<pwg:ContentRegionUnits>escl:ThreeHundredthsOfInches</pwg:ContentRegionUnits>
<pwg:Width>$w</pwg:Width>
<pwg:XOffset>0</pwg:XOffset>
<pwg:YOffset>0</pwg:YOffset>
</pwg:ScanRegion>
</pwg:ScanRegions>
<pwg:InputSource>$zrodlo</pwg:InputSource>
<pwg:DocumentFormat>image/jpeg</pwg:DocumentFormat>
<scan:DocumentFormatExt>image/jpeg</scan:DocumentFormatExt>
<scan:ColorMode>$tryb</scan:ColorMode>
<scan:XResolution>${u.dpi}</scan:XResolution>
<scan:YResolution>${u.dpi}</scan:YResolution>
$duplex
</scan:ScanSettings>"""
    }

    /**
     * Skanuje. Każda zeskanowana strona (JPEG) trafia do pliku w [katalog]; [postep] dostaje numer strony.
     * Zwraca listę plików.
     */
    fun skanuj(c: Context, adres: String, u: Ustawienia, katalog: File, postep: (Int) -> Unit): List<File> {
        val con = Siec.otworz(c, "$adres/ScanJobs")
        con.requestMethod = "POST"
        con.doOutput = true
        con.setRequestProperty("Content-Type", "text/xml")
        val body = xml(u).toByteArray(Charsets.UTF_8)
        con.setFixedLengthStreamingMode(body.size)
        con.outputStream.use { it.write(body) }
        val kod = con.responseCode
        var miejsce = con.getHeaderField("Location") ?: ""
        con.disconnect()
        if (kod == 503) throw Exception("Skaner jest zajęty - spróbuj za chwilę")
        if (kod == 409) throw Exception("Brak papieru w podajniku albo skaner zajęty")
        if (kod !in 200..299 || miejsce.isBlank()) throw Exception("Skaner odrzucił zadanie (HTTP $kod)")
        // Location bywa pełnym adresem albo samą ścieżką
        if (!miejsce.startsWith("http")) {
            val baza = URL(adres)
            val port = if (baza.port > 0) ":${baza.port}" else ""
            miejsce = "${baza.protocol}://${baza.host}$port" + (if (miejsce.startsWith("/")) miejsce else "/$miejsce")
        }
        miejsce = miejsce.trimEnd('/')

        val pliki = mutableListOf<File>()
        var proby = 0
        while (true) {
            val g = Siec.otworz(c, "$miejsce/NextDocument")
            g.readTimeout = 120000
            val k = g.responseCode
            if (k == 200) {
                val f = File(katalog, "skan_${System.currentTimeMillis()}_${pliki.size + 1}.jpg")
                g.inputStream.use { i -> FileOutputStream(f).use { o -> i.copyTo(o, 64 * 1024) } }
                g.disconnect()
                if (f.length() > 0) { pliki.add(f); postep(pliki.size) }
                proby = 0
                if (u.zrodlo == Zrodlo.SZYBA) break
            } else if (k == 503 && proby < 60) {
                g.disconnect(); proby++; Thread.sleep(1000)
            } else {
                g.disconnect()
                break // 404 = koniec stron
            }
        }
        try { Siec.otworz(c, miejsce).apply { requestMethod = "DELETE"; responseCode; disconnect() } } catch (e: Exception) {}
        if (pliki.isEmpty()) throw Exception("Nic nie zeskanowano (sprawdź, czy dokument leży na szybie / w podajniku)")
        return pliki
    }
}
