package id.jajanpasar.kasir.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import id.jajanpasar.kasir.logic.LineBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Membaca pemindai QR (STMicroelectronics Virtual COM Port, CDC/ACM, 9600 8N1)
 * langsung lewat USB OTG. Data disusun menjadi baris oleh [LineBuffer].
 */
class Pemindai(
    private val ctx: Context,
    private val onBaris: (String) -> Unit
) : SerialInputOutputManager.Listener {

    private val _status = MutableStateFlow("Pemindai: belum terhubung")
    val status: StateFlow<String> = _status
    private val _terhubung = MutableStateFlow(false)
    val terhubung: StateFlow<Boolean> = _terhubung
    private val _barisTerakhir = MutableStateFlow("")
    val barisTerakhir: StateFlow<String> = _barisTerakhir

    private val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
    private val buffer = LineBuffer()
    private val aksiIzin = "id.jajanpasar.kasir.USB_IZIN"

    @Volatile private var port: UsbSerialPort? = null
    private var io: SerialInputOutputManager? = null
    private var terdaftar = false

    private val penerima = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                aksiIzin, UsbManager.ACTION_USB_DEVICE_ATTACHED -> sambungkan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> putus("Pemindai: terputus")
            }
        }
    }

    fun mulai() {
        if (!terdaftar) {
            val f = IntentFilter().apply {
                addAction(aksiIzin)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(ctx, penerima, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            terdaftar = true
        }
        sambungkan()
    }

    fun berhenti() {
        if (terdaftar) {
            runCatching { ctx.unregisterReceiver(penerima) }
            terdaftar = false
        }
        putus("Pemindai: berhenti")
    }

    @Synchronized
    fun sambungkan() {
        if (port != null) return
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(usb).firstOrNull()
        if (driver == null) {
            _status.value = "Pemindai: tidak ada perangkat USB serial"
            return
        }
        val dev = driver.device
        if (!usb.hasPermission(dev)) {
            _status.value = "Pemindai: menunggu izin USB"
            val pi = PendingIntent.getBroadcast(
                ctx, 0, Intent(aksiIzin).setPackage(ctx.packageName), PendingIntent.FLAG_MUTABLE
            )
            usb.requestPermission(dev, pi)
            return
        }
        val koneksi = usb.openDevice(dev)
        if (koneksi == null) {
            _status.value = "Pemindai: gagal membuka perangkat"
            return
        }
        val p = driver.ports[0]
        try {
            p.open(koneksi)
            p.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            try { p.setDTR(true); p.setRTS(true) } catch (_: Exception) {}
            port = p
            _terhubung.value = true
            io = SerialInputOutputManager(p, this).also { it.start() }
            _status.value = "Pemindai: terhubung (%04x:%04x)".format(dev.vendorId, dev.productId)
        } catch (e: Exception) {
            _status.value = "Pemindai: ${e.message}"
            putus(_status.value)
        }
    }

    @Synchronized
    private fun putus(pesan: String) {
        runCatching { io?.stop() }
        io = null
        runCatching { port?.close() }
        port = null
        _terhubung.value = false
        _status.value = pesan
    }

    override fun onNewData(data: ByteArray) {
        for (baris in buffer.feed(data, SystemClock.elapsedRealtime())) {
            _barisTerakhir.value = baris
            onBaris(baris)
        }
    }

    override fun onRunError(e: Exception) {
        putus("Pemindai: terputus (${e.message})")
    }
}
