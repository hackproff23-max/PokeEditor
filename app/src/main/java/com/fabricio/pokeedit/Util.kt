package com.fabricio.pokeedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import java.io.ByteArrayOutputStream

object Rom { var data: ByteArray? = null }

object Lz77 {
    /** Retorna (dados, bytes consumidos) ou null se o bloco for inválido. */
    fun decompress(src: ByteArray, off: Int, maxOut: Int = 0x20000): Pair<ByteArray, Int>? {
        if (off < 0 || off + 4 > src.size || (src[off].toInt() and 0xFF) != 0x10) return null
        val size = (src[off + 1].toInt() and 0xFF) or ((src[off + 2].toInt() and 0xFF) shl 8) or
                ((src[off + 3].toInt() and 0xFF) shl 16)
        if (size == 0 || size > maxOut) return null
        val out = ByteArray(size)
        var o = 0
        var p = off + 4
        while (o < size) {
            if (p >= src.size) return null
            val flags = src[p++].toInt() and 0xFF
            for (bit in 7 downTo 0) {
                if (o >= size) break
                if (((flags shr bit) and 1) == 0) {
                    if (p >= src.size) return null
                    out[o++] = src[p++]
                } else {
                    if (p + 1 >= src.size) return null
                    val b1 = src[p++].toInt() and 0xFF
                    val b2 = src[p++].toInt() and 0xFF
                    val len = (b1 shr 4) + 3
                    val dist = (((b1 and 0xF) shl 8) or b2) + 1
                    if (dist > o) return null
                    for (k in 0 until len) {
                        if (o >= size) break
                        out[o] = out[o - dist]
                        o++
                    }
                }
            }
        }
        return Pair(out, p - off)
    }

    /** LZ77 do BIOS do GBA (seguro para VRAM: distância mínima 2). */
    fun compress(d: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x10)
        out.write(d.size and 0xFF)
        out.write((d.size shr 8) and 0xFF)
        out.write((d.size shr 16) and 0xFF)
        var i = 0
        while (i < d.size) {
            var flags = 0
            val blk = ByteArrayOutputStream()
            for (bit in 7 downTo 0) {
                if (i >= d.size) break
                var bestLen = 0
                var bestDist = 0
                val maxLen = minOf(18, d.size - i)
                val start = maxOf(0, i - 4096)
                var j = i - 2
                while (j >= start) {
                    var l = 0
                    while (l < maxLen && d[j + l] == d[i + l]) l++
                    if (l > bestLen) {
                        bestLen = l
                        bestDist = i - j
                        if (l == maxLen) break
                    }
                    j--
                }
                if (bestLen >= 3) {
                    flags = flags or (1 shl bit)
                    val dd = bestDist - 1
                    blk.write(((bestLen - 3) shl 4) or (dd shr 8))
                    blk.write(dd and 0xFF)
                    i += bestLen
                } else {
                    blk.write(d[i].toInt() and 0xFF)
                    i++
                }
            }
            out.write(flags)
            out.write(blk.toByteArray())
        }
        return out.toByteArray()
    }
}

class PixelView(ctx: Context, private val onPaint: (Int, Int) -> Unit) : View(ctx) {
    var bmp: Bitmap? = null
    private val paint = Paint()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val b = bmp
        val height = if (b == null) 100 else (width.toFloat() * b.height / b.width).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        c.drawBitmap(b, null, Rect(0, 0, width, height), paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bmp ?: return false
        if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) {
            val x = (e.x * b.width / width).toInt()
            val y = (e.y * b.height / height).toInt()
            if (x in 0 until b.width && y in 0 until b.height) onPaint(x, y)
            parent.requestDisallowInterceptTouchEvent(true)
        }
        return true
    }
}
