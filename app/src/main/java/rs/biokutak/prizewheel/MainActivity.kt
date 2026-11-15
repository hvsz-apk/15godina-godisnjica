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
    private var spinning = false
    private var spinStart = 0L
    private var spinTarget = -1
    private var carouselIndex = 0
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
        if (opening || reveal || spinning || index !in 0..14) return
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

    private fun startSpin() {
        if (opening || reveal || spinning || selected >= 0) return
        val candidates = packages.indices.filter { available(packages[it]) }
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Svi poklon paketi su trenutno ispražnjeni.", Toast.LENGTH_LONG).show()
            return
        }
        spinTarget = candidates.random()
        spinning = true
        spinStart = System.currentTimeMillis()
        tones.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        screen.invalidate()
        screen.postInvalidateOnAnimation()
    }

    private fun finishSpin() {
        if (!spinning) return
        spinning = false
        selected = spinTarget
        screen.invalidate()
        tones.startTone(ToneGenerator.TONE_PROP_ACK, 180)
    }

    private fun nextCustomer() {
        if (opening) return
        selected = -1
        spinning = false
        spinTarget = -1
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
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Direction keys only move the idle carousel. They must never trigger a draw/open.
                if (!spinning && !opening && !reveal && selected < 0) {
                    carouselIndex = (carouselIndex + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_UP) 14 else 1) % 15
                    screen.invalidate()
                }
                return true
            }
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

        private val stageBitmap: Bitmap by lazy { BitmapFactory.decodeResource(resources, R.drawable.anniversary_stage) }
        private fun drawBackdrop(c: Canvas, w: Float, h: Float) {
            val bmp=stageBitmap
            val scale=max(w/bmp.width,h/bmp.height)
            val sw=bmp.width*scale; val sh=bmp.height*scale
            val dst=RectF((w-sw)/2f,(h-sh)/2f,(w+sw)/2f,(h+sh)/2f)
            paint(Color.BLACK); c.drawRect(0f,0f,w,h,p)
            p.color=Color.WHITE; p.alpha=255; c.drawBitmap(bmp,null,dst,p)
            val now=System.currentTimeMillis()
            for (s in sparkles) {
                val y=((s[1]+(now%16000L)/16000f*.12f)%1f)*h
                val twinkle=(75+115*(.5+.5*sin(now/550.0+s[3]))).toInt()
                drawStar(c,s[0]*w,y,s[2]*.75f,twinkle)
            }
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w=width.toFloat(); val h=height.toFloat()
            drawBackdrop(c,w,h)
            // Grand anniversary title in the background
            text(c,"BIO KUTAK",w*.5f,h*.105f,h*.055f,paleGold)
            text(c,"15 GODINA SA VAMA",w*.5f,h*.165f,h*.034f,cream)
            text(c,"HVALA ŠTO STE DEO NAŠE PRIČE",w*.5f,h*.205f,h*.018f,gold,false)
            // A single hero gift on a circular golden roulette stage
            val cx=w*.5f; val cy=h*.565f
            val radius=min(w*.265f,h*.285f)
            val now=System.currentTimeMillis()
            val rotation=if(spinning) {
                val elapsed=(now-spinStart).toFloat()
                val t=(elapsed/4200f).coerceIn(0f,1f)
                val eased=1f-(1f-t).pow(3)
                val totalTurns=7.5f
                val targetAngle=spinTarget.coerceAtLeast(0)*(360f/15f)
                // The selected marker must finish exactly at the fixed pointer (right-hand side).
                (eased*totalTurns*360f - targetAngle) % 360f
            } else carouselIndex*24f
            // Outer glowing ring
            paint(Color.argb(45,255,207,104)); p.setShadowLayer(35f,0f,0f,Color.rgb(255,179,54))
            c.drawCircle(cx,cy,radius*1.22f,p); p.clearShadowLayer()
            paint(gold,Paint.Style.STROKE,5f); c.drawCircle(cx,cy,radius*1.18f,p)
            paint(Color.argb(120,255,231,166),Paint.Style.STROKE,1.5f); c.drawCircle(cx,cy,radius*1.10f,p)
            // Moving marquee bulbs
            for(i in 0 until 36) {
                val a=i*Math.PI*2/36.0 + if(spinning) now/900.0 else 0.0
                val bx=cx+cos(a).toFloat()*radius*1.18f
                val by=cy+sin(a).toFloat()*radius*1.18f
                drawStar(c,bx,by,if(i%3==0) 4f else 2.3f,if(i%3==0) 255 else 170)
            }
            // Fifteen premium package markers orbit around the hero gift.
            for(i in 0 until 15) {
                val a=(i*24f+rotation)*Math.PI/180.0
                val px=cx+cos(a).toFloat()*radius*.91f
                val py=cy+sin(a).toFloat()*radius*.91f
                val active=if(spinning) i==spinTarget else i==selected
                val dotR=if(active) 8f else 4f
                paint(if(available(packages[i])) Color.rgb(255,211,125) else Color.rgb(82,65,58))
                p.setShadowLayer(if(active) 22f else 5f,0f,0f,if(active) Color.rgb(255,173,57) else Color.BLACK)
                c.drawCircle(px,py,dotR,p); p.clearShadowLayer()
                if(active) {
                    paint(Color.rgb(255,241,195),Paint.Style.STROKE,2f)
                    c.drawCircle(px,py,14f,p); p.style=Paint.Style.FILL
                }
            }
            // Fixed pointer makes the selected package unambiguous when the carousel stops.
            val pointerX=cx+radius*1.12f
            val pointerY=cy
            val pointer=Path().apply {
                moveTo(pointerX+radius*.11f,pointerY)
                lineTo(pointerX+radius*.02f,pointerY-radius*.075f)
                lineTo(pointerX+radius*.02f,pointerY+radius*.075f)
                close()
            }
            p.shader=LinearGradient(pointerX,pointerY-radius*.08f,pointerX,pointerY+radius*.08f,intArrayOf(Color.rgb(255,244,196),Color.rgb(193,126,37)),null,Shader.TileMode.CLAMP)
            c.drawPath(pointer,p); p.shader=null
            paint(Color.rgb(255,238,177),Paint.Style.STROKE,2f); c.drawPath(pointer,p)
            // Centre pedestal and hero gift
            val pedestal=RectF(cx-radius*.72f,cy+radius*.52f,cx+radius*.72f,cy+radius*.77f)
            rounded(c,pedestal,30f,intArrayOf(Color.rgb(255,220,133),Color.rgb(121,69,21),Color.rgb(255,211,105)),Color.rgb(255,240,184),2f)
            val giftRect=RectF(cx-radius*.68f,cy-radius*.72f,cx+radius*.68f,cy+radius*.62f)
            if(opening) {
                val t=((now-animationStart)/4800f).coerceIn(0f,1f)
                animationProgress=t
                val ease=if(t<.58f) t/.58f else 1f-(1f-(t-.58f)/.42f).pow(3)
                c.save()
                val scale=1f+.20f*sin(ease*Math.PI).toFloat()
                c.scale(scale,scale,cx,cy)
                drawGift(c,giftRect,t>.68f,ease,true)
                c.restore()
                if(t<1f) postInvalidateOnAnimation() else finishOpening()
            } else {
                drawGift(c,giftRect,false,0f,true)
            }
            // Current package index, no package grid
            if(selected>=0 && !reveal) {
                val tag=RectF(cx-radius*.36f,cy+radius*.82f,cx+radius*.36f,cy+radius*1.04f)
                rounded(c,tag,18f,intArrayOf(Color.rgb(255,225,144),Color.rgb(155,91,24)),Color.WHITE,1.5f)
                text(c,"PAKET ${selected+1}",cx,tag.centerY()+h*.009f,h*.025f,Color.rgb(49,17,8))
            }
            val headline=when {
                reveal -> "VEČE ZA PAMĆENJE — ČESTITAMO!"
                opening -> "OTVARA SE VAŠ POKLON..."
                spinning -> "IZVLAČIMO VAŠ POKLON..."
                selected>=0 -> "PRITISNITE OK I OTVORITE PAKET"
                else -> "PRITISNITE OK I ZAVRTITE POKLON-KOLO"
            }
            text(c,headline,cx,h*.875f,h*.026f,gold)
            if(spinning) {
                val t=((now-spinStart)/4200f).coerceIn(0f,1f)
                carouselIndex=(carouselIndex+1)%15
                if(t>=1f) finishSpin() else postInvalidateOnAnimation()
            }
            if(reveal) {
                paint(Color.argb(205,3,1,7)); c.drawRect(0f,0f,w,h,p)
                val panel=RectF(w*.17f,h*.19f,w*.83f,h*.84f)
                p.shader=LinearGradient(panel.left,panel.top,panel.right,panel.bottom,intArrayOf(Color.rgb(92,7,27),Color.rgb(20,5,14),Color.rgb(71,5,23)),null,Shader.TileMode.CLAMP)
                c.drawRoundRect(panel,34f,34f,p); p.shader=null
                paint(Color.rgb(255,214,112),Paint.Style.STROKE,5f); c.drawRoundRect(panel,34f,34f,p)
                val tnow=System.currentTimeMillis()
                for(burst in 0..4) {
                    val bx=panel.left+panel.width()*(.12f+burst*.19f)
                    val by=panel.top+panel.height()*(.18f+(burst%2)*.09f)
                    for(k in 0..23) {
                        val a=k*Math.PI*2/24
                        val d=18f+((tnow/30+k*7+burst*19)%65)
                        drawStar(c,bx+cos(a).toFloat()*d,by+sin(a).toFloat()*d,2.5f,230)
                    }
                }
                text(c,"ČESTITAMO!",cx,h*.32f,h*.065f,paleGold)
                text(c,"OSVOJILI STE",cx,h*.405f,h*.028f,cream)
                val prizePlate=RectF(w*.25f,h*.455f,w*.75f,h*.59f)
                rounded(c,prizePlate,18f,intArrayOf(Color.rgb(255,227,153),Color.rgb(157,97,27),Color.rgb(255,218,119)),Color.WHITE,2f)
                text(c,rewardText.uppercase().take(48),cx,h*.535f,h*.037f,Color.rgb(47,13,9))
                text(c,"HVALA ŠTO STE DEO NAŠE PRIČE",cx,h*.645f,h*.023f,paleGold)
                text(c,"BIO KUTAK  •  15 GODINA SA VAMA",cx,h*.69f,h*.018f,cream,false)
                val btn=RectF(w*.36f,h*.735f,w*.64f,h*.815f)
                rounded(c,btn,18f,intArrayOf(Color.rgb(255,214,116),Color.rgb(139,77,18)),Color.WHITE,2f)
                text(c,"SLEDEĆI KUPAC",cx,h*.768f,h*.023f,Color.rgb(43,12,7))
            }
            val admin=RectF(w*.885f,h*.035f,w*.975f,h*.105f)
            rounded(c,admin,12f,intArrayOf(Color.rgb(110,65,21),Color.rgb(40,19,12)),gold,1.5f)
            text(c,"ADMIN",admin.centerX(),admin.centerY()+h*.007f,h*.018f,paleGold)
            if(!reveal && !opening && !spinning) postInvalidateDelayed(80)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if(event.action!=MotionEvent.ACTION_UP) return true
            val x=event.x/width; val y=event.y/height
            if(x>.88f && y<.12f) { askPin { adminDialog() }; return true }
            if(reveal) { if(x in .30f.. .70f && y in .70f.. .85f) nextCustomer(); return true }
            if(spinning || opening) return true
            if(selected<0) startSpin() else choosePackage(selected)
            return true
        }

        fun activateFocus() {
            if(reveal) nextCustomer()
            else if(!spinning && !opening) {
                if(selected<0) startSpin() else choosePackage(selected)
            }
        }
    }
}
