package id.sus.ruangkirim;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tampilan Ruang Kirim (WebView lokal, sama dengan versi PC) + pemilih file, pemindai QR, dan "Bagikan". */
public class MainActivity extends Activity implements KirimService.Listener {
    private static final String UI = "https://appassets.androidplatform.net/assets/ui/index.html";
    private static final int REQ_PICK = 21, REQ_PERM = 22;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private WebView web;
    private boolean ready = false;
    private String pickFor = null;
    private int pickReq = 0;
    private final List<Core.Source> pending = new ArrayList<>();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web = new WebView(this);
        boolean night = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        web.setBackgroundColor(night ? Color.parseColor("#111413") : Color.parseColor("#f4efe6"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.addJavascriptInterface(new Bridge(), "RKNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                WebResourceResponse res = loader.shouldInterceptRequest(r.getUrl());
                return res != null ? res : new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
            @Override
            public void onPageFinished(WebView v, String url) { ready = true; pushState(); }
        });
        setContentView(web);
        web.loadUrl(UI);

        List<String> ask = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) ask.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!ask.isEmpty()) requestPermissions(ask.toArray(new String[0]), REQ_PERM);

        KirimService.listener = this;
        KirimService.start(this);
        handleShare(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShare(intent);
    }
    @Override
    protected void onResume() {
        super.onResume();
        KirimService.uiVisible = true;
        KirimService.listener = this;
        if (KirimService.inst == null) KirimService.start(this);
        pushState();
    }
    @Override
    protected void onPause() { super.onPause(); KirimService.uiVisible = false; }
    @Override
    protected void onDestroy() {
        if (KirimService.listener == this) KirimService.listener = null;
        bg.shutdown();
        web.destroy();
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.RKUI?RKUI.back():false", r -> { if (!"true".equals(r)) moveTaskToBack(true); });
    }

    // ---------- keadaan dari layanan ----------
    @Override
    public void onState(JSONObject s) { main.post(() -> pushState(s)); }
    private void pushState() {
        Core c = core();
        if (c != null) bg.execute(() -> { JSONObject s = c.state(); main.post(() -> pushState(s)); });
    }
    private void pushState(JSONObject s) {
        if (!ready) return;
        try { s.put("pending", pendingJson()); } catch (Exception ignored) { }
        web.evaluateJavascript("window.__rkState&&__rkState(" + s + ")", null);
    }
    private static Core core() { KirimService k = KirimService.inst; return k == null ? null : k.core; }

    private JSONObject pendingJson() throws Exception {
        synchronized (pending) {
            if (pending.isEmpty()) return null;
            long total = 0;
            for (Core.Source x : pending) total += Math.max(0, x.size());
            return new JSONObject().put("count", pending.size()).put("total", total);
        }
    }

    private void toast(String m) { main.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }

    // ---------- file dari "Bagikan" ----------
    @SuppressWarnings("deprecation")
    private void handleShare(Intent i) {
        if (i == null) return;
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(i.getAction())) {
            Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
            else {
                CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (t != null) { addText(t.toString()); return; }
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(i.getAction())) {
            ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else return;
        if (uris.isEmpty()) return;
        bg.execute(() -> {
            List<Core.Source> src = new ArrayList<>();
            for (Uri u : uris) { try { src.add(source(u)); } catch (Exception e) { toast("File tidak bisa dibaca: " + e.getMessage()); } }
            synchronized (pending) { pending.clear(); pending.addAll(src); }
            pushState();
        });
    }
    // teks yang dibagikan dikirim sebagai file .txt
    private void addText(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        String name = "teks-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".txt";
        synchronized (pending) {
            pending.clear();
            pending.add(new Core.Source() {
                public String name() { return name; }
                public long size() { return b.length; }
                public InputStream open() { return new ByteArrayInputStream(b); }
            });
        }
        pushState();
    }

    /** Sumber file dari content:// (nama & ukuran dari penyedia; kalau ukuran tidak diketahui, disalin dulu). */
    private Core.Source source(Uri u) throws Exception {
        String name = null;
        long size = -1;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
                if (!c.isNull(1)) size = c.getLong(1);
            }
        }
        if (name == null || name.isEmpty()) name = u.getLastPathSegment() == null ? "file" : u.getLastPathSegment();
        if (size < 0) {
            try (AssetFileDescriptor fd = getContentResolver().openAssetFileDescriptor(u, "r")) { if (fd != null) size = fd.getLength(); }
        }
        if (size < 0) {
            File tmp = File.createTempFile("kirim", null, getCacheDir());
            try (InputStream in = getContentResolver().openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("tidak bisa dibuka");
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            }
            tmp.deleteOnExit();
            final String nm = name;
            final long sz = tmp.length();
            return new Core.Source() {
                public String name() { return nm; }
                public long size() { return sz; }
                public InputStream open() throws IOException { return new FileInputStream(tmp); }
            };
        }
        final String nm = name;
        final long sz = size;
        return new Core.Source() {
            public String name() { return nm; }
            public long size() { return sz; }
            public InputStream open() throws IOException {
                InputStream in = getContentResolver().openInputStream(u);
                if (in == null) throw new IOException("File tidak bisa dibuka");
                return new java.io.BufferedInputStream(in, 1 << 18);
            }
        };
    }

    // ---------- pemilih file ----------
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK) return;
        String id = pickFor;
        int rid = pickReq;
        pickFor = null;
        if (res != RESULT_OK || data == null || id == null) { reply(rid, true, "null"); return; }
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        bg.execute(() -> {
            List<Core.Source> src = new ArrayList<>();
            for (Uri u : uris) { try { src.add(source(u)); } catch (Exception e) { toast("File tidak bisa dibaca: " + e.getMessage()); } }
            Core c = core();
            if (c != null && !src.isEmpty()) c.send(id, src);
            reply(rid, true, "true");
        });
    }

    // ---------- jawaban ke tampilan ----------
    private void reply(int id, boolean ok, String json) {
        main.post(() -> web.evaluateJavascript("window.__rkRes&&__rkRes(" + id + "," + ok + "," + json + ")", null));
    }
    private void replyErr(int id, Exception e) {
        String m = e.getMessage() == null ? "Gagal" : e.getMessage();
        reply(id, false, JSONObject.quote(m));
    }

    private final class Bridge {
        @JavascriptInterface
        public void call(int id, String m, String argsJson) {
            JSONArray a;
            try { a = new JSONArray(argsJson); } catch (Exception e) { a = new JSONArray(); }
            final JSONArray args = a;
            if (m.equals("send")) { main.post(() -> pick(id, args.optString(0))); return; }
            if (m.equals("scanQr")) { main.post(() -> scan(id)); return; }
            bg.execute(() -> {
                Core c = core();
                try {
                    if (c == null) throw new Exception("Ruang Kirim belum siap, coba lagi sebentar.");
                    reply(id, true, run(c, m, args));
                } catch (Exception e) { replyErr(id, e); }
            });
        }
    }

    private String run(Core c, String m, JSONArray a) throws Exception {
        switch (m) {
            case "state": { JSONObject s = c.state(); s.put("pending", pendingJson()); return s.toString(); }
            case "sendPending": {
                List<Core.Source> src;
                synchronized (pending) { src = new ArrayList<>(pending); pending.clear(); }
                if (src.isEmpty()) throw new Exception("Tidak ada file yang menunggu dikirim");
                c.send(a.optString(0), src);
                pushState();
                return "true";
            }
            case "clearPending": synchronized (pending) { pending.clear(); } pushState(); return "true";
            case "pair": return JSONObject.quote(c.pair(a.optString(0)));
            case "pairCode": return JSONObject.quote(c.pairCode(a.optString(0)));
            case "respond": c.respond(a.optString(0), a.optBoolean(1)); return "true";
            case "cancel": c.cancel(a.optString(0)); return "true";
            case "forget": c.forget(a.optString(0)); return "true";
            case "rename": c.rename(a.optString(0)); return "true";
            case "setting": c.setting(a.optString(0), a.optBoolean(1)); return "true";
            case "addIp": return JSONObject.quote(c.addIp(a.optString(0)));
            case "rescan": c.rescan(); return "true";
            case "clearDone": c.clearDone(); return "true";
            case "open": return open(c, a.optString(0), a.optInt(1)) ? "true" : "false";
            case "openDir": {
                main.post(() -> {
                    try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                    catch (Exception e) { toast("File ada di folder Download/RuangKirim."); }
                });
                return "true";
            }
            case "quit": { KirimService k = KirimService.inst; if (k != null) main.post(k::shutdown); else main.post(this::finishAndRemoveTask); return "true"; }
            case "version": {
                try { return JSONObject.quote(getPackageManager().getPackageInfo(getPackageName(), 0).versionName); } catch (Exception e) { return "\"\""; }
            }
            default: throw new Exception("Fitur ini tidak ada di versi HP");
        }
    }

    private void pick(int id, String deviceId) {
        pickFor = deviceId;
        pickReq = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); }
        catch (Exception e) { pickFor = null; replyErr(id, new Exception("Pemilih file tidak tersedia")); }
    }

    private boolean open(Core c, String tid, int idx) {
        Core.Transfer t = c.find(tid);
        if (t == null) return false;
        String where;
        synchronized (t.saved) { if (idx < 0 || idx >= t.saved.size()) return false; where = t.saved.get(idx); }
        if (!where.startsWith("content:")) { toast("Tersimpan di " + where); return true; }
        Uri u = Uri.parse(where);
        String type = getContentResolver().getType(u);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, type != null ? type : "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        main.post(() -> {
            try { startActivity(Intent.createChooser(i, "Buka dengan").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (Exception e) { toast("Tidak ada aplikasi untuk membuka file ini. File ada di Download/RuangKirim."); }
        });
        return true;
    }

    // ---------- pindai QR (Layanan Google Play, tanpa izin kamera) ----------
    private void scan(int id) {
        GmsBarcodeScannerOptions opt = new GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
        GmsBarcodeScanner sc;
        try { sc = GmsBarcodeScanning.getClient(this, opt); }
        catch (Exception e) { replyErr(id, new Exception("Pemindai QR tidak tersedia di HP ini. Pakai tombol Pasangkan di kartu PC (cocokkan kode angka).")); return; }
        sc.startScan()
                .addOnSuccessListener(b -> {
                    String raw = b.getRawValue();
                    bg.execute(() -> {
                        Core c = core();
                        try {
                            if (c == null) throw new Exception("Ruang Kirim belum siap");
                            reply(id, true, JSONObject.quote(c.pairCode(raw)));
                        } catch (Exception e) { replyErr(id, e); }
                    });
                })
                .addOnCanceledListener(() -> reply(id, true, "null"))
                .addOnFailureListener(e -> replyErr(id, new Exception("Pemindai QR tidak bisa dibuka (butuh Layanan Google Play). Pakai tombol Pasangkan di kartu PC.")));
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) { o.write(b, 0, n); if (o.size() > max) throw new IOException("terlalu besar"); }
        return o.toByteArray();
    }
}
