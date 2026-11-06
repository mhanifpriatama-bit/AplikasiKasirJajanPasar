package id.jajanpasar.kasir.logic

import com.google.zxing.BarcodeFormat
import com.google.zxing.Dimension
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.datamatrix.encoder.SymbolShapeHint
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Membuat matriks modul (tanpa zona tenang) untuk kode yang ditampilkan di layar. */
object SimbolKode {
    /** Data Matrix persegi, minimal 14x14 modul. */
    fun dataMatrix(isi: String): BitMatrix =
        DataMatrixWriter().encode(
            isi, BarcodeFormat.DATA_MATRIX, 0, 0,
            mapOf<EncodeHintType, Any>(
                EncodeHintType.DATA_MATRIX_SHAPE to SymbolShapeHint.FORCE_SQUARE,
                EncodeHintType.MIN_SIZE to Dimension(14, 14)
            )
        )

    /** QR dengan koreksi galat Q (21x21 modul untuk isi pendek). */
    fun qr(isi: String): BitMatrix =
        QRCodeWriter().encode(
            isi, BarcodeFormat.QR_CODE, 0, 0,
            mapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q,
                EncodeHintType.MARGIN to 0
            )
        )

    fun matriks(isi: String, simbol: String): BitMatrix = if (simbol == "QR") qr(isi) else dataMatrix(isi)

    /** Lebar zona tenang dalam modul. */
    fun zona(simbol: String): Int = if (simbol == "QR") 3 else 2
}
