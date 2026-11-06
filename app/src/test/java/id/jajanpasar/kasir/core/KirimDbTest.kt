package id.jajanpasar.kasir.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KirimDbTest {
    @Test fun badanSesuaiSkema() {
        val data = JSONObject().put("petugas", "Budi")
        val b = JSONObject(KirimDb.badan(TujuanKirim("https://script.google.com/macros/s/X/exec", "TokoA", "rahasia16+"), data, "2026-11-07"))
        assertEquals("rahasia16+", b.getString("token"))
        assertEquals("TokoA", b.getString("toko"))
        assertEquals("2026-11-07", b.getString("tanggal"))
        assertEquals("Budi", b.getJSONObject("data").getString("petugas"))
    }

    @Test fun tanggalMemakaiWaktuJakarta() {
        // 2026-11-06 20:00 UTC = 2026-11-07 03:00 WIB
        val ms = java.time.Instant.parse("2026-11-06T20:00:00Z").toEpochMilli()
        assertEquals("2026-11-07", KirimDb.tanggalJakarta(ms))
    }

    @Test fun uraiBalasan() {
        val ok = KirimDb.urai("""{"ok":true,"file":"TokoA_2026-11-07.json"}""")
        assertTrue(ok.ok); assertEquals("TokoA_2026-11-07.json", ok.file)
        val gagal = KirimDb.urai("""{"ok":false,"error":"token salah"}""")
        assertFalse(gagal.ok); assertEquals("token salah", gagal.error)
        assertFalse(KirimDb.urai("""{"ok":false}""").ok)
        assertFalse(KirimDb.urai("<html>masuk dulu</html>").ok)
        assertFalse(KirimDb.urai("").ok)
    }
}
