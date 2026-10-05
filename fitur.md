1. Android → Google Sheets langsung 
Android Notification Listener
          │
          │ HTTPS POST
          ▼
     Google Apps Script
          │
          ▼
     Google Spreadsheet

Ini menurut saya paling simpel.

Google Apps Script bisa menyediakan endpoint HTTP (doPost) yang menerima JSON dari aplikasi Android, lalu menambahkan row ke spreadsheet.

Apps Script tinggal:

POST
  ↓
parse JSON
  ↓
Spreadsheet.appendRow(...)

Hasilnya:

Timestamp	Merchant	Amount	Source	Raw
16:30	STARBUCKS	125000	BCA	Pembayaran QRIS...

Tidak perlu server, database, Docker, API sendiri, atau homelab.

