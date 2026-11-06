package id.jajanpasar.kasir.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.jajanpasar.kasir.core.*
import id.jajanpasar.kasir.logic.Fmt
import id.jajanpasar.kasir.logic.SimbolKode
import id.jajanpasar.kasir.logic.TOMBOL_JUMLAH
import kotlinx.coroutines.delay

private val Merah = Color(0xFFC62828)

/** Tiap sesi punya warna sendiri agar mudah dibedakan sekilas. */
private class Tema(val bar: Color, val latar: Color, val aksen: Color)

private fun tema(t: Tahap): Tema = when (t) {
    Tahap.KASIR -> Tema(Color(0xFF5B3A1E), Color(0xFFF7F1E6), Color(0xFF8A4B1F))   // coklat dan krem
    Tahap.OPNAME -> Tema(Color(0xFF0D47A1), Color(0xFFE3F2FD), Color(0xFF1565C0))  // biru
    Tahap.REKAP -> Tema(Color(0xFF1B5E20), Color(0xFFE8F5E9), Color(0xFF2E7D32))   // hijau
    else -> Tema(Color(0xFF455A64), Color(0xFFECEFF1), Color(0xFF546E7A))
}

private fun labelSesi(t: Tahap): String = when (t) {
    Tahap.KASIR -> "Sesi Operasional"
    Tahap.OPNAME -> "Sesi Opname"
    Tahap.REKAP -> "Resume"
    else -> ""
}

@Composable
fun KasirApp(engine: Engine, pemindai: String, barisMentah: String, terhubung: Boolean) {
    val st by engine.state.collectAsState()
    val tm = tema(st.tahap)
    var admin by remember { mutableStateOf(false) }
    var pesanTampil by remember { mutableStateOf<Pesan?>(null) }
    LaunchedEffect(st.pesan?.id) {
        pesanTampil = st.pesan
        delay(2500)
        pesanTampil = null
    }

    MaterialTheme(colorScheme = lightColorScheme(primary = tm.aksen)) {
        Surface(Modifier.fillMaxSize(), color = tm.latar) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    BilahAtas(st, tm, terhubung) { admin = true }
                    when (st.tahap) {
                        Tahap.BELUM_BUKA -> Tengah("Scan QR ID petugas untuk membuka kasir")
                        Tahap.SELESAI -> PanelSelesai(
                            st,
                            onBagikan = { engine.bagikanUlang() },
                            onKirim = { engine.kirimUlang() },
                            onKosongkan = { engine.mintaKosongkan() }
                        )
                        Tahap.KASIR ->
                            if (st.produk.isEmpty()) Tengah("Scan QR produk untuk mencatat titipan")
                            else TabelKasir(Modifier.weight(1f), st, tm) { engine.ketukProduk(it) }
                        Tahap.OPNAME ->
                            if (st.produk.isEmpty()) Tengah("Belum ada produk")
                            else TabelOpname(
                                Modifier.weight(1f), st, tm,
                                onKurang = { engine.opnameUbah(it, -1) },
                                onTambah = { engine.opnameUbah(it, 1) },
                                onKonfirmasi = { engine.konfirmasi() }
                            )
                        Tahap.REKAP ->
                            if (st.rekap.isEmpty()) Tengah("Belum ada produk")
                            else TabelResume(Modifier.weight(1f), st, tm) { engine.konfirmasi() }
                    }
                }

                st.struk?.let { PanelStruk(st.toko, it) }

                when (val d = st.dialog) {
                    is Dlg.Titip -> DialogTitip(d, st.tampilan, engine)
                    is Dlg.KonfirmasiKosongkan -> AlertDialog(
                        onDismissRequest = { engine.tutupDialog() },
                        title = { Text("Kosongkan data?") },
                        text = { Text("Pastikan laporan (terutama JSON) sudah terkirim. Data di aplikasi akan dikosongkan dan aplikasi ditutup.") },
                        confirmButton = { TextButton({ engine.kosongkanDanTutup() }) { Text("Ya, sudah terkirim") } },
                        dismissButton = { TextButton({ engine.tutupDialog() }) { Text("Batal") } }
                    )
                    is Dlg.KonfirmasiBatal -> AlertDialog(
                        onDismissRequest = { engine.jawabBatal(false) },
                        title = { Text("Transaksi Dibatalkan?") },
                        text = { Text("Stok akan dikembalikan.\n\nScan ESC untuk membatalkan.\nScan UNDO untuk tidak jadi.") },
                        confirmButton = { TextButton({ engine.jawabBatal(true) }) { Text("Ya, batalkan") } },
                        dismissButton = { TextButton({ engine.jawabBatal(false) }) { Text("Tidak") } }
                    )
                    null -> {}
                }

                pesanTampil?.let {
                    Box(
                        Modifier.align(Alignment.BottomCenter).padding(24.dp)
                            .background(Color(0xE6333333), RoundedCornerShape(12.dp)).padding(14.dp)
                    ) { Text(it.teks, color = Color.White) }
                }

                if (admin) DialogAdmin(engine, st, pemindai, barisMentah) { admin = false }
            }
        }
    }
}

@Composable
private fun BilahAtas(st: UiState, tm: Tema, terhubung: Boolean, onTahan: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(tm.bar).padding(12.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onTahan() }) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(st.toko.ifBlank { "Kasir Jajan Pasar" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val sesi = labelSesi(st.tahap)
            if (sesi.isNotEmpty()) {
                Text(
                    (st.petugas?.let { "Petugas: $it · " } ?: "") + sesi,
                    color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp
                )
            }
        }
        // indikator pemindai: hijau = terhubung, abu-abu = tidak terhubung
        Box(
            Modifier.size(12.dp).clip(CircleShape)
                .background(if (terhubung) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
        )
    }
}

@Composable
private fun ColumnScope.Tengah(teks: String) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(teks, fontSize = 20.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
    }
}

// ---------------- tabel ----------------

@Composable
private fun Sel(
    teks: String, modifier: Modifier, tebal: Boolean = false, warna: Color = Color.Unspecified,
    rata: TextAlign = TextAlign.Start, ukuran: Int = 14
) {
    Text(
        teks, modifier,
        color = warna, fontSize = ukuran.sp, textAlign = rata, maxLines = 2,
        fontWeight = if (tebal) FontWeight.Bold else FontWeight.Normal
    )
}

private fun warnaBaris(i: Int): Color = if (i % 2 == 0) Color.White else Color(0xFFF5F5F5)

/** Sesi operasional: produk dan stok. Ketuk baris untuk koreksi titipan. */
@Composable
private fun TabelKasir(modifier: Modifier, st: UiState, tm: Tema, onKetuk: (Long) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(tm.bar).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Sel("Produk", Modifier.weight(1f), true, Color.White)
            Sel("Stok", Modifier.width(80.dp), true, Color.White, TextAlign.End)
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(st.produk, key = { _, p -> p.id }) { i, p ->
                Row(
                    Modifier.fillMaxWidth().background(warnaBaris(i)).clickable { onKetuk(p.id) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sel(p.nama, Modifier.weight(1f), ukuran = 16)
                    Sel(
                        p.stok.toString(), Modifier.width(80.dp), true,
                        if (p.stok < 0) Merah else Color.Unspecified, TextAlign.End, 18
                    )
                }
            }
        }
    }
}

/** Sesi opname: produk, stok, opname, tombol - dan +. Opname awal sama dengan stok (0 bila stok negatif). */
@Composable
private fun TabelOpname(
    modifier: Modifier, st: UiState, tm: Tema,
    onKurang: (Long) -> Unit, onTambah: (Long) -> Unit, onKonfirmasi: () -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(tm.bar).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Sel("Produk", Modifier.weight(1f), true, Color.White)
            Sel("Stok", Modifier.width(48.dp), true, Color.White, TextAlign.End)
            Sel("Opname", Modifier.width(68.dp), true, Color.White, TextAlign.End)
            Spacer(Modifier.width(108.dp))
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(st.produk, key = { _, p -> p.id }) { i, p ->
                val beda = p.opname != p.stok
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (beda) Color(0x33FFC107) else warnaBaris(i))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Sel(p.nama, Modifier.weight(1f), ukuran = 15)
                    Sel(p.stok.toString(), Modifier.width(48.dp), false, if (p.stok < 0) Merah else Color.Unspecified, TextAlign.End, 15)
                    Sel(p.opname.toString(), Modifier.width(68.dp), true, tm.aksen, TextAlign.End, 20)
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onKurang(p.id) }, modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp)
                    ) { Text("\u2212", fontSize = 24.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onTambah(p.id) }, modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp)
                    ) { Text("+", fontSize = 24.sp) }
                }
            }
        }
        TombolKonfirmasi("Konfirmasi opname", onKonfirmasi)
    }
}

@Composable
private fun TombolKonfirmasi(teks: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(12.dp).height(54.dp),
        shape = RoundedCornerShape(14.dp)
    ) { Text(teks, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
}

private fun angkaRp(n: Int): String = Fmt.rp(n).removePrefix("Rp ")

/**
 * Resume setelah opname, satu blok per penitip (urut abjad):
 *   Bu Joni      Sisa  Laku  Bayar (Rp)
 *   Apem Manis      3     0          0
 *   Kue lapis       8     4     38.000
 *                    Total     38.000
 */
@Composable
private fun TabelResume(modifier: Modifier, st: UiState, tm: Tema, onKonfirmasi: () -> Unit) {
    val wAngka = 52.dp
    val wRp = 100.dp
    Column(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.weight(1f)) {
            for (g in st.rekap) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 10.dp).background(tm.bar)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Sel(g.penitip, Modifier.weight(1f), true, Color.White, ukuran = 16)
                        Sel("Sisa", Modifier.width(wAngka), true, Color.White, TextAlign.End, 13)
                        Sel("Laku", Modifier.width(wAngka), true, Color.White, TextAlign.End, 13)
                        Sel("Bayar (Rp)", Modifier.width(wRp), true, Color.White, TextAlign.End, 13)
                    }
                }
                items(g.baris) { b ->
                    Row(
                        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Sel(b.nama, Modifier.weight(1f), ukuran = 15)
                        Sel(b.retur.toString(), Modifier.width(wAngka), false, rata = TextAlign.End, ukuran = 15)
                        Sel(b.terjual.toString(), Modifier.width(wAngka), false, rata = TextAlign.End, ukuran = 15)
                        Sel(angkaRp(b.uang), Modifier.width(wRp), false, rata = TextAlign.End, ukuran = 15)
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFFF1F8F2)).padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Sel("Total", Modifier.weight(1f), true, tm.aksen, TextAlign.End, 15)
                        Sel(angkaRp(g.total), Modifier.width(wRp), true, tm.aksen, TextAlign.End, 16)
                    }
                }
            }
            if (st.rekap.size > 1) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).background(tm.bar)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Sel("Total semua penitip", Modifier.weight(1f), true, Color.White, ukuran = 15)
                        Sel(angkaRp(st.rekap.sumOf { it.total }), Modifier.width(wRp), true, Color.White, TextAlign.End, 16)
                    }
                }
            }
        }
        TombolKonfirmasi("Konfirmasi resume", onKonfirmasi)
    }
}

// ---------------- struk ----------------

@Composable
private fun PanelStruk(toko: String, s: StrukUi) {
    Surface(Modifier.fillMaxSize(), color = Color(0xFFFFFDF7)) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text(toko.ifBlank { "STRUK" }, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("No. ${s.nomor} · ${Fmt.tgl(s.tanggal)}", color = Color.Gray)
                if (s.memberNomor != null) {
                    val nm = s.memberNama.orEmpty()
                    Text(
                        if (nm.isEmpty()) "Member: ${s.memberNomor}" else "Member: $nm (${s.memberNomor})",
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8A4B1F)
                    )
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f)) {
                    items(s.baris) { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${b.jumlah} x ${b.nama}", fontSize = 18.sp)
                                Text("@ ${Fmt.rp(b.harga)}", fontSize = 12.sp, color = Color.Gray)
                            }
                            Text(Fmt.rp(b.subtotal), fontSize = 18.sp)
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    Text(Fmt.rp(s.total), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
                if (s.sigma && !s.lunas) {
                    Text("Nominal terkumpul: ${Fmt.rp(s.terkumpul)}", fontSize = 18.sp, color = Color(0xFF8A4B1F))
                }
                if (s.lunas) {
                    Text("Uang diberikan: ${Fmt.rp(s.uang)}", fontSize = 18.sp)
                    Text("Kembalian: ${Fmt.rp(s.kembalian)}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (s.lunas) {
                Text(
                    "LUNAS", Modifier.align(Alignment.Center).rotate(-20f),
                    fontSize = 110.sp, fontWeight = FontWeight.Black, color = Color(0x552E7D32)
                )
            }
        }
    }
}

// ---------------- dialog angka (keypad QR atau ketukan) ----------------

@Composable
private fun HargaKotak(label: String, nilai: Int, modifier: Modifier) {
    Column(
        modifier.background(Color(0xFFFFF3CD), RoundedCornerShape(12.dp)).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFF6B5B00))
        Text(Fmt.rp(nilai), fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

/** Piksel per cm menurut ponsel, dikalikan faktor kalibrasi. */
@Composable
private fun rememberPxPerCm(kalibrasi: Float): Float {
    val dm = LocalContext.current.resources.displayMetrics
    val dpi = if (dm.xdpi > 50f) dm.xdpi else dm.densityDpi.toFloat()
    return dpi / 2.54f * kalibrasi
}

private fun warnaTombol(k: String): Color = when (k) {
    "+10" -> Color(0xFF00695C)
    "OK" -> Color(0xFF1565C0)
    else -> if (k.startsWith("-")) Color(0xFFC62828) else Color(0xFF2E7D32)
}

/** Kode Data Matrix atau QR berukuran fisik tertentu (cm), berlatar putih dengan zona tenang. */
@Composable
private fun KodeSimbol(isi: String, tp: Tampilan, pxPerCm: Float) {
    val matriks = remember(isi, tp.simbol) { SimbolKode.matriks(isi, tp.simbol) }
    val n = matriks.width
    val zona = SimbolKode.zona(tp.simbol)
    val modul = Math.max(1, Math.round(tp.kodeCm * pxPerCm / n))   // piksel bulat per modul agar tajam
    val totalPx = (n + 2 * zona) * modul
    val ukuran = with(LocalDensity.current) { totalPx.toDp() }
    Canvas(Modifier.size(ukuran)) {
        drawRect(Color.White)
        for (y in 0 until n) for (x in 0 until n) {
            if (matriks.get(x, y)) {
                drawRect(
                    Color.Black,
                    Offset(((x + zona) * modul).toFloat(), ((y + zona) * modul).toFloat()),
                    Size(modul.toFloat(), modul.toFloat())
                )
            }
        }
    }
}

@Composable
private fun TombolKode(label: String, tp: Tampilan, pxPerCm: Float, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(warnaTombol(label)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        KodeSimbol("KEY:$label", tp, pxPerCm)
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun DialogTitip(d: Dlg.Titip, tp: Tampilan, engine: Engine) {
    val pxPerCm = rememberPxPerCm(tp.kalibrasi)
    val density = LocalDensity.current
    val jarak = with(density) { (tp.jarakCm * pxPerCm).toDp() }
    Dialog(onDismissRequest = { engine.batalDialog() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(d.nama, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    d.penitip + if (d.baru) "" else " · saat ini ${d.saatIni}",
                    fontSize = 13.sp, color = Color.Gray
                )
                Spacer(Modifier.height(8.dp))
                // harga ditampilkan besar di atas isian jumlah, agar menjadi perhatian petugas
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HargaKotak("Harga titip", d.hargaTitip, Modifier.weight(1f))
                    HargaKotak("Harga jual", d.hargaJual, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                // mengetuk angka membuka keypad numerik
                Box(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFFEFE6D6), RoundedCornerShape(12.dp))
                        .then(if (d.mengetik) Modifier.border(3.dp, Color(0xFF1565C0), RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { engine.ketukAngka() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text("ketuk: keypad", Modifier.align(Alignment.CenterStart), fontSize = 11.sp, color = Color(0xFF8A7A5C))
                    Text(
                        if (d.mengetik) d.teks.ifEmpty { "0" } else d.jumlah.toString(),
                        Modifier.align(Alignment.CenterEnd), fontSize = 34.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (d.mengetik) {
                    KeypadAngka(onTombol = { engine.ketikPad(it) }, onBatal = { engine.ketukAngka() })
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(jarak)) {
                        for (baris in TOMBOL_JUMLAH.chunked(2)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(jarak)) {
                                for (k in baris) {
                                    TombolKode(k, tp, pxPerCm, Modifier.weight(1f)) { engine.tombolJumlah(k) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadAngka(onTombol: (String) -> Unit, onBatal: () -> Unit) {
    val baris = listOf(
        listOf("1", "2", "3"), listOf("4", "5", "6"),
        listOf("7", "8", "9"), listOf("HAPUS", "0", "ENTER")
    )
    for (r in baris) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in r) {
                Button(
                    onClick = { onTombol(k) },
                    modifier = Modifier.weight(1f).height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    when (k) {
                        "HAPUS" -> SimbolHapus()
                        "ENTER" -> SimbolEnter()
                        else -> Text(k, fontSize = 18.sp)
                    }
                }
            }
        }
    }
    TextButton(onBatal, Modifier.fillMaxWidth()) { Text("Batal") }
}

@Composable
private fun SimbolHapus() {
    val warna = LocalContentColor.current
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val sw = 3.dp.toPx()
        val y = h / 2
        drawLine(warna, Offset(w * 0.12f, y), Offset(w * 0.90f, y), sw, StrokeCap.Round)
        drawLine(warna, Offset(w * 0.12f, y), Offset(w * 0.40f, y - h * 0.26f), sw, StrokeCap.Round)
        drawLine(warna, Offset(w * 0.12f, y), Offset(w * 0.40f, y + h * 0.26f), sw, StrokeCap.Round)
    }
}

@Composable
private fun SimbolEnter() {
    val warna = LocalContentColor.current
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val sw = 3.dp.toPx()
        val xk = w * 0.85f
        val xl = w * 0.15f
        val y = h * 0.66f
        drawLine(warna, Offset(xk, h * 0.22f), Offset(xk, y), sw, StrokeCap.Round)
        drawLine(warna, Offset(xk, y), Offset(xl, y), sw, StrokeCap.Round)
        drawLine(warna, Offset(xl, y), Offset(xl + w * 0.24f, y - h * 0.22f), sw, StrokeCap.Round)
        drawLine(warna, Offset(xl, y), Offset(xl + w * 0.24f, y + h * 0.22f), sw, StrokeCap.Round)
    }
}

// ---------------- penutupan dan admin ----------------

@Composable
private fun ColumnScope.PanelSelesai(st: UiState, onBagikan: () -> Unit, onKirim: () -> Unit, onKosongkan: () -> Unit) {
    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Penutupan", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(st.selesaiInfo ?: "Menyusun laporan...")
        Spacer(Modifier.height(16.dp))
        Button(onBagikan, Modifier.fillMaxWidth()) { Text("Bagikan berkas laporan") }
        if (st.tujuanKirim.isNotBlank()) {
            OutlinedButton(onKirim, Modifier.fillMaxWidth()) { Text("Kirim ulang ke server") }
        }
        OutlinedButton(onKosongkan, Modifier.fillMaxWidth()) { Text("Sudah terkirim: kosongkan data dan tutup") }
        Spacer(Modifier.height(16.dp))
        if (st.rekap.isNotEmpty()) {
            Text("Uang penitip", fontWeight = FontWeight.Bold)
            for (r in st.rekap) {
                Row(Modifier.fillMaxWidth()) {
                    Text(r.penitip, Modifier.weight(1f))
                    Text(Fmt.rp(r.total))
                }
            }
        }
    }
}

@Composable
private fun DialogAdmin(engine: Engine, st: UiState, pemindai: String, barisMentah: String, onTutup: () -> Unit) {
    var uji by remember { mutableStateOf("") }
    var simbol by remember { mutableStateOf(st.tampilan.simbol) }
    var kodeCm by remember { mutableStateOf(st.tampilan.kodeCm.toString()) }
    var jarakCm by remember { mutableStateOf(st.tampilan.jarakCm.toString()) }
    var kalibrasi by remember { mutableStateOf(st.tampilan.kalibrasi.toString()) }
    AlertDialog(
        onDismissRequest = onTutup,
        title = { Text("Diagnostik dan pengaturan") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(pemindai, fontSize = 13.sp)
                Text("Baris mentah terakhir:", fontSize = 12.sp, color = Color.Gray)
                Text(barisMentah.ifEmpty { "(belum ada)" }, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                Text("Kode tombol di dialog titipan", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row {
                    TextButton({ simbol = "DM" }) { Text(if (simbol == "DM") "\u25CF Data Matrix" else "\u25CB Data Matrix") }
                    TextButton({ simbol = "QR" }) { Text(if (simbol == "QR") "\u25CF QR" else "\u25CB QR") }
                }
                OutlinedTextField(kodeCm, { kodeCm = it }, label = { Text("Ukuran kode (cm)") }, singleLine = true)
                OutlinedTextField(jarakCm, { jarakCm = it }, label = { Text("Jarak antartombol (cm)") }, singleLine = true)
                OutlinedTextField(kalibrasi, { kalibrasi = it }, label = { Text("Kalibrasi ukuran (1.0 = bawaan)") }, singleLine = true)
                TextButton({
                    engine.simpanTampilan(
                        Tampilan(
                            simbol, kodeCm.replace(',', '.').toFloatOrNull() ?: 1.4f,
                            jarakCm.replace(',', '.').toFloatOrNull() ?: 1f,
                            kalibrasi.replace(',', '.').toFloatOrNull() ?: 1f
                        )
                    )
                }) { Text("Simpan tampilan") }
                Spacer(Modifier.height(8.dp))
                Text("Tujuan kirim database", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text(
                    if (st.tujuanKirim.isBlank()) "Belum ada. Scan QR tujuan di sesi mana pun." else st.tujuanKirim,
                    fontSize = 13.sp
                )
                if (st.tujuanKirim.isNotBlank()) TextButton({ engine.hapusTujuan() }) { Text("Hapus tujuan kirim") }
                OutlinedTextField(uji, { uji = it }, label = { Text("Uji: ketik isi QR") }, singleLine = true)
                TextButton({ if (uji.isNotBlank()) { engine.terima(uji); uji = "" } }) { Text("Kirim sebagai scan") }
            }
        },
        confirmButton = { TextButton(onTutup) { Text("Tutup") } }
    )
}
