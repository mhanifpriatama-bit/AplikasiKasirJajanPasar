package id.jajanpasar.kasir.usb

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/** Bunyi dan getar dari aplikasi sendiri saat baris diterima (bukan bergantung pada bunyi mesin). */
class Umpan(ctx: Context) {
    private val nada = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    private val getar = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator

    fun ok() { nada.startTone(ToneGenerator.TONE_PROP_BEEP, 80); bergetar(40) }
    fun gagal() { nada.startTone(ToneGenerator.TONE_PROP_NACK, 250); bergetar(200) }
    fun lepas() { nada.release() }

    private fun bergetar(ms: Long) {
        if (Build.VERSION.SDK_INT >= 26) {
            getar.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") getar.vibrate(ms)
        }
    }
}
