package id.jajanpasar.kasir.core

import java.io.File

enum class Tahap(val label: String) {
    BELUM_BUKA("Belum buka"), KASIR("Kasir"), OPNAME("Opname"), REKAP("Rekap"), SELESAI("Selesai")
}

data class ProdukUi(
    val id: Long, val nama: String, val penitip: String,
    val hargaJual: Int, val hargaTitip: Int,
    val titip: Int, val stok: Int, val stokRiil: Int?, val terjual: Int,
    /** Angka opname: stok riil bila sudah diubah, selain itu sama dengan stok (0 bila stok negatif). */
    val opname: Int
)

data class BarisStruk(val nama: String, val jumlah: Int, val harga: Int) {
    val subtotal: Int get() = jumlah * harga
}

data class StrukUi(
    val nomor: Int, val tanggal: Long, val baris: List<BarisStruk>, val total: Int,
    val lunas: Boolean, val uang: Int, val kembalian: Int, val sigma: Boolean, val terkumpul: Int,
    val memberNomor: String? = null, val memberNama: String? = null
)

/** Opsi B: laku = titipan - opname; sisa = opname. */
data class RekapBaris(
    val nama: String, val penitip: String, val titip: Int,
    val terjual: Int, val retur: Int, val hargaTitip: Int
) {
    val uang: Int get() = terjual * hargaTitip
}

data class RekapPenitip(val penitip: String, val baris: List<RekapBaris>) {
    val total: Int get() = baris.sumOf { it.uang }
}

fun rekapBaris(p: ProdukUi): RekapBaris =
    RekapBaris(p.nama, p.penitip, p.titip, (p.titip - p.opname).coerceAtLeast(0), p.opname, p.hargaTitip)

sealed class Dlg {
    /**
     * Dialog jumlah titipan. [jumlah] diubah dengan tombol +1, -1, +5, -5, +10 (ketuk atau scan kode di layar);
     * OK menyimpan. Mengetuk angka membuka keypad ([mengetik]); [teks] adalah angka yang diketik.
     */
    data class Titip(
        val produkId: Long, val nama: String, val penitip: String,
        val saatIni: Int, val baru: Boolean,
        val jumlah: Int, val hargaTitip: Int, val hargaJual: Int,
        val mengetik: Boolean = false, val teks: String = ""
    ) : Dlg()
    object KonfirmasiKosongkan : Dlg()
    object KonfirmasiBatal : Dlg()
}

data class Pesan(val id: Long, val teks: String)

/** Pengaturan tampilan kode di dialog: simbol DM (Data Matrix) atau QR, ukuran dan jarak dalam cm. */
data class Tampilan(
    val simbol: String = "DM", val kodeCm: Float = 1.4f, val jarakCm: Float = 1f, val kalibrasi: Float = 1f
)

data class UiState(
    val tahap: Tahap = Tahap.BELUM_BUKA,
    val petugas: String? = null,
    val toko: String = "",
    /** Ringkasan tujuan kirim online (tanpa token), kosong bila belum ada. */
    val tujuanKirim: String = "",
    val produk: List<ProdukUi> = emptyList(),
    val struk: StrukUi? = null,
    val dialog: Dlg? = null,
    val pesan: Pesan? = null,
    val rekap: List<RekapPenitip> = emptyList(),
    val selesaiInfo: String? = null,
    val tampilan: Tampilan = Tampilan()
)

sealed class Efek {
    object Ok : Efek()
    object Gagal : Efek()
    data class Bagikan(val berkas: List<File>, val tutupSetelah: Boolean, val viaWa: Boolean = false) : Efek()
    object TutupAplikasi : Efek()
}
