package id.jajanpasar.kasir

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import id.jajanpasar.kasir.core.Engine
import id.jajanpasar.kasir.data.AppDb
import id.jajanpasar.kasir.data.MIGRASI_1_2
import id.jajanpasar.kasir.data.MIGRASI_2_3

class KasirViewModel(app: Application) : AndroidViewModel(app) {
    private val db = Room.databaseBuilder(app, AppDb::class.java, "kasir.db")
        .addMigrations(MIGRASI_1_2, MIGRASI_2_3)
        .build()
    val engine = Engine(app, db.dao(), viewModelScope)
}
