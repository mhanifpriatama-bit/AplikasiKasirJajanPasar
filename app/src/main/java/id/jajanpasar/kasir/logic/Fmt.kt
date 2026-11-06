package id.jajanpasar.kasir.logic

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    fun rp(n: Int): String {
        val s = Math.abs(n).toString()
        val sb = StringBuilder()
        for ((i, c) in s.reversed().withIndex()) {
            if (i > 0 && i % 3 == 0) sb.append('.')
            sb.append(c)
        }
        return (if (n < 0) "-" else "") + "Rp " + sb.reverse().toString()
    }

    fun tgl(ms: Long): String = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.US).format(Date(ms))
    fun jam(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date(ms))
    fun berkas(ms: Long): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(ms))
}
