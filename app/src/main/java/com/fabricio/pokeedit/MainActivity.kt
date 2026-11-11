package com.fabricio.pokeedit

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private var rom: ByteArray? = null
    private var romUri: Uri? = null
    private var base = 0
    private var names = 0
    private var statsOk = false
    private val count = 412
    private lateinit var list: ListView
    private lateinit var info: TextView

    private val REQ_OPEN = 1
    private val REQ_SAVE = 2

    // code -> (base stats, names)
    private val games = mapOf(
        "BPRE" to Pair(0x254784, 0x245EE0), // FireRed 1.0
        "BPGE" to Pair(0x254760, 0x245EBC), // LeafGreen 1.0
        "BPEE" to Pair(0x3203CC, 0x3185C8), // Emerald
        "AXVE" to Pair(0x2031CC, 0x1F716C), // Ruby 1.0
        "AXPE" to Pair(0x20315C, 0x1F70FC)  // Sapphire 1.0
    )
    private val types = listOf("Normal","Lutador","Voador","Veneno","Terra","Pedra","Inseto",
        "Fantasma","Aço","???","Fogo","Água","Planta","Elétrico","Psíquico","Gelo","Dragão","Sombrio")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(24, 24, 24, 24)
        val row = LinearLayout(this)
        val open = Button(this); open.text = "Abrir ROM"
        val save = Button(this); save.text = "Salvar ROM"
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val tex = Button(this); tex.text = "Texturas"
        row.addView(open, lp); row.addView(save, lp); row.addView(tex, lp)
        info = TextView(this); info.text = "Abra uma ROM de Pokémon GBA (FireRed, LeafGreen, Emerald, Ruby, Sapphire - v1.0)"
        list = ListView(this)
        root.addView(row); root.addView(info); root.addView(list)
        setContentView(root)

        open.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivityForResult(i, REQ_OPEN)
        }
        save.setOnClickListener { saveRom() }
        tex.setOnClickListener {
            if (Rom.data == null) toast("Abra uma ROM primeiro")
            else startActivity(Intent(this, GfxActivity::class.java))
        }
        list.setOnItemClickListener { _, _, pos, _ -> editMon(pos) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (res != RESULT_OK || uri == null) return
        if (req == REQ_OPEN) loadRom(uri)
        if (req == REQ_SAVE) writeTo(uri)
    }

    private fun loadRom(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val code = String(bytes, 0xAC, 4, Charsets.US_ASCII)
            rom = bytes; romUri = uri; Rom.data = bytes
            statsOk = false
            list.adapter = null
            val g = games[code]
            if (g != null && bytes.size >= 0x1000000) {
                base = g.first; names = g.second
                if (name(1) == "BULBASAUR") statsOk = true
            }
            if (statsOk) {
                val items = (0 until count).map { String.format("#%03d  %s", it, name(it)) }
                list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
                info.text = "$code carregada — toque num Pokémon para editar ou abra Texturas"
            } else {
                info.text = "$code carregada. Stats não suportados nesta ROM, mas Texturas funcionam"
            }
        } catch (e: Exception) { toast("Erro: ${e.message}") }
    }

    private fun u(i: Int) = rom!![i].toInt() and 0xFF

    private fun dec(c: Int): Char = when (c) {
        0 -> ' '
        in 0xA1..0xAA -> '0' + (c - 0xA1)
        in 0xBB..0xD4 -> 'A' + (c - 0xBB)
        in 0xD5..0xEE -> 'a' + (c - 0xD5)
        0xAE -> '-'
        0xAB -> '!'
        0xAC -> '?'
        0xAD -> '.'
        else -> '?'
    }

    private fun enc(ch: Char): Int = when (ch) {
        ' ' -> 0
        in '0'..'9' -> 0xA1 + (ch - '0')
        in 'A'..'Z' -> 0xBB + (ch - 'A')
        in 'a'..'z' -> 0xD5 + (ch - 'a')
        '-' -> 0xAE
        '!' -> 0xAB
        '?' -> 0xAC
        '.' -> 0xAD
        else -> 0xAC
    }

    private fun name(i: Int): String {
        val o = names + i * 11
        val sb = StringBuilder()
        for (k in 0 until 10) {
            val c = u(o + k)
            if (c == 0xFF) break
            sb.append(dec(c))
        }
        return sb.toString()
    }

    private fun setName(i: Int, s: String) {
        val o = names + i * 11
        val t = s.take(10)
        for (k in 0 until 11) rom!![o + k] = 0xFF.toByte()
        for (k in t.indices) rom!![o + k] = enc(t[k]).toByte()
    }

    private fun field(parent: LinearLayout, label: String, value: String, number: Boolean): EditText {
        val t = TextView(this); t.text = label; parent.addView(t)
        val e = EditText(this); e.setText(value)
        if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
        parent.addView(e)
        return e
    }

    private fun spinner(parent: LinearLayout, label: String, sel: Int): Spinner {
        val t = TextView(this); t.text = label; parent.addView(t)
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)
        s.setSelection(if (sel < types.size) sel else 9)
        parent.addView(s)
        return s
    }

    private fun editMon(idx: Int) {
        if (rom == null || !statsOk) return
        val o = base + idx * 28
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(32, 16, 32, 16)
        val fName = field(col, "Nome (máx. 10)", name(idx), false)
        val labels = listOf("HP", "Ataque", "Defesa", "Velocidade", "Atq. Esp.", "Def. Esp.")
        val fs = labels.indices.map { field(col, labels[it], u(o + it).toString(), true) }
        val t1 = spinner(col, "Tipo 1", u(o + 6))
        val t2 = spinner(col, "Tipo 2", u(o + 7))
        val fCatch = field(col, "Taxa de captura", u(o + 8).toString(), true)
        val fExp = field(col, "Exp base", u(o + 9).toString(), true)
        val fA1 = field(col, "Habilidade 1 (ID)", u(o + 22).toString(), true)
        val fA2 = field(col, "Habilidade 2 (ID)", u(o + 23).toString(), true)
        val sv = ScrollView(this); sv.addView(col)

        AlertDialog.Builder(this)
            .setTitle("#%03d".format(idx))
            .setView(sv)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aplicar") { _, _ ->
                fun n(e: EditText) = (e.text.toString().toIntOrNull() ?: 0).coerceIn(0, 255).toByte()
                setName(idx, fName.text.toString())
                for (k in 0 until 6) rom!![o + k] = n(fs[k])
                rom!![o + 6] = t1.selectedItemPosition.toByte()
                rom!![o + 7] = t2.selectedItemPosition.toByte()
                rom!![o + 8] = n(fCatch)
                rom!![o + 9] = n(fExp)
                rom!![o + 22] = n(fA1)
                rom!![o + 23] = n(fA2)
                (list.adapter as ArrayAdapter<String>).let {
                    // refresh name in list
                    val items = (0 until count).map { i -> String.format("#%03d  %s", i, name(i)) }
                    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
                    list.setSelection(idx)
                }
                toast("Aplicado (falta Salvar ROM)")
            }.show()
    }

    private fun saveRom() {
        if (rom == null) { toast("Abra uma ROM primeiro"); return }
        try {
            writeTo(romUri!!)
        } catch (e: Exception) {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/octet-stream"
            i.putExtra(Intent.EXTRA_TITLE, "editada.gba")
            startActivityForResult(i, REQ_SAVE)
        }
    }

    private fun writeTo(uri: Uri) {
        contentResolver.openOutputStream(uri, "wt")!!.use { it.write(rom!!) }
        toast("ROM salva!")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
