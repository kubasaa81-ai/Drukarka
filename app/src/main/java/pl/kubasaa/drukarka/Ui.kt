package pl.kubasaa.drukarka

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Wspólne kolory i "nowoczesne" kolorowe przyciski budowane w kodzie. */
object Ui {
    val granat = Color.rgb(30, 58, 95)
    val niebieski = Color.rgb(61, 106, 242)
    val zielony = Color.rgb(46, 160, 67)
    val pomarancz = Color.rgb(245, 124, 0)
    val fiolet = Color.rgb(123, 31, 162)
    val turkus = Color.rgb(0, 137, 123)
    val czerwony = Color.rgb(211, 47, 47)
    val szaryTlo = Color.rgb(236, 239, 244)
    val szaryTekst = Color.rgb(90, 98, 110)

    fun dp(c: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun tlo(kolor: Int, promien: Float, obramowanie: Int = 0, kolorObr: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(kolor)
            cornerRadius = promien
            if (obramowanie > 0) setStroke(obramowanie, kolorObr)
        }

    fun przycisk(c: Context, tekst: String, kolor: Int, onClick: () -> Unit): Button {
        val b = Button(c)
        b.text = tekst
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.typeface = Typeface.DEFAULT_BOLD
        b.stateListAnimator = null
        b.minHeight = dp(c, 50)
        b.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10))
        b.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
            tlo(kolor, dp(c, 14).toFloat()), null
        )
        b.setOnClickListener { onClick() }
        return b
    }

    /** Duży kafel na ekranie głównym (ikona-emoji + podpis). */
    fun kafel(c: Context, ikona: String, tekst: String, kolor: Int, onClick: () -> Unit): View {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.VERTICAL
        l.gravity = Gravity.CENTER
        l.setPadding(dp(c, 8), dp(c, 18), dp(c, 8), dp(c, 18))
        l.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(70, 255, 255, 255)),
            tlo(kolor, dp(c, 20).toFloat()), null
        )
        l.isClickable = true
        l.setOnClickListener { onClick() }
        val i = TextView(c)
        i.text = ikona
        i.setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
        i.gravity = Gravity.CENTER
        val t = TextView(c)
        t.text = tekst
        t.setTextColor(Color.WHITE)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        t.gravity = Gravity.CENTER
        t.setPadding(0, dp(c, 6), 0, 0)
        l.addView(i)
        l.addView(t)
        return l
    }

    /** Przełącznik-"chip": zaznaczony = kolorowy, niezaznaczony = jasny. */
    fun chip(c: Context, tekst: String, kolor: Int, zaznaczony: Boolean, onClick: () -> Unit): TextView {
        val t = TextView(c)
        t.text = tekst
        t.gravity = Gravity.CENTER
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9))
        if (zaznaczony) {
            t.setTextColor(Color.WHITE)
            t.background = tlo(kolor, dp(c, 18).toFloat())
        } else {
            t.setTextColor(kolor)
            t.background = tlo(Color.WHITE, dp(c, 18).toFloat(), dp(c, 2), kolor)
        }
        t.setOnClickListener { onClick() }
        return t
    }

    fun naglowek(c: Context, tekst: String): TextView {
        val t = TextView(c)
        t.text = tekst
        t.setTextColor(granat)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        t.setPadding(0, dp(c, 14), 0, dp(c, 6))
        return t
    }

    fun pasekTytulu(c: Context, tytul: String, wstecz: (() -> Unit)?): LinearLayout {
        val l = LinearLayout(c)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        l.setBackgroundColor(granat)
        l.setPadding(dp(c, 8), dp(c, 12), dp(c, 16), dp(c, 12))
        if (wstecz != null) {
            val w = TextView(c)
            w.text = "←"
            w.setTextColor(Color.WHITE)
            w.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            w.setPadding(dp(c, 10), 0, dp(c, 14), 0)
            w.setOnClickListener { wstecz() }
            l.addView(w)
        } else {
            l.setPadding(dp(c, 20), dp(c, 14), dp(c, 16), dp(c, 14))
        }
        val t = TextView(c)
        t.text = tytul
        t.setTextColor(Color.WHITE)
        t.typeface = Typeface.DEFAULT_BOLD
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        l.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return l
    }

    /** Wiersz z chipami, zawijany ręcznie co [naWiersz] elementów (bez dodatkowych bibliotek). */
    fun siatkaChipow(c: Context, chipy: List<View>, naWiersz: Int): LinearLayout {
        val pion = LinearLayout(c)
        pion.orientation = LinearLayout.VERTICAL
        var wiersz: LinearLayout? = null
        chipy.forEachIndexed { i, v ->
            if (i % naWiersz == 0) {
                wiersz = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                pion.addView(wiersz, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 8) })
            }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginEnd = dp(c, 6)
            wiersz!!.addView(v, lp)
        }
        // dopełnienie ostatniego wiersza, żeby chipy miały równą szerokość
        val reszta = chipy.size % naWiersz
        if (reszta != 0) repeat(naWiersz - reszta) {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.marginEnd = dp(c, 6)
            wiersz!!.addView(View(c), lp)
        }
        return pion
    }
}
