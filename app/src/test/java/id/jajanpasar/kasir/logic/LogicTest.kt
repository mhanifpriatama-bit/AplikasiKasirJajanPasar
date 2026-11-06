package id.jajanpasar.kasir.logic

import org.junit.Assert.*
import org.junit.Test

class LogicTest {
    @Test fun contohDariRancangan() {
        assertEquals("CDFGHKLQRTV", KodeHarga.deret("Apem Manis", "Bu Joni").joinToString(""))
        assertEquals("CDVL", KodeHarga.kodeHarga("Apem Manis", "Bu Joni", 13400))
        assertEquals(13400, KodeHarga.harga("Apem Manis", "Bu Joni", "CDVL"))
    }

    @Test fun kasusTepi() {
        assertEquals("0,5", KodeHarga.formatHarga(500))
        assertEquals("6,75", KodeHarga.formatHarga(6750))
        assertEquals("15", KodeHarga.formatHarga(15000))
        for (h in listOf(500, 1000, 2500, 6750, 13400, 99990)) {
            val k = KodeHarga.kodeHarga("Cenil", "Bu Wati", h)
            assertEquals(h, KodeHarga.harga("Cenil", "Bu Wati", k))
        }
    }

    @Test fun deretSelaluSebelasUnik() {
        val d = KodeHarga.deret("The quick brown fox jumps", "over the lazy dog")
        assertEquals(11, d.size)
        assertEquals(11, d.toSet().size)
    }

    @Test fun parserQr() {
        assertEquals(Scan.Petugas("Budi"), LineParser.parse("PTG:Budi"))
        assertEquals(Scan.Petugas("Budi"), LineParser.parse("ptg:Budi"))
        assertEquals(Scan.Toko("Jajan Pasar Sentosa"), LineParser.parse("TOKO:Jajan Pasar Sentosa"))
        assertEquals(Scan.Perintah(Jenis.SIGMA), LineParser.parse("CMD:SIGMA"))
        assertEquals(Scan.Cash(50000), LineParser.parse("CASH:50000"))
        assertEquals(Scan.Member("A017", "Bu Ani"), LineParser.parse("MBR:A017,Bu Ani"))
        assertEquals(Scan.Member("A017", ""), LineParser.parse("mbr:A017"))
        assertTrue(LineParser.parse("MBR:") is Scan.Salah)
        for (k in listOf("+1", "-1", "+5", "-5", "+10", "OK")) assertEquals(Scan.Tombol(k), LineParser.parse("KEY:$k"))
        assertEquals(Scan.Tombol("OK"), LineParser.parse("key:ok"))
        assertTrue(LineParser.parse("KEY:5") is Scan.Salah)      // keypad angka sudah tidak dipakai
        assertTrue(LineParser.parse("KEY:ENTER") is Scan.Salah)
        assertTrue(LineParser.parse("KEY:+2") is Scan.Salah)
        val p = LineParser.parse("Apem Manis,Bu Joni,15000,CDVL") as Scan.Produk
        assertEquals(13400, p.hargaTitip)
        assertTrue(LineParser.parse("Apem Manis,Bu Joni,15000") is Scan.Salah)
        assertTrue(LineParser.parse("Apem Manis,Bu Joni,15000,CDV") is Scan.Salah)
        assertTrue(LineParser.parse("Apem Manis,Bu Joni,10000,CDVL") is Scan.Salah) // titip > jual
    }

    @Test fun penyanggaBaris() {
        val b = LineBuffer()
        assertTrue(b.feed("PTG:Bu".toByteArray(), 0).isEmpty())
        assertEquals(listOf("PTG:Budi"), b.feed("di\r".toByteArray(), 100))
        // sisa terpotong dibuang bila jeda lama
        assertTrue(b.feed("CMD:SI".toByteArray(), 1000).isEmpty())
        assertEquals(listOf("CMD:ESC"), b.feed("CMD:ESC\r\n".toByteArray(), 3000))
    }

    @Test fun simbolKodeTombol() {
        for (k in TOMBOL_JUMLAH) {
            val dm = SimbolKode.dataMatrix("KEY:$k")
            assertEquals(dm.width, dm.height)
            assertTrue("Data Matrix terlalu kecil/besar: ${dm.width}", dm.width in 14..18)
            val qr = SimbolKode.qr("KEY:$k")
            assertEquals(21, qr.width)
        }
    }

    @Test fun qrTujuanKirim() {
        val ok = """{"v":1,"webapp":"https://script.google.com/macros/s/XXX/exec","toko":"TokoA","token":"rahasia16+"}"""
        assertEquals(
            Scan.Konfigurasi("https://script.google.com/macros/s/XXX/exec", "TokoA", "rahasia16+"),
            LineParser.parse(ok)
        )
        assertTrue(LineParser.parse(ok.replace("\"v\":1", "\"v\":2")) is Scan.Salah)
        assertTrue(LineParser.parse(ok.replace("script.google.com", "contoh.com")) is Scan.Salah)
        assertTrue(LineParser.parse(ok.replace("/exec", "/dev")) is Scan.Salah)
        assertTrue(LineParser.parse(ok.replace("rahasia16+", "")) is Scan.Salah)
        assertTrue(LineParser.parse("{bukan json") is Scan.Salah)
    }
}
