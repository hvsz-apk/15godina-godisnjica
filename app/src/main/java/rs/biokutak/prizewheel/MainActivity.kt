package rs.biokutak.prizewheel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

data class Prize(var name: String, var stock: Int, var weight: Int, var enabled: Boolean)

class MainActivity : Activity() {
    private val pin = "1515"
    private val prefs by lazy { getSharedPreferences("bio_kutak_15", MODE_PRIVATE) }
    private val prizes = mutableListOf<Prize>()
    private var wheelPrizes = listOf<Prize>()
    private var result = ""
    private var pendingResult = ""
    private var pendingPrize: Prize? = null
    private var spinning = false
    private var customerReady = true
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
        if (raw.isNullOrBlank()) {
            prizes.addAll(defaults())
        } else {
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    prizes.add(Prize(
                        o.optString("name", "POKLON").ifBlank { "POKLON" },
                        o.optInt("stock", 0).coerceAtLeast(0),
                        o.optInt("weight", 1).coerceAtLeast(0),
                        o.optBoolean("enabled", true)
                    ))
                }
            } catch (_: Exception) {
                prizes.clear()
                prizes.addAll(defaults())
            }
        }
        history = prefs.getInt("history", 0)
        result = prefs.getString("result", "") ?: ""
        customerReady = prefs.getBoolean("ready", true)
        refreshWheelPrizes()
    }

    private fun refreshWheelPrizes() {
        wheelPrizes = prizes.filter { it.enabled && it.stock > 0 && it.weight > 0 }.toList()
    }

    private fun saveState() {
        val a = JSONArray()
        prizes.forEach { p ->
            a.put(JSONObject().put("name", p.name).put("stock", p.stock).put("weight", p.weight).put("enabled", p.enabled))
        }
        prefs.edit()
            .putString("prizes", a.toString())
            .putInt("history", history)
            .putString("result", result)
            .putBoolean("ready", customerReady)
            .apply()
    }

    private fun drawPrize() {
        if (spinning || !customerReady) return
        refreshWheelPrizes()
        val total = wheelPrizes.sumOf { it.weight }
        spinning = true
        customerReady = false
        result = ""
        pendingPrize = null
        if (total <= 0 || wheelPrizes.isEmpty()) {
            pendingResult = "HVALA ŠTO STE NAŠ KUPAC!"
            wheelView.animateNoPrize()
            return
        }

        var pick = Random.nextInt(total)
        val chosenIndex = wheelPrizes.indexOfFirst { p ->
            pick -= p.weight
            pick < 0
        }.coerceAtLeast(0)
        val chosen = wheelPrizes[chosenIndex]
        pendingPrize = chosen
        pendingResult = chosen.name
        var beforeWeight = 0
        for (i in 0 until chosenIndex) beforeWeight += wheelPrizes[i].weight
        val selectedCenter = -90f + 360f * (beforeWeight + chosen.weight / 2f) / total.toFloat()
        val targetNormalized = ((-90f - selectedCenter) % 360f + 360f) % 360f
        wheelView.animateSpinTo(targetNormalized, 7)
    }

    private fun finishSpin() {
        spinning = false
        pendingPrize?.let { it.stock = (it.stock - 1).coerceAtLeast(0) }
        pendingPrize = null
        result = pendingResult
        pendingResult = ""
        history++
        refreshWheelPrizes()
        saveState()
        wheelView.invalidate()
    }

    private fun nextCustomer() {
        if (spinning) return
        result = ""
        customerReady = true
        refreshWheelPrizes()
        saveState()
        wheelView.invalidate()
    }

    private fun askPin(action: () -> Unit) {
        val input = EditText(this).apply {
            hint = "PIN"
            inputType = 2
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("ADMINISTRACIJA")
            .setMessage("Unesite administratorski PIN")
            .setView(input)
            .setNegativeButton("OTKAŽI", null)
            .setPositiveButton("POTVRDI") { _, _ ->
                if (input.text.toString() == pin) action()
                else Toast.makeText(this, "Pogrešan PIN", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private data class PrizeEditor(
        val name: EditText,
        val stock: EditText,
        val weight: EditText,
        val enabled: CheckBox
    )

    private fun adminDialog() {
        val editors = mutableListOf<PrizeEditor>()
        prizes.forEach { p ->
            editors.add(newEditor(p.name, p.stock, p.weight, p.enabled))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 8)
        }
        val scroll = ScrollView(this)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addButton = Button(this).apply { text = "+ DODAJ NOVU NAGRADU" }
        val hint = TextView(this).apply {
            text = "Količina se automatski umanjuje nakon osvajanja. Kada padne na 0, nagrada nestaje sa točka."
            textSize = 13f
            setPadding(4, 10, 4, 12)
        }
        root.addView(hint)
        scroll.addView(rows)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(addButton)

        lateinit var dialog: AlertDialog

        fun rebuildRows() {
            rows.removeAllViews()
            editors.forEachIndexed { index, editor ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 12)
                    setBackgroundColor(Color.rgb(242, 238, 230))
                }
                val titleRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                val title = TextView(this).apply {
                    text = "NAGRADA ${index + 1}"
                    textSize = 15f
                    setTextColor(Color.BLACK)
                    setTypeface(null, Typeface.BOLD)
                }
                val delete = Button(this).apply {
                    text = "OBRIŠI"
                    setOnClickListener {
                        editors.removeAt(index)
                        rebuildRows()
                    }
                }
                titleRow.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                titleRow.addView(delete)
                editor.name.hint = "Naziv nagrade"
                editor.stock.hint = "Količina na stanju"
                editor.weight.hint = "Verovatnoća / težina"
                editor.enabled.text = "Dostupna za izvlačenje"
                editor.name.setTextColor(Color.BLACK)
                editor.stock.setTextColor(Color.BLACK)
                editor.weight.setTextColor(Color.BLACK)
                card.addView(titleRow)
                card.addView(editor.name)
                card.addView(editor.stock)
                card.addView(editor.weight)
                card.addView(editor.enabled)
                val lp = LinearLayout.LayoutParams(-1, -2)
                lp.setMargins(0, 0, 0, 12)
                rows.addView(card, lp)
            }
        }

        addButton.setOnClickListener {
            editors.add(newEditor("", 1, 1, true))
            rebuildRows()
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        rebuildRows()

        dialog = AlertDialog.Builder(this)
            .setTitle("ADMIN PANEL – NAGRADE")
            .setView(root)
            .setNegativeButton("ZATVORI", null)
            .setNeutralButton("RESET KAMPANJE", null)
            .setPositiveButton("SAČUVAJ IZMENE", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), (resources.displayMetrics.heightPixels * 0.88f).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = mutableListOf<Prize>()
                var invalid = false
                editors.forEach { e ->
                    val name = e.name.text.toString().trim()
                    val stock = e.stock.text.toString().toIntOrNull()
                    val weight = e.weight.text.toString().toIntOrNull()
                    if (name.isBlank() || stock == null || stock < 0 || weight == null || weight < 0) {
                        invalid = true
                    } else {
                        updated.add(Prize(name, stock, weight, e.enabled.isChecked))
                    }
                }
                if (invalid) {
                    Toast.makeText(this, "Proverite naziv, količinu i verovatnoću svake nagrade.", Toast.LENGTH_LONG).show()
                } else {
                    prizes.clear()
                    prizes.addAll(updated)
                    refreshWheelPrizes()
                    saveState()
                    wheelView.invalidate()
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Reset kampanje?")
                    .setMessage("Vratiće početnih pet nagrada i njihove početne količine. Istorija izvlačenja ostaje sačuvana.")
                    .setNegativeButton("NE", null)
                    .setPositiveButton("DA") { _, _ ->
                        prizes.clear()
                        prizes.addAll(defaults())
                        result = ""
                        customerReady = true
                        refreshWheelPrizes()
                        saveState()
                        wheelView.invalidate()
                        dialog.dismiss()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun newEditor(name: String, stock: Int, weight: Int, enabled: Boolean): PrizeEditor {
        return PrizeEditor(
            EditText(this).apply { setText(name) },
            EditText(this).apply { setText(stock.toString()); inputType = 2 },
            EditText(this).apply { setText(weight.toString()); inputType = 2 },
            CheckBox(this).apply { isChecked = enabled }
        )
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (result.isNotBlank()) nextCustomer() else drawPrize()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_BACK -> {
                askPin { adminDialog() }
                return true
            }
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
        private var settledAngle = 0f

        fun animateSpinTo(targetNormalized: Float, turns: Int) {
            startAngle = settledAngle
            val current = ((settledAngle % 360f) + 360f) % 360f
            val delta = ((targetNormalized - current) % 360f + 360f) % 360f
            endAngle = settledAngle + turns * 360f + delta
            animStart = System.currentTimeMillis()
            animating = true
            postInvalidateOnAnimation()
        }

        fun animateNoPrize() {
            startAngle = settledAngle
            endAngle = settledAngle + 360f * 5f
            animStart = System.currentTimeMillis()
            animating = true
            postInvalidateOnAnimation()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawColor(Color.rgb(13, 7, 9))
            val bg = Paint(3).apply {
                shader = LinearGradient(0f, 0f, w, h,
                    intArrayOf(Color.rgb(38, 7, 13), Color.rgb(10, 8, 12), Color.rgb(53, 12, 16)),
                    null, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, h, bg)
            val cx = w * .36f
            val cy = h * .53f
            val radius = min(w * .29f, h * .39f)

            text(c, "BIO KUTAK", w * .69f, h * .13f, h * .075f, gold, true, Paint.Align.CENTER)
            text(c, "15 GODINA ZAJEDNO", w * .69f, h * .21f, h * .043f, Color.WHITE, true, Paint.Align.CENTER)
            text(c, "VELIKO NAGRADNO IZVLAČENJE", w * .69f, h * .27f, h * .023f, Color.LTGRAY, false, Paint.Align.CENTER)

            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * .065f
            paint.color = Color.rgb(116, 65, 22)
            c.drawCircle(cx, cy, radius + 13f, paint)
            paint.strokeWidth = radius * .018f
            paint.color = gold
            c.drawCircle(cx, cy, radius + 25f, paint)
            for (i in 0 until 40) {
                val a = Math.toRadians(i * 9.0)
                val x = cx + cos(a).toFloat() * (radius + 25f)
                val y = cy + sin(a).toFloat() * (radius + 25f)
                paint.style = Paint.Style.FILL
                paint.color = if (i % 2 == 0) Color.rgb(255, 225, 133) else Color.rgb(147, 63, 27)
                c.drawCircle(x, y, radius * .012f, paint)
            }

            var wheelRotation = settledAngle
            if (animating) {
                val t = ((System.currentTimeMillis() - animStart) / 4200f).coerceIn(0f, 1f)
                val eased = 1f - (1f - t).pow(4)
                wheelRotation = startAngle + (endAngle - startAngle) * eased
                if (t < 1f) postInvalidateOnAnimation()
                else {
                    animating = false
                    settledAngle = ((endAngle % 360f) + 360f) % 360f
                    finishSpin()
                }
            }

            val active = wheelPrizes
            c.save()
            c.rotate(wheelRotation, cx, cy)
            val wheelRect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            if (active.isEmpty()) {
                paint.style = Paint.Style.FILL
                paint.shader = null
                paint.color = Color.rgb(94, 12, 23)
                c.drawCircle(cx, cy, radius, paint)
                text(c, "HVALA", cx, cy + radius * .07f, radius * .19f, Color.WHITE, true, Paint.Align.CENTER)
            } else {
                val colors = intArrayOf(Color.rgb(132, 20, 30), Color.rgb(28, 26, 31), Color.rgb(166, 111, 32), Color.rgb(94, 12, 23), Color.rgb(42, 34, 27))
                val totalWeight = active.sumOf { it.weight }.coerceAtLeast(1)
                var sweepStart = -90f
                active.forEachIndexed { i, prize ->
                    val sweep = 360f * prize.weight / totalWeight.toFloat()
                    val middle = sweepStart + sweep / 2f
                    paint.style = Paint.Style.FILL
                    paint.shader = null
                    paint.color = colors[i % colors.size]
                    c.drawArc(wheelRect, sweepStart, sweep, true, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(2f, radius * .012f)
                    paint.color = gold
                    c.drawArc(wheelRect, sweepStart, sweep, true, paint)
                    val a = Math.toRadians(middle.toDouble())
                    val tx = cx + cos(a).toFloat() * radius * .64f
                    val ty = cy + sin(a).toFloat() * radius * .64f
                    val labelSize = min(h * .023f, (radius * sweep / 360f) * .30f).coerceAtLeast(h * .009f)
                    if (sweep > 8f) {
                        c.save()
                        c.rotate(middle + 90f, tx, ty)
                        text(c, prize.name.take(if (active.size > 10) 9 else 20), tx, ty, labelSize, Color.WHITE, true, Paint.Align.CENTER)
                        c.restore()
                    }
                    sweepStart += sweep
                }
            }

            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(cx, cy, radius * .23f,
                intArrayOf(Color.rgb(255, 224, 132), Color.rgb(151, 94, 24), Color.rgb(48, 17, 16)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius * .23f, paint)
            paint.shader = null
            text(c, "15", cx, cy + radius * .045f, radius * .23f, Color.WHITE, true, Paint.Align.CENTER)
            c.restore()

            paint.color = gold
            val path = Path().apply {
                moveTo(cx, cy - radius - 28f)
                lineTo(cx - 25f, cy - radius + 16f)
                lineTo(cx + 25f, cy - radius + 16f)
                close()
            }
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
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(80, 48, 18)
            c.drawRoundRect(RectF(w * .88f, h * .035f, w * .985f, h * .105f), 18f, 18f, paint)
            text(c, "⚙ ADMIN", w * .932f, h * .082f, h * .025f, Color.WHITE, true, Paint.Align.CENTER)
            text(c, "ADMIN: MENU / BACK", w * .96f, h * .97f, h * .014f, Color.GRAY, false, Paint.Align.RIGHT)
        }

        private fun button(c: Canvas, x: Float, y: Float, bw: Float, bh: Float, label: String, color: Int) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(83, 20, 20)
            val r = RectF(x - bw / 2, y - bh / 2, x + bw / 2, y + bh / 2)
            c.drawRoundRect(r, bh / 3, bh / 3, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = color
            c.drawRoundRect(r, bh / 3, bh / 3, paint)
            text(c, label, x, y + bh * .12f, bh * .27f, Color.WHITE, true, Paint.Align.CENTER)
        }

        private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Paint.Align) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.create("sans-serif-condensed", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textAlign = align
            s.split("\n").forEachIndexed { i, line -> c.drawText(line, x, y + i * (size * 1.15f), paint) }
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                val x = event.x / width
                val y = event.y / height
                if (result.isNotBlank() && x in .55f.. .9f && y in .70f.. .91f) nextCustomer()
                else if (result.isBlank() && x in .55f.. .9f && y in .59f.. .80f) drawPrize()
                else if ((x > .86f && y < .13f) || (x > .94f && y > .90f)) askPin { adminDialog() }
                return true
            }
            return true
        }
    }
}
