# Panduan Setup

Panduan ini menjelaskan konfigurasi Google Sheets, Google Apps Script, dan aplikasi Android. Solusi ini tidak memerlukan server/API khusus, database transaksi aplikasi, Docker, atau homelab.

## 1. Buat Google Spreadsheet

1. Buat spreadsheet di akun Google yang akan memiliki Apps Script.
2. Buat tab worksheet, misalnya `Transactions`.
3. Salin ID spreadsheet dari URL:

   ```text
   https://docs.google.com/spreadsheets/d/SPREADSHEET_ID/edit
   ```

   ID spreadsheet adalah bagian di antara `/d/` dan `/edit`.
4. Jika worksheet masih kosong, Apps Script akan membuat header berikut saat transaksi pertama diterima:

   ```text
   Timestamp | Merchant | Amount | Source | Raw
   ```

   Jika worksheet sudah berisi data, pastikan baris pertama memiliki header tersebut dalam urutan yang sama.

## 2. Buat dan konfigurasi Apps Script

1. Buka [script.google.com](https://script.google.com) dan buat project standalone.
2. Ganti isi `Code.gs` dengan file [`apps_script/Code.gs`](../apps_script/Code.gs) dari repository ini.
3. Buat shared secret acak sepanjang 32–256 karakter. Di Windows PowerShell, perintah berikut menghasilkan secret acak 64 karakter:

   ```powershell
   $bytes = New-Object byte[] 32
   [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
   ```

   Simpan secret tersebut dengan aman. Jangan masukkan secret ke repository atau spreadsheet.
4. Di Apps Script, buka **Project Settings → Script Properties**, lalu tambahkan:

   | Property | Nilai |
   |---|---|
   | `SCRIPT_TOKEN` | Shared secret acak yang dibuat pada langkah sebelumnya |
   | `SPREADSHEET_ID` | ID Google Spreadsheet |
   | `SHEET_NAME` | Nama tab worksheet yang persis, misalnya `Transactions` |

5. Simpan project, lalu pilih **Deploy → New deployment → Web app**:
   - **Execute as:** akun pemilik script;
   - **Who has access:** pilih akses yang diperlukan agar perangkat dapat memanggil endpoint. Kebijakan akun dapat membatasi pilihan; opsi yang umum digunakan adalah `Anyone`.
6. Berikan otorisasi agar script dapat mengakses spreadsheet jika diminta.
7. Salin URL deployment web app yang berakhir dengan `/exec`.
8. Jika secret terekspos, ganti `SCRIPT_TOKEN` di Script Properties dan perbarui secret pada aplikasi.

Deployment web app yang dapat diakses publik menggunakan shared secret sebagai validasi pada tingkat aplikasi. Secret adalah bearer credential dan dapat diekstrak dari perangkat oleh pihak yang cukup termotivasi; jangan menganggapnya sebagai autentikasi pengguna atau perlindungan penyalahgunaan yang kuat.

## 3. Siapkan dan jalankan aplikasi Flutter

Pasang Flutter stable, Android SDK, dan JDK 17. Workspace ini disiapkan di lingkungan yang tidak memiliki Flutter SDK, sehingga file Gradle wrapper yang biasanya dibuat Flutter mungkin belum tersedia. Jika `android/gradlew.bat` tidak ada, jalankan dari direktori utama project:

```powershell
flutter create --platforms=android .
```

Periksa perubahan yang dihasilkan agar konfigurasi Android 11+ dan source Kotlin listener tetap dipertahankan.

Dari direktori utama project, jalankan:

```powershell
flutter pub get
flutter analyze
flutter test
node scripts/test_apps_script.js
flutter build apk --debug
```

Pasang APK pada perangkat Android 11 (API 30) atau lebih baru. Di dalam aplikasi:

1. Masukkan URL deployment Apps Script yang berakhir dengan `/exec`.
2. Masukkan shared secret yang sama dengan nilai `SCRIPT_TOKEN` di Script Properties.
3. Tentukan apakah teks notifikasi mentah boleh dikirim. Teks mentah dapat berisi saldo atau sebagian informasi rekening/kartu dan akan disimpan pada kolom `Raw` di spreadsheet. Pilihan ini dapat dimatikan.
4. Simpan konfigurasi, lalu pilih **Tes koneksi Apps Script**. Tes ping memeriksa token dan worksheet tanpa menambahkan baris transaksi.
5. Pilih hanya aplikasi sumber notifikasi yang ingin dipantau. Secara default tidak ada aplikasi yang dipilih.
6. Tekan **Berikan akses**, lalu aktifkan akses notifikasi aplikasi ini di pengaturan Android.
7. Aktifkan **Penerusan otomatis**.

Nama menu dan tampilan pengaturan Android dapat berbeda antar produsen perangkat.

## 4. Verifikasi transaksi

Gunakan notifikasi transaksi uji dari aplikasi sumber yang didukung. Pastikan satu baris baru muncul di spreadsheet dengan kolom:

1. `Timestamp` — waktu notifikasi diterima perangkat dalam format ISO 8601;
2. `Merchant` — judul notifikasi jika tersedia, atau `Unknown merchant`;
3. `Amount` — nilai IDR numerik;
4. `Source` — nama aplikasi sumber;
5. `Raw` — teks notifikasi asli hanya jika pengguna mengaktifkan persetujuan pengiriman teks mentah.

Waktu pengiriman terakhir yang berhasil di aplikasi adalah status operasional, bukan riwayat transaksi lokal.

## 5. Pemecahan masalah

- **Tes koneksi gagal:** pastikan URL berakhir dengan `/exec`, token sama dengan `SCRIPT_TOKEN`, deployment dapat diakses perangkat, dan properti Apps Script sudah disetel.
- **`sheet_not_found`:** pastikan `SHEET_NAME` sama persis dengan nama tab worksheet.
- **`configuration_missing`:** pastikan `SCRIPT_TOKEN`, `SPREADSHEET_ID`, dan `SHEET_NAME` sudah dibuat di Script Properties pada project yang dideploy.
- **Aplikasi sumber tidak muncul:** daftar hanya menampilkan aplikasi yang dapat dibuka melalui launcher Android. Pastikan aplikasi sumber terpasang dan memiliki ikon launcher.
- **Baris tidak ditambahkan:** periksa akses notifikasi, status penerusan otomatis, pilihan aplikasi sumber, serta apakah notifikasi mengandung indikator pengeluaran dan nominal yang dapat dikenali dengan aman.
- **Pengiriman tertunda:** WorkManager menunggu jaringan tersedia dan menerapkan jeda percobaan ulang. Penghemat baterai tiap produsen perangkat dapat memengaruhi jadwal.
- **Ada baris duplikat:** pengiriman tepat satu kali tidak dijamin jika Apps Script sudah menambahkan baris tetapi responsnya tidak sampai ke ponsel sebelum percobaan ulang.

## 6. Cabut akses dan hapus konfigurasi

- Jeda penerusan otomatis di aplikasi atau cabut akses notification listener melalui pengaturan Android.
- Gunakan **Hapus konfigurasi dan pengiriman tertunda** untuk menghapus URL, secret, pilihan aplikasi sumber, serta pekerjaan pengiriman yang masih tertunda di perangkat.
- Penghapusan konfigurasi tidak menghapus baris yang sudah masuk ke spreadsheet. Hapus baris tersebut langsung melalui Google Sheets jika diperlukan.
