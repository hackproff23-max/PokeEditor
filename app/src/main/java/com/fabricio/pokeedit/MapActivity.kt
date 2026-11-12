package com.fabricio.pokeedit

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.*

/** Memoria de permissao (colisao+elevacao) por metatile, para o modo Auto. */
object PermStore { val m = HashMap<Long, Int>() }

class MapActivity : Activity() {
    private lateinit var rom: ByteArray
    private var frlg = false
    private var primTiles = 512
    private var primMeta = 512
    private var primPals = 6
    private var layouts = IntArray(0)
    private var li = 0
    var mw = 0
    var mh = 0
    var blocks = IntArray(0)
    private var mapOff = -1
    private var priP = -1
    private var secP = -1
    private var priMetaP = -1
    private var secMetaP = -1
    private val tiles = ByteArray(1024 * 32)
    private val pals = IntArray(256)
    private val metaBmps = arrayOfNulls<Bitmap>(1024)
    var metaCount = 1
    var selMeta = 0
    var showCol = false
    private var tool = 0 // 0 pintar, 1 colisao, 2 pegar, 3 copiar, 4 colar
    private var collSel = 0
    private var elevSel = 3
    private var autoPerm = true
    private var dirty = false
    var selX0 = -1
    var selY0 = -1
    var selX1 = -1
    var selY1 = -1
    private var clip: IntArray? = null
    private var clipW = 0
    private var clipH = 0
    private val undo = ArrayList<ArrayList<IntArray>>()
    private var stroke: ArrayList<IntArray>? = null
    private lateinit var info: TextView
    private lateinit var mv: MapView
    private lateinit var pv: PaletteView
    private lateinit var psv: ScrollView
    private val toolBtns = ArrayList<Button>()
    private lateinit var viewBtn: Button
    private lateinit var permBtn: Button
    private lateinit var elevBtn: Button
    private lateinit var autoBtn: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = Rom.data
        if (d == null) { finish(); return }
        rom = d
        val code = String(rom, 0xAC, 4, Charsets.US_ASCII)
        frlg = code.startsWith("BPR") || code.startsWith("BPG")
        if (frlg) { primTiles = 640; primMeta = 640; primPals = 7 }

        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(8, 8, 8, 8)
        info = TextView(this)
        info.textSize = 12f
        info.text = "Procurando mapas na ROM..."
        mv = MapView(this, this)
        pv = PaletteView(this, this)
        psv = ScrollView(this)
        psv.addView(pv)

        val tn = listOf("Pintar", "Colisão", "Pegar", "Copiar", "Colar")
        val tb = tn.mapIndexed { k, n -> mkBtn(n) { setTool(k) } }
        toolBtns.addAll(tb)
        viewBtn = mkBtn("") { showCol = !showCol; updateLabels(); mv.invalidate() }
        permBtn = mkBtn("") { collSel = if (collSel == 0) 1 else 0; updateLabels() }
        elevBtn = mkBtn("") { elevSel = (elevSel + 1) % 16; updateLabels() }
        autoBtn = mkBtn("") { autoPerm = !autoPerm; updateLabels() }

        root.addView(info)
        root.addView(mv, LinearLayout.LayoutParams(mp, 0, 1f))
        root.addView(mkRow(*tb.toTypedArray()))
        root.addView(mkRow(viewBtn, permBtn, elevBtn, autoBtn))
        root.addView(mkRow(
            mkBtn("◀ Mapa") { step(-1) },
            mkBtn("Mapa ▶") { step(1) },
            mkBtn("Ir p/ nº") { jump() },
            mkBtn("Desfazer") { undoLast() },
            mkBtn("Aplicar") { applyQuiet(); toast("Aplicado! Volte e toque em Salvar ROM.") }
        ))
        val dn = resources.displayMetrics.density
        root.addView(psv, LinearLayout.LayoutParams(mp, (150 * dn).toInt()))
        setContentView(root)
        updateLabels()
        scan()
    }

    override fun onPause() {
        super.onPause()
        if (dirty) applyQuiet()
    }

    private fun mkBtn(t: String, f: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.textSize = 11f
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(2, 0, 2, 0)
        b.setOnClickListener { f() }
        return b
    }

    private fun mkRow(vararg bs: Button): LinearLayout {
        val r = LinearLayout(this)
        for (b in bs) r.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun updateLabels() {
        viewBtn.text = if (showCol) "Ver colisão ✓" else "Ver colisão"
        permBtn.text = if (collSel == 0) "Perm: Livre" else "Perm: Bloq"
        elevBtn.text = "Elev: $elevSel"
        autoBtn.text = if (autoPerm) "Auto: sim" else "Auto: não"
        for (k in toolBtns.indices) toolBtns[k].alpha = if (k == tool) 1f else 0.45f
    }

    private fun setTool(k: Int) {
        tool = k
        selX0 = -1
        if (k == 1) showCol = true
        updateLabels()
        mv.invalidate()
    }

    // ---------- leitura da ROM ----------
    private fun u8(o: Int) = rom[o].toInt() and 0xFF
    private fun u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
    private fun u32(o: Int) = u16(o) or (u16(o + 2) shl 16)
    private fun ptr(o: Int): Int {
        val v = u32(o)
        return if (v >= 0x08000000 && v < 0x08000000 + rom.size) v - 0x08000000 else -1
    }
    private fun hex(v: Int) = Integer.toHexString(v).uppercase()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun isTileset(t: Int, sec: Int): Boolean {
        if (t < 0 || t + 24 > rom.size) return false
        if (u8(t) > 1 || u8(t + 1) != sec || u8(t + 2) != 0 || u8(t + 3) != 0) return false
        return ptr(t + 4) >= 0 && ptr(t + 8) >= 0 && ptr(t + 12) >= 0
    }

    private fun scan() {
        Thread {
            val found = ArrayList<Int>()
            val lim = rom.size - 24
            var i = 0
            while (i <= lim) {
                if (rom[i + 2].toInt() == 0 && rom[i + 3].toInt() == 0 &&
                    rom[i + 6].toInt() == 0 && rom[i + 7].toInt() == 0) {
                    val w = u32(i)
                    val h = u32(i + 4)
                    if (w in 1..512 && h in 1..512 && w * h <= 40000) {
                        val bp = ptr(i + 8)
                        val mp = ptr(i + 12)
                        val p = ptr(i + 16)
                        val s = ptr(i + 20)
                        if (bp >= 0 && mp >= 0 && mp + w * h * 2 <= rom.size && isTileset(p, 0) && isTileset(s, 1)) {
                            found.add(i)
                        }
                    }
                }
                i += 4
            }
            runOnUiThread {
                layouts = found.toIntArray()
                if (layouts.isEmpty()) {
                    info.text = "Nenhum mapa encontrado nesta ROM"
                } else {
                    loadLayout(0)
                }
            }
        }.start()
    }

    private fun key(m: Int): Long = (((priP.toLong() shl 25) or secP.toLong()) shl 10) or m.toLong()

    private fun loadPalettes(p: Int, s: Int) {
        java.util.Arrays.fill(pals, Color.BLACK)
        for (pl in 0 until 16) {
            val base = if (pl < primPals) p else s
            if (base < 0) continue
            val o = base + pl * 32
            if (o + 32 > rom.size) continue
            for (c in 0 until 16) {
                val v = u16(o + c * 2)
                val r = (v and 31) * 255 / 31
                val g = ((v shr 5) and 31) * 255 / 31
                val bl = ((v shr 10) and 31) * 255 / 31
                pals[pl * 16 + c] = Color.rgb(r, g, bl)
            }
        }
    }

    private fun loadTiles(t: Int, base: Int, maxTiles: Int): Int {
        if (t < 0) return 0
        val tp = ptr(t + 4)
        if (tp < 0) return 0
        val comp = u8(t) == 1
        val data: ByteArray = if (comp) {
            val dd = Lz77.decompress(rom, tp) ?: return 0
            dd.first
        } else {
            val n0 = minOf(maxTiles * 32, rom.size - tp)
            rom.copyOfRange(tp, tp + n0)
        }
        val n = minOf(data.size, maxTiles * 32)
        System.arraycopy(data, 0, tiles, base * 32, n)
        return n / 32
    }

    private fun loadLayout(i: Int) {
        if (layouts.isEmpty()) return
        li = ((i % layouts.size) + layouts.size) % layouts.size
        val o = layouts[li]
        mw = u32(o)
        mh = u32(o + 4)
        mapOff = ptr(o + 12)
        priP = ptr(o + 16)
        secP = ptr(o + 20)
        blocks = IntArray(mw * mh) { u16(mapOff + it * 2) }
        tiles.fill(0)
        metaBmps.fill(null)
        priMetaP = ptr(priP + 12)
        secMetaP = ptr(secP + 12)
        loadPalettes(ptr(priP + 8), ptr(secP + 8))
        loadTiles(priP, 0, primTiles)
        val st = loadTiles(secP, primTiles, 1024 - primTiles)
        // estima quantos metatiles o tileset secundario tem
        var n = 0
        if (secMetaP >= 0) {
            val maxSec = 1024 - primMeta
            val limitTiles = primTiles + st
            while (n < maxSec && secMetaP + (n + 1) * 16 <= rom.size) {
                var ok = true
                for (k in 0 until 8) {
                    if ((u16(secMetaP + n * 16 + k * 2) and 0x3FF) >= limitTiles) { ok = false; break }
                }
                if (!ok) break
                n++
            }
        }
        var mx = 0
        for (b in blocks) {
            val m = b and 0x3FF
            if (m > mx) mx = m
            val kk = key(m)
            if (!PermStore.m.containsKey(kk)) PermStore.m[kk] = b and 0xFC00
        }
        metaCount = minOf(1024, maxOf(primMeta + n, mx + 1))
        undo.clear()
        stroke = null
        dirty = false
        selX0 = -1
        if (selMeta >= metaCount) selMeta = 0
        mv.fit()
        pv.refresh()
        updateInfo()
    }

    private fun updateInfo() {
        info.text = "Mapa ${li + 1}/${layouts.size} • ${mw}x$mh blocos • 0x${hex(mapOff)}" +
            (if (dirty) "  ✎ não aplicado" else "") +
            "\nTileset 0x${hex(priP)} + 0x${hex(secP)} • bloco $selMeta"
    }

    // ---------- desenho dos metatiles ----------
    private fun metaOff(id: Int): Int {
        return if (id < primMeta) {
            if (priMetaP < 0) -1 else priMetaP + id * 16
        } else {
            if (secMetaP < 0) -1 else secMetaP + (id - primMeta) * 16
        }
    }

    private fun renderMeta(id: Int): IntArray {
        val px = IntArray(256)
        val o = metaOff(id)
        if (o < 0 || o + 16 > rom.size) {
            java.util.Arrays.fill(px, Color.rgb(90, 0, 90))
            return px
        }
        java.util.Arrays.fill(px, pals[0])
        for (layer in 0..1) {
            for (n in 0..3) {
                val e = u16(o + (layer * 4 + n) * 2)
                val tile = e and 0x3FF
                val hf = (e shr 10) and 1
                val vf = (e shr 11) and 1
                val pl = (e shr 12) and 15
                val ox = (n % 2) * 8
                val oy = (n / 2) * 8
                for (y in 0..7) {
                    for (x in 0..7) {
                        val sx = if (hf == 1) 7 - x else x
                        val sy = if (vf == 1) 7 - y else y
                        val bv = tiles[tile * 32 + sy * 4 + sx / 2].toInt() and 0xFF
                        val ci = if (sx % 2 == 0) bv and 15 else bv shr 4
                        if (ci != 0) px[(oy + y) * 16 + ox + x] = pals[pl * 16 + ci]
                    }
                }
            }
        }
        return px
    }

    fun metaBmp(id: Int): Bitmap {
        val k = id and 1023
        var b = metaBmps[k]
        if (b == null) {
            b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            b.setPixels(renderMeta(k), 0, 16, 0, 0, 16, 16)
            metaBmps[k] = b
        }
        return b
    }

    // ---------- edicao ----------
    private fun curPerm(m: Int): Int =
        if (autoPerm) (PermStore.m[key(m)] ?: (3 shl 12)) else ((collSel shl 10) or (elevSel shl 12))

    private fun setBlock(idx: Int, nv: Int) {
        if (blocks[idx] != nv) {
            stroke?.add(intArrayOf(idx, blocks[idx]))
            blocks[idx] = nv
            dirty = true
            mv.invalidate()
        }
    }

    private fun endStroke() {
        val s = stroke
        if (s != null && s.isNotEmpty()) {
            undo.add(s)
            if (undo.size > 100) undo.removeAt(0)
        }
        stroke = null
        updateInfo()
    }

    fun cancelGesture() {
        val s = stroke
        if (s != null) {
            for (k in s.indices.reversed()) blocks[s[k][0]] = s[k][1]
        }
        stroke = null
        selX0 = -1
        mv.invalidate()
    }

    fun selectMeta(m: Int) {
        selMeta = m
        if (tool != 0 && tool != 1) setTool(0)
        pv.invalidate()
        updateInfo()
    }

    /** phase: 0 = toque, 1 = arrastando, 2 = soltou */
    fun touch(cx: Int, cy: Int, phase: Int) {
        if (mw == 0 || mh == 0) return
        val inside = cx in 0 until mw && cy in 0 until mh
        val ccx = cx.coerceIn(0, mw - 1)
        val ccy = cy.coerceIn(0, mh - 1)
        when (tool) {
            0, 1 -> {
                if (phase == 0) stroke = ArrayList()
                if (phase != 2 && inside) {
                    val idx = cy * mw + cx
                    val old = blocks[idx]
                    val nv: Int
                    if (tool == 0) {
                        nv = selMeta or curPerm(selMeta)
                    } else {
                        nv = (old and 0x3FF) or (collSel shl 10) or (elevSel shl 12)
                        PermStore.m[key(old and 0x3FF)] = nv and 0xFC00
                    }
                    setBlock(idx, nv)
                }
                if (phase == 2) endStroke()
            }
            2 -> {
                if (phase == 0 && inside) {
                    val b = blocks[cy * mw + cx]
                    selMeta = b and 0x3FF
                    collSel = (b shr 10) and 3
                    elevSel = (b shr 12) and 15
                    setTool(0)
                    pv.invalidate()
                    updateInfo()
                    psv.post { psv.scrollTo(0, (selMeta / 16) * (pv.width / 16)) }
                }
            }
            3 -> {
                if (phase == 0) {
                    selX0 = ccx; selY0 = ccy; selX1 = ccx; selY1 = ccy
                } else if (phase == 1) {
                    selX1 = ccx; selY1 = ccy
                } else {
                    copySel()
                }
                mv.invalidate()
            }
            4 -> {
                if (phase == 0 && inside) paste(cx, cy)
            }
        }
    }

    private fun copySel() {
        if (selX0 < 0) return
        val x0 = minOf(selX0, selX1)
        val x1 = maxOf(selX0, selX1)
        val y0 = minOf(selY0, selY1)
        val y1 = maxOf(selY0, selY1)
        clipW = x1 - x0 + 1
        clipH = y1 - y0 + 1
        val c = IntArray(clipW * clipH)
        for (y in 0 until clipH) for (x in 0 until clipW) c[y * clipW + x] = blocks[(y0 + y) * mw + x0 + x]
        clip = c
        selX0 = -1
        setTool(4)
        toast("Copiado ${clipW}x$clipH. Toque no mapa para colar (canto superior esquerdo).")
    }

    private fun paste(cx: Int, cy: Int) {
        val c = clip
        if (c == null) { toast("Use Copiar primeiro"); return }
        stroke = ArrayList()
        for (y in 0 until clipH) {
            for (x in 0 until clipW) {
                val tx = cx + x
                val ty = cy + y
                if (tx in 0 until mw && ty in 0 until mh) setBlock(ty * mw + tx, c[y * clipW + x])
            }
        }
        endStroke()
    }

    private fun undoLast() {
        if (undo.isEmpty()) { toast("Nada para desfazer"); return }
        val s = undo.removeAt(undo.size - 1)
        for (k in s.indices.reversed()) blocks[s[k][0]] = s[k][1]
        dirty = true
        mv.invalidate()
        updateInfo()
    }

    private fun applyQuiet() {
        if (mapOff < 0) return
        for (i in blocks.indices) {
            rom[mapOff + i * 2] = (blocks[i] and 0xFF).toByte()
            rom[mapOff + i * 2 + 1] = ((blocks[i] shr 8) and 0xFF).toByte()
        }
        dirty = false
        updateInfo()
    }

    private fun step(d: Int) {
        if (dirty) applyQuiet()
        loadLayout(li + d)
    }

    private fun jump() {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER
        e.hint = "número do mapa (1-${layouts.size})"
        AlertDialog.Builder(this)
            .setTitle("Ir para o mapa")
            .setView(e)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ir") { _, _ ->
                val v = e.text.toString().toIntOrNull()
                if (v == null || v < 1 || v > layouts.size) toast("Número inválido")
                else { if (dirty) applyQuiet(); loadLayout(v - 1) }
            }.show()
    }
}

class MapView(ctx: Context, private val act: MapActivity) : View(ctx) {
    private var scale = 3f
    private var offX = 0f
    private var offY = 0f
    private val paint = Paint()
    private val fillP = Paint()
    private val lineP = Paint()
    private val txtP = Paint()
    private val dst = Rect()
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f
    private var lcx = 0
    private var lcy = 0
    private val sd = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val ns = (scale * d.scaleFactor).coerceIn(0.5f, 8f)
            val f = ns / scale
            offX = d.focusX - (d.focusX - offX) * f
            offY = d.focusY - (d.focusY - offY) * f
            scale = ns
            invalidate()
            return true
        }
    })

    init {
        paint.isFilterBitmap = false
        lineP.color = Color.argb(45, 255, 255, 255)
        lineP.strokeWidth = 1f
        txtP.color = Color.WHITE
        txtP.isFakeBoldText = true
        txtP.textAlign = Paint.Align.CENTER
    }

    fun fit() {
        scale = 3f
        offX = 0f
        offY = 0f
        invalidate()
    }

    private fun cell(e: MotionEvent) {
        val cs = 16 * scale
        lcx = Math.floor(((e.x - offX) / cs).toDouble()).toInt()
        lcy = Math.floor(((e.y - offY) / cs).toDouble()).toInt()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        sd.onTouchEvent(e)
        val n = e.pointerCount
        if (n >= 2) {
            if (!panning) act.cancelGesture()
            var cx = 0f
            var cy = 0f
            for (i in 0 until n) { cx += e.getX(i); cy += e.getY(i) }
            cx /= n
            cy /= n
            if (panning && e.actionMasked == MotionEvent.ACTION_MOVE) {
                offX += cx - lastX
                offY += cy - lastY
                invalidate()
            }
            lastX = cx
            lastY = cy
            panning = true
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { panning = false; cell(e); act.touch(lcx, lcy, 0) }
            MotionEvent.ACTION_MOVE -> if (!panning) { cell(e); act.touch(lcx, lcy, 1) }
            MotionEvent.ACTION_UP -> { if (!panning) act.touch(lcx, lcy, 2); panning = false }
            MotionEvent.ACTION_CANCEL -> { if (!panning) act.cancelGesture(); panning = false }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(30, 30, 30))
        if (act.mw == 0 || act.mh == 0) return
        val cs = 16 * scale
        val x0 = maxOf(0, Math.floor((-offX / cs).toDouble()).toInt())
        val y0 = maxOf(0, Math.floor((-offY / cs).toDouble()).toInt())
        val x1 = minOf(act.mw - 1, Math.floor(((width - offX) / cs).toDouble()).toInt())
        val y1 = minOf(act.mh - 1, Math.floor(((height - offY) / cs).toDouble()).toInt())
        txtP.textSize = cs * 0.4f
        for (y in y0..y1) {
            for (x in x0..x1) {
                val b = act.blocks[y * act.mw + x]
                dst.set((offX + x * cs).toInt(), (offY + y * cs).toInt(),
                    (offX + (x + 1) * cs).toInt(), (offY + (y + 1) * cs).toInt())
                c.drawBitmap(act.metaBmp(b and 0x3FF), null, dst, paint)
                if (act.showCol) {
                    fillP.color = if (((b shr 10) and 3) != 0) Color.argb(140, 255, 0, 0) else Color.argb(40, 0, 255, 0)
                    c.drawRect(dst, fillP)
                    if (cs >= 40f) {
                        c.drawText(((b shr 12) and 15).toString(), dst.exactCenterX(), dst.exactCenterY() + txtP.textSize / 3, txtP)
                    }
                }
            }
        }
        if (cs >= 24f) {
            for (x in x0..x1 + 1) {
                val px = offX + x * cs
                c.drawLine(px, offY + y0 * cs, px, offY + (y1 + 1) * cs, lineP)
            }
            for (y in y0..y1 + 1) {
                val py = offY + y * cs
                c.drawLine(offX + x0 * cs, py, offX + (x1 + 1) * cs, py, lineP)
            }
        }
        if (act.selX0 >= 0) {
            val sx0 = minOf(act.selX0, act.selX1)
            val sx1 = maxOf(act.selX0, act.selX1)
            val sy0 = minOf(act.selY0, act.selY1)
            val sy1 = maxOf(act.selY0, act.selY1)
            lineP.color = Color.YELLOW
            lineP.style = Paint.Style.STROKE
            lineP.strokeWidth = 4f
            c.drawRect(offX + sx0 * cs, offY + sy0 * cs, offX + (sx1 + 1) * cs, offY + (sy1 + 1) * cs, lineP)
            lineP.color = Color.argb(45, 255, 255, 255)
            lineP.strokeWidth = 1f
        }
    }
}

class PaletteView(ctx: Context, private val act: MapActivity) : View(ctx) {
    private val cols = 16
    private val paint = Paint()
    private val selP = Paint()
    private val dst = Rect()
    private val clipR = Rect()

    init {
        paint.isFilterBitmap = false
        selP.color = Color.YELLOW
        selP.style = Paint.Style.STROKE
        selP.strokeWidth = 4f
    }

    fun refresh() {
        requestLayout()
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val wd = MeasureSpec.getSize(wSpec)
        val cs = wd / cols
        val rows = (act.metaCount + cols - 1) / cols
        setMeasuredDimension(wd, maxOf(cs * rows, 1))
    }

    override fun onDraw(c: Canvas) {
        val cs = width / cols
        if (cs <= 0) return
        c.getClipBounds(clipR)
        val r0 = maxOf(0, clipR.top / cs)
        val r1 = minOf((act.metaCount - 1) / cols, clipR.bottom / cs)
        for (r in r0..r1) {
            for (k in 0 until cols) {
                val id = r * cols + k
                if (id >= act.metaCount) break
                dst.set(k * cs, r * cs, (k + 1) * cs, (r + 1) * cs)
                c.drawBitmap(act.metaBmp(id), null, dst, paint)
            }
        }
        val s = act.selMeta
        val sr = s / cols
        val sc = s % cols
        c.drawRect((sc * cs).toFloat(), (sr * cs).toFloat(), ((sc + 1) * cs).toFloat(), ((sr + 1) * cs).toFloat(), selP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val cs = width / cols
            if (cs > 0) {
                val id = (e.y / cs).toInt() * cols + (e.x / cs).toInt()
                if (id in 0 until act.metaCount) act.selectMeta(id)
            }
        }
        return true
    }
}
