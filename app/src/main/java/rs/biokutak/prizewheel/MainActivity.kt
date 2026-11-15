package rs.biokutak.prizewheel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.media.AudioManager
import android.media.ToneGenerator
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
    private val tones by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 78) }

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
        tones.startTone(ToneGenerator.TONE_PROP_BEEP, 180)
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
        tones.startTone(ToneGenerator.TONE_PROP_ACK, 140)
        screen.invalidate()
    }

    private fun finishOpening() {
        if (!opening) return
        opening = false
        reveal = true
        tones.startTone(ToneGenerator.TONE_PROP_BEEP2, 1100)
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
            rows.addView(TextView(this).apply { text = "NAZIV PAKETA"; textSize = 12f; setTextColor(Color.DKGRAY) })
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
            KeyEvent.KEYCODE_DPAD_LEFT -> { screen.moveFocus(-1, 0); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { screen.moveFocus(1, 0); return true }
            KeyEvent.KEYCODE_DPAD_UP -> { screen.moveFocus(0, -1); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { screen.moveFocus(0, 1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                screen.activateFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    inner class PackageView : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val gold = Color.rgb(244, 194, 77)
        private val paleGold = Color.rgb(255, 231, 166)
        private val cream = Color.rgb(255, 247, 226)
        private val ruby = Color.rgb(147, 8, 34)
        private val deep = Color.rgb(12, 4, 12)
        private val rect = RectF()
        private var focused = 0
        private val sparkles = Array(90) { i -> floatArrayOf(Random.nextFloat(), Random.nextFloat(), 1.2f + Random.nextFloat() * 3f, Random.nextFloat() * 6.28f) }

        private fun paint(color: Int, style: Paint.Style = Paint.Style.FILL, width: Float = 1f) {
            p.shader = null; p.color = color; p.style = style; p.strokeWidth = width; p.alpha = 255
        }

        private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER) {
            paint(color)
            p.textSize = size; p.textAlign = align
            p.typeface = Typeface.create(if (bold) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            p.setShadowLayer(if (color == gold || color == paleGold) 12f else 2f, 0f, 2f, Color.argb(150, 0, 0, 0))
            c.drawText(value, x, y, p); p.clearShadowLayer()
        }

        private fun rounded(c: Canvas, r: RectF, radius: Float, colors: IntArray, stroke: Int? = null, sw: Float = 1f) {
            p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, colors, null, Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL; c.drawRoundRect(r, radius, radius, p); p.shader = null
            if (stroke != null) { paint(stroke, Paint.Style.STROKE, sw); c.drawRoundRect(r, radius, radius, p); p.style = Paint.Style.FILL }
        }

        private fun drawStar(c: Canvas, x: Float, y: Float, radius: Float, alpha: Int) {
            paint(Color.argb(alpha, 255, 220, 130))
            p.setShadowLayer(radius * 3f, 0f, 0f, Color.rgb(255, 177, 45))
            c.drawCircle(x, y, radius, p); p.clearShadowLayer()
        }

        private fun drawGift(c: Canvas, r: RectF, opened: Boolean, phase: Float, hero: Boolean = false) {
            val cx = r.centerX()
            val baseW = r.width() * if (hero) .62f else .70f
            val baseH = r.height() * if (hero) .43f else .42f
            val left = cx - baseW / 2f
            val top = r.centerY() - baseH * .30f
            val right = cx + baseW / 2f
            val bottom = top + baseH
            val lift = if (opened) -baseH * (.22f + .60f * phase) else 0f
            // Ground shadow and luminous pedestal
            paint(Color.argb(120, 0, 0, 0)); p.setShadowLayer(18f, 0f, 8f, Color.BLACK)
            c.drawOval(RectF(left - baseW*.12f, bottom - 2f, right + baseW*.12f, bottom + baseH*.20f), p); p.clearShadowLayer()
            // Gift body with dimensional side face
            val front = RectF(left, top, right, bottom)
            p.shader = LinearGradient(left, top, right, bottom, intArrayOf(Color.rgb(255, 57, 69), Color.rgb(137, 3, 29), Color.rgb(67, 1, 20)), floatArrayOf(0f,.52f,1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(front, baseW*.035f, baseW*.035f, p); p.shader = null
            val side = Path().apply { moveTo(right, top); lineTo(right + baseW*.12f, top - baseH*.09f); lineTo(right + baseW*.12f, bottom - baseH*.08f); lineTo(right, bottom); close() }
            p.shader = LinearGradient(right, top, right + baseW*.12f, bottom, intArrayOf(Color.rgb(105,0,22),Color.rgb(35,0,12)),null,Shader.TileMode.CLAMP)
            c.drawPath(side,p); p.shader = null
            val lidY = top - baseH*.12f + lift
            val lid = RectF(left-baseW*.07f, lidY, right+baseW*.07f, lidY+baseH*.17f)
            p.shader = LinearGradient(lid.left,lid.top,lid.right,lid.bottom,intArrayOf(Color.rgb(255,224,140),Color.rgb(180,111,27),Color.rgb(255,207,91)),null,Shader.TileMode.CLAMP)
            c.drawRoundRect(lid,baseW*.035f,baseW*.035f,p); p.shader = null
            // Wide metallic ribbon wrapping body
            val ribbonW = baseW*.16f
            p.shader = LinearGradient(cx-ribbonW/2,top,cx+ribbonW/2,bottom,intArrayOf(Color.rgb(255,240,174),Color.rgb(190,123,34),Color.rgb(255,222,119)),null,Shader.TileMode.CLAMP)
            c.drawRect(cx-ribbonW/2,top,cx+ribbonW/2,bottom,p); p.shader = null
            p.shader = LinearGradient(left,top+baseH*.38f,right,top+baseH*.51f,intArrayOf(Color.rgb(255,225,133),Color.rgb(170,104,25),Color.rgb(255,220,118)),null,Shader.TileMode.CLAMP)
            c.drawRect(left,top+baseH*.38f,right,top+baseH*.51f,p); p.shader = null
            // Floating lid and metallic bow
            if (opened) c.save()
            val bowY = lidY + if (opened) -baseH*.02f else 0f
            paint(paleGold,Paint.Style.STROKE, max(3f,baseW*.035f)); p.strokeCap = Paint.Cap.ROUND
            val bow = Path().apply {
                moveTo(cx,bowY)
                cubicTo(cx-baseW*.55f,bowY-baseH*.55f,cx-baseW*.52f,bowY+baseH*.04f,cx,bowY)
                cubicTo(cx+baseW*.52f,bowY+baseH*.04f,cx+baseW*.55f,bowY-baseH*.55f,cx,bowY)
            }
            c.drawPath(bow,p); p.style = Paint.Style.FILL
            paint(Color.rgb(255,232,155)); c.drawCircle(cx,bowY,baseW*.045f,p)
            // Glowing magic burst inside the open gift
            if (opened) {
                val glow = Paint(Paint.ANTI_ALIAS_FLAG)
                glow.shader = RadialGradient(cx,top,baseW*.85f,intArrayOf(Color.argb((190*(1f-phase)).toInt().coerceIn(0,190),255,228,145),Color.argb(0,255,176,40)),null,Shader.TileMode.CLAMP)
                c.drawCircle(cx,top,baseW*.85f,glow)
                for (i in 0..25) {
                    val a = i * (Math.PI*2/26.0) + phase*2
                    val dist = baseW*(.18f + phase*.65f)
                    drawStar(c,cx+cos(a).toFloat()*dist,top+sin(a).toFloat()*dist,2f+phase*3f,220)
                }
                c.restore()
            }
        }

        private fun drawBackdrop(c: Canvas, w: Float, h: Float) {
            p.shader = RadialGradient(w*.5f,h*.35f,w*.82f,intArrayOf(Color.rgb(94,10,30),Color.rgb(35,4,18),Color.rgb(5,3,9)),null,Shader.TileMode.CLAMP)
            c.drawRect(0f,0f,w,h,p); p.shader = null
            // theatre spotlights
            val sweep = (System.currentTimeMillis()%9000L)/9000f
            for (i in 0..1) {
                val sx = if (i==0) w*.08f else w*.92f
                val ex = w*(if(i==0) .34f+.08f*sin(sweep*6.28f) else .66f-.08f*sin(sweep*6.28f))
                val beam = Path().apply { moveTo(sx,h*.04f); lineTo(ex-w*.13f,h*.72f); lineTo(ex+w*.13f,h*.72f); close() }
                p.shader = LinearGradient(sx,h*.05f,ex,h*.72f,intArrayOf(Color.argb(0,255,211,123),Color.argb(24,255,188,75),Color.argb(0,255,211,123)),null,Shader.TileMode.CLAMP)
                c.drawPath(beam,p); p.shader = null
                paint(Color.argb(180,255,229,161)); p.setShadowLayer(18f,0f,0f,Color.rgb(255,174,52))
                c.drawCircle(sx,h*.045f,h*.009f,p); p.clearShadowLayer()
            }
            val now = System.currentTimeMillis()
            for (s in sparkles) {
                val y = ((s[1] + (now%16000L)/16000f*.12f) % 1f) * h
                val twinkle = (100 + 130*(.5+.5*sin(now/550.0+s[3]))).toInt()
                drawStar(c,s[0]*w,y,s[2],twinkle)
            }
            // Fine double gold frame
            paint(Color.argb(180,222,171,76),Paint.Style.STROKE,2f); c.drawRoundRect(RectF(12f,10f,w-12f,h-10f),26f,26f,p)
            paint(Color.argb(75,255,232,166),Paint.Style.STROKE,1f); c.drawRoundRect(RectF(20f,18f,w-20f,h-18f),22f,22f,p)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w=width.toFloat(); val h=height.toFloat()
            drawBackdrop(c,w,h)
            // Header plaque
            val plaque=RectF(w*.22f,h*.025f,w*.78f,h*.155f)
            rounded(c,plaque,22f,intArrayOf(Color.rgb(58,7,22),Color.rgb(19,5,12)),gold,2.5f)
            text(c,"BIO KUTAK",w*.5f,h*.078f,h*.052f,paleGold)
            text(c,"15 GODINA ZAJEDNO",w*.5f,h*.126f,h*.025f,cream)
            text(c,if(reveal) "VEČE ZA PAMĆENJE — ČESTITAMO!" else "IZABERITE SVOJ SREĆNI POKLON",w*.5f,h*.205f,h*.029f,gold)
            // subtle decorative separators
            paint(Color.argb(190,213,158,63),Paint.Style.STROKE,1.5f)
            c.drawLine(w*.12f,h*.222f,w*.34f,h*.222f,p); c.drawLine(w*.66f,h*.222f,w*.88f,h*.222f,p)
            val mx=w*.045f; val gx=w*.014f; val gy=h*.022f
            val top=h*.255f; val bottom=h*.885f
            val cw=(w-2*mx-4*gx)/5f; val ch=(bottom-top-2*gy)/3f
            for(i in 0 until 15) {
                val col=i%5; val row=i/5
                val x=mx+col*(cw+gx); val y=top+row*(ch+gy)
                val card=RectF(x,y,x+cw,y+ch)
                val chosen=selected==i
                val enabled=available(packages[i])
                val dim=selected>=0 && !chosen
                val alpha=if(dim) 58 else if(!enabled) 70 else 255
                c.saveLayerAlpha(card,alpha)
                // card has bevelled dark glass and inset trim
                rounded(c,card,18f,intArrayOf(Color.rgb(80,13,31),Color.rgb(23,7,17),Color.rgb(52,7,22)),if(chosen) paleGold else if(enabled) Color.rgb(187,132,48) else Color.rgb(79,65,61),if(chosen) 4f else 1.5f)
                val inner=RectF(card.left+5,card.top+5,card.right-5,card.bottom-5)
                paint(Color.argb(65,255,228,155),Paint.Style.STROKE,1f); c.drawRoundRect(inner,14f,14f,p)
                if(chosen && opening) {
                    val t=((System.currentTimeMillis()-animationStart)/4800f).coerceIn(0f,1f)
                    animationProgress=t
                    val ease=if(t<.58f) t/.58f else 1f-(1f-(t-.58f)/.42f).pow(3)
                    val br=RectF(card.left+cw*.07f,card.top+ch*.02f,card.right-cw*.07f,card.bottom-ch*.02f)
                    c.save(); val scale=1f+.18f*sin(ease*Math.PI).toFloat()
                    c.scale(scale,scale,card.centerX(),card.centerY())
                    drawGift(c,br,t>.68f,ease,false); c.restore()
                    text(c,if(t<.68f) "OTVARANJE POKLONA..." else "MAGIJA!",card.centerX(),card.bottom-h*.012f,h*.017f,gold)
                    if(t<1f) postInvalidateOnAnimation() else finishOpening()
                } else {
                    // embossed package number medallion
                    val med=RectF(card.centerX()-cw*.13f,card.top+ch*.025f,card.centerX()+cw*.13f,card.top+ch*.15f)
                    rounded(c,med,med.height()*.45f,intArrayOf(Color.rgb(255,220,130),Color.rgb(139,81,20)),Color.rgb(255,239,182),1f)
                    text(c,String.format("%02d",i+1),card.centerX(),med.centerY()+h*.008f,h*.022f,Color.rgb(55,20,8))
                    val br=RectF(card.left+cw*.06f,card.top+ch*.12f,card.right-cw*.06f,card.bottom-ch*.10f)
                    drawGift(c,br,chosen&&reveal,1f,false)
                    text(c,packages[i].name.uppercase().take(20),card.centerX(),card.bottom-h*.018f,h*.016f,cream)
                    if(!enabled) text(c,"RASPRODATO",card.centerX(),card.centerY()+h*.045f,h*.018f,Color.rgb(255,150,150))
                }
                if(i==focused && selected<0 && enabled) {
                    paint(Color.argb(220,255,224,135),Paint.Style.STROKE,2.5f)
                    c.drawRoundRect(RectF(card.left-3,card.top-3,card.right+3,card.bottom+3),20f,20f,p)
                }
                c.restore()
            }
            if(reveal) {
                // Full-screen cinematic prize reveal over a darkened stage
                paint(Color.argb(205,3,1,7)); c.drawRect(0f,0f,w,h,p)
                val panel=RectF(w*.17f,h*.19f,w*.83f,h*.84f)
                p.shader=LinearGradient(panel.left,panel.top,panel.right,panel.bottom,intArrayOf(Color.rgb(92,7,27),Color.rgb(20,5,14),Color.rgb(71,5,23)),null,Shader.TileMode.CLAMP)
                c.drawRoundRect(panel,34f,34f,p); p.shader=null
                paint(Color.rgb(255,214,112),Paint.Style.STROKE,5f); c.drawRoundRect(panel,34f,34f,p)
                paint(Color.argb(100,255,232,160),Paint.Style.STROKE,1.5f); c.drawRoundRect(RectF(panel.left+10,panel.top+10,panel.right-10,panel.bottom-10),27f,27f,p)
                // gold fireworks
                val now=System.currentTimeMillis()
                for(burst in 0..3) {
                    val bx=panel.left+panel.width()*(.18f+burst*.21f)
                    val by=panel.top+panel.height()*(.20f+(burst%2)*.08f)
                    for(k in 0..23) {
                        val a=k*Math.PI*2/24
                        val d=18f+((now/30+k*7+burst*19)%55)
                        drawStar(c,bx+cos(a).toFloat()*d,by+sin(a).toFloat()*d,2.2f,220)
                    }
                }
                text(c,"ČESTITAMO!",w*.5f,h*.32f,h*.065f,paleGold)
                text(c,"OSVOJILI STE",w*.5f,h*.405f,h*.028f,cream)
                // prize presented as a gold illuminated title plate
                val prizePlate=RectF(w*.25f,h*.455f,w*.75f,h*.59f)
                rounded(c,prizePlate,18f,intArrayOf(Color.rgb(255,227,153),Color.rgb(157,97,27),Color.rgb(255,218,119)),Color.WHITE,2f)
                text(c,rewardText.uppercase().take(48),w*.5f,h*.535f,h*.037f,Color.rgb(47,13,9))
                text(c,"HVALA ŠTO STE DEO NAŠE PRIČE",w*.5f,h*.645f,h*.023f,paleGold)
                text(c,"BIO KUTAK  •  15 GODINA ZAJEDNO",w*.5f,h*.69f,h*.018f,cream,false)
                val btn=RectF(w*.36f,h*.735f,w*.64f,h*.815f)
                rounded(c,btn,18f,intArrayOf(Color.rgb(255,214,116),Color.rgb(139,77,18)),Color.WHITE,2f)
                text(c,"SLEDEĆI KUPAC",w*.5f,h*.768f,h*.023f,Color.rgb(43,12,7))
            } else {
                text(c,if(opening) "SAČEKAJTE IZNENAĐENJE..." else "DODIRNITE PAKET I OTKRIJTE SVOJ POKLON",w*.5f,h*.945f,h*.022f,cream,false)
            }
            // discreet but visible admin control
            val admin=RectF(w*.885f,h*.035f,w*.975f,h*.105f)
            rounded(c,admin,12f,intArrayOf(Color.rgb(110,65,21),Color.rgb(40,19,12)),gold,1.5f)
            text(c,"ADMIN",admin.centerX(),admin.centerY()+h*.007f,h*.018f,paleGold)
            if(!reveal) postInvalidateOnAnimation()
        }

        private fun packageAt(x:Float,y:Float):Int {
            val mx=.045f; val gx=.014f; val gy=.022f; val top=.255f; val bottom=.885f
            val cw=(1f-2*mx-4*gx)/5f; val ch=(bottom-top-2*gy)/3f
            if(x<mx || x>1f-mx || y<top || y>bottom) return -1
            val col=((x-mx)/(cw+gx)).toInt().coerceIn(0,4)
            val row=((y-top)/(ch+gy)).toInt().coerceIn(0,2)
            val lx=(x-mx)-col*(cw+gx); val ly=(y-top)-row*(ch+gy)
            return if(lx<=cw && ly<=ch) row*5+col else -1
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action!=MotionEvent.ACTION_UP) return true
            val x=event.x/width; val y=event.y/height
            if(x>.88f && y<.12f) { askPin { adminDialog() }; return true }
            if(reveal) { if(x in .30f.. .70f && y in .70f.. .85f) nextCustomer(); return true }
            if(!opening && selected<0) packageAt(x,y).takeIf { it>=0 }?.let { focused=it; choosePackage(it) }
            return true
        }

        fun moveFocus(dx:Int,dy:Int) {
            if(selected>=0 || opening || reveal) return
            val col=(focused%5+dx).coerceIn(0,4)
            val row=(focused/5+dy).coerceIn(0,2)
            focused=row*5+col; invalidate()
        }
        fun activateFocus() {
            if(reveal) nextCustomer() else if(!opening && selected<0) choosePackage(focused)
        }
    }
}
