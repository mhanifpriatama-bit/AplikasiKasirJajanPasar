# 📘 Proyek: Kasir Upload ke Google Drive

Dokumentasi lengkap + kode sumber. Versi final, termasuk dukungan `?download=FILE_ID`.

---

## Daftar Isi

1. [Ringkasan](#1-ringkasan)
2. [Peran dan Tanggung Jawab](#2-peran-dan-tanggung-jawab)
3. [Arsitektur](#3-arsitektur)
4. [Struktur File](#4-struktur-file)
5. [Konfigurasi](#5-konfigurasi)
6. [Endpoint API](#6-endpoint-api)
7. [Alur Kerja](#7-alur-kerja)
8. [Keamanan](#8-keamanan)
9. [Deployment & Pemeliharaan](#9-deployment--pemeliharaan)
10. [Troubleshooting](#10-troubleshooting)
11. [Pengujian Cepat (curl)](#11-pengujian-cepat-curl)
12. [Kirim & Download Lewat Alamat Browser (Satu Baris)](#12-kirim--download-lewat-alamat-browser-satu-baris)
13. [Kode Sumber Lengkap](#13-kode-sumber-lengkap)
14. [Roadmap](#14-roadmap)

---

## 1. Ringkasan

Aplikasi kasir Android mengirim data transaksi harian (JSON) secara otomatis ke Google Drive melalui Google Apps Script (GAS) sebagai perantara. Data disimpan dalam folder per toko, dan dapat dilihat oleh Admin melalui halaman khusus atau langsung di Drive.

**Pengganti alur lama:** kirim file JSON manual lewat WhatsApp.

---

## 2. Peran dan Tanggung Jawab

| Peran | Siapa | Tanggung Jawab | Akses |
|---|---|---|---|
| **Superadmin** | Pemilik Drive + Developer | Menulis kode GAS, deploy Web App, memegang `FOLDER_ID`, menyiapkan Script Properties | Drive (pemilik), Project GAS (Editor) |
| **Admin** | Pengelola token | Mengganti `SECRET_TOKEN` lewat halaman admin | URL Web App `?page=admin` + password admin |
| **Aplikasi Kasir** | Klien Android | Mengirim JSON harian ke endpoint GAS | `SECRET_TOKEN` (via kode aplikasi) |

---

## 3. Arsitektur

```
┌─────────────────┐        HTTP POST (JSON + token)        ┌──────────────────┐
│  Aplikasi Kasir │ ─────────────────────────────────────► │  GAS Web App     │
│  (Android)      │                                        │  doPost(e)       │
└─────────────────┘                                        └────────┬─────────┘
                                                                    │
                                                                    │ DriveApp
                                                                    ▼
                                                           ┌──────────────────┐
                                                           │ Google Drive     │
                                                           │ FOLDER_ID/       │
                                                           │   TokoA/         │
                                                           │     tgl_jam.json │
                                                           └──────────────────┘
                                                                    ▲
┌─────────────────┐        HTTP GET (token)                        │
│  Admin (HTML)   │ ──────────────────────────────────────────────┘
│  daftar.html    │        (opsional, lihat daftar file)
└─────────────────┘

┌─────────────────┐        google.script.run
│  Admin.html     │ ─────────────────────────────► simpanTokenBaru()
│  (di dalam GAS) │                                 → Script Properties
└─────────────────┘
```

---

## 4. Struktur File

### Di project GAS
```
Project GAS
├── Config.gs       → konfigurasi terpusat (KASIR_CONFIG)
├── Kode.gs         → doPost, doGet, helper, simpanTokenBaru, cekPasswordAdmin
└── Admin.html      → halaman admin (ganti token)
```

### Di luar GAS (file lokal)
```
upload.html         → form upload manual (uji coba / fallback)
daftar.html         → lihat daftar file di Drive
```

### Di Google Drive
```
FOLDER_ID/
├── TokoA/
│   ├── 2026-11-05_143022.json
│   └── 2026-11-06_081200.json
├── TokoB/
│   └── 2026-11-05_091500.json
└── (file lain di root, opsional)
```

---

## 5. Konfigurasi

### 5.1 `Config.gs` — Konstanta

| Key | Contoh Nilai | Keterangan |
|---|---|---|
| `SECRET_TOKEN` | `'abc123...'` | Token verifikasi request. **Fallback** jika Script Properties kosong. |
| `FOLDER_ID` | `'1AbCd...'` | ID folder root di Drive. |
| `TIMEZONE` | `'Asia/Jakarta'` | Zona waktu untuk nama file `HHmmss`. |
| `POLA_NAMA_FILE` | `'{tanggal}_{jam}.json'` | Pola nama file di folder toko. Placeholder: `{tanggal}`, `{jam}`, `{toko}`. |
| `MAX_SIZE` | `5 * 1024 * 1024` | Batas ukuran body request (byte). |
| `TOKO_DIIZINKAN` | `[]` | Daftar toko yang boleh kirim. Kosong = semua boleh. |
| `VERSI_API` | `1` | Versi format response. |

### 5.2 Script Properties (Project Settings → Script Properties)

| Key | Keterangan | Diubah oleh |
|---|---|---|
| `SECRET_TOKEN` | Token aktif. **Menimpa** nilai di `Config.gs`. | Admin (via `Admin.html`) |
| `ADMIN_PASSWORD` | Password untuk membuka halaman admin. | Superadmin (sekali saat setup) |

> **Urutan prioritas token:** Script Properties → `Config.gs`. Kalau Script Properties kosong, pakai nilai di `Config.gs`.

### 5.3 Parameter di HTML

| File | Konstanta | Nilai |
|---|---|---|
| `upload.html` | `ENDPOINT` | URL Web App GAS |
| `daftar.html` | `ENDPOINT` | URL Web App GAS |

---

## 6. Endpoint API

### 6.1 `POST` — Kirim data (dipakai aplikasi kasir)

**URL:** `https://script.google.com/macros/s/{ID}/exec`

**Body:**
```json
{
  "token": "SECRET_TOKEN",
  "toko": "TokoA",
  "tanggal": "2026-11-05",
  "data": { "transaksi": [ ... ] }
}
```

**Response sukses:**
```json
{
  "ok": true,
  "versi": 1,
  "folder": "TokoA",
  "file": "2026-11-05_143022.json",
  "fileId": "...",
  "url": "https://drive.google.com/file/d/..."
}
```

**Response gagal:**
```json
{ "ok": false, "error": "Token tidak valid" }
```

**Aturan validasi:**
- `token` harus cocok.
- `toko`, `tanggal`, `data` wajib ada.
- `tanggal` format `YYYY-MM-DD`.
- `toko` harus ada di `TOKO_DIIZINKAN` (kalau daftar tidak kosong).
- Ukuran body ≤ `MAX_SIZE`.

### 6.2 `GET` — Daftar file (dipakai `daftar.html`)

**URL:** `.../exec?token=SECRET_TOKEN[&toko=TokoA]`

**Response:**
```json
{
  "ok": true,
  "versi": 1,
  "folder": "TokoA",
  "folders": [],
  "files": [
    {
      "name": "2026-11-05_143022.json",
      "id": "...",
      "size": 12345,
      "updated": "2026-11-05T07:30:22.000Z",
      "url": "https://drive.google.com/file/d/..."
    }
  ]
}
```

### 6.3 `GET ?page=admin` — Halaman admin

**URL:** `.../exec?page=admin`

Menampilkan `Admin.html`. Admin memasukkan password + token baru, disimpan ke Script Properties via `google.script.run`.

### 6.4 `GET ?download=FILE_ID` — Unduh isi file

**URL:** `.../exec?token=SECRET_TOKEN&download=FILE_ID`

Mengembalikan isi file JSON sebagai response (browser mengunduh). File tetap privat di Drive; GAS yang membacakannya sebagai Superadmin.

---

## 7. Alur Kerja

### 7.1 Setup Awal (Superadmin, sekali)

1. Buat folder root di Drive → salin `FOLDER_ID` dari URL.
2. Buat project GAS → tambahkan `Config.gs`, `Kode.gs`, `Admin.html`.
3. Isi `FOLDER_ID` di `Config.gs`.
4. Buka **Project Settings → Script Properties**:
   - `SECRET_TOKEN` = token awal (string acak ≥32 karakter)
   - `ADMIN_PASSWORD` = password admin
5. **Deploy → New deployment → Web app**:
   - Execute as: **Me**
   - Who has access: **Anyone**
6. Salin URL Web App → tempel ke `ENDPOINT` di `upload.html` dan `daftar.html`.

### 7.2 Pengiriman Harian (Aplikasi Kasir)

1. `WorkManager` memicu pengiriman (misal tiap tutup toko).
2. Aplikasi POST JSON ke `ENDPOINT` dengan token.
3. GAS memvalidasi → simpan ke `FOLDER_ID/{toko}/{tanggal}_{jam}.json`.
4. Response sukses/gagal ditangani aplikasi (retry jika perlu).

### 7.3 Ganti Token (Admin)

1. Buka `.../exec?page=admin`.
2. Masukkan password admin → **Buka**.
3. Masukkan token baru → klik **Simpan**.
4. Token tersimpan di Script Properties. **Tidak perlu deploy ulang.**
5. Aplikasi kasir perlu diperbarui tokennya agar bisa kirim lagi.

### 7.4 Melihat Data (Admin)

- **Cara 1:** Langsung buka folder `FOLDER_ID` di Google Drive.
- **Cara 2:** Buka `daftar.html`, masukkan token, lihat daftar file.
- **Cara 3:** Akses `URL_WEB_APP?token=TOKEN&toko=TokoA` di browser.

---

## 8. Keamanan

| Aspek | Implementasi |
|---|---|
| Autentikasi request | `SECRET_TOKEN` di body/query |
| Token di server | Script Properties (bukan hardcode) |
| Password admin | `ADMIN_PASSWORD` di Script Properties |
| Akses Drive | Script berjalan sebagai Superadmin ("Execute as: Me") |
| Akses Admin | Hanya URL `?page=admin` + password; tidak perlu akses GAS/Drive |
| CORS | GAS tidak menangani preflight; gunakan `Content-Type: text/plain` |

**Risiko yang perlu disadari:**
- Token terlihat di URL saat pakai `daftar.html` atau akses browser langsung (query string). Wajar untuk penggunaan internal.
- Kalau `SECRET_TOKEN` bocor, siapa pun bisa mengirim data. Mitigasi: ganti via `Admin.html`.
- `Who has access: Anyone` membuat endpoint publik. Token adalah satu-satunya gerbang.
- `?download=FILE_ID` memungkinkan siapa pun yang punya token mengunduh file apa pun yang ID-nya diketahui. Jaga token tetap rahasia.

---

## 9. Deployment & Pemeliharaan

### Setiap kali mengubah kode GAS:
1. **Deploy → Manage deployments → Edit (pensil)**
2. **Version: New version**
3. **Deploy**
4. Uji ulang dengan `upload.html` atau `curl`.

### Tanpa deploy ulang:
- Ganti `SECRET_TOKEN` lewat `Admin.html` (Script Properties).
- Ganti `ADMIN_PASSWORD` lewat Script Properties (Superadmin).

### Dengan deploy ulang:
- Ubah `Config.gs` (kecuali token yang sudah di Properties).
- Ubah logika `Kode.gs`.
- Tambah/ubah `Admin.html`.
- Ubah `doGet`/`doPost`.

---

## 10. Troubleshooting

| Gejala | Penyebab | Solusi |
|---|---|---|
| `ID file atau folder tidak valid` | `FOLDER_ID` salah/placeholder | Salin ulang ID dari URL Drive |
| `Token tidak valid` | Token di klien ≠ di Properties/Config | Samakan; ganti via `Admin.html` |
| `Script function not found: doGet` | Belum deploy ulang setelah tambah `doGet` | Deploy versi baru |
| `Failed to fetch` di HTML | Deploy belum "Anyone", atau URL berubah | Cek deployment |
| File tidak muncul di Drive | `FOLDER_ID` menunjuk folder lain | Cek ID folder |
| `File terlalu besar` | Body > `MAX_SIZE` | Perkecil payload / naikkan batas |
| Admin tidak bisa simpan token | `ADMIN_PASSWORD` belum di-set | Set di Script Properties |
| `?download=` error `No item with the given ID` | `FILE_ID` salah / file sudah dihapus | Ambil ulang `id` dari daftar file |

---

## 11. Pengujian Cepat (curl)

```bash
# Kirim data
curl -L -X POST "URL_WEB_APP" \
  -H "Content-Type: text/plain" \
  -d '{"token":"...","toko":"TokoA","tanggal":"2026-11-05","data":{"x":1}}'

# Daftar file
curl -L "URL_WEB_APP?token=...&toko=TokoA"
```

Flag `-L` penting untuk mengikuti redirect GAS.

---

## 12. Kirim & Download Lewat Alamat Browser (Satu Baris)

Bagian ini untuk uji cepat atau operasi manual tanpa aplikasi. Ganti `URL_WEB_APP` dengan URL Web App Anda, dan `TOKEN` dengan `SECRET_TOKEN` aktif.

### 12.1 Kirim Data (POST) — Satu Baris

Browser tidak bisa melakukan POST dari address bar. Jadi POST "satu baris" dilakukan lewat **DevTools Console** (F12 → Console) atau **curl**.

**Lewat DevTools Console (satu baris, jalan di browser):**

```javascript
fetch('URL_WEB_APP',{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({token:'TOKEN',toko:'TokoA',tanggal:'2026-11-05',data:{transaksi:[{id:'u1',total:50000}]}}),redirect:'follow'}).then(r=>r.text()).then(console.log)
```

**Lewat curl (satu baris, terminal):**

```bash
curl -L -X POST "URL_WEB_APP" -H "Content-Type: text/plain" -d '{"token":"TOKEN","toko":"TokoA","tanggal":"2026-11-05","data":{"transaksi":[{"id":"u1","total":50000}]}}'
```

### 12.2 Lihat Daftar File (GET) — Satu Baris di Address Bar

```
URL_WEB_APP?token=TOKEN
```

Folder toko tertentu:

```
URL_WEB_APP?token=TOKEN&toko=TokoA
```

Browser menampilkan JSON daftar file.

### 12.3 Download Isi File JSON — Satu Baris di Address Bar

Setelah dapat `fileId` dari 12.2:

**Cara 1 — Lewat endpoint GAS (butuh token, file bisa privat):**

```
URL_WEB_APP?token=TOKEN&download=FILE_ID
```

Browser langsung mengunduh file JSON. File tetap privat di Drive.

**Cara 2 — Lewat Google Drive langsung (file harus bisa diakses publik):**

```
https://drive.google.com/uc?export=download&id=FILE_ID
```

Hanya berhasil kalau file di-share (minimal "Anyone with the link"). Untuk file privat, pakai Cara 1.

### 12.4 Ringkasan Satu Baris

| Tujuan | Cara | Satu Baris |
|---|---|---|
| Kirim data (POST) | DevTools Console | `fetch('URL_WEB_APP',{method:'POST',headers:{'Content-Type':'text/plain'},body:JSON.stringify({token:'TOKEN',toko:'TokoA',tanggal:'2026-11-05',data:{...}}),redirect:'follow'}).then(r=>r.text()).then(console.log)` |
| Kirim data (POST) | Terminal | `curl -L -X POST "URL_WEB_APP" -H "Content-Type: text/plain" -d '{"token":"TOKEN","toko":"TokoA","tanggal":"2026-11-05","data":{...}}'` |
| Lihat daftar file | Address bar | `URL_WEB_APP?token=TOKEN` |
| Lihat file toko | Address bar | `URL_WEB_APP?token=TOKEN&toko=TokoA` |
| Download file | Address bar | `URL_WEB_APP?token=TOKEN&download=FILE_ID` |
| Download via Drive | Address bar | `https://drive.google.com/uc?export=download&id=FILE_ID` |

> **Catatan:** `URL_WEB_APP?token=...` menampilkan token di riwayat browser. Untuk operasi sensitif, gunakan mode incognito atau hapus riwayat setelahnya.

---

## 13. Kode Sumber Lengkap

### 13.1 `Config.gs`

```javascript
/**
 * Config.gs
 * ------------------------------------------------------------------
 * Pusat konfigurasi aplikasi kasir.
 * Ubah nilai di sini saja. File .gs lain otomatis ikut.
 *
 * CATATAN:
 * - Setiap kali mengubah nilai di file ini, WAJIB deploy ulang.
 * - Jangan letakkan objek ini di dalam fungsi. Harus di level atas.
 * - Nilai SECRET_TOKEN di sini adalah FALLBACK. Kalau Script
 *   Properties punya SECRET_TOKEN, nilai itu yang dipakai.
 * ------------------------------------------------------------------
 */

const KASIR_CONFIG = {

  // ====== KEAMANAN ======
  // Token fallback. Harus SAMA PERSIS dengan yang dikirim HTML/Android.
  // Disarankan: atur nilai final lewat Script Properties (Admin.html).
  SECRET_TOKEN : 'ganti-dengan-token-panjang-acak-anda',

  // ====== LOKASI PENYIMPANAN ======
  // ID folder Google Drive root tempat semua folder toko dibuat.
  // Cara ambil: buka folder di Drive, lihat URL:
  //   https://drive.google.com/drive/folders/XXXXXXXXXXXX
  //   → FOLDER_ID = XXXXXXXXXXXX
  FOLDER_ID : 'ganti-dengan-ID-folder-google-drive-anda',

  // ====== WAKTU ======
  // Zona waktu untuk nama file jam (HHmmss).
  // Pilihan: 'Asia/Jakarta' (WIB), 'Asia/Makassar' (WITA), 'Asia/Jayapura' (WIT)
  TIMEZONE : 'Asia/Jakarta',

  // ====== PENAMAAN FILE ======
  // Pola nama file di dalam folder toko.
  // Placeholder: {tanggal} → YYYY-MM-DD, {jam} → HHmmss, {toko} → nama toko
  POLA_NAMA_FILE : '{tanggal}_{jam}.json',

  // ====== BATAS UKURAN ======
  // Ukuran maksimum body request dalam byte. 5 MB.
  MAX_SIZE : 5 * 1024 * 1024,

  // ====== DAFTAR TOKO ======
  // Toko yang diizinkan mengirim data. Kosongkan [] = semua toko boleh.
  TOKO_DIIZINKAN : [],

  // ====== VERSI API ======
  VERSI_API : 1

};
```

### 13.2 `Kode.gs`

```javascript
/**
 * Kode.gs
 * ------------------------------------------------------------------
 * Handler utama Web App:
 *   doPost()             → terima data dari aplikasi kasir
 *   doGet()              → daftar file / download / halaman admin
 *   simpanTokenBaru()    → dipanggil dari Admin.html
 *   cekPasswordAdmin()   → dipanggil dari Admin.html (validasi password)
 * ------------------------------------------------------------------
 */

// ==================================================================
// UTIL: baca token aktif (Script Properties > Config.gs)
// ==================================================================
function getTokenAktif_() {
  const props = PropertiesService.getScriptProperties();
  const dariProps = props.getProperty('SECRET_TOKEN');
  if (dariProps && dariProps.trim() !== '') return dariProps.trim();
  return KASIR_CONFIG.SECRET_TOKEN;
}

// ==================================================================
// POST: terima data dari aplikasi kasir
// ==================================================================
function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse({ ok: false, error: 'Body kosong' });
    }

    const body = JSON.parse(e.postData.contents);

    // Verifikasi token
    if (body.token !== getTokenAktif_()) {
      return jsonResponse({ ok: false, error: 'Token tidak valid' });
    }

    // Validasi field wajib
    if (!body.toko || !body.tanggal || !body.data) {
      return jsonResponse({ ok: false, error: 'Field toko/tanggal/data wajib ada' });
    }

    // Validasi format tanggal
    if (!/^\d{4}-\d{2}-\d{2}$/.test(body.tanggal)) {
      return jsonResponse({ ok: false, error: 'Format tanggal harus YYYY-MM-DD' });
    }

    // Batasi toko (kalau daftar tidak kosong)
    if (KASIR_CONFIG.TOKO_DIIZINKAN.length > 0 &&
        !KASIR_CONFIG.TOKO_DIIZINKAN.includes(body.toko)) {
      return jsonResponse({ ok: false, error: 'Toko tidak terdaftar' });
    }

    // Batasi ukuran
    const ukuran = Utilities.newBlob(e.postData.contents).getBytes().length;
    if (ukuran > KASIR_CONFIG.MAX_SIZE) {
      return jsonResponse({
        ok: false,
        error: 'File terlalu besar (maks ' +
          Math.round(KASIR_CONFIG.MAX_SIZE / 1024 / 1024) + ' MB)'
      });
    }

    const namaToko = sanitize_(body.toko);
    const tanggal  = sanitize_(body.tanggal);
    const jam      = Utilities.formatDate(new Date(), KASIR_CONFIG.TIMEZONE, 'HHmmss');

    // Folder toko (buat kalau belum ada)
    const rootFolder = DriveApp.getFolderById(KASIR_CONFIG.FOLDER_ID);
    let folderToko   = rootFolder.getFoldersByName(namaToko);
    folderToko = folderToko.hasNext() ? folderToko.next() : rootFolder.createFolder(namaToko);

    // Nama file dari pola di Config
    const namaFile = KASIR_CONFIG.POLA_NAMA_FILE
      .replace('{tanggal}', tanggal)
      .replace('{jam}', jam)
      .replace('{toko}', namaToko);

    // Simpan
    const konten = JSON.stringify(body.data, null, 2);
    const file   = folderToko.createFile(namaFile, konten, MimeType.PLAIN_TEXT);

    return jsonResponse({
      ok: true,
      versi: KASIR_CONFIG.VERSI_API,
      folder: namaToko,
      file: file.getName(),
      fileId: file.getId(),
      url: file.getUrl()
    });

  } catch (err) {
    return jsonResponse({ ok: false, error: err.message });
  }
}

// ==================================================================
// GET: routing
//   ?page=admin       → halaman admin
//   ?token=...&download=FILE_ID → unduh isi file
//   ?token=...        → daftar file (root / folder toko)
// ==================================================================
function doGet(e) {
  try {
    const params = (e && e.parameter) || {};

    // ---- Halaman admin ----
    if (params.page === 'admin') {
      return HtmlService
        .createHtmlOutputFromFile('Admin')
        .setTitle('Admin - Pengaturan Token')
        .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
    }

    // ---- Validasi token ----
    if (params.token !== getTokenAktif_()) {
      return jsonResponse({ ok: false, error: 'Token tidak valid' });
    }

    // ---- Download isi file ----
    if (params.download) {
      const file = DriveApp.getFileById(params.download);
      const isi  = file.getBlob().getDataAsString();
      return ContentService
        .createTextOutput(isi)
        .setMimeType(ContentService.MimeType.JSON);
    }

    // ---- Daftar file ----
    const rootFolder = DriveApp.getFolderById(KASIR_CONFIG.FOLDER_ID);
    let targetFolder = rootFolder;
    let namaFolder   = '(root)';

    if (params.toko) {
      const namaToko = sanitize_(params.toko);
      const found = rootFolder.getFoldersByName(namaToko);
      if (!found.hasNext()) {
        return jsonResponse({ ok: false, error: 'Folder toko tidak ditemukan: ' + namaToko });
      }
      targetFolder = found.next();
      namaFolder   = namaToko;
    }

    const files = [];
    const it = targetFolder.getFiles();
    while (it.hasNext()) {
      const f = it.next();
      files.push({
        name: f.getName(),
        id: f.getId(),
        size: f.getSize(),
        updated: f.getLastUpdated().toISOString(),
        url: f.getUrl()
      });
    }
    files.sort((a, b) => (a.updated < b.updated ? 1 : -1));

    let folders = [];
    if (!params.toko) {
      const fit = targetFolder.getFolders();
      while (fit.hasNext()) {
        const d = fit.next();
        folders.push({ name: d.getName(), id: d.getId() });
      }
      folders.sort((a, b) => a.name.localeCompare(b.name));
    }

    return jsonResponse({
      ok: true,
      versi: KASIR_CONFIG.VERSI_API,
      folder: namaFolder,
      folders: folders,
      files: files
    });

  } catch (err) {
    return jsonResponse({ ok: false, error: err.message });
  }
}

// ==================================================================
// Dipanggil dari Admin.html via google.script.run
// ==================================================================
function cekPasswordAdmin(passwordAdmin) {
  const passwordTersimpan = PropertiesService
    .getScriptProperties()
    .getProperty('ADMIN_PASSWORD');

  if (!passwordTersimpan) {
    throw new Error('ADMIN_PASSWORD belum di-set oleh Superadmin');
  }
  if (passwordAdmin !== passwordTersimpan) {
    throw new Error('Password admin salah');
  }
  return true;
}

function simpanTokenBaru(tokenBaru, passwordAdmin) {
  const props = PropertiesService.getScriptProperties();
  const passwordTersimpan = props.getProperty('ADMIN_PASSWORD');

  if (!passwordTersimpan) {
    throw new Error('ADMIN_PASSWORD belum di-set oleh Superadmin');
  }
  if (passwordAdmin !== passwordTersimpan) {
    throw new Error('Password admin salah');
  }
  if (!tokenBaru || tokenBaru.trim().length < 16) {
    throw new Error('Token baru minimal 16 karakter');
  }

  props.setProperty('SECRET_TOKEN', tokenBaru.trim());
  return 'Token berhasil diperbarui. Aplikasi kasir perlu memakai token baru.';
}

// ==================================================================
// HELPER
// ==================================================================
function jsonResponse(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}

function sanitize_(str) {
  return String(str).replace(/[\\/:*?"<>|]/g, '_').trim();
}
```

### 13.3 `Admin.html`

```html
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Admin - Pengaturan Token</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    max-width: 460px;
    margin: 40px auto;
    padding: 0 20px;
    color: #202124;
  }
  h1 { font-size: 20px; margin-bottom: 4px; }
  p.sub { color: #5f6368; font-size: 13px; margin-top: 0; line-height: 1.5; }
  label { display: block; font-size: 13px; font-weight: 600; margin: 16px 0 6px; }
  input[type="password"], input[type="text"] {
    width: 100%;
    padding: 10px 12px;
    border: 1px solid #dadce0;
    border-radius: 8px;
    font-size: 14px;
  }
  button {
    margin-top: 20px;
    width: 100%;
    padding: 12px;
    border: none;
    border-radius: 8px;
    background: #1a73e8;
    color: #fff;
    font-size: 15px;
    font-weight: 600;
    cursor: pointer;
  }
  button:disabled { background: #9aa0a6; cursor: not-allowed; }
  button.ghost {
    background: #fff;
    color: #1a73e8;
    border: 1px solid #dadce0;
    margin-top: 10px;
  }
  #status {
    margin-top: 16px;
    padding: 12px;
    border-radius: 8px;
    font-size: 13px;
    white-space: pre-wrap;
    word-break: break-word;
    display: none;
  }
  #status.ok   { background: #e6f4ea; color: #137333; display: block; }
  #status.err  { background: #fce8e6; color: #c5221f; display: block; }
  #status.info { background: #e8f0fe; color: #1a73e8; display: block; }
  .panel { display: none; }
  .panel.aktif { display: block; }
</style>
</head>
<body>

<h1>Pengaturan Token</h1>
<p class="sub">
  Halaman ini hanya untuk Admin. Masukkan password admin untuk membuka form ganti token.
</p>

<div id="loginPanel" class="panel aktif">
  <label for="pass">Password Admin</label>
  <input type="password" id="pass" placeholder="Password admin" autocomplete="off">
  <button id="btnLogin" onclick="login()">Buka</button>
</div>

<div id="formPanel" class="panel">
  <label for="token">Token Baru</label>
  <input type="text" id="token" placeholder="Minimal 16 karakter" autocomplete="off">

  <label for="pass2">Password Admin (konfirmasi)</label>
  <input type="password" id="pass2" placeholder="Ulangi password admin" autocomplete="off">

  <button id="btnSimpan" onclick="simpan()">Simpan Token</button>
  <button class="ghost" onclick="kembali()">Kembali</button>
</div>

<div id="status"></div>

<script>
const $ = id => document.getElementById(id);

function setStatus(msg, cls) {
  const el = $('status');
  el.textContent = msg;
  el.className = cls || '';
}

function tampilPanel(id) {
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('aktif'));
  $(id).classList.add('aktif');
}

function login() {
  const pass = $('pass').value;
  if (!pass) return setStatus('Password wajib diisi.', 'err');

  setStatus('Memeriksa...', 'info');
  google.script.run
    .withSuccessHandler(function () {
      setStatus('', '');
      $('pass2').value = pass;
      tampilPanel('formPanel');
    })
    .withFailureHandler(function (err) {
      setStatus('❌ ' + err.message, 'err');
    })
    .cekPasswordAdmin(pass);
}

function simpan() {
  const token = $('token').value.trim();
  const pass  = $('pass2').value;

  if (!token) return setStatus('Token baru wajib diisi.', 'err');
  if (token.length < 16) return setStatus('Token minimal 16 karakter.', 'err');
  if (!pass) return setStatus('Password admin wajib diisi.', 'err');

  $('btnSimpan').disabled = true;
  setStatus('Menyimpan...', 'info');

  google.script.run
    .withSuccessHandler(function (res) {
      setStatus('✅ ' + res, 'ok');
      $('token').value = '';
      $('btnSimpan').disabled = false;
    })
    .withFailureHandler(function (err) {
      setStatus('❌ ' + err.message, 'err');
      $('btnSimpan').disabled = false;
    })
    .simpanTokenBaru(token, pass);
}

function kembali() {
  $('pass').value = '';
  $('pass2').value = '';
  $('token').value = '';
  setStatus('', '');
  tampilPanel('loginPanel');
}
</script>

</body>
</html>
```

### 13.4 `upload.html` (file lokal)

```html
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Upload JSON ke Google Drive</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    max-width: 520px; margin: 40px auto; padding: 0 20px; color: #202124;
  }
  h1 { font-size: 20px; margin-bottom: 4px; }
  p.sub { color: #5f6368; font-size: 13px; margin-top: 0; line-height: 1.5; }
  code { background: #f1f3f4; padding: 1px 5px; border-radius: 4px; font-size: 12px; }
  label { display: block; font-size: 13px; font-weight: 600; margin: 16px 0 6px; }
  input[type="text"], input[type="file"] {
    width: 100%; padding: 10px 12px; border: 1px solid #dadce0;
    border-radius: 8px; font-size: 14px; background: #fff;
  }
  input[type="file"] { padding: 8px; }
  button {
    margin-top: 20px; width: 100%; padding: 12px; border: none;
    border-radius: 8px; background: #1a73e8; color: #fff;
    font-size: 15px; font-weight: 600; cursor: pointer;
  }
  button:disabled { background: #9aa0a6; cursor: not-allowed; }
  #status {
    margin-top: 16px; padding: 12px; border-radius: 8px; font-size: 13px;
    white-space: pre-wrap; word-break: break-word; display: none;
  }
  #status.ok   { background: #e6f4ea; color: #137333; display: block; }
  #status.err  { background: #fce8e6; color: #c5221f; display: block; }
  #status.info { background: #e8f0fe; color: #1a73e8; display: block; }
  small.hint { display: block; color: #5f6368; font-size: 12px; margin-top: 4px; }
</style>
</head>
<body>

<h1>Upload JSON ke Google Drive</h1>
<p class="sub">
  File akan disimpan di folder toko masing-masing.<br>
  Struktur: <code>FOLDER_ID/{toko}/{tanggal}_{jam}.json</code>
</p>

<label for="token">Token Rahasia</label>
<input type="text" id="token" placeholder="Tempel SECRET_TOKEN di sini" autocomplete="off">

<label for="toko">Nama Toko</label>
<input type="text" id="toko" placeholder="Contoh: TokoA" autocomplete="off">
<small class="hint">Akan dibuatkan folder otomatis jika belum ada.</small>

<label for="tanggal">Tanggal (YYYY-MM-DD)</label>
<input type="text" id="tanggal" placeholder="2026-11-05" autocomplete="off">
<small class="hint">Dikosongkan → pakai tanggal hari ini.</small>

<label for="file">File JSON</label>
<input type="file" id="file" accept=".json,application/json">

<button id="btn" onclick="kirim()">Unggah</button>
<div id="status"></div>

<script>
const ENDPOINT = 'https://script.google.com/macros/s/AKfycbyjRKl5MmA9bDpibfy70aEQC7J9c8dC1gdIpSs6T_594Gplhjjbjx8kMijADU1u2vtg/exec';
const $ = id => document.getElementById(id);

function setStatus(msg, cls) {
  const el = $('status');
  el.textContent = msg;
  el.className = cls || '';
}

function tanggalHariIni() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${dd}`;
}

async function kirim() {
  const token   = $('token').value.trim();
  const toko    = $('toko').value.trim();
  let   tanggal = $('tanggal').value.trim();
  const file    = $('file').files[0];

  if (!tanggal) tanggal = tanggalHariIni();
  if (!token)   return setStatus('Token wajib diisi.', 'err');
  if (!toko)    return setStatus('Nama toko wajib diisi.', 'err');
  if (!file)    return setStatus('Pilih file JSON terlebih dahulu.', 'err');
  if (!/^\d{4}-\d{2}-\d{2}$/.test(tanggal)) {
    return setStatus('Format tanggal harus YYYY-MM-DD (contoh: 2026-11-05).', 'err');
  }

  const btn = $('btn');
  btn.disabled = true;
  setStatus('Membaca file...', 'info');

  try {
    const teks = await file.text();
    let data;
    try { data = JSON.parse(teks); }
    catch (e) { throw new Error('File bukan JSON yang valid: ' + e.message); }

    const body = { token, toko, tanggal, data };
    setStatus('Mengirim ke Google Apps Script...', 'info');

    const res = await fetch(ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'text/plain;charset=utf-8' },
      body: JSON.stringify(body),
      redirect: 'follow'
    });

    const text = await res.text();
    let hasil;
    try { hasil = JSON.parse(text); }
    catch { throw new Error('Respons bukan JSON:\n' + text.slice(0, 300)); }

    if (hasil.ok) {
      setStatus(
        '✅ Berhasil disimpan!\n' +
        'Folder : ' + (hasil.folder || '-') + '\n' +
        'File   : ' + hasil.file + '\n' +
        'URL    : ' + (hasil.url || '-'),
        'ok'
      );
    } else {
      setStatus('❌ Gagal: ' + (hasil.error || 'Tidak diketahui'), 'err');
    }

  } catch (err) {
    setStatus('❌ Error: ' + err.message, 'err');
  } finally {
    btn.disabled = false;
  }
}
</script>

</body>
</html>
```

### 13.5 `daftar.html` (file lokal)

```html
<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Daftar File di Google Drive</title>
<style>
  * { box-sizing: border-box; }
  body {
    font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
    max-width: 720px; margin: 40px auto; padding: 0 20px; color: #202124;
  }
  h1 { font-size: 20px; margin-bottom: 4px; }
  p.sub { color: #5f6368; font-size: 13px; margin-top: 0; }
  label { display: block; font-size: 13px; font-weight: 600; margin: 16px 0 6px; }
  input[type="text"] {
    width: 100%; padding: 10px 12px; border: 1px solid #dadce0;
    border-radius: 8px; font-size: 14px;
  }
  .row { display: flex; gap: 8px; align-items: flex-end; }
  .row > div { flex: 1; }
  button {
    padding: 10px 16px; border: none; border-radius: 8px;
    background: #1a73e8; color: #fff; font-size: 14px;
    font-weight: 600; cursor: pointer; white-space: nowrap;
  }
  button:disabled { background: #9aa0a6; cursor: not-allowed; }
  #status {
    margin-top: 16px; padding: 12px; border-radius: 8px; font-size: 13px;
    white-space: pre-wrap; word-break: break-word; display: none;
  }
  #status.ok   { background: #e6f4ea; color: #137333; display: block; }
  #status.err  { background: #fce8e6; color: #c5221f; display: block; }
  #status.info { background: #e8f0fe; color: #1a73e8; display: block; }
  .crumb { margin-top: 20px; font-size: 13px; color: #5f6368; }
  .crumb b { color: #202124; }
  ul.folders { list-style: none; padding: 0; margin: 12px 0 0; }
  ul.folders li {
    padding: 10px 12px; border: 1px solid #dadce0; border-radius: 8px;
    margin-bottom: 8px; cursor: pointer; display: flex;
    justify-content: space-between; align-items: center;
  }
  ul.folders li:hover { background: #f8f9fa; }
  ul.folders li .arrow { color: #1a73e8; font-weight: 600; }
  table { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
  th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #e8eaed; vertical-align: top; }
  th { color: #5f6368; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .03em; }
  td.nama { font-weight: 600; word-break: break-all; }
  td a { color: #1a73e8; text-decoration: none; margin-right: 8px; }
  td a:hover { text-decoration: underline; }
  .empty { color: #5f6368; font-size: 13px; margin-top: 12px; }
</style>
</head>
<body>

<h1>Daftar File di Google Drive</h1>
<p class="sub">Lihat folder toko dan file JSON yang sudah diunggah.</p>

<label for="token">Token Rahasia</label>
<input type="text" id="token" placeholder="Tempel SECRET_TOKEN di sini" autocomplete="off">

<div class="row" style="margin-top:16px">
  <div>
    <label for="toko" style="margin-top:0">Nama Toko (opsional)</label>
    <input type="text" id="toko" placeholder="Kosongkan untuk lihat semua toko" autocomplete="off">
  </div>
  <button id="btn" onclick="muat()">Muat</button>
</div>

<div class="crumb" id="crumb"></div>
<div id="status"></div>
<ul class="folders" id="folders"></ul>
<div id="filesWrap"></div>

<script>
const ENDPOINT = 'https://script.google.com/macros/s/AKfycbyjRKl5MmA9bDpibfy70aEQC7J9c8dC1gdIpSs6T_594Gplhjjbjx8kMijADU1u2vtg/exec';
const $ = id => document.getElementById(id);
let tokoAktif = '';

function setStatus(msg, cls) {
  const el = $('status');
  el.textContent = msg;
  el.className = cls || '';
}

function formatSize(bytes) {
  if (bytes == null) return '-';
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

function formatTanggal(iso) {
  if (!iso) return '-';
  const d = new Date(iso);
  return d.toLocaleString('id-ID', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  });
}

async function muat(toko) {
  const token = $('token').value.trim();
  if (!token) return setStatus('Token wajib diisi.', 'err');

  if (typeof toko === 'string') tokoAktif = toko;
  else tokoAktif = $('toko').value.trim();

  const btn = $('btn');
  btn.disabled = true;
  setStatus('Memuat data...', 'info');
  $('folders').innerHTML = '';
  $('filesWrap').innerHTML = '';
  $('crumb').innerHTML = '';

  try {
    let url = ENDPOINT + '?token=' + encodeURIComponent(token);
    if (tokoAktif) url += '&toko=' + encodeURIComponent(tokoAktif);

    const res = await fetch(url, { redirect: 'follow' });
    const text = await res.text();

    let hasil;
    try { hasil = JSON.parse(text); }
    catch { throw new Error('Respons bukan JSON:\n' + text.slice(0, 300)); }

    if (!hasil.ok) {
      setStatus('❌ ' + (hasil.error || 'Gagal memuat'), 'err');
      return;
    }

    setStatus('', '');

    if (tokoAktif) {
      $('crumb').innerHTML =
        '<a href="#" onclick="muat(\'\');return false;">Semua Toko</a> › <b>' +
        escapeHtml(hasil.folder) + '</b>';
    } else {
      $('crumb').innerHTML = '<b>Semua Toko</b>';
    }

    if (hasil.folders && hasil.folders.length) {
      const ul = $('folders');
      hasil.folders.forEach(f => {
        const li = document.createElement('li');
        li.innerHTML = '<span>📁 ' + escapeHtml(f.name) + '</span><span class="arrow">›</span>';
        li.onclick = () => muat(f.name);
        ul.appendChild(li);
      });
    }

    if (!hasil.files || hasil.files.length === 0) {
      $('filesWrap').innerHTML = '<div class="empty">Belum ada file di folder ini.</div>';
      return;
    }

    const rows = hasil.files.map(f => `
      <tr>
        <td class="nama">${escapeHtml(f.name)}</td>
        <td>${formatSize(f.size)}</td>
        <td>${formatTanggal(f.updated)}</td>
        <td>
          <a href="${f.url}" target="_blank" rel="noopener">Buka</a>
          <a href="${ENDPOINT}?token=${encodeURIComponent($('token').value.trim())}&download=${encodeURIComponent(f.id)}" target="_blank" rel="noopener">Unduh</a>
        </td>
      </tr>
    `).join('');

    $('filesWrap').innerHTML = `
      <table>
        <thead>
          <tr><th>Nama File</th><th>Ukuran</th><th>Diperbarui</th><th>Aksi</th></tr>
        </thead>
        <tbody>${rows}</tbody>
      </table>
    `;

  } catch (err) {
    setStatus('❌ Error: ' + err.message, 'err');
  } finally {
    btn.disabled = false;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}
</script>

</body>
</html>
```

---

## 14. Roadmap

- [x] `doPost` menerima JSON + token, simpan ke folder toko.
- [x] `doGet` daftar file (root & folder toko).
- [x] `Admin.html` untuk ganti token via Script Properties.
- [x] `?download=FILE_ID` untuk unduh file lewat GAS.
- [ ] Halaman index HTML untuk navigasi daftar → download tanpa salin URL manual.
- [ ] Aplikasi manajer (Android/Web) yang membaca folder toko via `doGet`.
- [ ] Validasi skema JSON di `doPost` (field wajib per transaksi).
- [ ] Notifikasi (email/WA) saat file baru masuk.
- [ ] Migrasi `SECRET_TOKEN` sepenuhnya ke Script Properties (hapus fallback di `Config.gs`).

---

*Dokumentasi ini adalah versi final untuk kondisi proyek saat ini.*
