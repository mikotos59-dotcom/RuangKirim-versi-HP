package id.sus.ruangkirim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Layanan latar: menjalankan Core (pencarian WiFi + penerima) selama Ruang Kirim aktif, dengan pemberitahuan tetap.
 * Dihentikan lewat tombol "Berhenti" di pemberitahuan atau di Pengaturan aplikasi.
 */
public class KirimService extends Service {
    static final String STOP = "id.sus.ruangkirim.STOP";
    private static final String CH_RUN = "jalan", CH_ASK = "permintaan";
    private static final int N_RUN = 1, N_ASK = 2;

    interface Listener { void onState(JSONObject s); }
    static volatile KirimService inst;
    static volatile Listener listener;
    static volatile boolean uiVisible;

    Core core;
    private WifiManager.MulticastLock mlock;
    private PowerManager.WakeLock wlock;
    private WifiManager.WifiLock wifiLock;
    private String lastNote = "";

    static void start(Context c) {
        Intent i = new Intent(c, KirimService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_RUN, "Ruang Kirim aktif", NotificationManager.IMPORTANCE_LOW));
        NotificationChannel ask = new NotificationChannel(CH_ASK, "Permintaan kirim & pasangkan", NotificationManager.IMPORTANCE_HIGH);
        ask.setDescription("Muncul saat perangkat lain mau mengirim file atau memasangkan perangkat");
        nm.createNotificationChannel(ask);
        goForeground("Siap menerima di WiFi ini");

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            mlock = wm.createMulticastLock("ruangkirim");
            mlock.setReferenceCounted(false);
            mlock.acquire();
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ruangkirim");
            wifiLock.setReferenceCounted(false);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wlock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruangkirim:kirim");
        wlock.setReferenceCounted(false);

        core = new Core(new Plat(), "hp", deviceName(), Core.UDP_PORT, Core.TCP_PORT);
        inst = this;
        new Thread(() -> { try { core.start(); } catch (Exception e) { lastNote = "Gagal mulai: " + e.getMessage(); } }).start();
    }

    private String deviceName() {
        String n = null;
        try { n = Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) { }
        if (n == null || n.trim().isEmpty()) {
            String m = Build.MODEL == null ? "HP" : Build.MODEL;
            String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
            n = m.toLowerCase().startsWith(b.toLowerCase()) ? m : (b.isEmpty() ? m : Character.toUpperCase(b.charAt(0)) + b.substring(1) + " " + m);
        }
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification runNote(String text) {
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, KirimService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CH_RUN)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Ruang Kirim")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder(null, "Berhenti", stop).build())
                .build();
    }
    private void goForeground(String text) {
        Notification n = runNote(text);
        if (Build.VERSION.SDK_INT >= 29) startForeground(N_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(N_RUN, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) { shutdown(); return START_NOT_STICKY; }
        return START_NOT_STICKY;
    }

    void shutdown() {
        Listener l = listener;
        stopForeground(true);
        stopSelf();
        if (l instanceof MainActivity) ((MainActivity) l).runOnUiThread(((MainActivity) l)::finishAndRemoveTask);
    }

    // Android 15: batas waktu layanan dataSync
    @Override
    public void onTimeout(int startId, int type) { shutdown(); }

    @Override
    public void onDestroy() {
        inst = null;
        if (core != null) core.stop();
        if (mlock != null && mlock.isHeld()) mlock.release();
        if (wlock != null && wlock.isHeld()) wlock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------- keadaan → pemberitahuan & kunci layar ----------
    private void onCoreState(JSONObject s) {
        Listener l = listener;
        if (l != null) l.onState(s);
        boolean busy = core != null && core.busy();
        try {
            if (busy) { if (!wlock.isHeld()) wlock.acquire(6 * 3600 * 1000L); if (wifiLock != null && !wifiLock.isHeld()) wifiLock.acquire(); }
            else { if (wlock.isHeld()) wlock.release(); if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); }
        } catch (Exception ignored) { }
        String text = "Siap menerima di WiFi ini";
        try {
            org.json.JSONArray ts = s.optJSONArray("transfers");
            for (int i = 0; ts != null && i < ts.length(); i++) {
                JSONObject t = ts.getJSONObject(i);
                if (!"jalan".equals(t.optString("status"))) continue;
                long total = t.optLong("total"), done = t.optLong("done");
                text = (t.optString("dir").equals("kirim") ? "Mengirim ke " : "Menerima dari ") + t.optJSONObject("peer").optString("name") + " · " + (total > 0 ? (done * 100 / total) : 0) + "%";
                break;
            }
        } catch (Exception ignored) { }
        if (!text.equals(lastNote)) {
            lastNote = text;
            getSystemService(NotificationManager.class).notify(N_RUN, runNote(text));
        }
        if (s.optJSONArray("prompts") == null || s.optJSONArray("prompts").length() == 0) getSystemService(NotificationManager.class).cancel(N_ASK);
    }

    private void onCorePrompt(JSONObject p) {
        if (uiVisible || "tunggu".equals(p.optString("kind"))) return;
        JSONObject f = p.optJSONObject("from");
        String who = f == null ? "Perangkat" : f.optString("name");
        boolean known = f != null && f.optBoolean("known");
        int cnt = p.optInt("count");
        String title = "pasang".equals(p.optString("kind")) ? "Permintaan pasangkan dari " + who : who + (cnt > 0 ? " mau mengirim " + cnt + " file" : " mengirim pesan");
        String body = "pasang".equals(p.optString("kind")) ? "Ketuk untuk mencocokkan kode " + p.optString("code") : (known ? "Perangkat dikenal" : "BELUM DIKENAL") + " · ketuk untuk Terima / Tolak";
        Notification n = new Notification.Builder(this, CH_ASK)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp())
                .setTimeoutAfter(Core.PROMPT_MS)
                .build();
        try { getSystemService(NotificationManager.class).notify(N_ASK, n); } catch (SecurityException ignored) { }
    }

    // ---------- bagian khusus Android untuk Core ----------
    private final class Plat implements Core.Platform {
        private File cfg() { return new File(getFilesDir(), "ruangkirim.json"); }
        @Override public String loadConfig() {
            try (InputStream in = new java.io.FileInputStream(cfg())) {
                return new String(MainActivity.readAll(in, 4 << 20), StandardCharsets.UTF_8);
            } catch (Exception e) { return null; }
        }
        @Override public void saveConfig(String json) {
            File tmp = new File(getFilesDir(), "ruangkirim.json.tmp");
            try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); o.getFD().sync(); }
            catch (IOException e) { return; }
            tmp.renameTo(cfg());
        }
        @Override public Core.Sink create(String rel, long size) throws IOException {
            String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel;
            String sub = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "";
            String mime = mimeOf(name);
            if (Build.VERSION.SDK_INT >= 29) return mediaStoreSink(name, sub, mime);
            return legacySink(name, sub, mime);
        }
        @Override public List<String> ips() {
            List<String> out = new ArrayList<>();
            for (InterfaceAddress a : addrs()) out.add(a.getAddress().getHostAddress());
            return out;
        }
        @Override public List<String> broadcasts() {
            List<String> out = new ArrayList<>();
            out.add("255.255.255.255");
            for (InterfaceAddress a : addrs()) if (a.getBroadcast() != null && !out.contains(a.getBroadcast().getHostAddress())) out.add(a.getBroadcast().getHostAddress());
            return out;
        }
        @Override public void onState(JSONObject state) { onCoreState(state); }
        @Override public void onPrompt(JSONObject prompt) { onCorePrompt(prompt); }
        @Override public void log(String s) { android.util.Log.i("RuangKirim", s); }
    }

    private static List<InterfaceAddress> addrs() {
        List<InterfaceAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName();
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("v4-")) continue; // data seluler / VPN
                for (InterfaceAddress a : ni.getInterfaceAddresses()) if (a.getAddress() instanceof Inet4Address && !a.getAddress().isLinkLocalAddress()) out.add(a);
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return m != null ? m : "application/octet-stream";
    }

    // Android 10+: simpan ke Download/RuangKirim lewat MediaStore (tanpa izin penyimpanan)
    private Core.Sink mediaStoreSink(String name, String sub, String mime) throws IOException {
        ContentResolver cr = getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RuangKirim" + (sub.isEmpty() ? "" : "/" + sub));
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Tidak bisa membuat file di folder Download");
        OutputStream out = cr.openOutputStream(uri, "w");
        if (out == null) { cr.delete(uri, null, null); throw new IOException("Tidak bisa menulis file"); }
        OutputStream buf = new java.io.BufferedOutputStream(out, 1 << 18);
        return new Core.Sink() {
            @Override public OutputStream out() { return buf; }
            @Override public String commit() throws IOException {
                buf.close();
                ContentValues d = new ContentValues();
                d.put(MediaStore.MediaColumns.IS_PENDING, 0);
                cr.update(uri, d, null, null);
                return uri.toString();
            }
            @Override public void abort() { try { buf.close(); } catch (Exception ignored) { } try { cr.delete(uri, null, null); } catch (Exception ignored) { } }
        };
    }

    // Android 8–9: file biasa di Download/RuangKirim, lalu didaftarkan ke MediaStore supaya bisa dibuka
    private Core.Sink legacySink(String name, String sub, String mime) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RuangKirim" + (sub.isEmpty() ? "" : "/" + sub));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Izin penyimpanan belum diberikan");
        File f = new File(dir, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; f.exists(); i++) f = new File(dir, base + " (" + i + ")" + ext);
        File fin = f, tmp = new File(dir, f.getName() + ".rkpart");
        OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(tmp), 1 << 18);
        return new Core.Sink() {
            @Override public OutputStream out() { return out; }
            @Override public String commit() throws IOException {
                out.close();
                if (!tmp.renameTo(fin)) throw new IOException("Gagal menyimpan " + fin.getName());
                String[] got = {fin.getPath()};
                CountDownLatch l = new CountDownLatch(1);
                MediaScannerConnection.scanFile(KirimService.this, new String[]{fin.getPath()}, new String[]{mime}, (p, u) -> { if (u != null) got[0] = u.toString(); l.countDown(); });
                try { l.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                return got[0];
            }
            @Override public void abort() { try { out.close(); } catch (Exception ignored) { } tmp.delete(); }
        };
    }
}
