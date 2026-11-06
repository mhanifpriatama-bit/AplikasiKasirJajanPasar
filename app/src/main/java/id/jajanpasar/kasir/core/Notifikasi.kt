package id.jajanpasar.kasir.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import id.jajanpasar.kasir.MainActivity

object Notifikasi {
    private const val SALURAN = "laporan"

    /** Menampilkan notifikasi sistem. Bila izin notifikasi ditolak, diam saja (pesan tetap tampil di layar). */
    fun tampilkan(ctx: Context, judul: String, isi: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 26) {
            val sistem = ctx.getSystemService(NotificationManager::class.java)
            sistem.createNotificationChannel(
                NotificationChannel(SALURAN, "Laporan kasir", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val buka = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, SALURAN)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(judul)
            .setContentText(isi)
            .setStyle(NotificationCompat.BigTextStyle().bigText(isi))
            .setContentIntent(buka)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            nm.notify(System.currentTimeMillis().toInt(), n)
        } catch (e: SecurityException) {
            // izin belum diberikan
        }
    }
}
