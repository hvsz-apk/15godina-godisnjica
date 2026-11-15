package rs.biokutak.prizewheel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

data class GiftPrize(var name: String, var stock: Int, var percent: Int, var enabled: Boolean)
data class GiftPackage(var name: String, val rewards: MutableList<GiftPrize>)

class MainActivity : Activity() {
    private val pin = "1515"
    private val prefs by lazy { getSharedPreferences("bio_kutak_15_packages", MODE_PRIVATE) }
    private val packages = mutableListOf<GiftPackage>()
    private var selected = -1
    private var opening = false
    private var reveal = false
    private var rewardText = ""
    private var animationStart = 0L
    private var animationProgress = 0f
    private var history = 0
    private lateinit var screen: PackageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load()
        screen = PackageView()
        setContentView(screen)
    }

    private fun defaults(): MutableList<GiftPackage> = MutableList(15) { i ->
        GiftPackage("POKLON PAKET ${i + 1}", mutableListOf(
            GiftPrize("Poklon iznenađenja", 5, 60, true),
            GiftPrize("Vaučer 200 RSD", 3, 30, true),
            GiftPrize("Specijalni poklon", 1, 10, true)
        ))
    }

    private fun load() {
        packages.clear()
        val raw = prefs.getString("packages", null)
        if (raw.isNullOrBlank()) packages.addAll(defaults()) else try {
            val a = JSONArray(raw)
            for (i in 0 until 15) {
                val o = a.optJSONObject(i)
                if (o == null) { packages.add(defaults()[i]); continue }
                val rewards = mutableListOf<GiftPrize>()
                val ra = o.optJSONArray("rewards") ?: JSONArray()
                for (j in 0 until ra.length()) {
                    val p = ra.getJSONObject(j)
                    rewards.add(GiftPrize(p.optString("name", "Poklon"), p.optInt("stock", 0).coerceAtLeast(0), p.optInt("percent", 0).coerceAtLeast(0), p.optBoolean("enabled", true)))
                }
                packages.add(GiftPackage(o.optString("name", "PAKET ${i + 1}"), rewards))
            }
        } catch (_: Exception) { packages.clear(); packages.addAll(defaults()) }
        history = prefs.getInt("history", 0)
    }

    private fun save() {
        val a = JSONArray()
        packages.forEach { box ->
            val ra = JSONArray()
            box.rewards.forEach { p -> ra.put(JSONObject().put("name", p.name).put("stock", p.stock).put("percent", p.percent).put("enabled", p.enabled)) }
            a.put(JSONObject().put("name", box.name).put("rewards", ra))
        }
        prefs.edit().putString("packages", a.toString()).putInt("history", history).apply()
    }

    private fun available(box: GiftPackage) = box.rewards.any { it.enabled && it.stock > 0 && it.percent > 0 }

    private fun choosePackage(index: Int) {
        if (opening || reveal || index !in 0..14) return
        val box = packages[index]
        if (!available(box)) {
            Toast.makeText(this, "Ovaj paket je trenutno ispražnjen.", Toast.LENGTH_SHORT).show()
            return
        }
        val eligible = box.rewards.filter { it.enabled && it.stock > 0 && it.percent > 0 }
        val total = eligible.sumOf { it.percent }
        var roll = Random.nextInt(total)
        val prize = eligible.first { roll -= it.percent; roll < 0 }
        // Reserve and persist the stock immediately: a restart during the animation cannot award it twice.
        prize.stock = (prize.stock - 1).coerceAtLeast(0)
        save()
        selected = index
        rewardText = prize.name
        opening = true
        reveal = false
        animationProgress = 0f
        animationStart = System.currentTimeMillis()
        screen.invalidate()
        screen.postInvalidateOnAnimation()
    }

    private fun nextCustomer() {
        if (opening) return
        selected = -1
        reveal = false
        rewardText = ""
        screen.invalidate()
    }

    private fun finishOpening() {
        if (!opening) return
        opening = false
        reveal = true
        history++
        save()
        screen.invalidate()
    }

    private fun askPin(action: () -> Unit) {
        val field = EditText(this).apply { hint = "PIN"; inputType = 2; setSingleLine() }
        AlertDialog.Builder(this).setTitle("BIO KUTAK • ADMIN").setMessage("Unesite administratorski PIN")
            .setView(field).setNegativeButton("OTKAŽI", null)
            .setPositiveButton("POTVRDI") { _, _ ->
                if (field.text.toString() == pin) action()
                else Toast.makeText(this, "Pogrešan PIN", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun adminDialog() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 12, 22, 12) }
        val scroll = ScrollView(this).apply { addView(list) }
        packages.forEachIndexed { i, box ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val info = TextView(this).apply {
                text = "${i + 1}.  ${box.name}\n   ${box.rewards.count { it.enabled && it.stock > 0 }} aktivnih nagrada"
                textSize = 17f; setTextColor(Color.BLACK); setPadding(12, 15, 8, 15)
            }
            val edit = Button(this).apply { text = "UREDI"; setOnClickListener { editPackage(i) } }
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); row.addView(edit)
            list.addView(row)
            val line = View(this).apply { setBackgroundColor(Color.LTGRAY) }
            list.addView(line, LinearLayout.LayoutParams(-1, 1))
        }
        AlertDialog.Builder(this).setTitle("ADMIN PANEL • 15 PAKETA")
            .setView(scroll).setPositiveButton("ZATVORI", null)
            .setNeutralButton("RESET KOLIČINA", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        AlertDialog.Builder(this).setTitle("Reset količina?")
                            .setMessage("Vratiće početne vrednosti svih 15 paketa. Istorija ostaje sačuvana.")
                            .setNegativeButton("NE", null).setPositiveButton("DA") { _, _ ->
                                packages.clear(); packages.addAll(defaults()); save(); screen.invalidate(); dialog.dismiss()
                            }.show()
                    }
                }
                dialog.show()
            }
    }

    private data class RewardFields(val name: EditText, val stock: EditText, val percent: EditText, val enabled: CheckBox)

    private fun editPackage(index: Int) {
        val box = packages[index]
        val packageName = EditText(this).apply { setText(box.name); hint = "Naziv paketa"; textSize = 18f }
        val editors = box.rewards.map { newRewardFields(it) }.toMutableList()
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(rows) }
        fun rebuild() {
            rows.removeAllViews()
            rows.addView(TextView(this).apply { text = "NAZIV PAKETA"; textSize = 12f; setTextColor(Color.DKGRAY })
            rows.addView(packageName)
            rows.addView(TextView(this).apply { text = "NAGRADE U OVOM PAKETU"; textSize = 16f; setTextColor(Color.BLACK); setPadding(0, 20, 0, 8) })
            editors.forEachIndexed { n, e ->
                val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 12); setBackgroundColor(Color.rgb(244, 239, 229)) }
                card.addView(TextView(this).apply { text = "NAGRADA ${n + 1}"; setTextColor(Color.BLACK); textSize = 14f })
                e.name.hint = "Naziv nagrade"; e.stock.hint = "Komada na stanju"; e.percent.hint = "Verovatnoća (%)"
                listOf(e.name, e.stock, e.percent).forEach { it.setTextColor(Color.BLACK); card.addView(it) }
                e.enabled.text = "Aktivna nagrada"; e.enabled.setTextColor(Color.BLACK); card.addView(e.enabled)
                card.addView(Button(this).apply { text = "OBRIŠI NAGRADU"; setOnClickListener { editors.removeAt(n); rebuild() } })
                val lp = LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, 8, 0, 8); rows.addView(card, lp)
            }
            rows.addView(Button(this).apply { text = "+ DODAJ NAGRADU"; setOnClickListener { editors.add(newRewardFields(GiftPrize("", 1, 1, true))); rebuild() } })
            rows.addView(TextView(this).apply {
                text = "Procenti su težine izvlačenja unutar ovog paketa. Zbir ne mora biti 100; sistem ih proporcionalno preračunava."
                textSize = 12f; setTextColor(Color.DKGRAY); setPadding(4, 12, 4, 12)
            })
        }
        rebuild()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8); addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)) }
        val dialog = AlertDialog.Builder(this).setTitle("PAKET ${index + 1} • PODEŠAVANJE").setView(root)
            .setNegativeButton("OTKAŽI", null).setPositiveButton("SAČUVAJ", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .86f).toInt(), (resources.displayMetrics.heightPixels * .86f).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val updated = mutableListOf<GiftPrize>()
                var bad = packageName.text.toString().isBlank()
                editors.forEach { e ->
                    val n = e.name.text.toString().trim()
                    val s = e.stock.text.toString().toIntOrNull()
                    val p = e.percent.text.toString().toIntOrNull()
                    if (n.isBlank() || s == null || s < 0 || p == null || p < 0) bad = true
                    else updated.add(GiftPrize(n, s, p, e.enabled.isChecked))
                }
                if (bad) Toast.makeText(this, "Proverite naziv, količinu i procenat.", Toast.LENGTH_LONG).show()
                else {
                    box.name = packageName.text.toString().trim()
                    box.rewards.clear(); box.rewards.addAll(updated); save(); screen.invalidate(); dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun newRewardFields(p: GiftPrize) = RewardFields(
        EditText(this).apply { setText(p.name) },
        EditText(this).apply { setText(p.stock.toString()); inputType = 2 },
        EditText(this).apply { setText(p.percent.toString()); inputType = 2 },
        CheckBox(this).apply { isChecked = p.enabled }
    )

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { askPin { adminDialog() }; return true }
            KeyEvent.KEYCODE_BACK -> { if (reveal) { nextCustomer(); return true }; askPin { adminDialog() }; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (reveal) nextCustomer() else if (!opening && selected < 0) choosePackage(0)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class PackageView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val gold = Color.rgb(239, 194, 91)
        private val cream = Color.rgb(255, 246, 224)
        private val red = Color.rgb(133, 13, 34)
        private val rect = RectF()

        private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER) {
            p.shader = null; p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.textAlign = align
            p.typeface = Typeface.create(if (bold) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(s, x, y, p)
        }

        private fun drawGift(c: Canvas, r: RectF, open: Boolean, scale: Float = 1f) {
            val cx = r.centerX(); val cy = r.centerY()
            val bw = r.width() * .57f * scale; val bh = r.height() * .42f * scale
            val left = cx - bw / 2; val top = cy - bh / 2 + r.height() * .08f
            val box = RectF(left, top, left + bw, top + bh)
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(left, top, left + bw, top + bh, intArrayOf(Color.rgb(210, 33, 49), Color.rgb(92, 4, 24)), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, 12f * scale, 12f * scale, p); p.shader = null
            p.color = gold; c.drawRect(cx - bw * .10f, top, cx + bw * .10f, top + bh, p)
            c.drawRect(left, top + bh * .38f, left + bw, top + bh * .52f, p)
            // Lid floats upward during the reveal.
            val lidY = if (open) top - bh * .62f else top - bh * .10f
            val lid = RectF(left - bw * .08f, lidY, left + bw * 1.08f, lidY + bh * .19f)
            p.shader = LinearGradient(lid.left, lid.top, lid.right, lid.bottom, intArrayOf(Color.rgb(255, 222, 133), Color.rgb(170, 112, 32)), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(lid, 8f * scale, 8f * scale, p); p.shader = null
            // Bow.
            p.style = Paint.Style.STROKE; p.strokeWidth = 7f * scale; p.strokeCap = Paint.Cap.ROUND; p.color = gold
            val bow = Path().apply { moveTo(cx, lidY); cubicTo(cx - bw * .45f, lidY - bh * .40f, cx - bw * .35f, lidY - bh * .05f, cx, lidY); cubicTo(cx + bw * .35f, lidY - bh * .05f, cx + bw * .45f, lidY - bh * .40f, cx, lidY) }
            c.drawPath(bow, p); p.style = Paint.Style.FILL
            if (open) {
                for (i in 0..17) {
                    val a = i * (Math.PI * 2 / 18)
                    val sx = cx + cos(a).toFloat() * bw * .56f
                    val sy = top - bh * .20f + sin(a).toFloat() * bh * .55f
                    p.color = if (i % 2 == 0) gold else Color.WHITE
                    c.drawCircle(sx, sy, 4f * scale, p)
                }
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat(); val h = height.toFloat()
            c.drawColor(Color.rgb(7, 5, 10))
            p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(Color.rgb(8, 6, 13), Color.rgb(51, 5, 20), Color.rgb(9, 6, 12)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.argb(150, 194, 145, 57)
            c.drawRoundRect(RectF(9f, 9f, w - 9f, h - 9f), 25f, 25f, p); p.style = Paint.Style.FILL
            txt(c, "BIO KUTAK", w * .5f, h * .075f, h * .058f, gold)
            txt(c, "15 GODINA ZAJEDNO", w * .5f, h * .125f, h * .027f, cream)
            txt(c, if (reveal) "VAŠ POKLON JE SPREMAN!" else "IZABERITE JEDAN OD 15 POKLON-PAKETA", w * .5f, h * .185f, h * .026f, gold)
            val marginX = w * .055f; val gapX = w * .018f; val gapY = h * .025f
            val gridTop = h * .235f; val gridBottom = h * .89f
            val cellW = (w - 2 * marginX - 4 * gapX) / 5f
            val cellH = (gridBottom - gridTop - 2 * gapY) / 3f
            for (i in 0 until 15) {
                val col = i % 5; val row = i / 5
                val x = marginX + col * (cellW + gapX); val y = gridTop + row * (cellH + gapY)
                val card = RectF(x, y, x + cellW, y + cellH)
                val isChosen = selected == i
                val isDimmed = selected >= 0 && selected != i
                val enabled = available(packages[i])
                val alpha = if (isDimmed) 55 else if (!enabled) 85 else 255
                p.alpha = alpha; p.shader = LinearGradient(x, y, x + cellW, y + cellH, intArrayOf(Color.rgb(83, 13, 29), Color.rgb(23, 10, 18), Color.rgb(60, 11, 25)), null, Shader.TileMode.CLAMP)
                c.drawRoundRect(card, 17f, 17f, p); p.shader = null; p.alpha = 255
                p.style = Paint.Style.STROKE; p.strokeWidth = if (isChosen) 5f else 2f; p.color = if (isChosen) Color.WHITE else if (enabled) gold else Color.rgb(91, 77, 71)
                c.drawRoundRect(card, 17f, 17f, p); p.style = Paint.Style.FILL
                if (isChosen && opening) {
                    val t = ((System.currentTimeMillis() - animationStart) / 4800f).coerceIn(0f, 1f)
                    animationProgress = t
                    val ease = if (t < .58f) (t / .58f) else 1f - (1f - (t - .58f) / .42f).pow(3)
                    val boxRect = RectF(card.left + cellW * .08f, card.top + cellH * .05f, card.right - cellW * .08f, card.bottom - cellH * .08f)
                    c.save(); val scale = 1f + .16f * sin(ease * Math.PI).toFloat()
                    c.scale(scale, scale, card.centerX(), card.centerY())
                    drawGift(c, boxRect, t > .72f, 1.15f)
                    c.restore()
                    txt(c, if (t < .72f) "OTVARANJE..." else "IZNENAĐENJE!", card.centerX(), card.bottom - 10f, h * .018f, gold)
                    if (t < 1f) postInvalidateOnAnimation() else finishOpening()
                } else {
                    val boxRect = RectF(card.left + cellW * .08f, card.top + cellH * .03f, card.right - cellW * .08f, card.bottom - cellH * .08f)
                    drawGift(c, boxRect, isChosen && reveal, 1f)
                    txt(c, String.format("%02d", i + 1), card.centerX(), card.top + h * .028f, h * .020f, gold)
                    txt(c, packages[i].name.uppercase().take(22), card.centerX(), card.bottom - h * .018f, h * .018f, if (enabled) cream else Color.LTGRAY)
                    if (!enabled) txt(c, "ISCRPLJEN", card.centerX(), card.centerY(), h * .022f, Color.rgb(255, 128, 128))
                }
            }
            if (reveal) {
                val overlay = RectF(w * .22f, h * .29f, w * .78f, h * .77f)
                p.color = Color.argb(245, 14, 8, 15); c.drawRoundRect(overlay, 30f, 30f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = gold; c.drawRoundRect(overlay, 30f, 30f, p); p.style = Paint.Style.FILL
                txt(c, "ČESTITAMO!", w * .5f, h * .405f, h * .060f, gold)
                txt(c, "OSVOJILI STE", w * .5f, h * .49f, h * .027f, cream)
                txt(c, rewardText.uppercase().take(42), w * .5f, h * .565f, h * .042f, Color.WHITE)
                txt(c, "HVALA ŠTO STE DEO NAŠE PRIČE", w * .5f, h * .625f, h * .020f, gold)
                val b = RectF(w * .36f, h * .68f, w * .64f, h * .745f)
                p.color = red; c.drawRoundRect(b, 18f, 18f, p); p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = gold; c.drawRoundRect(b, 18f, 18f, p); p.style = Paint.Style.FILL
                txt(c, "SLEDEĆI KUPAC", w * .5f, h * .725f, h * .024f, cream)
            } else if (!opening) {
                txt(c, "DODIRNITE PAKET KOJI ŽELITE", w * .5f, h * .94f, h * .021f, cream, false)
            } else {
                txt(c, "BIO KUTAK • 15 GODINA ZAJEDNO", w * .5f, h * .94f, h * .021f, gold)
            }
            // Admin remains discreet but clearly accessible at upper-right.
            val admin = RectF(w * .89f, h * .025f, w * .98f, h * .095f)
            p.color = Color.rgb(54, 30, 20); c.drawRoundRect(admin, 12f, 12f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = gold; c.drawRoundRect(admin, 12f, 12f, p); p.style = Paint.Style.FILL
            txt(c, "ADMIN", admin.centerX(), admin.centerY() + h * .008f, h * .018f, gold)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action != MotionEvent.ACTION_UP) return true
            val x = event.x / width; val y = event.y / height
            if (x > .88f && y < .12f) { askPin { adminDialog() }; return true }
            if (reveal) {
                if (x in .30f.. .70f && y in .65f.. .80f) nextCustomer()
                return true
            }
            if (!opening && selected < 0) {
                val marginX = .055f; val gapX = .018f; val gapY = .025f
                val gridTop = .235f; val gridBottom = .89f
                val cellW = (1f - 2 * marginX - 4 * gapX) / 5f
                val cellH = (gridBottom - gridTop - 2 * gapY) / 3f
                if (x >= marginX && x <= 1f - marginX && y >= gridTop && y <= gridBottom) {
                    val col = ((x - marginX) / (cellW + gapX)).toInt().coerceIn(0, 4)
                    val row = ((y - gridTop) / (cellH + gapY)).toInt().coerceIn(0, 2)
                    val lx = (x - marginX) - col * (cellW + gapX)
                    val ly = (y - gridTop) - row * (cellH + gapY)
                    if (lx <= cellW && ly <= cellH) choosePackage(row * 5 + col)
                }
            }
            return true
        }
    }
}
