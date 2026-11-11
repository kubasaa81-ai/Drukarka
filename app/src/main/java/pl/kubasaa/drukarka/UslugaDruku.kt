package pl.kubasaa.drukarka

import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * Usługa drukowania Androida: dzięki niej drukarki z aplikacji "Drukarka" pojawiają się
 * po naciśnięciu "Drukuj" w każdej innej aplikacji (galeria, PDF, Chrome, Gmail...).
 */
class UslugaDruku : PrintService() {

    private val glowny = Handler(Looper.getMainLooper())

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession = Sesja()

    override fun onRequestCancelPrintJob(job: PrintJob) {
        if (!job.isCompleted && !job.isFailed) job.cancel()
    }

    override fun onPrintJobQueued(job: PrintJob) {
        if (!job.isQueued) return
        job.start()
        val info = job.info
        val ip = info.printerId?.localId ?: ""
        val d = Drukarki.lista(this).firstOrNull { it.ip == ip }
        if (d == null) { job.fail("Nie znam tej drukarki"); return }
        val atr = info.attributes
        val dane = job.document.data
        if (dane == null) { job.fail("Brak danych do druku"); return }
        val kopie = info.copies.coerceAtLeast(1)
        // opcje z ekranu "Więcej opcji" (OpcjeDrukuActivity)
        val wysoka = job.hasAdvancedOption("jakosc") && job.getAdvancedIntOption("jakosc") == 5
        val reczny = job.hasAdvancedOption("reczny") && job.getAdvancedIntOption("reczny") == 1
        thread {
            var blad: String? = null
            val plik = File(cacheDir, "usluga_${System.currentTimeMillis()}.pdf")
            try {
                FileInputStream(dane.fileDescriptor).use { i -> FileOutputStream(plik).use { o -> i.copyTo(o) } }
                try { dane.close() } catch (e: Exception) {}
                if (!Siec.osiagalna(this, d.ip, d.ippPort))
                    throw Exception("Drukarka ${d.nazwa} nie odpowiada - sprawdź, czy jesteś w sieci Wi-Fi" +
                            (if (d.ssid.isNotBlank()) " „${d.ssid}”" else " drukarki"))
                val m = Mozliwosci(Ipp.atrybuty(this, d))
                if (!m.bezposrednio) throw Exception("Drukarka nie przyjmuje druku wprost")
                val ms = atr.mediaSize
                val (mediaNazwa, mediaMm) = if (ms != null) {
                    val a = minOf(ms.widthMils, ms.heightMils) * 0.0254
                    val b = maxOf(ms.widthMils, ms.heightMils) * 0.0254
                    val n = if (m.media.contains(ms.id)) ms.id else (Ipp.dopasujMedia(m.media, a, b) ?: "")
                    if (n.isNotBlank()) Pair(n, Pair(a, b)) else Wydruk.mediaA4(m)
                } else Wydruk.mediaA4(m)
                val o = Ipp.Opcje(
                    kopie = kopie,
                    kolor = atr.colorMode != PrintAttributes.COLOR_MODE_MONOCHROME,
                    dwustronnie = atr.duplexMode == PrintAttributes.DUPLEX_MODE_LONG_EDGE ||
                            atr.duplexMode == PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                    media = mediaNazwa,
                    wysokaJakosc = wysoka,
                    recznyPodajnik = reczny && m.recznyPodajnik,
                    mediaSzerSetneMm = Math.round(mediaMm.first * 100).toInt(),
                    mediaWysSetneMm = Math.round(mediaMm.second * 100).toInt()
                )
                val w = Wydruk.drukujPdf(this, d, m, plik, o, info.label, mediaNazwa, mediaMm)
                if (w.isNotEmpty()) blad = w
            } catch (e: OutOfMemoryError) {
                blad = "Za mało pamięci telefonu"
            } catch (e: Exception) {
                blad = e.message ?: "Błąd drukowania"
            } finally {
                plik.delete()
            }
            glowny.post {
                if (blad == null) job.complete() else job.fail(blad)
            }
        }
    }

    // ---------------- Lista drukarek dla okna drukowania ----------------

    inner class Sesja : PrinterDiscoverySession() {

        private fun id(d: Drukarka): PrinterId = generatePrinterId(d.ip)

        private fun podstawowe(d: Drukarka, status: Int = PrinterInfo.STATUS_IDLE): PrinterInfo =
            PrinterInfo.Builder(id(d), d.nazwa, status).setDescription("${d.ip} · Drukarka").build()

        override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
            addPrinters(Drukarki.lista(this@UslugaDruku).map { podstawowe(it) })
        }

        override fun onStopPrinterDiscovery() {}

        override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}

        override fun onStartPrinterStateTracking(printerId: PrinterId) {
            val d = Drukarki.lista(this@UslugaDruku).firstOrNull { it.ip == printerId.localId } ?: return
            thread {
                val c = this@UslugaDruku
                var info: PrinterInfo
                try {
                    if (!Siec.osiagalna(c, d.ip, d.ippPort)) throw Exception()
                    val m = Mozliwosci(Ipp.atrybuty(c, d))
                    info = PrinterInfo.Builder(printerId, d.nazwa, PrinterInfo.STATUS_IDLE)
                        .setDescription("${d.ip} · Drukarka")
                        .setCapabilities(mozliwosciAndroid(printerId, m))
                        .build()
                } catch (e: Exception) {
                    info = PrinterInfo.Builder(printerId, d.nazwa, PrinterInfo.STATUS_UNAVAILABLE)
                        .setDescription("Niedostępna - sprawdź Wi-Fi" + (if (d.ssid.isNotBlank()) " (${d.ssid})" else ""))
                        .build()
                }
                glowny.post { if (!isDestroyed) addPrinters(listOf(info)) }
            }
        }

        override fun onStopPrinterStateTracking(printerId: PrinterId) {}

        override fun onDestroy() {}
    }

    private fun mozliwosciAndroid(pid: PrinterId, m: Mozliwosci): PrinterCapabilitiesInfo {
        val b = PrinterCapabilitiesInfo.Builder(pid)
        val dodane = HashSet<String>()
        var maDomyslny = false
        for (n in m.media) {
            if (n.startsWith("custom_") || !dodane.add(n)) continue
            val w = Ipp.wymiaryMm(n) ?: continue
            val a = minOf(w.first, w.second); val c = maxOf(w.first, w.second)
            val domyslny = !maDomyslny && n.startsWith("iso_a4")
            if (domyslny) maDomyslny = true
            b.addMediaSize(PrintAttributes.MediaSize(n, etykieta(n, a, c), (a / 0.0254).toInt(), (c / 0.0254).toInt()), domyslny)
        }
        if (dodane.isEmpty() || !maDomyslny) {
            b.addMediaSize(PrintAttributes.MediaSize("iso_a4_210x297mm", "A4", 8268, 11693), true)
        }
        b.addResolution(PrintAttributes.Resolution("r${m.rasterDpi}", "${m.rasterDpi} dpi", m.rasterDpi, m.rasterDpi), true)
        val kolory = if (m.kolor) PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME else PrintAttributes.COLOR_MODE_MONOCHROME
        b.setColorModes(kolory, if (m.kolor) PrintAttributes.COLOR_MODE_COLOR else PrintAttributes.COLOR_MODE_MONOCHROME)
        if (m.dwustronnie) {
            b.setDuplexModes(PrintAttributes.DUPLEX_MODE_NONE or PrintAttributes.DUPLEX_MODE_LONG_EDGE or PrintAttributes.DUPLEX_MODE_SHORT_EDGE,
                PrintAttributes.DUPLEX_MODE_NONE)
        }
        b.setMinMargins(PrintAttributes.Margins(0, 0, 0, 0))
        return b.build()
    }

    /** Czytelne nazwy papieru: A4, 10x15 cm (4x6") itd. */
    private fun etykieta(n: String, a: Double, b: Double): String {
        val r = Regex("^[a-z]+_([a-z0-9.-]+)_").find(n)?.groupValues?.get(1) ?: ""
        val cm = "${Math.round(a / 10)}x${Math.round(b / 10)} cm"
        return when {
            n.startsWith("iso_") || n.startsWith("jis_") -> r.uppercase().replace("-", " ")
            r == "index-4x6" -> "10x15 cm (4x6\")"
            r == "5x7" -> "13x18 cm (5x7\")"
            r == "photo-l" -> "9x13 cm (L)"
            r == "letter" -> "Letter"
            r == "legal" -> "Legal"
            else -> "$cm ($r)"
        }
    }
}
