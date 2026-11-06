package id.jajanpasar.kasir.logic

/** Fungsi kode harga titip, sesuai rancangan (bagian 3). */
object KodeHarga {
    private const val ABJAD = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"

    fun hurufUnik(teks: String): List<Char> {
        val hasil = LinkedHashSet<Char>()
        for (ch in teks.uppercase()) if (ch in 'A'..'Z') hasil.add(ch)
        return hasil.toList()
    }

    /** 11 huruf: abjad yang tidak terpakai; bila kurang, tambah huruf produk lalu penitip. */
    fun deret(produk: String, penitip: String): List<Char> {
        val terpakai = hurufUnik(produk + penitip).toSet()
        val d = ABJAD.filter { it !in terpakai }.take(11).toMutableList()
        if (d.size < 11) {
            for (c in hurufUnik(produk) + hurufUnik(penitip)) {
                if (c !in d) d.add(c)
                if (d.size == 11) break
            }
        }
        return d
    }

    /** 13400 -> "13,4"; 500 -> "0,5"; 15000 -> "15". */
    fun formatHarga(harga: Int): String {
        val ribu = harga / 1000
        val pecahan = (harga % 1000).toString().padStart(3, '0').trimEnd('0')
        return ribu.toString() + if (pecahan.isEmpty()) "" else ",$pecahan"
    }

    private fun indeks(ch: Char): Int = when (ch) {
        ',' -> 11
        '0' -> 10
        else -> {
            val n = ch - '0'
            if (n % 2 == 1) (n + 1) / 2 else 5 + n / 2
        }
    }

    fun kodeHarga(produk: String, penitip: String, harga: Int): String {
        val d = deret(produk, penitip)
        return formatHarga(harga).map { d[indeks(it) - 1] }.joinToString("")
    }

    /** Kebalikan kodeHarga. Null bila ada huruf di luar deret atau bentuknya tidak sah. */
    fun harga(produk: String, penitip: String, kode: String): Int? {
        val d = deret(produk, penitip)
        val sb = StringBuilder()
        for (ch in kode.uppercase()) {
            val idx = d.indexOf(ch) + 1
            if (idx == 0) return null
            sb.append(
                when {
                    idx == 11 -> ','
                    idx == 10 -> '0'
                    idx <= 5 -> '0' + (2 * idx - 1)
                    else -> '0' + 2 * (idx - 5)
                }
            )
        }
        val bagian = sb.toString().split(',')
        if (bagian.size > 2) return null
        val ribu = bagian[0].toIntOrNull() ?: return null
        val pecahan = if (bagian.size == 2) {
            if (bagian[1].isEmpty() || bagian[1].length > 3) return null
            (bagian[1] + "000").take(3).toIntOrNull() ?: return null
        } else 0
        return ribu * 1000 + pecahan
    }
}
