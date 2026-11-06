package id.jajanpasar.kasir.logic

import java.io.ByteArrayOutputStream

/**
 * Menyusun potongan byte serial menjadi baris utuh (diakhiri CR atau LF).
 * Sisa tanpa terminator dibuang bila jeda > [tenggatMs], agar potongan lama
 * tidak menempel pada scan berikutnya.
 */
class LineBuffer(private val tenggatMs: Long = 500) {
    private val bytes = ByteArrayOutputStream()
    private var terakhir = 0L

    fun feed(data: ByteArray, now: Long): List<String> {
        if (bytes.size() > 0 && now - terakhir > tenggatMs) bytes.reset()
        terakhir = now
        val hasil = ArrayList<String>()
        for (b in data) {
            if (b == 13.toByte() || b == 10.toByte()) {
                if (bytes.size() > 0) {
                    hasil.add(bytes.toString(Charsets.UTF_8.name()).trim())
                    bytes.reset()
                }
            } else {
                bytes.write(b.toInt())
                if (bytes.size() > 2048) bytes.reset()
            }
        }
        return hasil
    }
}
