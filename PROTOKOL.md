# Protokol Ruang Kirim v1

Dipakai sama persis oleh versi PC (`lib/proto.js`, `lib/core.js`) dan versi HP (`Proto.java`, `Core.java`).

## Pencarian (UDP 47820)
Tiap 4 detik, setiap perangkat mengirim broadcast `{"rk":1,"t":"hai","id","name","type","port"}`.
Perangkat yang baru menyala mengirim `"t":"cari"`, dan yang lain membalas `"hai"` langsung ke pengirim.

## Identitas
- Kunci ECDH P-256 tetap per perangkat.
- `id` = 16 karakter hex pertama dari SHA-256 kunci publik (65 byte, tak terkompresi).
- Karena id terikat ke kunci, perangkat lain tidak bisa mengaku sebagai perangkat dikenal tanpa kunci rahasianya.

## Sambungan (TCP 47821, atau port acak kalau dipakai)
- Setiap frame: `u32 panjang (big-endian)` + isi, maksimal 1 MB.
- Jabat tangan: yang menyambung mengirim "hai" JSON lebih dulu: `{rk, id, name, type, port, pub, eph}`, dengan `pub` dan `eph` dalam base64. Penerima membalas dengan bentuk yang sama.
- `salt = SHA-256(u32 len(hc) || hc || u32 len(hs) || hs)` (hc = "hai" dari yang menyambung, hs = dari penerima).
- `ikm = ECDH(eph, eph) || ECDH(tetap, tetap)`.
- `kunci c2s / s2c = HKDF-SHA256(ikm, salt, "rk1 c2s" / "rk1 s2c", 32)`.
- `kode cocok = u32(HKDF(ikm, salt, "rk1 kode", 4)) mod 1 000 000`, ditampilkan 6 digit di kedua layar.
- Setelah jabat tangan, setiap frame dienkripsi AES-256-GCM:
  - nonce = 4 byte nol + penghitung 8 byte per arah;
  - isi = 1 byte jenis (1 = JSON, 2 = data file) + muatan.

## Pesan
- **Kirim:**
  1. → `{"t":"kirim","files":[{"n","s"}],"total"}`
  2. ← `{"t":"terima"}` atau `{"t":"tolak","why"}`
  3. → frame data, berurutan per file sesuai ukuran (potongan 256 KB)
  4. → `{"t":"selesai"}`
  5. ← `{"t":"ok"}`
- **Pasangkan kode angka:** → `{"t":"pasang"}`. Penerima menampilkan kode dan bertanya Ya/Tidak, lalu membalas ← `{"t":"dipasang","name"}` atau `{"t":"tolak"}`.
- **Pasangkan QR:** QR berisi `RK1:` + base64url `{"k":pub,"n":nama,"a":[ip],"p":port,"t":token}`. Pemindai mengirim → `{"t":"pasang","tok"}`.
  - Kunci publik di QR dicocokkan dengan jabat tangan.
  - Token berlaku 5 menit dan hanya sekali pakai.
- **Batal:** `{"t":"batal"}` dari pihak mana pun.
- **Cek alamat:** `{"t":"tutup"}`, hanya jabat tangan lalu ditutup.
