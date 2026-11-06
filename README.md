# Kasir Jajan Pasar (Android, Kotlin)

Aplikasi kasir jajan pasar sesuai dokumen rancangan. Input utama dari pemindai QR
(STMicroelectronics Virtual COM Port, 9600 8N1) lewat USB OTG, dibaca langsung tanpa
aplikasi perantara serial-keyboard.

## Membangun
- Android Studio (Koala atau lebih baru), JDK 17, Gradle 8.7+ (AGP 8.5.2, Kotlin 2.0.20).
- Buka folder ini, tunggu sinkronisasi Gradle, lalu Run. Pustaka USB serial diambil dari JitPack
  (`com.github.mik3y:usb-serial-for-android:3.8.1`), jadi butuh internet saat sinkronisasi pertama.
- Folder ini tidak membawa `gradle-wrapper.jar`; Android Studio membuatnya, atau jalankan `gradle wrapper`.
- Uji logika murni (tanpa ponsel): `./gradlew test` (kode harga, parser QR, penyangga baris).

## Struktur
- `logic/` kode harga, parser QR, penyangga baris, format. Tanpa ketergantungan Android.
- `data/` Room: pengaturan, sesi, produk, stok_sesi, struk, struk_item, antrean_kirim.
- `core/Engine.kt` mesin status (semua scan dan ketukan diproses berurutan dari satu antrean).
- `core/Laporan.kt` PDF, CSV, JSON, salinan ke Unduhan/KasirJajanPasar, kirim HTTP.
- `usb/` pembacaan serial, bunyi dan getar.
- `ui/Ui.kt` layar Compose.

## Format QR
`PTG:<nama>`, `TOKO:<nama>`, `MBR:<nomor>,<nama>`, `KEY:+1`/`KEY:-1`/`KEY:+5`/`KEY:-5`/`KEY:+10`/`KEY:OK` (tombol dialog titipan, tampil di layar), `CMD:SIGMA|BAYAR|UNDO|ESC`, `CASH:<nominal>`,
produk: `produk,penitip,harga jual,kode harga titip` (boleh diberi tanda kutip bila ada koma).
Awalan dan kode harga tidak membedakan huruf besar/kecil.

## Menu tersembunyi
Tekan lama bilah atas: status pemindai, baris mentah terakhir, alamat server laporan,
dan kolom uji (ketik isi QR lalu "Kirim sebagai scan").

## Pengiriman laporan
Lihat bagian "Kirim database online". Tiga berkas dibuat saat penutupan: database JSON, HTML rekap, dan PDF keuangan;
berkas disalin ke Unduhan/KasirJajanPasar (Android 10 ke atas) dan folder aplikasi.

## Keputusan yang saya ambil di luar dokumen
- QR produk yang kode harganya tidak cocok (huruf di luar deret, atau terpotong) atau harga titip
  melebihi harga jual ditolak dengan bunyi gagal dan pesan.
- Stok berkurang saat struk LUNAS. Struk BATAL tidak dihitung, sehingga stok kembali sendiri.
- Nominal yang sama dengan total dianggap lunas. `CMD:BAYAR` tanpa nominal = uang pas.
- Scan selama dialog terbuka ditolak (selesaikan dialog dulu).
- Produk baru: bila jumlah titipan 0 atau dialog dibatalkan, produk tidak dicatat.
- Opname: bila stok riil tidak diisi, dianggap sama dengan stok aplikasi (minimal 0).

## Pembaruan (versi 2 database)
- Struk lunas: scan `CMD:BAYAR` sekali lagi (diabaikan 1 detik pertama), atau scan produk berikutnya, untuk menutup.
- `CMD:ESC` pada struk lunas membuka "Transaksi Dibatalkan?": scan ESC lagi = batalkan (stok kembali), scan UNDO = tidak jadi. UNDO langsung pada struk lunas ditolak.
- Layar: indikator pemindai kecil (hijau = terhubung, abu-abu = tidak), ikon produk di kiri dengan stok sebagai angka pangkat, tombol dialog angka berupa simbol enter dan backspace.
- Member: scan `MBR:<nomor>,<nama>` saat struk tampil. Nama tampil di struk dan tersimpan di data transaksi
  (laporan PDF dan JSON). Pendaftaran, poin, dan diskon dilakukan di luar aplikasi.
- Database naik ke versi 2 (kolom member pada struk) dengan migrasi otomatis; data lama aman saat pasang ulang.

## Ikon aplikasi
Ikon adaptif (latar gradien + simbol mesin kasir QR, dengan versi monokrom untuk ikon bertema) dan ikon lama untuk Android 7.x, di `res/mipmap-*`. Berkas untuk Play Store: `logo_aplikasi_512.png`.

## Pembaruan: tabel, keypad QR, resume, petugas
- Daftar produk berupa tabel (produk, stok). Ketuk baris untuk koreksi titipan.
- Dialog jumlah titipan kini memakai enam tombol kode (lihat bagian "Dialog titipan enam tombol"); keypad QR angka tidak dipakai lagi.
- Sesi punya warna berbeda: operasional coklat, opname biru, resume hijau.
- Opname: tabel produk, stok, opname, tombol - dan +. Opname awal sama dengan stok (0 bila stok negatif).
- Resume: tabel produk, penitip, sisa, laku, Rp terima, urut abjad penitip, dengan jumlah per penitip dan total.
- Penutupan kasir, penutupan opname, dan penutupan resume harus dengan QR petugas yang sama dengan pembukaan.
  Petugas berbeda ditolak (bunyi gagal dan pesan).

## Hasil opname di laporan dan database
- PDF keuangan memuat bagian **Hasil opname**: jumlah produk diperiksa, produk dengan selisih, unit kurang dan lebih,
  nilai selisih (harga titip), serta tabel produk yang selisihnya tidak nol (selisih = stok aplikasi - opname).
- JSON memuat `ringkasan` (omzet, harga pokok, laba kotor, ringkasan opname) dan, per produk, `opname`,
  `selisih`, dan `selisih_nilai`.
- Saat opname ditutup, angka opname semua produk (termasuk yang tidak diubah) dicatat di database aplikasi.

## Perubahan minor
- Dialog jumlah titipan menampilkan **harga titip** dan **harga jual** dengan huruf besar di atas isian jumlah.
- Opname dan resume punya tombol **Konfirmasi** di bawah layar sebagai pengganti scan `PTG` (scan tetap berfungsi
  dan tetap memeriksa petugas; tombol tidak memeriksa petugas).
- Resume: satu blok per penitip (Sisa, Laku, Bayar (Rp), lalu Total), urut abjad penitip.
- PDF keuangan: di bagian paling bawah ada dua baris, harga pokok (uang untuk penitip) berdasarkan transaksi dan
  setelah opname. JSON `ringkasan` memuat `harga_pokok_transaksi` dan `harga_pokok_setelah_opname`.

## Laba kotor resmi dan selisih opname
- **Laba kotor resmi** = omzet - harga pokok berdasarkan transaksi (yang diterima manajemen).
- Penitip tetap dibayar dari titipan dikurangi opname. **Selisih bersih (barang kurang) ditanggung kasir dan kru**;
  kelebihan bersih ditampilkan terpisah dan tidak mengurangi tanggungan.
- PDF dan JSON memuat laba kotor resmi, laba setelah opname (informasi), selisih ditanggung, kelebihan bersih,
  dan jumlah produk yang angka opname-nya diubah petugas.
- Database naik ke **versi 3** (kolom `opnameDiubah` pada `StokSesi`) dengan migrasi otomatis.

## Status paket ini
- Kode **belum pernah dikompilasi di lingkungan penyusunnya**; bagian logika murni (kode harga, parser QR, penyangga baris)
  punya uji otomatis di `app/src/test` (`./gradlew test`).
- Sudah ada: titip, jual, struk, member, opname, resume, tiga laporan, ikon, migrasi database sampai versi 3.
- Belum teruji: pengiriman online ke skrip Google sungguhan, dan pembacaan Data Matrix oleh mesin pemindai.

## Dialog titipan enam tombol
- Di bawah harga titip dan harga jual (huruf besar) dan kotak angka, ada enam tombol: +1, +5, -1, -5, +10, OK.
  Tiap tombol bisa diketuk, dan berisi kode yang bisa dipindai (`KEY:+1`, `KEY:+5`, `KEY:-1`, `KEY:-5`, `KEY:+10`, `KEY:OK`).
- Produk baru mulai dari 0 (harus lebih dari 0 saat OK). Koreksi titipan mulai dari jumlah saat ini.
- Mengetuk kotak angka mengganti tombol dengan keypad numerik: angka mengganti jumlah, ENTER langsung menyimpan,
  Batal kembali ke enam tombol. Saat keypad terbuka, scan tombol ditolak; `CMD:ESC` membatalkan seluruh dialog.
- Kode digambar dengan pustaka ZXing. Bawaan **Data Matrix** (minimal 14x14 modul), alternatif **QR** (21x21).
  **Mesin pemindai harus bisa membaca Data Matrix**; bila tidak terbaca sama sekali, pilih QR di menu tersembunyi.
- Menu tersembunyi (tekan lama bilah atas) memuat: simbol, ukuran kode (bawaan 1,4 cm), jarak antartombol (bawaan 1 cm),
  dan kalibrasi ukuran (1.0 = ukuran fisik menurut ponsel; ubah bila ukuran terukur di layar tidak sesuai).

## Kirim database online
- **QR tujuan** (dipindai di sesi mana pun, juga saat struk tampil; tidak berlaku bila dialog sedang terbuka):
  `{"v":1,"webapp":"https://script.google.com/macros/s/XXX/exec","toko":"TokoA","token":"rahasia16+"}`.
  Hanya alamat `https://script.google.com/.../exec` yang diterima. Tujuan disimpan di aplikasi (tidak ikut dikosongkan)
  dan ditampilkan tanpa token di menu tersembunyi, yang juga punya tombol "Hapus tujuan kirim".
- **Kirim** (OkHttp): POST ke `webapp`, isi `{"token","toko","tanggal","data"}` dengan `data` berupa objek database JSON,
  `Content-Type: text/plain;charset=utf-8`, pengalihan diikuti. `tanggal` = hari ini di Asia/Jakarta (yyyy-MM-dd).
- **Balasan** `{ok, error?, file?}`. Berhasil hanya bila `ok` benar: data aplikasi dikosongkan, notifikasi "Database terkirim",
  aplikasi ditutup.
- **Bila tidak ok** (atau gagal jaringan): notifikasi sistem berisi alasannya, data tetap tersimpan, dan WhatsApp terbuka
  dengan berkas JSON, PDF, dan HTML untuk dikirim manual. Setelah itu tekan "Sudah terkirim: kosongkan data dan tutup",
  atau "Kirim ulang ke server".
- Tanpa tujuan kirim, penutupan memakai menu bagikan biasa.
- Android 13 ke atas meminta izin notifikasi saat aplikasi dibuka; bila ditolak, pesan tetap tampil di layar.
