package id.jajanpasar.kasir

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import id.jajanpasar.kasir.core.Efek
import id.jajanpasar.kasir.ui.KasirApp
import id.jajanpasar.kasir.usb.Pemindai
import id.jajanpasar.kasir.usb.Umpan
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val vm: KasirViewModel by viewModels()
    private lateinit var pemindai: Pemindai
    private lateinit var umpan: Umpan
    private val izinNotifikasi = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var menungguBagikan = false
    private var sudahPause = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        umpan = Umpan(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) izinNotifikasi.launch(Manifest.permission.POST_NOTIFICATIONS)
        pemindai = Pemindai(applicationContext) { baris -> vm.engine.terima(baris) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.engine.efek.collect { tangani(it) }
            }
        }

        setContent {
            val status by pemindai.status.collectAsState()
            val mentah by pemindai.barisTerakhir.collectAsState()
            val tersambung by pemindai.terhubung.collectAsState()
            KasirApp(vm.engine, status, mentah, tersambung)
        }
    }

    override fun onStart() {
        super.onStart()
        pemindai.mulai()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) pemindai.sambungkan()
    }

    override fun onPause() {
        super.onPause()
        if (menungguBagikan) sudahPause = true
    }

    override fun onResume() {
        super.onResume()
        // Kembali dari menu bagikan: aplikasi ditutup (data sudah dikosongkan setelah JSON terkirim).
        if (menungguBagikan && sudahPause) finishAndRemoveTask()
    }

    override fun onDestroy() {
        pemindai.berhenti()
        umpan.lepas()
        super.onDestroy()
    }

    private fun tangani(e: Efek) {
        when (e) {
            Efek.Ok -> umpan.ok()
            Efek.Gagal -> umpan.gagal()
            is Efek.Bagikan -> bagikan(e.berkas, e.tutupSetelah, e.viaWa)
            Efek.TutupAplikasi -> finishAndRemoveTask()
        }
    }

    private fun bagikan(berkas: List<File>, tutup: Boolean, viaWa: Boolean) {
        if (berkas.isEmpty()) return
        val uris = ArrayList(berkas.map { FileProvider.getUriForFile(this, "$packageName.fileprovider", it) })
        val clip = ClipData.newRawUri("laporan", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val i = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        menungguBagikan = tutup
        sudahPause = false
        if (viaWa) {
            // kirim manual lewat WhatsApp; bila tidak terpasang, tampilkan pilihan aplikasi biasa
            for (paket in listOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    startActivity(Intent(i).setPackage(paket))
                    return
                } catch (e: ActivityNotFoundException) {
                    // coba paket berikutnya
                }
            }
        }
        startActivity(Intent.createChooser(i, "Kirim laporan"))
    }
}
