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
            val goldBright = Color.rgb(255, 224, 145)
            val gold = Color.rgb(218, 166, 62)
            val cream = Color.rgb(255, 246, 218)

            // Deep burgundy stage with a dramatic central spotlight.
            c.drawColor(Color.rgb(5, 4, 8))
            val backdrop = Paint(3).apply {
                shader = LinearGradient(0f, 0f, w, h,
                    intArrayOf(Color.rgb(7, 5, 12), Color.rgb(42, 5, 16), Color.rgb(8, 5, 12)),
                    floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, h, backdrop)
            val glow = Paint(3).apply {
                shader = RadialGradient(w * .36f, h * .50f, h * .78f,
                    intArrayOf(Color.argb(95, 171, 30, 39), Color.argb(25, 105, 10, 23), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w, h, glow)

            // Fine gold framing and subtle stage rays.
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.argb(150, 190, 139, 49)
            c.drawRoundRect(RectF(12f, 12f, w - 12f, h - 12f), 28f, 28f, paint)
            paint.strokeWidth = 1f
            paint.color = Color.argb(55, 255, 221, 140)
            for (i in 0..17) {
                val x = w * (i / 17f)
                c.drawLine(x, 0f, x + (x - w * .36f) * .16f, h, paint)
            }

            val cx = w * .355f
            val cy = h * .535f
            val radius = min(w * .285f, h * .405f)

            // Anniversary header, gold embossed typography.
            text(c, "BIO KUTAK", w * .70f, h * .115f, h * .078f, goldBright, true, Paint.Align.CENTER)
            text(c, "15 GODINA ZAJEDNO", w * .70f, h * .195f, h * .040f, cream, true, Paint.Align.CENTER)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f
            paint.color = gold
            c.drawLine(w * .55f, h * .225f, w * .85f, h * .225f, paint)
            paint.style = Paint.Style.FILL
            text(c, "VELIKO JUBILARNO IZVLAČENJE", w * .70f, h * .265f, h * .021f, Color.rgb(238, 204, 137), true, Paint.Align.CENTER)

            // The wheel's gold pedestal and illuminated marquee.
            paint.shader = RadialGradient(cx, cy, radius * 1.42f,
                intArrayOf(Color.argb(90, 255, 197, 74), Color.argb(25, 255, 166, 50), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius * 1.42f, paint)
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(cx - radius, cy - radius, cx + radius, cy + radius,
                intArrayOf(Color.rgb(255, 226, 144), Color.rgb(111, 57, 18), Color.rgb(255, 214, 103)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius + 31f, paint)
            paint.shader = null
            paint.color = Color.rgb(24, 12, 14)
            c.drawCircle(cx, cy, radius + 22f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = goldBright
            c.drawCircle(cx, cy, radius + 18f, paint)
            paint.strokeWidth = 2f
            paint.color = Color.rgb(103, 57, 24)
            c.drawCircle(cx, cy, radius + 9f, paint)

            // Animated bulbs around the circumference.
            val pulse = ((System.currentTimeMillis() / 360L) % 2L).toInt()
            for (i in 0 until  sixtyBulbs()) {
                val a = Math.toRadians(i * 6.0 - 90.0)
                val bx = cx + cos(a).toFloat() * (radius + 25f)
                val by = cy + sin(a).toFloat() * (radius + 25f)
                paint.style = Paint.Style.FILL
                paint.color = if ((i + pulse) % 2 == 0) goldBright else Color.rgb(163, 83, 27)
                c.drawCircle(bx, by, radius * .014f, paint)
                if ((i + pulse) % 2 == 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f
                    paint.color = Color.argb(150, 255, 220, 135)
                    c.drawCircle(bx, by, radius * .023f, paint)
                }
            }

            var wheelRotation = settledAngle
            if (animating) {
                val t = ((System.currentTimeMillis() - animStart) / 5200f).coerceIn(0f, 1f)
                // Long fast phase followed by a pronounced slow-down.
                val eased = if (t < .72f) (t / .72f) * .88f else .88f + .12f * (1f - (1f - (t - .72f) / .28f).pow(4))
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
                paint.shader = RadialGradient(cx, cy, radius,
                    intArrayOf(Color.rgb(255, 213, 106), Color.rgb(135, 19, 35), Color.rgb(42, 5, 15)),
                    null, Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, radius, paint)
                paint.shader = null
                text(c, "HVALA", cx, cy + radius * .07f, radius * .19f, Color.WHITE, true, Paint.Align.CENTER)
            } else {
                val colors = intArrayOf(
                    Color.rgb(151, 13, 35), Color.rgb(28, 23, 35), Color.rgb(184, 119, 30),
                    Color.rgb(105, 8, 26), Color.rgb(34, 42, 49), Color.rgb(143, 74, 19),
                    Color.rgb(92, 12, 37), Color.rgb(37, 25, 21)
                )
                val totalWeight = active.sumOf { it.weight }.coerceAtLeast(1)
                var sweepStart = -90f
                active.forEachIndexed { i, prize ->
                    val sweep = 360f * prize.weight / totalWeight.toFloat()
                    val mid = sweepStart + sweep / 2f
                    val base = colors[i % colors.size]
                    paint.style = Paint.Style.FILL
                    paint.shader = SweepGradient(cx, cy,
                        intArrayOf(Color.rgb(255, 238, 174), base, Color.rgb(20, 8, 14), base),
                        floatArrayOf(0f, .12f, .58f, 1f))
                    c.drawArc(wheelRect, sweepStart, sweep, true, paint)
                    paint.shader = null
                    // Bright metallic separators.
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = max(2.5f, radius * .009f)
                    paint.color = goldBright
                    c.drawArc(wheelRect, sweepStart, sweep, true, paint)
                    val a = Math.toRadians(mid.toDouble())
                    val tx = cx + cos(a).toFloat() * radius * .64f
                    val ty = cy + sin(a).toFloat() * radius * .64f
                    if (sweep >= 7f) {
                        val labelSize = min(h * .026f, (radius * sweep / 360f) * .32f).coerceAtLeast(h * .009f)
                        c.save()
                        c.rotate(mid + 90f, tx, ty)
                        text(c, prize.name.uppercase().take(if (active.size > 12) 8 else 22), tx, ty, labelSize, cream, true, Paint.Align.CENTER)
                        if (sweep > 17f && active.size <= 10) {
                            text(c, "POKLON", tx, ty + labelSize * 1.35f, labelSize * .55f, goldBright, true, Paint.Align.CENTER)
                        }
                        c.restore()
                    }
                    sweepStart += sweep
                }
                // Glossy glass highlight over the upper half of the wheel.
                paint.style = Paint.Style.FILL
                paint.shader = LinearGradient(cx, cy - radius, cx, cy + radius,
                    intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(28, 0, 0, 0)),
                    floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(cx, cy, radius - 3f, paint)
                paint.shader = null
            }

            // Layered centre cap with anniversary medallion.
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(cx - radius * .07f, cy - radius * .08f, radius * .27f,
                intArrayOf(Color.rgb(255, 244, 190), Color.rgb(208, 151, 48), Color.rgb(92, 40, 13)),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, radius * .245f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = radius * .018f
            paint.color = Color.rgb(255, 231, 157)
            c.drawCircle(cx, cy, radius * .205f, paint)
            text(c, "BIO", cx, cy - radius * .015f, radius * .075f, Color.rgb(67, 22, 14), true, Paint.Align.CENTER)
            text(c, "15", cx, cy + radius * .105f, radius * .105f, Color.rgb(67, 22, 14), true, Paint.Align.CENTER)
            c.restore()

            // Fixed illuminated pointer at 12 o'clock.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(cx - 28f, cy - radius - 44f, cx + 28f, cy - radius + 22f,
                intArrayOf(Color.rgb(255, 244, 188), Color.rgb(227, 43, 36), Color.rgb(105, 5, 18)),
                null, Shader.TileMode.CLAMP)
            val pointer = Path().apply {
                moveTo(cx, cy - radius + 28f)
                lineTo(cx - 30f, cy - radius - 27f)
                lineTo(cx + 30f, cy - radius - 27f)
                close()
            }
            c.drawPath(pointer, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = goldBright
            c.drawPath(pointer, paint)

            val rightX = w * .705f
            // Right-side premium information panel.
            val panel = RectF(w * .535f, h * .315f, w * .875f, h * .92f)
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
                intArrayOf(Color.rgb(38, 12, 19), Color.rgb(13, 10, 17), Color.rgb(48, 13, 20)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(panel, 30f, 30f, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            paint.color = gold
            c.drawRoundRect(panel, 30f, 30f, paint)

            if (result.isNotBlank()) {
                text(c, "OSVOJILI STE!", rightX, h * .435f, h * .058f, goldBright, true, Paint.Align.CENTER)
                text(c, result.uppercase().take(32), rightX, h * .535f, h * .038f, Color.WHITE, true, Paint.Align.CENTER)
                text(c, "ČESTITAMO I HVALA NA POVERENJU", rightX, h * .625f, h * .020f, Color.rgb(241, 205, 133), true, Paint.Align.CENTER)
                button(c, rightX, h * .79f, w * .245f, h * .115f, "SLEDEĆI KUPAC", goldBright)
                // Confetti burst after the wheel has stopped and the prize is visible.
                for (i in 0 until 42) {
                    val seed = i * 37
                    val px = rightX + (((seed * 17) % 181) - 90) * w / 900f
                    val py = h * (.34f + ((seed * 13) % 54) / 100f)
                    paint.style = Paint.Style.FILL
                    paint.color = intArrayOf(goldBright, Color.rgb(214, 35, 48), Color.WHITE, Color.rgb(63, 170, 135))[i % 4]
                    c.save()
                    c.rotate((seed * 11 % 120).toFloat(), px, py)
                    c.drawRoundRect(RectF(px - 3f, py - 7f, px + 3f, py + 7f), 2f, 2f, paint)
                    c.restore()
                }
            } else {
                text(c, "DA LI STE SPREMNI?", rightX, h * .425f, h * .035f, goldBright, true, Paint.Align.CENTER)
                text(c, "ZAVRTITE TOČAK", rightX, h * .495f, h * .055f, Color.WHITE, true, Paint.Align.CENTER)
                text(c, "I OSVOJITE JUBILARNI POKLON", rightX, h * .555f, h * .020f, Color.rgb(238, 205, 155), false, Paint.Align.CENTER)
                button(c, rightX, h * .72f, w * .265f, h * .145f, if (customerReady) "ZAVRTI TOČAK!" else "SREĆNO!", goldBright)
                text(c, "15 GODINA • 15 GODINA RADOSTI", rightX, h * .86f, h * .017f, Color.rgb(210, 177, 120), true, Paint.Align.CENTER)
            }

            // Top-right admin access is deliberately visible to staff.
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(w * .88f, h * .025f, w * .99f, h * .11f,
                intArrayOf(Color.rgb(255, 224, 145), Color.rgb(130, 77, 21)),
                null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(w * .88f, h * .025f, w * .985f, h * .11f), 18f, 18f, paint)
            paint.shader = null
            text(c, "⚙ ADMIN", w * .932f, h * .082f, h * .023f, Color.rgb(38, 12, 14), true, Paint.Align.CENTER)
            text(c, "HVALA ŠTO STE DEO NAŠE PRIČE", w * .5f, h * .972f, h * .018f, Color.rgb(225, 190, 126), true, Paint.Align.CENTER)
            if (animating) postInvalidateOnAnimation()
        }

        private fun sixtyBulbs() = 60

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
