package id.jajanpasar.kasir.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity
data class Pengaturan(@PrimaryKey val kunci: String, val nilai: String)

@Entity
data class Sesi(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petugas: String,
    val waktuBuka: Long,
    val waktuTutup: Long? = null,
    val tahap: String
)

@Entity(indices = [Index(value = ["nama", "penitip"], unique = true)])
data class ProdukEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nama: String,
    val penitip: String,
    val hargaJual: Int,
    val kodeHarga: String,
    val hargaTitip: Int
)

@Entity(primaryKeys = ["sesiId", "produkId"])
data class StokSesi(
    val sesiId: Long,
    val produkId: Long,
    val jumlahTitip: Int,
    val stokRiil: Int? = null,
    /** true bila petugas mengubah angka opname dari bawaan (stok). */
    @ColumnInfo(defaultValue = "0") val opnameDiubah: Boolean = false
)

@Entity
data class Struk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sesiId: Long,
    val nomor: Int,
    val waktuBuat: Long,
    val waktuLunas: Long? = null,
    val status: String, // AKTIF, LUNAS, BATAL
    val total: Int = 0,
    val uangDiberikan: Int = 0,
    val kembalian: Int = 0,
    val memberNomor: String? = null,
    val memberNama: String? = null
)

@Entity(indices = [Index(value = ["strukId", "produkId"], unique = true)])
data class StrukItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strukId: Long,
    val produkId: Long,
    val jumlah: Int,
    val hargaJual: Int,
    val hargaTitip: Int
)

@Entity
data class AntreanKirim(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sesiId: Long,
    val jenis: String,
    val berkas: String,
    val status: String, // MENUNGGU, TERKIRIM
    val percobaan: Int = 0
)

data class TerjualRow(val produkId: Long, val jumlah: Int)

@Dao
abstract class KasirDao {
    @Query("SELECT nilai FROM Pengaturan WHERE kunci = :k")
    abstract suspend fun getPengaturan(k: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun setPengaturan(p: Pengaturan)

    @Query("SELECT * FROM Sesi WHERE waktuTutup IS NULL ORDER BY id DESC LIMIT 1")
    abstract suspend fun sesiAktif(): Sesi?

    @Insert abstract suspend fun insertSesi(s: Sesi): Long
    @Update abstract suspend fun updateSesi(s: Sesi)

    @Query("SELECT * FROM ProdukEntity WHERE nama = :n COLLATE NOCASE AND penitip = :p COLLATE NOCASE LIMIT 1")
    abstract suspend fun cariProduk(n: String, p: String): ProdukEntity?

    @Insert abstract suspend fun insertProduk(p: ProdukEntity): Long

    @Query("SELECT * FROM ProdukEntity ORDER BY id")
    abstract suspend fun semuaProduk(): List<ProdukEntity>

    @Query("DELETE FROM ProdukEntity WHERE id = :id")
    abstract suspend fun hapusProdukId(id: Long)

    @Query("SELECT * FROM StokSesi WHERE sesiId = :s")
    abstract suspend fun stokSesi(s: Long): List<StokSesi>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertStok(s: StokSesi)

    @Query(
        "SELECT si.produkId AS produkId, SUM(si.jumlah) AS jumlah FROM StrukItem si " +
            "JOIN Struk s ON s.id = si.strukId WHERE s.sesiId = :sesi AND s.status = 'LUNAS' " +
            "GROUP BY si.produkId"
    )
    abstract suspend fun terjual(sesi: Long): List<TerjualRow>

    @Query("SELECT COALESCE(MAX(nomor), 0) + 1 FROM Struk WHERE sesiId = :s")
    abstract suspend fun nomorBerikut(s: Long): Int

    @Insert abstract suspend fun insertStruk(s: Struk): Long
    @Update abstract suspend fun updateStruk(s: Struk)

    @Query("SELECT * FROM Struk WHERE id = :id")
    abstract suspend fun struk(id: Long): Struk?

    @Query("SELECT * FROM Struk WHERE sesiId = :s AND status = 'AKTIF' ORDER BY id DESC LIMIT 1")
    abstract suspend fun strukAktif(s: Long): Struk?

    @Query("SELECT * FROM Struk WHERE sesiId = :s ORDER BY id")
    abstract suspend fun semuaStruk(s: Long): List<Struk>

    @Query("DELETE FROM Struk WHERE id = :id")
    abstract suspend fun hapusStrukId(id: Long)

    @Query("DELETE FROM StrukItem WHERE strukId = :id")
    abstract suspend fun hapusItemStruk(id: Long)

    @Query("SELECT * FROM StrukItem WHERE strukId = :id ORDER BY id")
    abstract suspend fun itemStruk(id: Long): List<StrukItem>

    @Query("SELECT * FROM StrukItem WHERE strukId = :id AND produkId = :pid")
    abstract suspend fun item(id: Long, pid: Long): StrukItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertItem(i: StrukItem)

    @Query("DELETE FROM StrukItem WHERE strukId = :id AND produkId = :pid")
    abstract suspend fun hapusItem(id: Long, pid: Long)

    @Insert abstract suspend fun insertAntrean(a: AntreanKirim): Long

    @Query("UPDATE AntreanKirim SET status = :st WHERE id = :id")
    abstract suspend fun tandaiAntrean(id: Long, st: String)

    @Query("DELETE FROM AntreanKirim WHERE status = 'TERKIRIM'")
    abstract suspend fun hapusAntreanTerkirim()

    @Query("DELETE FROM StrukItem") abstract suspend fun hapusSemuaItem()
    @Query("DELETE FROM Struk") abstract suspend fun hapusSemuaStruk()
    @Query("DELETE FROM StokSesi") abstract suspend fun hapusSemuaStok()
    @Query("DELETE FROM ProdukEntity") abstract suspend fun hapusSemuaProduk()
    @Query("DELETE FROM Sesi") abstract suspend fun hapusSemuaSesi()

    /** Mengosongkan semua tabel kecuali pengaturan dan antrean_kirim. */
    @Transaction
    open suspend fun kosongkan() {
        hapusSemuaItem(); hapusSemuaStruk(); hapusSemuaStok(); hapusSemuaProduk(); hapusSemuaSesi()
    }
}

@Database(
    entities = [Pengaturan::class, Sesi::class, ProdukEntity::class, StokSesi::class,
        Struk::class, StrukItem::class, AntreanKirim::class],
    version = 3,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): KasirDao
}

/** Versi 2: member pada struk. */
val MIGRASI_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Struk ADD COLUMN memberNomor TEXT")
        db.execSQL("ALTER TABLE Struk ADD COLUMN memberNama TEXT")
    }
}

/** Versi 3: penanda angka opname diubah petugas. */
val MIGRASI_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE StokSesi ADD COLUMN opnameDiubah INTEGER NOT NULL DEFAULT 0")
    }
}
