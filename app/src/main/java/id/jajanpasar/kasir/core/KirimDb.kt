package id.jajanpasar.kasir.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class TujuanKirim(val webapp: String, val toko: String, val token: String)

/** Balasan skrip: {ok, error?, file?}. */
data class HasilKirim(val ok: Boolean, val error: String? = null, val file: String? = null)

/**
 * Mengirim database ke web app Google Apps Script.
 * POST {webapp}, isi {"token","toko","tanggal","data"}, Content-Type text/plain;charset=utf-8,
 * pengalihan (redirect) diikuti. Tanggal = hari ini di Asia/Jakarta (yyyy-MM-dd).
 */
object KirimDb {
    private val klien = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun tanggalJakarta(ms: Long = System.currentTimeMillis()): String {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Jakarta")
        return f.format(Date(ms))
    }

    fun badan(t: TujuanKirim, data: JSONObject, tanggal: String): String =
        JSONObject().put("token", t.token).put("toko", t.toko).put("tanggal", tanggal).put("data", data).toString()

    /** Mengurai balasan. Berhasil hanya bila ok bernilai true (atau teks "true"/"ok"). */
    fun urai(isi: String): HasilKirim {
        val j = try {
            JSONObject(isi.trim())
        } catch (e: Exception) {
            return HasilKirim(false, "balasan bukan JSON: " + isi.trim().take(80))
        }
        val v = j.opt("ok")
        val ok = v == true || (v is String && (v.equals("true", true) || v.equals("ok", true)))
        val galat = j.optString("error").ifEmpty { null }
        return HasilKirim(ok, if (ok) galat else (galat ?: "balasan tidak ok"), j.optString("file").ifEmpty { null })
    }

    suspend fun kirim(t: TujuanKirim, data: JSONObject): HasilKirim = withContext(Dispatchers.IO) {
        try {
            val body = badan(t, data, tanggalJakarta()).toRequestBody("text/plain;charset=utf-8".toMediaType())
            val req = Request.Builder().url(t.webapp).post(body).build()
            klien.newCall(req).execute().use { r ->
                val isi = r.body?.string().orEmpty()
                if (!r.isSuccessful) HasilKirim(false, "HTTP ${r.code}") else urai(isi)
            }
        } catch (e: Exception) {
            HasilKirim(false, e.message ?: e.javaClass.simpleName)
        }
    }
}
