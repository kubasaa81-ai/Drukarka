package pl.kubasaa.drukarka

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Zapisana drukarka. esclUrl = adres skanera (np. http://192.168.50.5:80/eSCL), puste = domyślny. */
data class Drukarka(
    var nazwa: String,
    var ip: String,
    var ssid: String = "",
    var ippPort: Int = 631,
    var ippSciezka: String = "/ipp/print",
    var esclUrl: String = ""
) {
    fun ippUrl() = "http://$ip:$ippPort$ippSciezka"
    fun ippUri() = "ipp://$ip:$ippPort$ippSciezka"
    fun stronaUstawien() = "http://$ip/"
    fun esclLubDomyslny() = esclUrl.ifBlank { "http://$ip/eSCL" }

    fun json(): JSONObject = JSONObject()
        .put("nazwa", nazwa).put("ip", ip).put("ssid", ssid)
        .put("ippPort", ippPort).put("ippSciezka", ippSciezka).put("esclUrl", esclUrl)

    companion object {
        fun zJson(o: JSONObject) = Drukarka(
            o.optString("nazwa"), o.optString("ip"), o.optString("ssid"),
            o.optInt("ippPort", 631), o.optString("ippSciezka", "/ipp/print"), o.optString("esclUrl")
        )
    }
}

object Drukarki {
    private fun prefs(c: Context) = c.getSharedPreferences("drukarki", Context.MODE_PRIVATE)

    fun lista(c: Context): MutableList<Drukarka> {
        val s = prefs(c).getString("lista", "[]") ?: "[]"
        val a = try { JSONArray(s) } catch (e: Exception) { JSONArray() }
        val wynik = mutableListOf<Drukarka>()
        for (i in 0 until a.length()) wynik.add(Drukarka.zJson(a.getJSONObject(i)))
        return wynik
    }

    fun zapisz(c: Context, lista: List<Drukarka>) {
        val a = JSONArray()
        lista.forEach { a.put(it.json()) }
        prefs(c).edit().putString("lista", a.toString()).apply()
    }

    fun aktywnyIndeks(c: Context): Int = prefs(c).getInt("aktywna", 0)
    fun ustawAktywna(c: Context, i: Int) = prefs(c).edit().putInt("aktywna", i).apply()

    fun aktywna(c: Context): Drukarka? {
        val l = lista(c)
        if (l.isEmpty()) return null
        return l[aktywnyIndeks(c).coerceIn(0, l.size - 1)]
    }

    /** Dodaje albo aktualizuje (po IP). Zwraca indeks. */
    fun dodajLubZaktualizuj(c: Context, d: Drukarka): Int {
        val l = lista(c)
        val i = l.indexOfFirst { it.ip == d.ip }
        return if (i >= 0) {
            val stara = l[i]
            if (d.ssid.isBlank()) d.ssid = stara.ssid
            if (d.esclUrl.isBlank()) d.esclUrl = stara.esclUrl
            if (d.nazwa.isBlank()) d.nazwa = stara.nazwa
            l[i] = d; zapisz(c, l); i
        } else {
            l.add(d); zapisz(c, l); l.size - 1
        }
    }

    fun usun(c: Context, i: Int) {
        val l = lista(c)
        if (i in l.indices) l.removeAt(i)
        zapisz(c, l)
        if (aktywnyIndeks(c) >= l.size) ustawAktywna(c, 0)
    }
}
