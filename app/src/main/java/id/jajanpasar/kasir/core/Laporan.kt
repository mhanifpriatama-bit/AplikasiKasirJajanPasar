package id.jajanpasar.kasir.core

import android.content.ContentValues
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import id.jajanpasar.kasir.data.KasirDao
import id.jajanpasar.kasir.data.Sesi
import id.jajanpasar.kasir.logic.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LapProduk(
    val nama: String, val penitip: String, val hargaJual: Int, val hargaTitip: Int, val kode: String,
    val titip: Int, val terjualTercatat: Int, val stokSistem: Int, val stokRiil: Int?,
    val selisih: Int, val terjualDibayar: Int, val retur: Int, val uangPenitip: Int,
    val opnameDiubah: Boolean = false
)

data class LapItem(val nama: String, val penitip: String, val jumlah: Int, val hargaJual: Int, val hargaTitip: Int)

data class LapStruk(
    val nomor: Int, val waktuBuat: Long, val waktuLunas: Long?, val status: String,
    val total: Int, val uang: Int, val kembalian: Int, val items: List<LapItem>,
    val memberNomor: String? = null, val memberNama: String? = null
)

data class Laporan(
    val toko: String, val petugas: String, val waktuBuka: Long, val waktuTutup: Long,
    val produk: List<LapProduk>, val struk: List<LapStruk>
) {
    val totalPenjualan: Int get() = struk.filter { it.status == "LUNAS" }.sumOf { it.total }
    val uangPerPenitip: Map<String, Int>
        get() = produk.groupBy { it.penitip }.mapValues { e -> e.value.sumOf { it.uangPenitip } }

    /** Omzet = jumlah semua struk LUNAS. */
    val omzet: Int get() = totalPenjualan
    /** Harga pokok = uang untuk seluruh penitip (titipan dikurangi opname, dikali harga titip). */
    val hargaPokok: Int get() = produk.sumOf { it.uangPenitip }
    /** Laba kotor resmi (diterima manajemen): omzet dikurangi harga pokok berdasarkan transaksi. */
    val labaKotor: Int get() = omzet - hargaPokokTransaksi
    /** Informasi: laba bila harga pokok dihitung setelah opname. */
    val labaSetelahOpname: Int get() = omzet - hargaPokok
    /** Harga pokok menurut transaksi: jumlah terjual di struk LUNAS dikali harga titip. */
    val hargaPokokTransaksi: Int
        get() = struk.filter { it.status == "LUNAS" }.sumOf { s -> s.items.sumOf { it.jumlah * it.hargaTitip } }

    /** Hasil opname: selisih = stok aplikasi - opname (positif = barang kurang, negatif = lebih). */
    val produkSelisih: Int get() = produk.count { it.selisih != 0 }
    val unitKurang: Int get() = produk.filter { it.selisih > 0 }.sumOf { it.selisih }
    val unitLebih: Int get() = produk.filter { it.selisih < 0 }.sumOf { -it.selisih }
    /** Nilai selisih dengan harga titip; positif = kerugian toko. */
    val nilaiSelisih: Int get() = hargaPokok - hargaPokokTransaksi
    /** Kekurangan bersih yang ditanggung kasir dan kru (selisih dijumlahkan bersih per sesi). */
    val selisihDitanggung: Int get() = nilaiSelisih.coerceAtLeast(0)
    /** Kelebihan bersih; ditampilkan terpisah dan tidak mengurangi tanggungan. */
    val kelebihanBersih: Int get() = (-nilaiSelisih).coerceAtLeast(0)
    val produkOpnameDiubah: Int get() = produk.count { it.opnameDiubah }
}

object PenyusunLaporan {
    suspend fun bangun(dao: KasirDao, s: Sesi, toko: String, tutup: Long): Laporan {
        val produk = dao.semuaProduk()
        val stok = dao.stokSesi(s.id).associateBy { it.produkId }
        val jual = dao.terjual(s.id).associate { it.produkId to it.jumlah }
        val lp = produk.mapNotNull { p ->
            val st = stok[p.id] ?: return@mapNotNull null
            val terj = jual[p.id] ?: 0
            val sistem = st.jumlahTitip - terj
            val riil = st.stokRiil ?: sistem.coerceAtLeast(0)
            val dibayar = (st.jumlahTitip - riil).coerceAtLeast(0)
            LapProduk(p.nama, p.penitip, p.hargaJual, p.hargaTitip, p.kodeHarga, st.jumlahTitip, terj,
                sistem, st.stokRiil, sistem - riil, dibayar, riil, dibayar * p.hargaTitip, st.opnameDiubah)
        }
        val nama = produk.associate { it.id to it }
        val struk = dao.semuaStruk(s.id).map { st ->
            LapStruk(st.nomor, st.waktuBuat, st.waktuLunas, st.status, st.total, st.uangDiberikan, st.kembalian,
                dao.itemStruk(st.id).map { i ->
                    LapItem(nama[i.produkId]?.nama ?: "?", nama[i.produkId]?.penitip ?: "?",
                        i.jumlah, i.hargaJual, i.hargaTitip)
                }, st.memberNomor, st.memberNama)
        }
        return Laporan(toko, s.petugas, s.waktuBuka, tutup, lp, struk)
    }
}

data class Berkas(val pdf: File, val html: File, val json: File)

object BerkasLaporan {
    fun tulis(ctx: Context, l: Laporan): Berkas {
        val dir = ctx.getExternalFilesDir("laporan") ?: File(ctx.filesDir, "laporan")
        dir.mkdirs()
        val aman = l.petugas.replace(Regex("[^A-Za-z0-9]"), "_")
        val dasar = "laporan_${Fmt.berkas(l.waktuTutup)}_$aman"
        val b = Berkas(File(dir, "keuangan_$dasar.pdf"), File(dir, "rekap_$dasar.html"), File(dir, "database_$dasar.json"))
        tulisPdfKeuangan(b.pdf, l)
        b.html.writeText(html(l), Charsets.UTF_8)
        b.json.writeText(json(l), Charsets.UTF_8)
        runCatching { salinKeUnduhan(ctx, b.pdf, "application/pdf") }
        runCatching { salinKeUnduhan(ctx, b.html, "text/html") }
        runCatching { salinKeUnduhan(ctx, b.json, "application/json") }
        return b
    }

    private fun salinKeUnduhan(ctx: Context, f: File, mime: String) {
        if (Build.VERSION.SDK_INT < 29) return
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KasirJajanPasar")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return
        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
    }

    fun json(l: Laporan): String = jsonObjek(l).toString(2)

    fun jsonObjek(l: Laporan): JSONObject {
        val o = JSONObject()
        o.put("toko", l.toko)
        o.put("petugas", l.petugas)
        o.put("waktu_buka", Fmt.iso(l.waktuBuka))
        o.put("waktu_tutup", Fmt.iso(l.waktuTutup))
        o.put("total_penjualan", l.totalPenjualan)
        o.put("ringkasan", JSONObject()
            .put("omzet", l.omzet).put("harga_pokok_transaksi", l.hargaPokokTransaksi)
            .put("harga_pokok_setelah_opname", l.hargaPokok).put("laba_kotor", l.labaKotor)
            .put("laba_setelah_opname", l.labaSetelahOpname)
            .put("selisih_ditanggung", l.selisihDitanggung).put("kelebihan_bersih", l.kelebihanBersih)
            .put("opname_produk_diubah", l.produkOpnameDiubah)
            .put("opname_produk_diperiksa", l.produk.size).put("opname_produk_selisih", l.produkSelisih)
            .put("opname_unit_kurang", l.unitKurang).put("opname_unit_lebih", l.unitLebih)
            .put("opname_nilai_selisih", l.nilaiSelisih))
        val prod = JSONArray()
        for (p in l.produk) {
            prod.put(JSONObject()
                .put("produk", p.nama).put("penitip", p.penitip)
                .put("harga_jual", p.hargaJual).put("harga_titip", p.hargaTitip).put("kode_harga", p.kode)
                .put("titip", p.titip).put("terjual_tercatat", p.terjualTercatat)
                .put("stok_sistem", p.stokSistem).put("stok_riil", p.stokRiil ?: JSONObject.NULL)
                .put("selisih", p.selisih).put("terjual_dibayar", p.terjualDibayar)
                .put("retur", p.retur).put("opname", p.retur).put("opname_diubah", p.opnameDiubah)
                .put("selisih_nilai", p.selisih * p.hargaTitip).put("uang_penitip", p.uangPenitip))
        }
        o.put("produk", prod)
        val pen = JSONArray()
        for ((n, u) in l.uangPerPenitip) pen.put(JSONObject().put("penitip", n).put("uang", u))
        o.put("penitip", pen)
        val trx = JSONArray()
        for (s in l.struk) {
            val items = JSONArray()
            for (i in s.items) {
                items.put(JSONObject().put("produk", i.nama).put("penitip", i.penitip)
                    .put("jumlah", i.jumlah).put("harga_jual", i.hargaJual).put("harga_titip", i.hargaTitip))
            }
            trx.put(JSONObject().put("nomor", s.nomor).put("waktu", Fmt.iso(s.waktuBuat))
                .put("waktu_lunas", s.waktuLunas?.let { Fmt.iso(it) } ?: JSONObject.NULL)
                .put("status", s.status).put("total", s.total).put("uang_diberikan", s.uang)
                .put("kembalian", s.kembalian)
                .put("member_nomor", s.memberNomor ?: JSONObject.NULL)
                .put("member_nama", s.memberNama ?: JSONObject.NULL)
                .put("item", items))
        }
        o.put("transaksi", trx)
        return o
    }

    private fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Satu berkas HTML mandiri berisi tabel rekap, diurutkan abjad penitip. */
    fun html(l: Laporan): String {
        val urut = l.produk.sortedWith(compareBy({ it.penitip.lowercase() }, { it.nama.lowercase() }))
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html lang=\"id\"><head><meta charset=\"UTF-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        sb.append("<title>Rekap ${esc(l.toko.ifBlank { "Kasir" })} ${Fmt.tgl(l.waktuTutup)}</title><style>")
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:16px;color:#1e293b}")
        sb.append("h1{font-size:20px;margin:0 0 4px}p{margin:2px 0;color:#475569;font-size:14px}")
        sb.append("table{border-collapse:collapse;width:100%;margin-top:14px;font-size:14px}")
        sb.append("th,td{border:1px solid #cbd5e1;padding:6px 8px;text-align:left}")
        sb.append("th{background:#1b5e20;color:#fff}td.n,th.n{text-align:right}")
        sb.append("tr.sub td{background:#e8f5e9;font-weight:bold}tr.tot td{background:#1b5e20;color:#fff;font-weight:bold}")
        sb.append("@media print{body{margin:8mm}}</style></head><body>")
        sb.append("<h1>Rekap ${esc(l.toko.ifBlank { "Kasir" })}</h1>")
        sb.append("<p>Petugas: ${esc(l.petugas)}</p>")
        sb.append("<p>Sesi: ${Fmt.tgl(l.waktuBuka)} s.d. ${Fmt.tgl(l.waktuTutup)}</p>")
        sb.append("<table><thead><tr><th>Produk</th><th>Penitip</th><th class=\"n\">Sisa</th>")
        sb.append("<th class=\"n\">Laku</th><th class=\"n\">Rp terima</th></tr></thead><tbody>")
        var total = 0
        for ((penitip, baris) in urut.groupBy { it.penitip }) {
            for (p in baris) {
                sb.append("<tr><td>${esc(p.nama)}</td><td>${esc(p.penitip)}</td>")
                sb.append("<td class=\"n\">${p.retur}</td><td class=\"n\">${p.terjualDibayar}</td>")
                sb.append("<td class=\"n\">${Fmt.rp(p.uangPenitip)}</td></tr>")
            }
            val sub = baris.sumOf { it.uangPenitip }
            total += sub
            sb.append("<tr class=\"sub\"><td colspan=\"4\">Jumlah ${esc(penitip)}</td>")
            sb.append("<td class=\"n\">${Fmt.rp(sub)}</td></tr>")
        }
        sb.append("<tr class=\"tot\"><td colspan=\"4\">TOTAL Rp terima</td><td class=\"n\">${Fmt.rp(total)}</td></tr>")
        sb.append("</tbody></table></body></html>")
        return sb.toString()
    }

    /** PDF laporan keuangan: omzet, harga pokok, laba kotor, petugas, dan hasil opname. */
    private fun tulisPdfKeuangan(f: File, l: Laporan) {
        val doc = PdfDocument()
        val judul = Paint().apply { textSize = 22f; typeface = Typeface.DEFAULT_BOLD }
        val subjudul = Paint().apply { textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        val teks = Paint().apply { textSize = 12f }
        val tebal = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        val kanan = Paint().apply { textSize = 13f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
        val kecil = Paint().apply { textSize = 9f; color = 0xFF555555.toInt() }
        val th = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
        val thR = Paint().apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
        val td = Paint().apply { textSize = 10f }
        val tdR = Paint().apply { textSize = 10f; textAlign = Paint.Align.RIGHT }
        val garis = Paint().apply { strokeWidth = 1f; color = 0xFF999999.toInt() }

        var no = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, no).create())
        var c = page.canvas
        var y = 64f

        fun halamanBaru() {
            doc.finishPage(page)
            no++
            page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, no).create())
            c = page.canvas
            y = 56f
        }
        fun baris(label: String, nilai: String, p: Paint = teks) {
            c.drawText(label, 40f, y, p)
            c.drawText(nilai, 555f, y, kanan)
            y += 24f
        }
        fun kepalaTabelOpname() {
            c.drawText("Produk", 40f, y, th); c.drawText("Penitip", 190f, y, th)
            c.drawText("Stok", 345f, y, thR); c.drawText("Opname", 405f, y, thR)
            c.drawText("Selisih", 462f, y, thR); c.drawText("Nilai (Rp)", 555f, y, thR)
            y += 6f
            c.drawLine(40f, y, 555f, y, garis); y += 14f
        }

        // --- keuangan
        c.drawText("LAPORAN KEUANGAN", 40f, y, judul); y += 24f
        c.drawText(l.toko.ifBlank { "-" }, 40f, y, tebal); y += 18f
        c.drawText("Petugas : ${l.petugas}", 40f, y, teks); y += 16f
        c.drawText("Sesi    : ${Fmt.tgl(l.waktuBuka)} s.d. ${Fmt.tgl(l.waktuTutup)}", 40f, y, teks); y += 22f
        c.drawLine(40f, y, 555f, y, garis); y += 28f

        baris("Omzet (penjualan lunas)", Fmt.rp(l.omzet))
        baris("Harga pokok (berdasarkan transaksi)", Fmt.rp(-l.hargaPokokTransaksi))
        c.drawLine(40f, y - 12f, 555f, y - 12f, garis); y += 4f
        baris("Laba kotor (diterima manajemen)", Fmt.rp(l.labaKotor), tebal)
        if (l.omzet > 0) {
            baris("Margin laba kotor", String.format(java.util.Locale.US, "%.1f%%", l.labaKotor * 100.0 / l.omzet))
        }
        y += 4f
        baris("Struk lunas", l.struk.count { it.status == "LUNAS" }.toString())
        baris("Struk batal", l.struk.count { it.status == "BATAL" }.toString())
        y += 10f
        c.drawLine(40f, y, 555f, y, garis); y += 26f

        // --- hasil opname
        c.drawText("HASIL OPNAME", 40f, y, subjudul); y += 26f
        baris("Produk diperiksa", l.produk.size.toString())
        baris("Produk yang angka opname-nya diubah petugas", l.produkOpnameDiubah.toString())
        baris("Produk dengan selisih", l.produkSelisih.toString())
        baris("Unit kurang dari stok aplikasi", l.unitKurang.toString())
        baris("Unit lebih dari stok aplikasi", l.unitLebih.toString())
        baris("Nilai selisih bersih (harga titip)", Fmt.rp(l.nilaiSelisih))
        baris("Selisih ditanggung kasir dan kru", Fmt.rp(l.selisihDitanggung), tebal)
        if (l.kelebihanBersih > 0) baris("Kelebihan bersih (tidak ditanggung)", Fmt.rp(l.kelebihanBersih))
        baris("Laba kotor setelah opname (informasi)", Fmt.rp(l.labaSetelahOpname))
        y += 6f
        val beda = l.produk.filter { it.selisih != 0 }
            .sortedWith(compareBy({ it.penitip.lowercase() }, { it.nama.lowercase() }))
        if (beda.isEmpty()) {
            c.drawText("Semua hasil opname sama dengan stok aplikasi.", 40f, y, teks); y += 20f
        } else {
            kepalaTabelOpname()
            for (p in beda) {
                if (y > 790f) { halamanBaru(); kepalaTabelOpname() }
                c.drawText(p.nama.take(24), 40f, y, td)
                c.drawText(p.penitip.take(20), 190f, y, td)
                c.drawText(p.stokSistem.toString(), 345f, y, tdR)
                c.drawText(p.retur.toString(), 405f, y, tdR)
                c.drawText(p.selisih.toString(), 462f, y, tdR)
                c.drawText(Fmt.rp(p.selisih * p.hargaTitip).replace("Rp ", ""), 555f, y, tdR)
                y += 14f
            }
            y += 8f
        }

        if (y > 770f) halamanBaru()
        y += 12f
        c.drawText("Laba kotor resmi = omzet - harga pokok berdasarkan transaksi. Penitip tetap dibayar dari", 40f, y, kecil); y += 12f
        c.drawText("titipan dikurangi opname; kekurangan bersihnya ditanggung kasir dan kru.", 40f, y, kecil); y += 12f
        c.drawText("Selisih = stok aplikasi - opname. Positif = barang kurang, negatif = barang lebih.", 40f, y, kecil)
        y += 22f

        // --- paling bawah: dua perhitungan harga pokok
        if (y > 740f) halamanBaru()
        c.drawLine(40f, y, 555f, y, garis); y += 24f
        baris("Harga pokok (uang untuk penitip) berdasarkan transaksi", Fmt.rp(l.hargaPokokTransaksi))
        baris("Harga pokok (uang untuk penitip) setelah opname", Fmt.rp(l.hargaPokok))

        doc.finishPage(page)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
