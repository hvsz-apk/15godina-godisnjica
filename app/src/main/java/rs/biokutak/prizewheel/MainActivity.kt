package rs.biokutak.prizewheel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

data class Prize(var name: String, var stock: Int, var weight: Int, var enabled: Boolean)

class MainActivity : Activity() {
    private val pin = "1515"
    private val prefs by lazy { getSharedPreferences("bio_kutak_15", MODE_PRIVATE) }
    private val prizes = mutableListOf<Prize>()
    private var result = ""
    private var spinning = false
    private var customerReady = true
    private var angle = 0f
    private var history = 0
    private lateinit var wheelView: WheelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadState()
        wheelView = WheelView()
        setContentView(wheelView)
    }

    private fun defaults() = listOf(
        Prize("GLAVNI POKLON PAKET", 1, 3, true),
        Prize("POKLON PAKET", 2, 8, true),
        Prize("POKLON PROIZVOD", 5, 20, true),
        Prize("VAUČER 200 RSD", 7, 30, true),
        Prize("POKLON IZNENAĐENJA", 10, 39, true)
    )

    private fun loadState() {
        val raw = prefs.getString("prizes", null)
        if (raw.isNullOrBlank()) prizes.addAll(defaults()) else try {
            val a = JSONArray(raw)
            for (i in 0 until min(5, a.length())) {
                val o = a.getJSONObject(i)
                prizes.add(Prize(o.optString("name", "POKLON"), o.optInt("stock", 0).coerceAtLeast(0), o.optInt("weight", 1).coerceAtLeast(0), o.optBoolean("enabled", true)))
            }
            while (prizes.size < 5) prizes.add(defaults()[prizes.size])
        } catch (_: Exception) { prizes.addAll(defaults()) }
        history = prefs.getInt("history", 0)
        result = prefs.getString("result", "") ?: ""
        customerReady = prefs.getBoolean("ready", true)
    }

    private fun saveState() {
        val a = JSONArray()
        prizes.forEach { p -> a.put(JSONObject().put("name", p.name).put("stock", p.stock).put("weight", p.weight).put("enabled", p.enabled)) }
        prefs.edit().putString("prizes", a.toString()).putInt("history", history).putString("result", result).putBoolean("ready", customerReady).apply()
    }

    private fun drawPrize() {
        if (spinning || !customerReady) return
        val eligible = prizes.filter { it.enabled && it.stock > 0 && it.weight > 0 }
        val total = eligible.sumOf { it.weight }
        spinning = true
        customerReady = false
        if (total <= 0) {
            result = "HVALA ŠTO STE NAŠ KUPAC!"
            finishSpin()
            return
        }
        var pick = Random.nextInt(total)
        val chosen = eligible.first { p -> pick -= p.weight; pick < 0 }
        chosen.stock--
        result = chosen.name
        val slot = prizes.indexOf(chosen)
        angle = (360f * (5 - slot) / 5f) + 360f * 5f + Random.nextInt(0, 50)
        saveState()
        wheelView.animateSpin()
    }

    private fun finishSpin() {
        spinning = false
        history++
        saveState()
        wheelView.invalidate()
    }

    private fun nextCustomer() {
        if (spinning) return
        result = ""
        customerReady = true
        saveState()
        wheelView.invalidate()
    }

    private fun askPin(action: () -> Unit) {
        val input = EditText(this).apply { hint = "PIN"; inputType = 2; setSingleLine() }
        AlertDialog.Builder(this).setTitle("ADMINISTRACIJA").setMessage("Unesite administratorski PIN").setView(input)
            .setNegativeButton("OTKAŽI", null).setPositiveButton("POTVRDI") { _, _ ->
                if (input.text.toString() == pin) action() else Toast.makeText(this, "Pogrešan PIN", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun adminDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 8, 36, 0) }
        val fields = mutableListOf<Triple<EditText, EditText, android.widget.CheckBox>>()
        prizes.forEachIndexed { i, p ->
            val heading = android.widget.TextView(this).apply { text = "NAGRADA " + (i + 1); textSize = 16f; setPadding(0, 18, 0, 4) }
            val name = EditText(this).apply { setText(p.name); hint = "Naziv nagrade" }
            val stock = EditText(this).apply { setText(p.stock.toString()); hint = "Količina"; inputType = 2 }
            val weight = EditText(this).apply { setText(p.weight.toString()); hint = "Težina / verovatnoća"; inputType = 2 }
            val enabled = android.widget.CheckBox(this).apply { text = "Uključena u izvlačenje"; isChecked = p.enabled }
            layout.addView(heading); layout.addView(name); layout.addView(stock); layout.addView(weight); layout.addView(enabled)
            fields.add(Triple(name, stock, enabled))
            name.tag = weight
        }
        val scroll = android.widget.ScrollView(this).apply { addView(layout) }
        val dialog = AlertDialog.Builder(this).setTitle("PODEŠAVANJE NAGRADA").setView(scroll)
            .setNegativeButton("OTKAŽI", null).setNeutralButton("RESET KAMPANJE", null).setPositiveButton("SAČUVAJ", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                fields.forEachIndexed { i, triple ->
                    val name = triple.first; val stock = triple.second; val enabled = triple.third
                    val weight = name.tag as EditText
                    prizes[i].name = name.text.toString().trim().ifBlank { "POKLON" }
                    prizes[i].stock = stock.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    prizes[i].weight = weight.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
                    prizes[i].enabled = enabled.isChecked
                }
                saveState(); dialog.dismiss(); wheelView.invalidate()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("Reset kampanje?").setMessage("Vratiće početne količine nagrada. Istorija izvlačenja ostaje sačuvana.")
                    .setNegativeButton("NE", null).setPositiveButton("DA") { _, _ ->
                        val d = defaults()
                        prizes.indices.forEach { prizes[it] = d[it] }
                        result = ""; customerReady = true; saveState(); wheelView.invalidate()
                    }.show()
            }
        }
        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (result.isNotBlank()) askPin { nextCustomer() } else drawPrize(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BACK -> { askPin { adminDialog() }; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class WheelView : View(this@MainActivity) {
        private val paint = Paint(3)
        private val gold = Color.rgb(226, 184, 76)
        private var animStart = 0L
        private var animating = false
        private var startAngle = 0f
        private var endAngle = 0f

        fun animateSpin() {
            startAngle = angle - 1800f
            endAngle = angle
            animStart = System.currentTimeMillis()
            animating = true
            postInvalidateOnAnimation()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(Color.rgb(13, 7, 9))
            val bg = Paint(3).apply { shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(38, 7, 13), Color.rgb(10, 8, 12), Color.rgb(53, 12, 16)), null, Shader.TileMode.CLAMP) }
            c.drawRect(0f, 0f, w, h, bg)
            val cx = w * .36f; val cy = h * .53f; val radius = min(w * .29f, h * .39f)
            text(c, "BIO KUTAK", w * .69f, h * .13f, h * .075f, gold, true, Paint.Align.CENTER)
            text(c, "15 GODINA ZAJEDNO", w * .69f, h * .21f, h * .043f, Color.WHITE, true, Paint.Align.CENTER)
            text(c, "VELIKO NAGRADNO IZVLAČENJE", w * .69f, h * .27f, h * .023f, Color.LTGRAY, false, Paint.Align.CENTER)
            paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = radius * .065f; paint.color = Color.rgb(116, 65, 22)
            c.drawCircle(cx, cy, radius + 13f, paint)
            paint.strokeWidth = radius * .018f; paint.color = gold; c.drawCircle(cx, cy, radius + 25f, paint)
            for (i in 0 until 40) {
                val a = Math.toRadians(i * 9.0)
                val x = cx + cos(a).toFloat() * (radius + 25f); val y = cy + sin(a).toFloat() * (radius + 25f)
                paint.style = Paint.Style.FILL; paint.color = if (i % 2 == 0) Color.rgb(255, 225, 133) else Color.rgb(147, 63, 27)
                c.drawCircle(x, y, radius * .012f, paint)
            }
            val wheelRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            var wheelRotation = 0f
            if (animating) {
                val t = ((System.currentTimeMillis() - animStart) / 4200f).coerceIn(0f, 1f)
                val eased = 1f - (1f - t).pow(4)
                wheelRotation = startAngle + (endAngle - startAngle) * eased
                if (t < 1f) postInvalidateOnAnimation() else { animating = false; finishSpin() }
            }
            c.save()
            c.rotate(wheelRotation, cx, cy)
            val colors = intArrayOf(Color.rgb(132, 20, 30), Color.rgb(28, 26, 31), Color.rgb(166, 111, 32), Color.rgb(94, 12, 23), Color.rgb(42, 34, 27))
            for (i in 0 until 5) {
                paint.style = Paint.Style.FILL; paint.shader = null; paint.color = colors[i]
                c.drawArc(wheelRect, -90f + i * 72f, 72f, true, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = gold
                c.drawArc(wheelRect, -90f + i * 72f, 72f, true, paint)
                val a = Math.toRadians(-54.0 + i * 72.0)
                val tx = cx + cos(a).toFloat() * radius * .64f; val ty = cy + sin(a).toFloat() * radius * .64f
                text(c, prizes[i].name.take(18), tx, ty, h * .021f, Color.WHITE, true, Paint.Align.CENTER)
                text(c, "PREOSTALO: " + prizes[i].stock, tx, ty + h * .035f, h * .014f, gold, false, Paint.Align.CENTER)
            }
            paint.style = Paint.Style.FILL; paint.shader = RadialGradient(cx, cy, radius * .23f, intArrayOf(Color.rgb(255, 224, 132), Color.rgb(151, 94, 24), Color.rgb(48, 17, 16)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius * .23f, paint); paint.shader = null
            text(c, "15", cx, cy + radius * .045f, radius * .23f, Color.WHITE, true, Paint.Align.CENTER)
            c.restore()
            paint.color = gold
            val path = Path().apply { moveTo(cx, cy - radius - 28f); lineTo(cx - 25f, cy - radius + 16f); lineTo(cx + 25f, cy - radius + 16f); close() }
            c.drawPath(path, paint)
            val rightX = w * .69f
            if (result.isNotBlank()) {
                text(c, "ČESTITAMO!", rightX, h * .43f, h * .07f, gold, true, Paint.Align.CENTER)
                text(c, result, rightX, h * .54f, h * .043f, Color.WHITE, true, Paint.Align.CENTER)
                text(c, "HVALA ŠTO STE DEO NAŠE PRIČE", rightX, h * .64f, h * .024f, gold, true, Paint.Align.CENTER)
                button(c, rightX, h * .80f, w * .22f, h * .105f, "SLEDEĆI KUPAC", gold)
            } else {
                text(c, "ZAVRTI TOČAK SREĆE!", rightX, h * .46f, h * .046f, Color.WHITE, true, Paint.Align.CENTER)
                text(c, "Osvoji poklon povodom našeg jubileja", rightX, h * .53f, h * .022f, Color.LTGRAY, false, Paint.Align.CENTER)
                button(c, rightX, h * .68f, w * .25f, h * .14f, if (customerReady) "ZAVRTI TOČAK" else "SAČEKAJ", gold)
            }
            text(c, "HVALA ŠTO STE NAŠ KUPAC!", w * .5f, h * .965f, h * .021f, Color.rgb(218, 184, 112), false, Paint.Align.CENTER)
            text(c, "ADMIN: MENU / BACK", w * .96f, h * .97f, h * .014f, Color.GRAY, false, Paint.Align.RIGHT)
        }

        private fun button(c: Canvas, x: Float, y: Float, bw: Float, bh: Float, label: String, color: Int) {
            paint.style = Paint.Style.FILL; paint.color = Color.rgb(83, 20, 20)
            val r = RectF(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
            c.drawRoundRect(r, bh / 3, bh / 3, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = color; c.drawRoundRect(r, bh / 3, bh / 3, paint)
            text(c, label, x, y + bh * .12f, bh * .27f, Color.WHITE, true, Paint.Align.CENTER)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align) {
            paint.shader = null; paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = size
            paint.typeface = if (bold) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textAlign = align
            s.split("\n").forEachIndexed { i, line -> c.drawText(line, x, y + i * (size * 1.15f), paint) }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val x = event.x / width; val y = event.y / height
                if (result.isNotBlank() && x in .55f.. .9f && y in .70f.. .91f) askPin { nextCustomer() }
                else if (result.isBlank() && x in .55f.. .9f && y in .59f.. .80f) drawPrize()
                else if (x > .94f && y > .90f) askPin { adminDialog() }
                return true
            }
            return true
        }
    }
}
