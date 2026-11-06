package id.jajanpasar.kasir.core

import android.content.Context
import id.jajanpasar.kasir.data.*
import id.jajanpasar.kasir.logic.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Mesin status kasir. Semua kejadian (scan, ketukan layar) masuk ke satu antrean
 * dan diproses berurutan, sehingga tidak ada balapan antara dua scan cepat.
 * Database adalah sumber kebenaran; yang disimpan di memori hanya hal sementara
 * (dialog, akumulasi sigma, riwayat undo).
 */
class Engine(
    private val app: Context,
    private val dao: KasirDao,
    scope: CoroutineScope
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _efek = MutableSharedFlow<Efek>(extraBufferCapacity = 64)
    val efek: SharedFlow<Efek> = _efek.asSharedFlow()

    private val antrian = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private sealed class Aksi {
        class Tambah(val produkId: Long) : Aksi()
        class Nominal(val nilai: Int) : Aksi()
        class Member(val nomorLama: String?, val namaLama: String?) : Aksi()
    }

    private var sesi: Sesi? = null
    private var tahap = Tahap.BELUM_BUKA
    private var toko = ""
    private var tujuan: TujuanKirim? = null
    private var strukId: Long? = null // struk yang sedang tampil (AKTIF atau LUNAS)
    private var sigma = false
    private var terkumpul = 0
    private val riwayat = ArrayList<Aksi>()
    private var dlg: Dlg? = null
    private var pesanAktif: Pesan? = null
    private var pesanSeq = 0L
    private var selesaiInfo: String? = null
    private var tampilan = Tampilan()
    private var berkasLaporan: List<File> = emptyList()

    init {
        scope.launch { for (job in antrian) job() }
        kirim { muat() }
    }

    private fun kirim(blok: suspend () -> Unit) {
        val job: suspend () -> Unit = {
            try {
                blok()
            } catch (e: Exception) {
                gagal("Kesalahan: ${e.message}")
            }
            segarkan()
        }
        antrian.trySend(job)
    }

    // ---------- pintu masuk dari luar ----------
    fun terima(baris: String) = kirim { proses(baris) }
    fun ketukProduk(id: Long) = kirim { ketuk(id) }
    /** Tombol jumlah titipan di layar (+1, -1, +5, -5, +10, OK); sama dengan memindai kodenya. */
    fun tombolJumlah(k: String) = kirim {
        val d = dlg
        if (d is Dlg.Titip && !d.mengetik) ubahJumlah(d, k)
    }
    /** Mengetuk angka: membuka atau menutup keypad numerik. */
    fun ketukAngka() = kirim {
        val d = dlg
        if (d is Dlg.Titip) dlg = d.copy(mengetik = !d.mengetik, teks = "")
    }
    fun ketikPad(k: String) = kirim {
        val d = dlg
        if (d is Dlg.Titip && d.mengetik) padTitip(d, k)
    }
    fun simpanTampilan(t: Tampilan) = kirim {
        tampilan = Tampilan(
            if (t.simbol == "QR") "QR" else "DM", t.kodeCm.coerceIn(1f, 3f),
            t.jarakCm.coerceIn(0f, 2f), t.kalibrasi.coerceIn(0.5f, 2f)
        )
        dao.setPengaturan(Pengaturan("simbol", tampilan.simbol))
        dao.setPengaturan(Pengaturan("kode_cm", tampilan.kodeCm.toString()))
        dao.setPengaturan(Pengaturan("jarak_cm", tampilan.jarakCm.toString()))
        dao.setPengaturan(Pengaturan("kalibrasi", tampilan.kalibrasi.toString()))
        pesan("Tampilan disimpan")
    }
    fun opnameUbah(id: Long, delta: Int) = kirim { ubahOpname(id, delta) }
    /** Tombol konfirmasi di layar: menutup opname, atau menutup resume dan mengirim laporan. */
    fun konfirmasi() = kirim {
        if (dlg != null) return@kirim
        when (tahap) {
            Tahap.OPNAME -> tutupOpname()
            Tahap.REKAP -> tutupResume()
            else -> {}
        }
    }
    fun batalDialog() = kirim { batalkanDialog() }
    fun tutupDialog() = kirim { dlg = null }
    fun hapusTujuan() = kirim {
        tujuan = null
        dao.setPengaturan(Pengaturan("kirim_webapp", ""))
        dao.setPengaturan(Pengaturan("kirim_toko", ""))
        dao.setPengaturan(Pengaturan("kirim_token", ""))
        pesan("Tujuan kirim dihapus")
    }
    fun kirimUlang() = kirim { jalankanSelesai() }
    fun bagikanUlang() = kirim {
        if (berkasLaporan.isNotEmpty()) _efek.tryEmit(Efek.Bagikan(berkasLaporan, false))
    }
    fun mintaKosongkan() = kirim { dlg = Dlg.KonfirmasiKosongkan }
    fun jawabBatal(ya: Boolean) = kirim { jawabBatalStruk(ya) }
    fun kosongkanDanTutup() = kirim {
        dlg = null
        kosongkanSemua()
        _efek.tryEmit(Efek.TutupAplikasi)
    }

    // ---------- util ----------
    private fun now() = System.currentTimeMillis()
    private fun ok() { _efek.tryEmit(Efek.Ok) }
    private fun gagal(m: String) { pesan(m); _efek.tryEmit(Efek.Gagal) }
    private fun pesan(t: String) { pesanAktif = Pesan(++pesanSeq, t) }

    private suspend fun muat() {
        toko = dao.getPengaturan("nama_toko").orEmpty()
        val wa = dao.getPengaturan("kirim_webapp").orEmpty()
        val tk = dao.getPengaturan("kirim_toko").orEmpty()
        val tt = dao.getPengaturan("kirim_token").orEmpty()
        tujuan = if (wa.isNotBlank() && tk.isNotBlank() && tt.isNotBlank()) TujuanKirim(wa, tk, tt) else null
        tampilan = Tampilan(
            simbol = if (dao.getPengaturan("simbol") == "QR") "QR" else "DM",
            kodeCm = dao.getPengaturan("kode_cm")?.toFloatOrNull()?.coerceIn(1f, 3f) ?: 1.4f,
            jarakCm = dao.getPengaturan("jarak_cm")?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 1f,
            kalibrasi = dao.getPengaturan("kalibrasi")?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 1f
        )
        val s = dao.sesiAktif()
        sesi = s
        tahap = s?.let { runCatching { Tahap.valueOf(it.tahap) }.getOrDefault(Tahap.KASIR) } ?: Tahap.BELUM_BUKA
        if (s != null) strukId = dao.strukAktif(s.id)?.id
        if (tahap == Tahap.SELESAI) jalankanSelesai()
    }

    private suspend fun ubahTahap(t: Tahap) {
        tahap = t
        val s = sesi ?: return
        sesi = s.copy(tahap = t.name)
        dao.updateSesi(sesi!!)
    }

    private suspend fun simpanTujuan(s: Scan.Konfigurasi) {
        tujuan = TujuanKirim(s.webapp, s.toko, s.token)
        dao.setPengaturan(Pengaturan("kirim_webapp", s.webapp))
        dao.setPengaturan(Pengaturan("kirim_toko", s.toko))
        dao.setPengaturan(Pengaturan("kirim_token", s.token))
        if (toko.isBlank()) {
            toko = s.toko
            dao.setPengaturan(Pengaturan("nama_toko", s.toko))
        }
        pesan("Tujuan kirim disimpan: ${s.toko}")
        ok()
    }

    private fun ringkasTujuan(): String {
        val t = tujuan ?: return ""
        return "${t.toko} \u00B7 \u2026${t.webapp.removeSuffix("/exec").takeLast(6)}/exec"
    }

    private suspend fun simpanToko(nama: String) {
        toko = nama
        dao.setPengaturan(Pengaturan("nama_toko", nama))
        pesan("Toko: $nama")
        ok()
    }

    private fun tutupTampilan() {
        strukId = null; sigma = false; terkumpul = 0; riwayat.clear()
    }

    private suspend fun strukSekarang(): Struk? {
        val id = strukId ?: return null
        val s = dao.struk(id)
        return if (s == null || s.status == "BATAL") null else s
    }

    // ---------- scan ----------
    private suspend fun proses(baris: String) {
        val s = LineParser.parse(baris)
        if (s is Scan.Salah) { gagal("QR tidak dikenal: ${s.alasan}"); return }
        if (dlg is Dlg.KonfirmasiBatal) {
            when {
                s is Scan.Perintah && s.jenis == Jenis.ESC -> jawabBatalStruk(true)
                s is Scan.Perintah && s.jenis == Jenis.UNDO -> jawabBatalStruk(false)
                else -> gagal("Scan ESC untuk membatalkan, atau UNDO untuk tidak jadi")
            }
            return
        }
        val d = dlg
        if (d is Dlg.Titip) {
            when {
                s is Scan.Tombol ->
                    if (d.mengetik) gagal("Keypad sedang terbuka. Selesaikan dulu, atau scan ESC")
                    else ubahJumlah(d, s.isi)
                s is Scan.Perintah && s.jenis == Jenis.ESC -> { batalkanDialog(); ok() }
                else -> gagal("Selesaikan dialog dulu: scan tombol di layar, atau ESC untuk batal")
            }
            return
        }
        if (d != null) { gagal("Selesaikan dialog dulu"); return }
        // QR tujuan kirim berlaku di semua sesi (juga saat struk tampil)
        if (s is Scan.Konfigurasi) { simpanTujuan(s); return }
        when (tahap) {
            Tahap.BELUM_BUKA -> when (s) {
                is Scan.Petugas -> bukaKasir(s.nama)
                is Scan.Toko -> simpanToko(s.nama)
                else -> gagal("Scan QR ID petugas dulu")
            }
            Tahap.KASIR -> kasir(s)
            Tahap.OPNAME -> opname(s)
            Tahap.REKAP -> rekap(s)
            Tahap.SELESAI -> gagal("Laporan sedang diselesaikan")
        }
    }

    private suspend fun bukaKasir(nama: String) {
        val baru = Sesi(petugas = nama, waktuBuka = now(), tahap = Tahap.KASIR.name)
        sesi = baru.copy(id = dao.insertSesi(baru))
        tahap = Tahap.KASIR
        tutupTampilan()
        pesan("Kasir dibuka: $nama")
        ok()
    }

    private suspend fun kasir(s: Scan) {
        val struk = strukSekarang()
        val aktif = struk?.status == "AKTIF"
        when (s) {
            is Scan.Petugas -> {
                if (!petugasSama(s.nama)) { tolakPetugas(); return }
                if (aktif) { gagal("Selesaikan struk dulu"); return }
                tutupTampilan()
                ubahTahap(Tahap.OPNAME)
                ok()
            }
            is Scan.Toko -> if (aktif) gagal("Selesaikan struk dulu") else simpanToko(s.nama)
            is Scan.Produk -> scanProduk(s, struk)
            is Scan.Cash -> if (aktif && struk != null) bayarNominal(struk, s.nominal) else gagal("Tidak ada struk aktif")
            is Scan.Member -> member(s, struk)
            is Scan.Tombol -> gagal("Tidak ada dialog yang terbuka")
            is Scan.Konfigurasi -> simpanTujuan(s)
            is Scan.Perintah -> perintah(s.jenis, struk)
            is Scan.Salah -> gagal(s.alasan)
        }
    }

    private suspend fun scanProduk(s: Scan.Produk, struk: Struk?) {
        val sid = sesi!!.id
        val ada = dao.cariProduk(s.nama, s.penitip)
        val punyaStok = ada != null && dao.stokSesi(sid).any { it.produkId == ada.id }
        if (ada == null || !punyaStok) {
            if (struk?.status == "AKTIF") { gagal("Produk belum tercatat. Selesaikan struk dulu"); return }
            tutupTampilan()
            val id = ada?.id ?: dao.insertProduk(
                ProdukEntity(nama = s.nama, penitip = s.penitip, hargaJual = s.hargaJual,
                    kodeHarga = s.kode, hargaTitip = s.hargaTitip)
            )
            dlg = Dlg.Titip(
                produkId = id, nama = s.nama, penitip = s.penitip, saatIni = 0, baru = true, jumlah = 0,
                hargaTitip = ada?.hargaTitip ?: s.hargaTitip, hargaJual = ada?.hargaJual ?: s.hargaJual
            )
            ok()
            return
        }
        if (ada.hargaJual != s.hargaJual || !ada.kodeHarga.equals(s.kode, ignoreCase = true)) {
            pesan("Harga pada QR berbeda dari yang tercatat; memakai data tercatat")
        }
        if (struk != null && struk.status == "LUNAS") tutupTampilan()
        tambahKeStruk(ada)
    }

    private suspend fun stokDari(pid: Long): Int {
        val sid = sesi!!.id
        val titip = dao.stokSesi(sid).firstOrNull { it.produkId == pid }?.jumlahTitip ?: 0
        val jual = dao.terjual(sid).firstOrNull { it.produkId == pid }?.jumlah ?: 0
        return titip - jual
    }

    private suspend fun hitungTotal(id: Long) {
        val total = dao.itemStruk(id).sumOf { it.jumlah * it.hargaJual }
        dao.struk(id)?.let { dao.updateStruk(it.copy(total = total)) }
    }

    private suspend fun tambahKeStruk(p: ProdukEntity) {
        val sid = sesi!!.id
        var struk = strukId?.let { dao.struk(it) }
        if (struk == null || struk.status != "AKTIF") {
            val id = dao.insertStruk(Struk(sesiId = sid, nomor = dao.nomorBerikut(sid), waktuBuat = now(), status = "AKTIF"))
            strukId = id
            sigma = false; terkumpul = 0; riwayat.clear()
            struk = dao.struk(id)!!
        }
        val lama = dao.item(struk.id, p.id)
        val jumlah = (lama?.jumlah ?: 0) + 1
        dao.upsertItem(StrukItem(id = lama?.id ?: 0, strukId = struk.id, produkId = p.id,
            jumlah = jumlah, hargaJual = p.hargaJual, hargaTitip = p.hargaTitip))
        riwayat.add(Aksi.Tambah(p.id))
        hitungTotal(struk.id)
        val stok = stokDari(p.id)
        if (jumlah > stok) pesan("Stok ${p.nama} tidak cukup (stok $stok)")
        ok()
    }

    private suspend fun perintah(jenis: Jenis, struk: Struk?) {
        val aktif = struk?.status == "AKTIF"
        when (jenis) {
            Jenis.SIGMA -> {
                if (aktif && struk != null && struk.total > 0) {
                    if (!sigma) { sigma = true; terkumpul = 0 }
                    ok()
                } else gagal("Tidak ada struk berisi")
            }
            Jenis.BAYAR -> {
                if (struk != null && struk.status == "LUNAS") {
                    // BAYAR sekali lagi setelah lunas = tutup struk (abaikan 1 detik pertama, anti scan ganda)
                    if (now() - (struk.waktuLunas ?: 0L) >= 1000) { tutupTampilan(); ok() }
                    return
                }
                if (!aktif || struk == null || struk.total <= 0) { gagal("Tidak ada struk berisi"); return }
                val uang = if (sigma && terkumpul > 0) terkumpul else struk.total
                if (uang >= struk.total) lunaskan(struk, uang)
                // uang kurang: tidak ada reaksi
            }
            Jenis.UNDO -> undo(struk)
            Jenis.ESC -> batal(struk)
        }
    }

    private suspend fun bayarNominal(struk: Struk, nominal: Int) {
        if (struk.total <= 0) { gagal("Struk masih kosong"); return }
        if (sigma) {
            terkumpul += nominal
            riwayat.add(Aksi.Nominal(nominal))
            ok()
            return
        }
        if (nominal >= struk.total) lunaskan(struk, nominal)
        // nominal kurang: tidak ada reaksi
    }

    private suspend fun lunaskan(struk: Struk, uang: Int) {
        dao.updateStruk(struk.copy(status = "LUNAS", waktuLunas = now(),
            uangDiberikan = uang, kembalian = uang - struk.total))
        sigma = false
        ok()
    }

    private suspend fun batalkan(struk: Struk) {
        if (struk.status == "AKTIF" && dao.itemStruk(struk.id).isEmpty()) {
            dao.hapusStrukId(struk.id)
        } else {
            dao.updateStruk(struk.copy(status = "BATAL"))
        }
        tutupTampilan()
    }

    private suspend fun batal(struk: Struk?) {
        if (struk == null) { gagal("Tidak ada struk"); return }
        if (struk.status == "LUNAS") { dlg = Dlg.KonfirmasiBatal; ok(); return }
        batalkan(struk)
        ok()
    }

    private suspend fun undo(struk: Struk?) {
        if (struk == null) { gagal("Tidak ada struk"); return }
        if (struk.status == "LUNAS") { gagal("Struk sudah lunas. Scan ESC untuk membatalkan"); return }
        when (val a = riwayat.removeLastOrNull()) {
            null -> batalkan(struk)
            is Aksi.Nominal -> terkumpul = (terkumpul - a.nilai).coerceAtLeast(0)
            is Aksi.Member -> dao.updateStruk(struk.copy(memberNomor = a.nomorLama, memberNama = a.namaLama))
            is Aksi.Tambah -> {
                val item = dao.item(struk.id, a.produkId)
                if (item != null) {
                    if (item.jumlah <= 1) dao.hapusItem(struk.id, a.produkId)
                    else dao.upsertItem(item.copy(jumlah = item.jumlah - 1))
                }
                hitungTotal(struk.id)
                if (dao.itemStruk(struk.id).isEmpty()) {
                    dao.hapusStrukId(struk.id)
                    tutupTampilan()
                }
            }
        }
        ok()
    }

    /** Member melekat pada struk yang sedang tampil (aktif atau lunas) dan tersimpan di data transaksi. */
    private suspend fun member(s: Scan.Member, struk: Struk?) {
        if (struk == null) { gagal("Scan produk dulu: member melekat pada struk"); return }
        dao.updateStruk(struk.copy(memberNomor = s.nomor, memberNama = s.nama.ifEmpty { null }))
        if (struk.status == "AKTIF") riwayat.add(Aksi.Member(struk.memberNomor, struk.memberNama))
        pesan("Member: ${s.nama.ifEmpty { s.nomor }}")
        ok()
    }

    private suspend fun jawabBatalStruk(ya: Boolean) {
        if (dlg !is Dlg.KonfirmasiBatal) return
        dlg = null
        if (ya) strukSekarang()?.let { batalkan(it) }
        ok()
    }

    private fun petugasSama(nama: String) =
        nama.trim().equals(sesi?.petugas?.trim(), ignoreCase = true)

    private fun tolakPetugas() {
        gagal("Petugas berbeda. Hanya ${sesi?.petugas} yang boleh menutup")
    }

    private suspend fun cariProdukUi(s: Scan.Produk): ProdukUi? =
        bangunProduk().firstOrNull { it.nama.equals(s.nama, true) && it.penitip.equals(s.penitip, true) }

    private suspend fun tutupOpname() {
        simpanOpname()
        ubahTahap(Tahap.REKAP)
        ok()
    }

    private suspend fun tutupResume() {
        ubahTahap(Tahap.SELESAI)
        ok()
        jalankanSelesai()
    }

    /** Saat opname ditutup, angka opname setiap produk dicatat di database (termasuk yang tidak diubah). */
    private suspend fun simpanOpname() {
        val sid = sesi?.id ?: return
        for (p in bangunProduk()) {
            if (p.stokRiil == null) dao.upsertStok(StokSesi(sid, p.id, p.titip, p.opname))
        }
    }

    private suspend fun opname(s: Scan) {
        when (s) {
            is Scan.Petugas -> {
                if (!petugasSama(s.nama)) { tolakPetugas(); return }
                tutupOpname()
            }
            is Scan.Toko -> simpanToko(s.nama)
            is Scan.Produk -> {
                val p = cariProdukUi(s)
                if (p == null) gagal("Produk tidak tercatat")
                else { pesan("${p.nama}: stok ${p.stok}, opname ${p.opname}"); ok() }
            }
            else -> gagal("Perintah ini tidak berlaku saat opname")
        }
    }

    private suspend fun rekap(s: Scan) {
        when (s) {
            is Scan.Petugas -> {
                if (!petugasSama(s.nama)) { tolakPetugas(); return }
                tutupResume()
            }
            is Scan.Toko -> simpanToko(s.nama)
            is Scan.Produk -> {
                val p = cariProdukUi(s)
                if (p == null) gagal("Produk tidak tercatat")
                else {
                    val r = rekapBaris(p)
                    pesan("${p.nama}: sisa ${r.retur}, laku ${r.terjual}, ${Fmt.rp(r.uang)}")
                    ok()
                }
            }
            else -> gagal("Perintah ini tidak berlaku saat resume")
        }
    }

    // ---------- ketukan layar dan keypad ----------
    private suspend fun ketuk(id: Long) {
        if (tahap != Tahap.KASIR || dlg != null) return
        if (strukSekarang()?.status == "AKTIF") return
        val p = bangunProduk().firstOrNull { it.id == id } ?: return
        dlg = Dlg.Titip(
            produkId = p.id, nama = p.nama, penitip = p.penitip, saatIni = p.titip, baru = false, jumlah = p.titip,
            hargaTitip = p.hargaTitip, hargaJual = p.hargaJual
        )
    }

    private suspend fun ubahOpname(id: Long, delta: Int) {
        if (tahap != Tahap.OPNAME) return
        val sid = sesi?.id ?: return
        val p = bangunProduk().firstOrNull { it.id == id } ?: return
        val baru = (p.opname + delta).coerceAtLeast(0)
        if (baru == p.opname) return
        dao.upsertStok(StokSesi(sid, id, p.titip, baru, opnameDiubah = true))
    }

    private suspend fun ubahJumlah(d: Dlg.Titip, k: String) {
        if (k == "OK") { konfirmasiTitip(d); return }
        val delta = when (k) { "+1" -> 1; "+5" -> 5; "+10" -> 10; "-1" -> -1; "-5" -> -5; else -> 0 }
        dlg = d.copy(jumlah = (d.jumlah + delta).coerceIn(0, 999_999))
        ok()
    }

    /** Keypad numerik: angka mengganti jumlah; ENTER langsung menyimpan. */
    private suspend fun padTitip(d: Dlg.Titip, k: String) {
        when (k) {
            "ENTER" -> {
                if (d.teks.isEmpty()) { gagal("Isi angka dulu"); return }
                konfirmasiTitip(d.copy(jumlah = d.teks.toIntOrNull() ?: 0))
            }
            "HAPUS" -> { dlg = d.copy(teks = d.teks.dropLast(1)); ok() }
            else -> {
                if (d.teks.length >= 6) { gagal("Angka terlalu panjang"); return }
                val gabung = d.teks + k
                dlg = d.copy(teks = if (gabung.all { it == '0' }) "0" else gabung.trimStart('0'))
                ok()
            }
        }
    }

    private suspend fun konfirmasiTitip(d: Dlg.Titip) {
        val sid = sesi?.id ?: return
        if (d.baru && d.jumlah <= 0) { gagal("Jumlah titipan harus lebih dari 0"); return }
        val lama = dao.stokSesi(sid).firstOrNull { it.produkId == d.produkId }
        dao.upsertStok(lama?.copy(jumlahTitip = d.jumlah) ?: StokSesi(sid, d.produkId, d.jumlah))
        dlg = null
        ok()
    }

    private suspend fun batalkanDialog() {
        val d = dlg
        if (d is Dlg.Titip && d.baru) dao.hapusProdukId(d.produkId)
        dlg = null
    }

    // ---------- selesai ----------
    private suspend fun jalankanSelesai() {
        val s = sesi ?: return
        selesaiInfo = "Menyusun laporan..."
        segarkan()
        val lap = PenyusunLaporan.bangun(dao, s, toko, now())
        val b = BerkasLaporan.tulis(app, lap)
        berkasLaporan = listOf(b.pdf, b.html, b.json)
        val antrean = dao.insertAntrean(
            AntreanKirim(sesiId = s.id, jenis = "JSON", berkas = b.json.absolutePath, status = "MENUNGGU")
        )
        val t = tujuan
        if (t != null) {
            selesaiInfo = "Mengirim database ke ${t.toko}..."
            segarkan()
            val hasil = KirimDb.kirim(t, BerkasLaporan.jsonObjek(lap))
            if (hasil.ok) {
                dao.tandaiAntrean(antrean, "TERKIRIM")
                kosongkanSemua()
                Notifikasi.tampilkan(app, "Database terkirim", hasil.file ?: "Laporan ${t.toko} tersimpan")
                _efek.tryEmit(Efek.TutupAplikasi)
                return
            }
            val alasan = hasil.error ?: "balasan tidak ok"
            selesaiInfo = "Pengiriman online gagal ($alasan). Data tetap tersimpan. " +
                "Kirim manual lewat WhatsApp, lalu tekan \"Sudah terkirim\" untuk mengosongkan data."
            Notifikasi.tampilkan(app, "Pengiriman database gagal", "$alasan. Kirim manual lewat WhatsApp.")
            _efek.tryEmit(Efek.Bagikan(listOf(b.json, b.pdf, b.html), false, true))
        } else {
            selesaiInfo = "Belum ada tujuan kirim online (scan QR tujuan). Kirim berkas (JSON, PDF, HTML) lewat menu bagikan, " +
                "lalu tekan \"Sudah terkirim\" untuk mengosongkan data."
            _efek.tryEmit(Efek.Bagikan(berkasLaporan, false))
        }
    }

    private suspend fun kosongkanSemua() {
        dao.kosongkan()
        dao.hapusAntreanTerkirim()
        sesi = null
        tahap = Tahap.BELUM_BUKA
        tutupTampilan()
        dlg = null
        selesaiInfo = null
    }

    // ---------- membangun state tampilan ----------
    private suspend fun bangunProduk(): List<ProdukUi> {
        val s = sesi ?: return emptyList()
        val stok = dao.stokSesi(s.id).associateBy { it.produkId }
        val jual = dao.terjual(s.id).associate { it.produkId to it.jumlah }
        return dao.semuaProduk().mapNotNull { p ->
            val st = stok[p.id] ?: return@mapNotNull null
            val terjual = jual[p.id] ?: 0
            ProdukUi(p.id, p.nama, p.penitip, p.hargaJual, p.hargaTitip,
                st.jumlahTitip, st.jumlahTitip - terjual, st.stokRiil, terjual,
                st.stokRiil ?: (st.jumlahTitip - terjual).coerceAtLeast(0))
        }
    }

    private suspend fun bangunStruk(produk: List<ProdukUi>): StrukUi? {
        val st = strukSekarang() ?: return null
        val nama = produk.associate { it.id to it.nama }
        val baris = dao.itemStruk(st.id).map { BarisStruk(nama[it.produkId] ?: "?", it.jumlah, it.hargaJual) }
        return StrukUi(st.nomor, st.waktuBuat, baris, st.total, st.status == "LUNAS",
            st.uangDiberikan, st.kembalian, sigma, terkumpul, st.memberNomor, st.memberNama)
    }

    private suspend fun segarkan() {
        val produk = bangunProduk()
        val rekap = if (tahap == Tahap.REKAP || tahap == Tahap.SELESAI) {
            produk.map { rekapBaris(it) }.groupBy { it.penitip }.entries
                .sortedBy { it.key.lowercase() }
                .map { e -> RekapPenitip(e.key, e.value.sortedBy { b -> b.nama.lowercase() }) }
        } else emptyList()
        _state.value = UiState(
            tahap = tahap, petugas = sesi?.petugas, toko = toko, tujuanKirim = ringkasTujuan(),
            produk = produk, struk = bangunStruk(produk), dialog = dlg, pesan = pesanAktif,
            rekap = rekap, selesaiInfo = selesaiInfo, tampilan = tampilan
        )
    }
}
