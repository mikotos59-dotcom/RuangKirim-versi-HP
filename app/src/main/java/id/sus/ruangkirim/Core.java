package id.sus.ruangkirim;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Mesin Ruang Kirim versi HP. Sama perilakunya dengan lib/core.js versi PC; tampilan (WebView) membaca state()
 * dalam bentuk JSON yang sama. Bagian khusus Android (menyimpan file, alamat WiFi) lewat {@link Platform}.
 */
final class Core {
    static final int UDP_PORT = 47820, TCP_PORT = 47821;
    static final long ONLINE_MS = 15000, PROMPT_MS = 90000;

    interface Sink { OutputStream out(); String commit() throws IOException; void abort(); }
    interface Source { String name(); long size(); InputStream open() throws IOException; }
    interface Platform {
        String loadConfig();
        void saveConfig(String json);
        Sink create(String relName, long size) throws IOException;
        List<String> ips();
        List<String> broadcasts();
        void onState(JSONObject state);
        void onPrompt(JSONObject prompt);
        void log(String s);
    }

    static final class Device { String id, name, type, ip; int port; long seen; }
    static final class Transfer {
        String id, dir, status = "menyambung", err = "", current = "", msg = "";
        Proto.Peer peer = new Proto.Peer();
        List<String[]> files = new ArrayList<>(); // [nama, ukuran]
        long total, done, speed, at = System.currentTimeMillis(), t0, tl, dl;
        boolean known;
        volatile boolean cancelled;
        volatile Proto.Session sess;
        final List<String> saved = new ArrayList<>();
        boolean active() { return status.equals("menyambung") || status.equals("menunggu") || status.equals("jalan"); }
    }
    static class Prompt {
        final JSONObject data; volatile Boolean answer; final Object lock = new Object();
        Prompt(JSONObject d) { data = d; }
        void done(boolean v) { synchronized (lock) { if (answer == null) answer = v; lock.notifyAll(); } }
    }

    private final Platform pf;
    final String type;
    private final int udpPort, tcpPort;
    Proto.Identity me;
    private JSONObject cfg;
    private final Map<String, Device> nearby = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<Transfer> transfers = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Prompt> prompts = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final SecureRandom rnd = new SecureRandom();
    private int seq = 0;
    int port;
    private ServerSocket server;
    private DatagramSocket udp;
    private volatile boolean running, emitPending;

    Core(Platform pf, String type, String defaultName, int udpPort, int tcpPort) {
        this.pf = pf; this.type = type; this.udpPort = udpPort; this.tcpPort = tcpPort;
        load(defaultName);
    }

    private synchronized String next(String p) { return p + (++seq); }

    // ---------- simpan / muat ----------
    private void load(String defaultName) {
        JSONObject c = null;
        try { String t = pf.loadConfig(); if (t != null) c = new JSONObject(t); } catch (Exception ignored) { }
        if (c == null) c = new JSONObject();
        try {
            if (c.has("priv") && c.has("pub")) me = Proto.Identity.load(c.getString("priv"), c.getString("pub"));
        } catch (Exception ignored) { }
        try {
            boolean fresh = me == null;
            if (fresh) me = Proto.Identity.create();
            c.put("priv", me.privB64()); c.put("pub", me.pubB64());
            JSONObject s = c.optJSONObject("settings");
            if (s == null) s = new JSONObject();
            if (!s.has("name")) s.put("name", defaultName);
            if (!s.has("autoKnown")) s.put("autoKnown", false);
            if (!s.has("onlyKnown")) s.put("onlyKnown", false);
            if (!s.has("keepBasket")) s.put("keepBasket", false);
            s.put("saveDir", "Download/RuangKirim");
            c.put("settings", s);
            if (c.optJSONObject("known") == null) c.put("known", new JSONObject());
            cfg = c;
            if (fresh) save();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private synchronized void save() { pf.saveConfig(cfg.toString()); }
    JSONObject settings() { return cfg.optJSONObject("settings"); }
    String name() { return settings().optString("name", "HP"); }
    private JSONObject known() { return cfg.optJSONObject("known"); }
    boolean isKnown(String id) { return known().has(id); }

    // ---------- mulai ----------
    void start() throws IOException {
        running = true;
        try { server = new ServerSocket(tcpPort); }
        catch (IOException e) { server = new ServerSocket(0); } // port bawaan dipakai aplikasi lain
        port = server.getLocalPort();
        pool.execute(this::acceptLoop);
        try {
            udp = new DatagramSocket(null);
            udp.setReuseAddress(true);
            udp.setBroadcast(true);
            udp.bind(new InetSocketAddress(udpPort));
            pool.execute(this::udpLoop);
        } catch (IOException e) { pf.log("UDP: " + e.getMessage()); }
        timer.scheduleWithFixedDelay(() -> { announce("hai"); emit(); }, 4, 4, TimeUnit.SECONDS);
        pool.execute(() -> announce("cari"));
        emit();
    }
    void stop() {
        running = false;
        try { server.close(); } catch (Exception ignored) { }
        try { udp.close(); } catch (Exception ignored) { }
        timer.shutdownNow();
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> incoming(s));
            } catch (IOException e) { if (!running) return; }
        }
    }

    // ---------- pencarian di WiFi ----------
    private void udpLoop() {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                JSONObject j = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                String id = j.optString("id");
                if (j.optInt("rk") != 1 || id.equals(me.id) || !id.matches("[0-9a-f]{16}")) continue;
                Proto.Peer peer = new Proto.Peer();
                peer.id = id;
                String nm = j.optString("name", "Perangkat");
                peer.name = nm.length() > 60 ? nm.substring(0, 60) : nm;
                peer.type = "hp".equals(j.optString("type")) ? "hp" : "pc";
                peer.port = j.optInt("port", TCP_PORT);
                seen(peer, p.getAddress().getHostAddress());
                if ("cari".equals(j.optString("t"))) udpSend("hai", p.getAddress());
            } catch (Exception e) { if (!running) return; }
        }
    }
    private void udpSend(String t, InetAddress to) {
        try {
            JSONObject j = new JSONObject();
            j.put("rk", 1); j.put("t", t); j.put("id", me.id); j.put("name", name()); j.put("type", type); j.put("port", port);
            byte[] b = j.toString().getBytes(StandardCharsets.UTF_8);
            if (udp != null) udp.send(new DatagramPacket(b, b.length, to, udpPort));
        } catch (Exception ignored) { }
    }
    void announce(String t) {
        for (String b : pf.broadcasts()) { try { udpSend(t, InetAddress.getByName(b)); } catch (Exception ignored) { } }
    }
    void rescan() {
        synchronized (nearby) { nearby.values().removeIf(d -> System.currentTimeMillis() - d.seen > 5000); }
        pool.execute(() -> announce("cari"));
        emit();
    }
    void seen(Proto.Peer p, String ip) {
        if (ip == null || p.id.equals(me.id)) return;
        ip = ip.replaceFirst("^::ffff:", "");
        Device d = nearby.get(p.id);
        if (d == null) d = new Device();
        int prevPort = d.port;
        d.id = p.id; d.name = p.name; d.type = p.type; d.ip = ip;
        d.port = p.port > 0 ? p.port : (prevPort > 0 ? prevPort : TCP_PORT);
        d.seen = System.currentTimeMillis();
        nearby.put(p.id, d);
        JSONObject k = known().optJSONObject(p.id);
        if (k != null && (!ip.equals(k.optString("ip")) || !p.name.equals(k.optString("name")) || (p.port > 0 && k.optInt("port") != p.port))) {
            try { k.put("ip", ip); k.put("name", p.name); if (p.port > 0) k.put("port", p.port); } catch (Exception ignored) { }
            save();
        }
        emit();
    }

    // ---------- state untuk tampilan ----------
    JSONArray devices() throws Exception {
        long now = System.currentTimeMillis();
        Map<String, JSONObject> out = new LinkedHashMap<>();
        JSONObject kn = known();
        for (Iterator<String> it = kn.keys(); it.hasNext(); ) {
            JSONObject k = kn.getJSONObject(it.next());
            JSONObject d = new JSONObject();
            d.put("id", k.optString("id")); d.put("name", k.optString("name")); d.put("type", k.optString("type"));
            d.put("ip", k.optString("ip")); d.put("port", k.optInt("port", TCP_PORT)); d.put("known", true); d.put("online", false);
            d.put("fp", Proto.fmtId(k.optString("id")));
            out.put(k.optString("id"), d);
        }
        List<Device> list;
        synchronized (nearby) { list = new ArrayList<>(nearby.values()); }
        for (Device n : list) {
            boolean on = now - n.seen < ONLINE_MS;
            if (!on && !out.containsKey(n.id)) continue;
            JSONObject d = out.get(n.id);
            if (d == null) { d = new JSONObject(); d.put("known", false); d.put("fp", Proto.fmtId(n.id)); }
            d.put("id", n.id); d.put("name", n.name); d.put("type", n.type); d.put("ip", n.ip); d.put("port", n.port); d.put("online", on);
            out.put(n.id, d);
        }
        List<JSONObject> arr = new ArrayList<>(out.values());
        arr.sort((a, b) -> {
            int c = Boolean.compare(b.optBoolean("online"), a.optBoolean("online"));
            if (c != 0) return c;
            c = Boolean.compare(b.optBoolean("known"), a.optBoolean("known"));
            return c != 0 ? c : a.optString("name").compareToIgnoreCase(b.optString("name"));
        });
        JSONArray r = new JSONArray();
        for (JSONObject o : arr) r.put(o);
        return r;
    }
    JSONObject state() {
        try {
            JSONObject s = new JSONObject();
            JSONObject m = new JSONObject();
            m.put("id", me.id); m.put("fp", Proto.fmtId(me.id)); m.put("name", name()); m.put("type", type); m.put("port", port);
            m.put("ips", new JSONArray(pf.ips()));
            s.put("me", m);
            s.put("settings", new JSONObject(settings().toString()));
            s.put("devices", devices());
            JSONArray ts = new JSONArray();
            List<Transfer> tl;
            synchronized (transfers) { tl = new ArrayList<>(transfers); }
            for (Transfer t : tl) ts.put(transferJson(t));
            s.put("transfers", ts);
            JSONArray ps = new JSONArray();
            synchronized (prompts) { for (Prompt p : prompts.values()) ps.put(p.data); }
            s.put("prompts", ps);
            return s;
        } catch (Exception e) { return new JSONObject(); }
    }
    private JSONObject transferJson(Transfer t) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", t.id); o.put("dir", t.dir);
        JSONObject p = new JSONObject(); p.put("id", t.peer.id); p.put("name", t.peer.name); p.put("type", t.peer.type);
        o.put("peer", p);
        JSONArray fs = new JSONArray();
        for (int i = 0; i < Math.min(50, t.files.size()); i++) { JSONObject f = new JSONObject(); f.put("n", t.files.get(i)[0]); f.put("s", Long.parseLong(t.files.get(i)[1])); fs.put(f); }
        o.put("files", fs); o.put("count", t.files.size()); o.put("total", t.total); o.put("done", t.done);
        o.put("status", t.status); o.put("err", t.err); o.put("speed", t.speed); o.put("current", t.current);
        synchronized (t.saved) { o.put("saved", new JSONArray(t.saved)); }
        o.put("at", t.at); o.put("known", t.known); o.put("msg", t.msg);
        return o;
    }
    void emit() {
        if (emitPending) return;
        emitPending = true;
        try {
            timer.schedule(() -> { emitPending = false; pf.onState(state()); }, 150, TimeUnit.MILLISECONDS);
        } catch (Exception e) { emitPending = false; }
    }

    // ---------- permintaan Ya/Tidak ----------
    private boolean ask(JSONObject data, Proto.Session sess) throws Exception {
        String rid = next("p");
        data.put("rid", rid);
        data.put("expires", System.currentTimeMillis() + PROMPT_MS);
        Prompt p = new Prompt(data);
        prompts.put(rid, p);
        emit();
        pf.onPrompt(data);
        long end = System.currentTimeMillis() + PROMPT_MS;
        try {
            synchronized (p.lock) {
                while (p.answer == null && System.currentTimeMillis() < end) {
                    p.lock.wait(300);
                    // pengirim membatalkan saat masih menunggu jawaban
                    if (p.answer == null && sess != null && sess.pending()) {
                        try { Proto.Msg m = sess.recv(1000); if ("batal".equals(m.t())) p.answer = false; } catch (IOException e) { p.answer = false; }
                    }
                }
            }
        } finally { prompts.remove(rid); emit(); }
        return Boolean.TRUE.equals(p.answer);
    }
    void respond(String rid, boolean yes) { Prompt p = prompts.get(rid); if (p != null) p.done(yes); }

    // ---------- perangkat dikenal & pengaturan ----------
    private void addKnown(Proto.Peer peer, String ip) {
        try {
            JSONObject k = new JSONObject();
            k.put("id", peer.id); k.put("name", peer.name); k.put("type", peer.type); k.put("pub", peer.pub);
            k.put("ip", ip); k.put("port", peer.port > 0 ? peer.port : TCP_PORT); k.put("added", System.currentTimeMillis());
            known().put(peer.id, k);
        } catch (Exception ignored) { }
        save(); emit();
    }
    void forget(String id) { known().remove(id); save(); emit(); }
    void rename(String n) {
        n = n == null ? "" : n.trim();
        if (n.isEmpty()) return;
        if (n.length() > 40) n = n.substring(0, 40);
        try { settings().put("name", n); } catch (Exception ignored) { }
        save(); pool.execute(() -> announce("hai")); emit();
    }
    void setting(String k, boolean v) {
        if (!k.equals("autoKnown") && !k.equals("onlyKnown") && !k.equals("keepBasket")) return;
        try { settings().put(k, v); } catch (Exception ignored) { }
        save(); emit();
    }

    // ---------- sambung keluar ----------
    static final class Conn { Proto.Hs hs; String ip; }
    private Conn open(String ip, int port, String wantId) throws Exception {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 8000);
        } catch (java.net.ConnectException e) {
            try { s.close(); } catch (Exception ignored) { }
            throw new Exception("Ruang Kirim tidak terbuka di " + ip + ".");
        } catch (IOException e) {
            try { s.close(); } catch (Exception ignored) { }
            throw new Exception("Tidak bisa menyambung ke " + ip + ". Pastikan satu WiFi dan Ruang Kirim terbuka di sana.");
        }
        try {
            s.setTcpNoDelay(true);
            Proto.Hs h = Proto.handshake(s, me, name(), type, port(), true);
            if (wantId != null && !wantId.equals(h.peer.id)) throw new Exception("Perangkat di alamat itu bukan perangkat yang dipilih (alamatnya mungkin berubah). Coba Cari ulang.");
            seen(h.peer, ip);
            Conn c = new Conn();
            c.hs = h; c.ip = ip;
            return c;
        } catch (Exception e) { try { s.close(); } catch (Exception ignored) { } throw e; }
    }
    private int port() { return port; }
    private String[] target(String id) throws Exception {
        Device d = nearby.get(id);
        JSONObject k = known().optJSONObject(id);
        String ip = d != null && d.ip != null ? d.ip : k != null ? k.optString("ip", null) : null;
        int p = d != null && d.port > 0 ? d.port : k != null ? k.optInt("port", TCP_PORT) : TCP_PORT;
        if (ip == null || ip.isEmpty()) throw new Exception("Alamat perangkat belum diketahui. Buka Ruang Kirim di perangkat itu, lalu Cari ulang.");
        return new String[]{ip, String.valueOf(p)};
    }

    String addIp(String text) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d+))?$").matcher(text == null ? "" : text.trim());
        if (!m.matches()) throw new Exception("Tulis alamat seperti 192.168.1.7");
        Conn c = open(m.group(1), m.group(2) != null ? Integer.parseInt(m.group(2)) : TCP_PORT, null);
        try { c.hs.sess.sendJson("tutup"); } catch (Exception ignored) { }
        c.hs.sess.close();
        return c.hs.peer.name;
    }

    /** Pasangkan dengan membandingkan kode 6 digit di kedua layar. */
    String pair(String id) throws Exception {
        String[] t = target(id);
        Conn c = open(t[0], Integer.parseInt(t[1]), id);
        String rid = next("w");
        JSONObject d = new JSONObject();
        d.put("rid", rid); d.put("kind", "tunggu"); d.put("code", c.hs.code);
        JSONObject from = new JSONObject(); from.put("id", id); from.put("name", c.hs.peer.name); from.put("type", c.hs.peer.type);
        d.put("from", from);
        Prompt p = new Prompt(d) {
            @Override void done(boolean v) { super.done(v); c.hs.sess.kill(); }
        };
        prompts.put(rid, p);
        emit();
        try {
            c.hs.sess.sendJson("pasang");
            Proto.Msg r = c.hs.sess.recv((int) PROMPT_MS + 5000);
            if ("dipasang".equals(r.t())) { addKnown(c.hs.peer, c.ip); return c.hs.peer.name; }
            throw new Exception(r.json != null && r.json.has("why") ? r.json.optString("why") : "Pemasangan ditolak");
        } catch (IOException e) {
            if (p.answer != null) throw new Exception("Pemasangan dibatalkan");
            throw e;
        } finally { prompts.remove(rid); emit(); c.hs.sess.close(); }
    }

    /** Pasangkan dengan kode QR dari PC (RK1:...). */
    String pairCode(String text) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^RK1:([A-Za-z0-9_-]+)$").matcher(text == null ? "" : text.trim());
        if (!m.matches()) throw new Exception("Kode tidak dikenali. Kode Ruang Kirim diawali RK1:");
        JSONObject j;
        try { j = new JSONObject(new String(Base64.getUrlDecoder().decode(m.group(1)), StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new Exception("Kode rusak"); }
        byte[] pub;
        try { pub = Base64.getDecoder().decode(j.optString("k")); Proto.pubFromRaw(pub); } catch (Exception e) { throw new Exception("Kode rusak"); }
        String id = Proto.idOf(pub);
        JSONArray a = j.optJSONArray("a");
        Exception last = null;
        for (int i = 0; a != null && i < Math.min(6, a.length()); i++) {
            String ip = a.optString(i);
            Conn c;
            try { c = open(ip, j.optInt("p", TCP_PORT), id); } catch (Exception e) { last = e; continue; }
            try {
                JSONObject q = new JSONObject(); q.put("t", "pasang"); q.put("tok", j.optString("t"));
                c.hs.sess.sendJson(q);
                Proto.Msg r = c.hs.sess.recv((int) PROMPT_MS + 5000);
                if ("dipasang".equals(r.t())) { addKnown(c.hs.peer, ip); return c.hs.peer.name; }
                throw new Exception(r.json != null && r.json.has("why") ? r.json.optString("why") : "Pemasangan ditolak");
            } finally { c.hs.sess.close(); }
        }
        throw last != null ? last : new Exception("Perangkat dari kode itu tidak ditemukan di WiFi ini");
    }

    // ---------- kirim ----------
    private Transfer newTransfer(String dir, String pid, String pname, String ptype, List<String[]> files, boolean known) {
        Transfer t = new Transfer();
        t.id = next("t"); t.dir = dir; t.peer.id = pid; t.peer.name = pname; t.peer.type = ptype; t.known = known;
        t.files = files;
        for (String[] f : files) t.total += Long.parseLong(f[1]);
        synchronized (transfers) {
            transfers.add(0, t);
            while (transfers.size() > 60) transfers.remove(transfers.size() - 1);
        }
        emit();
        return t;
    }
    private void progress(Transfer t, long n) {
        t.done += n;
        long now = System.currentTimeMillis();
        if (t.t0 == 0) { t.t0 = now; t.tl = now; }
        if (now - t.tl > 500) {
            double dt = (now - t.tl) / 1000.0;
            t.speed = Math.round(((t.done - t.dl) / dt) * 0.6 + t.speed * 0.4);
            t.tl = now; t.dl = t.done;
            emit();
        }
    }
    Transfer find(String tid) { synchronized (transfers) { for (Transfer t : transfers) if (t.id.equals(tid)) return t; } return null; }
    void cancel(String tid) {
        Transfer t = find(tid);
        if (t == null || !t.active()) return;
        t.cancelled = true;
        synchronized (prompts) { for (Prompt p : prompts.values()) if (tid.equals(p.data.optString("tid"))) p.done(false); }
        Proto.Session s = t.sess;
        if (s != null) pool.execute(() -> { try { s.sendJson("batal"); Thread.sleep(300); } catch (Exception ignored) { } s.kill(); });
        t.status = "batal"; emit();
    }
    void clearDone() { synchronized (transfers) { transfers.removeIf(t -> !t.active()); } emit(); }
    boolean busy() { synchronized (transfers) { for (Transfer t : transfers) if (t.active()) return true; } return false; }

    /** src boleh kosong kalau hanya mengirim pesan. */
    void send(String id, List<Source> src, String msg) {
        String m = msg == null ? "" : (msg.length() > 20000 ? msg.substring(0, 20000) : msg);
        List<String[]> files = new ArrayList<>();
        for (Source s : src) files.add(new String[]{Core.safeRel(s.name()), String.valueOf(Math.max(0, s.size()))});
        String pname = "Perangkat", ptype = "pc";
        Device d = nearby.get(id);
        JSONObject k = known().optJSONObject(id);
        if (d != null) { pname = d.name; ptype = d.type; } else if (k != null) { pname = k.optString("name"); ptype = k.optString("type"); }
        Transfer t = newTransfer("kirim", id, pname, ptype, files, isKnown(id));
        t.msg = m;
        pool.execute(() -> sendRun(t, id, src));
    }
    private void sendRun(Transfer t, String id, List<Source> src) {
        Conn c = null;
        try {
            String[] tg = target(id);
            c = open(tg[0], Integer.parseInt(tg[1]), id);
            Proto.Session s = c.hs.sess;
            t.sess = s; t.peer.name = c.hs.peer.name;
            if (t.cancelled) throw new Exception("batal");
            t.status = "menunggu"; emit();
            JSONObject offer = new JSONObject();
            offer.put("t", "kirim");
            JSONArray fa = new JSONArray();
            for (String[] f : t.files) { JSONObject o = new JSONObject(); o.put("n", f[0]); o.put("s", Long.parseLong(f[1])); fa.put(o); }
            offer.put("files", fa); offer.put("total", t.total); offer.put("msg", t.msg);
            s.sendJson(offer);
            Proto.Msg r = s.recv((int) PROMPT_MS + 10000);
            if (!"terima".equals(r.t())) { t.status = "ditolak"; t.err = r.json != null ? r.json.optString("why", "Ditolak") : "Ditolak"; emit(); return; }
            t.status = "jalan"; emit();
            byte[] buf = new byte[Proto.CHUNK];
            for (int i = 0; i < src.size(); i++) {
                long size = Long.parseLong(t.files.get(i)[1]);
                t.current = t.files.get(i)[0];
                long sent = 0;
                try (InputStream in = src.get(i).open()) {
                    while (sent < size) {
                        if (t.cancelled) throw new Exception("batal");
                        int want = (int) Math.min(buf.length, size - sent), got = 0;
                        while (got < want) { int n = in.read(buf, got, want - got); if (n < 0) break; got += n; }
                        if (got <= 0) break;
                        s.send(Proto.T_DATA, buf, 0, got);
                        sent += got;
                        progress(t, got);
                        if (s.pending()) { Proto.Msg m = s.recv(1000); if ("batal".equals(m.t())) throw new Exception("Dibatalkan oleh penerima"); }
                    }
                }
                if (sent < size) throw new Exception("File " + t.current + " berubah saat dikirim");
            }
            s.sendJson("selesai");
            Proto.Msg ok = s.recv(60000);
            if (!"ok".equals(ok.t())) throw new Exception(ok.json != null ? ok.json.optString("why", "Penerima tidak mengonfirmasi") : "Penerima tidak mengonfirmasi");
            t.status = "selesai"; t.current = ""; emit();
        } catch (Exception e) {
            if (!t.status.equals("batal")) {
                String msg = e.getMessage() == null ? "Gagal" : e.getMessage();
                t.status = t.cancelled || msg.startsWith("Dibatalkan") ? "batal" : "gagal";
                t.err = t.cancelled ? "" : msg;
            }
            emit();
        } finally { t.sess = null; if (c != null) c.hs.sess.close(); }
    }

    // ---------- terima ----------
    private void incoming(Socket sock) {
        String ip = sock.getInetAddress().getHostAddress().replaceFirst("^::ffff:", "");
        Proto.Hs h;
        try { sock.setTcpNoDelay(true); h = Proto.handshake(sock, me, name(), type, port, false); }
        catch (Exception e) { try { sock.close(); } catch (Exception ignored) { } return; }
        seen(h.peer, ip);
        try {
            Proto.Msg m = h.sess.recv(30000);
            if ("pasang".equals(m.t())) inPair(h, m.json, ip);
            else if ("kirim".equals(m.t())) inReceive(h, m.json, ip);
        } catch (Exception ignored) {
        } finally { h.sess.close(); }
    }

    private void inPair(Proto.Hs h, JSONObject j, String ip) throws Exception {
        if (j.has("tok") && !j.optString("tok").isEmpty()) {
            JSONObject no = new JSONObject(); no.put("t", "tolak"); no.put("why", "Perangkat ini tidak menampilkan kode QR. Pasangkan lewat kode angka.");
            h.sess.sendJson(no);
            return;
        }
        JSONObject d = new JSONObject();
        d.put("kind", "pasang"); d.put("code", h.code); d.put("from", fromJson(h.peer, ip));
        boolean yes = ask(d, h.sess);
        if (!yes) { JSONObject no = new JSONObject(); no.put("t", "tolak"); no.put("why", "Pemasangan ditolak di perangkat tujuan"); h.sess.sendJson(no); return; }
        addKnown(h.peer, ip);
        JSONObject ok = new JSONObject(); ok.put("t", "dipasang"); ok.put("name", name());
        h.sess.sendJson(ok);
    }
    private JSONObject fromJson(Proto.Peer p, String ip) throws Exception {
        JSONObject f = new JSONObject();
        f.put("id", p.id); f.put("name", p.name); f.put("type", p.type); f.put("ip", ip); f.put("fp", Proto.fmtId(p.id)); f.put("known", isKnown(p.id));
        return f;
    }

    private void inReceive(Proto.Hs h, JSONObject j, String ip) throws Exception {
        Proto.Session s = h.sess;
        JSONArray raw = j.optJSONArray("files");
        String msg = j.optString("msg", "");
        if (msg.length() > 20000) msg = msg.substring(0, 20000);
        if (raw == null) raw = new JSONArray();
        if ((raw.length() == 0 && msg.trim().isEmpty()) || raw.length() > 5000) { tolak(s, "Daftar file tidak sah"); return; }
        List<String[]> files = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject f = raw.optJSONObject(i);
            long sz = f == null ? -1 : f.optLong("s", -1);
            if (sz < 0 || sz > (1L << 42)) { tolak(s, "Ukuran file tidak sah"); return; }
            files.add(new String[]{safeRel(f.optString("n")), String.valueOf(sz)});
            total += sz;
        }
        boolean known = isKnown(h.peer.id);
        if (settings().optBoolean("onlyKnown") && !known) { tolak(s, "Perangkat tujuan hanya menerima dari perangkat dikenal"); return; }
        Transfer t = newTransfer("terima", h.peer.id, h.peer.name, h.peer.type, files, known);
        t.msg = msg;
        t.status = "menunggu"; t.sess = s;
        boolean yes;
        if (known && settings().optBoolean("autoKnown")) yes = true;
        else {
            JSONObject d = new JSONObject();
            d.put("kind", "kirim"); d.put("code", h.code); d.put("from", fromJson(h.peer, ip));
            JSONArray fl = new JSONArray();
            for (int i = 0; i < Math.min(30, files.size()); i++) { JSONObject o = new JSONObject(); o.put("n", files.get(i)[0]); o.put("s", Long.parseLong(files.get(i)[1])); fl.put(o); }
            d.put("files", fl); d.put("count", files.size()); d.put("total", total); d.put("tid", t.id); d.put("msg", msg);
            yes = ask(d, s);
        }
        if (!yes || t.cancelled) {
            t.status = "ditolak"; t.sess = null; emit();
            try { tolak(s, t.cancelled ? "Dibatalkan" : "Ditolak oleh penerima"); } catch (Exception ignored) { }
            return;
        }
        Sink sink = null;
        try {
            s.sendJson("terima");
            t.status = "jalan"; emit();
            for (String[] f : files) {
                t.current = f[0];
                long left = Long.parseLong(f[1]);
                sink = pf.create(f[0], left);
                OutputStream out = sink.out();
                while (left > 0) {
                    Proto.Msg m = s.recv(60000);
                    if (t.cancelled) throw new Exception("batal");
                    if (m.json != null) throw new Exception("batal".equals(m.t()) ? "Dibatalkan oleh pengirim" : "Pesan tidak terduga");
                    if (m.data.length > left) throw new Exception("Data melebihi ukuran file");
                    out.write(m.data);
                    left -= m.data.length;
                    progress(t, m.data.length);
                }
                String where = sink.commit();
                sink = null;
                synchronized (t.saved) { t.saved.add(where); }
            }
            Proto.Msg end = s.recv(60000);
            if (!"selesai".equals(end.t())) throw new Exception("Pengirim berhenti sebelum selesai");
            s.sendJson("ok");
            t.status = "selesai"; t.current = ""; emit();
        } catch (Exception e) {
            if (sink != null) sink.abort();
            if (!t.status.equals("batal")) {
                String em = e.getMessage() == null ? "Gagal" : e.getMessage();
                t.status = em.startsWith("Dibatalkan") ? "batal" : "gagal";
                t.err = em;
            }
            emit();
        } finally { t.sess = null; }
    }
    private static void tolak(Proto.Session s, String why) throws Exception {
        JSONObject o = new JSONObject(); o.put("t", "tolak"); o.put("why", why); s.sendJson(o);
    }

    // ---------- nama file aman ----------
    static String safeName(String n) {
        String s = (n == null ? "" : n).replaceAll("[\\u0000-\\u001f<>:\"|?*\\\\/]", "_").replaceAll("^[\\s.]+|[\\s.]+$", "");
        if (s.length() > 180) s = s.substring(0, 180);
        if (s.matches("(?i)^(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?$")) s = "_" + s;
        return s.isEmpty() ? "file" : s;
    }
    static String safeRel(String r) {
        List<String> parts = new ArrayList<>();
        for (String p : (r == null ? "" : r).split("[\\\\/]+")) if (!p.isEmpty() && !p.equals(".") && !p.equals("..")) parts.add(safeName(p));
        while (parts.size() > 12) parts.remove(0);
        return parts.isEmpty() ? "file" : String.join("/", parts);
    }

    static String sha(String s) {
        try { return Proto.toHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { return ""; }
    }
}
