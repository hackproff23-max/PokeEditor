package com.fabricio.pokeedit

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.*

class GfxActivity : Activity() {
    private lateinit var rom: ByteArray
    private var gfx = IntArray(0)
    private var pals = IntArray(0)
    private var gi = 0
    private var pi = 0
    private var sub = 0
    private var tiles = ByteArray(0)
    private var pal = ByteArray(32)
    private var tw = 8
    private var color = 1
    private var gDirty = false
    private var pDirty = false
    private val colors = IntArray(16)
    private val slot = HashMap<Int, Int>() // offset -> espaço disponível (bytes)
    private lateinit var info: TextView
    private lateinit var pv: PixelView
    private lateinit var sw: LinearLayout

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = Rom.data
        if (d == null) { finish(); return }
        rom = d
        val mp = LinearLayout.LayoutParams.MATCH_PARENT
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(16, 16, 16, 16)
        info = TextView(this)
        info.text = "Procurando gráficos na ROM..."
        pv = PixelView(this) { x, y -> paintPixel(x, y) }
        val scroll = ScrollView(this)
        scroll.addView(pv)
        sw = LinearLayout(this)
        root.addView(info)
        root.addView(scroll, LinearLayout.LayoutParams(mp, 0, 1f))
        root.addView(sw, LinearLayout.LayoutParams(mp, 90))
        root.addView(row("◀ Gfx" to { stepG(-1) }, "Gfx ▶" to { stepG(1) }, "Largura" to { nextWidth() }))
        root.addView(row("◀ Pal" to { stepP(-1) }, "Pal ▶" to { stepP(1) }, "Sub-pal" to { nextSub() }))
        root.addView(row("Ir p/ offset" to { jump() }, "Aplicar na ROM" to { applyChanges() }))
        setContentView(root)
        resetPal()
        scan()
    }

    private fun row(vararg items: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this)
        for ((t, f) in items) {
            val bt = Button(this)
            bt.text = t
            bt.isAllCaps = false
            bt.setOnClickListener { f() }
            r.addView(bt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun looksPalette(d: ByteArray): Boolean {
        var i = 1
        while (i < d.size) {
            if ((d[i].toInt() and 0x80) != 0) return false
            i += 2
        }
        return true
    }

    private fun scan() {
        Thread {
            val g = ArrayList<Int>()
            val p = ArrayList<Int>()
            var i = 0
            while (i + 4 < rom.size) {
                if (rom[i].toInt() == 0x10) {
                    val size = (rom[i + 1].toInt() and 0xFF) or ((rom[i + 2].toInt() and 0xFF) shl 8) or
                            ((rom[i + 3].toInt() and 0xFF) shl 16)
                    val isPal = size == 32 || size == 512
                    if (isPal || (size % 32 == 0 && size in 64..0x10000)) {
                        val d = Lz77.decompress(rom, i)
                        if (d != null) {
                            if (isPal && looksPalette(d.first)) p.add(i)
                            if (size >= 64) g.add(i)
                        }
                    }
                }
                i += 4
            }
            runOnUiThread {
                gfx = g.toIntArray()
                pals = p.toIntArray()
                if (gfx.isEmpty()) {
                    info.text = "Nenhum gráfico comprimido encontrado nesta ROM"
                } else {
                    loadGfx(0)
                    if (pals.isNotEmpty()) { loadPal(0); render() }
                }
            }
        }.start()
    }

    private fun hex(v: Int) = Integer.toHexString(v).uppercase()

    private fun capOf(off: Int, cons: Int): Int = slot.getOrPut(off) { (cons + 3) and 3.inv() }

    private fun resetPal() {
        pal = ByteArray(32)
        for (i in 0 until 16) {
            val v = (i * 2) or ((i * 2) shl 5) or ((i * 2) shl 10)
            pal[i * 2] = (v and 0xFF).toByte()
            pal[i * 2 + 1] = (v shr 8).toByte()
        }
        sub = 0
        pDirty = false
    }

    private fun loadGfx(i: Int) {
        if (gfx.isEmpty()) return
        gi = ((i % gfx.size) + gfx.size) % gfx.size
        val d = Lz77.decompress(rom, gfx[gi])
        if (d == null) { toast("Bloco inválido"); return }
        tiles = d.first
        capOf(gfx[gi], d.second)
        gDirty = false
        render()
    }

    private fun loadPal(i: Int) {
        if (pals.isEmpty()) return
        pi = ((i % pals.size) + pals.size) % pals.size
        val d = Lz77.decompress(rom, pals[pi])
        if (d == null) { toast("Paleta inválida"); return }
        pal = d.first
        capOf(pals[pi], d.second)
        sub = 0
        pDirty = false
    }

    private fun palColors() {
        for (i in 0 until 16) {
            val o = sub * 32 + i * 2
            val v = if (o + 1 < pal.size) (pal[o].toInt() and 0xFF) or ((pal[o + 1].toInt() and 0xFF) shl 8) else 0
            val r = (v and 31) * 255 / 31
            val g = ((v shr 5) and 31) * 255 / 31
            val b = ((v shr 10) and 31) * 255 / 31
            colors[i] = Color.rgb(r, g, b)
        }
    }

    private fun render() {
        palColors()
        val nt = tiles.size / 32
        val th = (nt + tw - 1) / tw
        val w = tw * 8
        val h = th * 8
        val px = IntArray(w * h)
        for (t in 0 until nt) {
            val tx = (t % tw) * 8
            val ty = (t / tw) * 8
            for (p in 0 until 64) {
                val bv = tiles[t * 32 + p / 2].toInt() and 0xFF
                val idx = if (p % 2 == 0) bv and 15 else bv shr 4
                px[(ty + p / 8) * w + tx + p % 8] = colors[idx]
            }
        }
        val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bm.setPixels(px, 0, w, 0, 0, w, h)
        pv.bmp = bm
        pv.requestLayout()
        pv.invalidate()
        buildSwatches()
        val g = if (gfx.isEmpty()) "-" else "${gi + 1}/${gfx.size} @0x${hex(gfx[gi])}"
        val p = if (pals.isEmpty()) "padrão (cinza)" else "${pi + 1}/${pals.size} @0x${hex(pals[pi])}"
        info.text = "Gfx $g • ${nt} tiles\nPal $p • sub $sub${if (gDirty || pDirty) "  ✎ não aplicado" else ""}"
    }

    private fun buildSwatches() {
        sw.removeAllViews()
        for (i in 0 until 16) {
            val t = TextView(this)
            t.text = if (i == color) "●" else hex(i)
            t.gravity = Gravity.CENTER
            t.setBackgroundColor(colors[i])
            val lum = Color.red(colors[i]) * 3 + Color.green(colors[i]) * 6 + Color.blue(colors[i])
            t.setTextColor(if (lum > 1280) Color.BLACK else Color.WHITE)
            t.setOnClickListener { color = i; buildSwatches() }
            t.setOnLongClickListener { editColor(i); true }
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(1, 1, 1, 1)
            sw.addView(t, lp)
        }
    }

    private fun paintPixel(x: Int, y: Int) {
        val t = (y / 8) * tw + x / 8
        if (t * 32 >= tiles.size) return
        val p = (y % 8) * 8 + x % 8
        val bi = t * 32 + p / 2
        val old = tiles[bi].toInt() and 0xFF
        val nv = if (p % 2 == 0) (old and 0xF0) or color else (old and 0x0F) or (color shl 4)
        tiles[bi] = nv.toByte()
        pv.bmp!!.setPixel(x, y, colors[color])
        pv.invalidate()
        if (!gDirty) { gDirty = true }
    }

    private fun editColor(i: Int) {
        val o = sub * 32 + i * 2
        if (pals.isEmpty() || o + 1 >= pal.size) { toast("Paleta padrão não é editável"); return }
        val v = (pal[o].toInt() and 0xFF) or ((pal[o + 1].toInt() and 0xFF) shl 8)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(32, 16, 32, 16)
        fun f(label: String, value: Int): EditText {
            val t = TextView(this); t.text = label; col.addView(t)
            val e = EditText(this)
            e.inputType = InputType.TYPE_CLASS_NUMBER
            e.setText(value.toString())
            col.addView(e)
            return e
        }
        val er = f("Vermelho (0-31)", v and 31)
        val eg = f("Verde (0-31)", (v shr 5) and 31)
        val eb = f("Azul (0-31)", (v shr 10) and 31)
        AlertDialog.Builder(this)
            .setTitle("Cor $i")
            .setView(col)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                fun n(e: EditText) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, 31)
                val nv = n(er) or (n(eg) shl 5) or (n(eb) shl 10)
                pal[o] = (nv and 0xFF).toByte()
                pal[o + 1] = (nv shr 8).toByte()
                pDirty = true
                render()
            }.show()
    }

    private fun stepG(d: Int) {
        if (gDirty) toast("Alterações não aplicadas foram descartadas")
        loadGfx(gi + d)
    }

    private fun stepP(d: Int) {
        if (pals.isEmpty()) return
        if (pDirty) toast("Alterações de paleta não aplicadas foram descartadas")
        loadPal(pi + d)
        render()
    }

    private fun nextSub() {
        if (pal.size > 32) { sub = (sub + 1) % (pal.size / 32); render() }
    }

    private fun nextWidth() {
        val ws = intArrayOf(4, 8, 16, 32)
        tw = ws[(ws.indexOf(tw) + 1) % ws.size]
        render()
    }

    private fun jump() {
        val e = EditText(this)
        e.hint = "offset em hex (ex: 245EE0)"
        AlertDialog.Builder(this)
            .setTitle("Ir para offset")
            .setView(e)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Ir") { _, _ ->
                val v = e.text.toString().trim().removePrefix("0x").toIntOrNull(16)
                val idx = if (v == null) -1 else gfx.indexOfFirst { it >= v }
                if (idx >= 0) loadGfx(idx) else toast("Offset inválido ou sem gráfico depois dele")
            }.show()
    }

    private fun applyChanges() {
        var gOut: ByteArray? = null
        var pOut: ByteArray? = null
        if (gDirty) {
            val c = Lz77.compress(tiles)
            val cap = capOf(gfx[gi], 0)
            if (c.size > cap) {
                toast("Imagem recomprimida tem ${c.size}B mas cabem só ${cap}B. Simplifique (menos detalhe/mais repetição).")
                return
            }
            gOut = c
        }
        if (pDirty && pals.isNotEmpty()) {
            val c = Lz77.compress(pal)
            val cap = capOf(pals[pi], 0)
            if (c.size > cap) {
                toast("Paleta recomprimida tem ${c.size}B mas cabem só ${cap}B. Tente cores mais repetidas.")
                return
            }
            pOut = c
        }
        if (gOut == null && pOut == null) { toast("Nada para aplicar"); return }
        gOut?.let { System.arraycopy(it, 0, rom, gfx[gi], it.size) }
        pOut?.let { System.arraycopy(it, 0, rom, pals[pi], it.size) }
        gDirty = false
        pDirty = false
        render()
        toast("Aplicado! Volte e toque em Salvar ROM.")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
