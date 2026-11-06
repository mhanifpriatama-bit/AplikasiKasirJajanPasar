package id.jajanpasar.kasir.logic

import org.json.JSONObject

enum class Jenis { SIGMA, BAYAR, UNDO, ESC }

/** Tombol dialog jumlah titipan. Isi kode yang ditampilkan di layar: "KEY:" diikuti nama tombol. */
val TOMBOL_JUMLAH = listOf("+1", "+5", "-1", "-5", "+10", "OK")

sealed class Scan {
    data class Petugas(val nama: String) : Scan()
    data class Toko(val nama: String) : Scan()
    data class Perintah(val jenis: Jenis) : Scan()
    data class Cash(val nominal: Int) : Scan()
    data class Member(val nomor: String, val nama: String) : Scan()
    data class Tombol(val isi: String) : Scan()
    /** QR tujuan kirim database: {"v":1,"webapp":"https://script.google.com/macros/s/.../exec","toko":"...","token":"..."} */
    data class Konfigurasi(val webapp: String, val toko: String, val token: String) : Scan()
    data class Produk(
        val nama: String, val penitip: String, val hargaJual: Int,
        val kode: String, val hargaTitip: Int
    ) : Scan()
    data class Salah(val alasan: String) : Scan()
}

object LineParser {
    fun parse(baris: String): Scan {
        val s = baris.trim()
        if (s.isEmpty()) return Scan.Salah("kosong")
        if (s.startsWith("{")) return parseKonfigurasi(s)
        val i = s.indexOf(':')
        if (i > 0) {
            val awalan = s.substring(0, i).trim().uppercase()
            val isi = s.substring(i + 1).trim()
            when (awalan) {
                "PTG" -> return if (isi.isNotEmpty()) Scan.Petugas(isi) else Scan.Salah("ID petugas kosong")
                "TOKO" -> return if (isi.isNotEmpty()) Scan.Toko(isi) else Scan.Salah("nama toko kosong")
                "CMD" -> return when (isi.uppercase()) {
                    "SIGMA" -> Scan.Perintah(Jenis.SIGMA)
                    "BAYAR" -> Scan.Perintah(Jenis.BAYAR)
                    "UNDO" -> Scan.Perintah(Jenis.UNDO)
                    "ESC" -> Scan.Perintah(Jenis.ESC)
                    else -> Scan.Salah("perintah tidak dikenal")
                }
                "KEY" -> {
                    val k = isi.uppercase()
                    return if (k in TOMBOL_JUMLAH) Scan.Tombol(k) else Scan.Salah("tombol dialog tidak dikenal")
                }
                "MBR" -> {
                    val f = splitCsv(isi)
                    val nomor = f.getOrElse(0) { "" }
                    val nama = f.getOrElse(1) { "" }
                    return if (nomor.isNotEmpty()) Scan.Member(nomor, nama) else Scan.Salah("nomor member kosong")
                }
                "CASH" -> {
                    val n = isi.replace(".", "").toIntOrNull()
                    return if (n != null && n > 0) Scan.Cash(n) else Scan.Salah("nominal salah")
                }
            }
        }
        return parseProduk(s)
    }

    fun webappSah(u: String): Boolean =
        u.startsWith("https://script.google.com/") && "/macros/" in u && u.endsWith("/exec")

    private fun parseKonfigurasi(s: String): Scan {
        val j = try { JSONObject(s) } catch (e: Exception) { return Scan.Salah("QR tujuan kirim bukan JSON yang sah") }
        if (j.optInt("v", -1) != 1) return Scan.Salah("QR tujuan kirim: versi tidak didukung")
        val webapp = j.optString("webapp").trim()
        val toko = j.optString("toko").trim()
        val token = j.optString("token")
        if (!webappSah(webapp)) return Scan.Salah("alamat webapp harus https://script.google.com/.../exec")
        if (toko.isEmpty() || toko.length > 64) return Scan.Salah("QR tujuan kirim: nama toko kosong atau terlalu panjang")
        if (token.isEmpty()) return Scan.Salah("QR tujuan kirim: token kosong")
        return Scan.Konfigurasi(webapp, toko, token)
    }

    fun splitCsv(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var kutip = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                kutip && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> kutip = !kutip
                c == ',' && !kutip -> { out.add(sb.toString().trim()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun parseProduk(s: String): Scan {
        val f = splitCsv(s)
        if (f.size != 4) return Scan.Salah("bukan QR produk (butuh 4 kolom, ada ${f.size})")
        val nama = f[0]
        val penitip = f[1]
        if (nama.isEmpty() || penitip.isEmpty()) return Scan.Salah("nama produk atau penitip kosong")
        val jual = f[2].replace(".", "").toIntOrNull()
        if (jual == null || jual <= 0) return Scan.Salah("harga jual salah")
        val kode = f[3].uppercase()
        if (kode.isEmpty() || kode.any { it !in 'A'..'Z' }) return Scan.Salah("kode harga salah")
        val titip = KodeHarga.harga(nama, penitip, kode) ?: return Scan.Salah("kode harga tidak cocok (label salah/terpotong)")
        if (titip > jual) return Scan.Salah("harga titip lebih besar dari harga jual")
        return Scan.Produk(nama, penitip, jual, kode, titip)
    }
}
