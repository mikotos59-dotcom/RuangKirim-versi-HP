package id.sus.ruangkirim;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protokol Ruang Kirim v1, sama persis dengan lib/proto.js versi PC (lihat PROTOKOL.md).
 * ECDH P-256 (kunci tetap + kunci sementara) → HKDF-SHA256 → AES-256-GCM per frame.
 */
final class Proto {
    static final int MAX_FRAME = 1 << 20;
    static final int CHUNK = 256 * 1024;
    static final byte T_JSON = 1, T_DATA = 2;
    private static final byte[] SPKI = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200");

    private Proto() { }

    static byte[] hex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
    static String toHex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 0xff));
        return s.toString();
    }
    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) d.update(p);
            return d.digest();
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static String idOf(byte[] pub) { return toHex(sha256(pub)).substring(0, 16); }
    static String fmtId(String id) {
        String s = id == null ? "" : id.toUpperCase();
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { if (i > 0 && i % 4 == 0) o.append(' '); o.append(s.charAt(i)); }
        return o.toString();
    }
    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] o = new byte[n];
        int i = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, o, i, p.length); i += p.length; }
        return o;
    }
    static byte[] u32(int v) { return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v}; }

    static byte[] hkdf(byte[] ikm, byte[] salt, String info, int len) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = m.doFinal(ikm);
            m.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[len], t = new byte[0];
            int pos = 0;
            for (int i = 1; pos < len; i++) {
                m.update(t);
                m.update(info.getBytes(StandardCharsets.UTF_8));
                m.update((byte) i);
                t = m.doFinal();
                int n = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, out, pos, n);
                pos += n;
            }
            return out;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ---------- kunci ----------
    static byte[] rawPub(PublicKey k) {
        java.security.spec.ECPoint w = ((ECPublicKey) k).getW();
        return concat(new byte[]{4}, pad32(w.getAffineX()), pad32(w.getAffineY()));
    }
    private static byte[] pad32(BigInteger v) {
        byte[] b = v.toByteArray(), o = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, o, 0, 32);
        else System.arraycopy(b, 0, o, 32 - b.length, b.length);
        return o;
    }
    static PublicKey pubFromRaw(byte[] raw) throws Exception {
        if (raw == null || raw.length != 65 || raw[0] != 4) throw new Exception("kunci tidak sah");
        return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(concat(SPKI, raw)));
    }
    static KeyPair newPair() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }
    static byte[] ecdh(PrivateKey priv, byte[] peerRaw) throws Exception {
        KeyAgreement a = KeyAgreement.getInstance("ECDH");
        a.init(priv);
        a.doPhase(pubFromRaw(peerRaw), true);
        return a.generateSecret();
    }

    static final class Identity {
        final PrivateKey priv;
        final byte[] pub;
        final String id;
        Identity(PrivateKey priv, byte[] pub) { this.priv = priv; this.pub = pub; this.id = idOf(pub); }
        static Identity create() throws Exception { KeyPair kp = newPair(); return new Identity(kp.getPrivate(), rawPub(kp.getPublic())); }
        static Identity load(String privB64, String pubB64) throws Exception {
            PrivateKey p = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)));
            return new Identity(p, Base64.getDecoder().decode(pubB64));
        }
        String privB64() { return Base64.getEncoder().encodeToString(priv.getEncoded()); }
        String pubB64() { return Base64.getEncoder().encodeToString(pub); }
    }

    // ---------- frame ----------
    static void writeFrame(OutputStream out, byte[] body) throws IOException {
        synchronized (out) {
            out.write(u32(body.length));
            out.write(body);
            out.flush();
        }
    }
    static byte[] readFrame(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > MAX_FRAME) throw new IOException("Frame terlalu besar");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    static final class Msg {
        final byte type; final JSONObject json; final byte[] data;
        Msg(byte type, JSONObject json, byte[] data) { this.type = type; this.json = json; this.data = data; }
        String t() { return json == null ? "" : json.optString("t"); }
    }

    static final class Session {
        final Socket sock;
        final DataInputStream in;
        final OutputStream out;
        private final SecretKeySpec kSend, kRecv;
        private long nSend = 0, nRecv = 0;

        Session(Socket sock, DataInputStream in, OutputStream out, byte[] kSend, byte[] kRecv) {
            this.sock = sock; this.in = in; this.out = out;
            this.kSend = new SecretKeySpec(kSend, "AES"); this.kRecv = new SecretKeySpec(kRecv, "AES");
        }
        private static byte[] nonce(long n) {
            byte[] b = new byte[12];
            for (int i = 0; i < 8; i++) b[11 - i] = (byte) (n >>> (8 * i));
            return b;
        }
        synchronized void send(byte type, byte[] p, int off, int len) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, kSend, new GCMParameterSpec(128, nonce(nSend++)));
                byte[] plain = new byte[len + 1];
                plain[0] = type;
                System.arraycopy(p, off, plain, 1, len);
                writeFrame(out, c.doFinal(plain));
            } catch (IOException e) { throw e; } catch (Exception e) { throw new IOException(e.getMessage()); }
        }
        void sendJson(JSONObject o) throws IOException { byte[] b = o.toString().getBytes(StandardCharsets.UTF_8); send(T_JSON, b, 0, b.length); }
        void sendJson(String t) throws IOException { try { sendJson(new JSONObject().put("t", t)); } catch (Exception e) { throw new IOException(e.getMessage()); } }
        /** ada frame yang sudah tiba (dipakai pengirim untuk mendeteksi "batal" dari penerima) */
        boolean pending() { try { return in.available() > 0; } catch (IOException e) { return true; } }

        Msg recv(int ms) throws IOException {
            sock.setSoTimeout(ms);
            byte[] f;
            try { f = readFrame(in); }
            catch (SocketTimeoutException e) { throw new IOException("Perangkat lain tidak menjawab"); }
            catch (EOFException e) { throw new IOException("Sambungan ditutup perangkat lain"); }
            if (f.length < 17) throw new IOException("Frame rusak");
            byte[] p;
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, kRecv, new GCMParameterSpec(128, nonce(nRecv++)));
                p = c.doFinal(f);
            } catch (Exception e) { throw new IOException("Data tidak bisa dibuka (kunci tidak cocok atau diubah di jalan)"); }
            if (p[0] == T_JSON) {
                try { return new Msg(T_JSON, new JSONObject(new String(p, 1, p.length - 1, StandardCharsets.UTF_8)), null); }
                catch (Exception e) { throw new IOException("Pesan rusak"); }
            }
            return new Msg(p[0], null, Arrays.copyOfRange(p, 1, p.length));
        }
        void close() {
            try { sock.shutdownOutput(); } catch (Exception ignored) { }
            new Thread(() -> { try { Thread.sleep(2000); sock.close(); } catch (Exception ignored) { } }).start();
        }
        void kill() { try { sock.close(); } catch (Exception ignored) { } }
    }

    static final class Peer {
        String id, name, type, pub; int port;
    }
    static final class Hs {
        Session sess; Peer peer; String code;
    }

    /** Jabat tangan. client = true untuk pihak yang menyambung. */
    static Hs handshake(Socket s, Identity me, String name, String type, int port, boolean client) throws Exception {
        s.setSoTimeout(15000);
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(s.getInputStream(), 1 << 16));
        OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 1 << 16);
        KeyPair eph = newPair();
        JSONObject h = new JSONObject();
        h.put("rk", 1); h.put("id", me.id); h.put("name", name); h.put("type", type); h.put("port", port);
        h.put("pub", me.pubB64()); h.put("eph", Base64.getEncoder().encodeToString(rawPub(eph.getPublic())));
        byte[] mine = h.toString().getBytes(StandardCharsets.UTF_8), theirs;
        try {
            if (client) { writeFrame(out, mine); theirs = readFrame(in); }
            else { theirs = readFrame(in); writeFrame(out, mine); }
        } catch (SocketTimeoutException e) { throw new Exception("Perangkat lain tidak menjawab"); }
        JSONObject o;
        try { o = new JSONObject(new String(theirs, StandardCharsets.UTF_8)); } catch (Exception e) { throw new Exception("Bukan perangkat Ruang Kirim"); }
        if (o.optInt("rk") != 1) throw new Exception("Versi Ruang Kirim berbeda");
        byte[] pub, epub;
        try { pub = Base64.getDecoder().decode(o.optString("pub")); epub = Base64.getDecoder().decode(o.optString("eph")); }
        catch (Exception e) { throw new Exception("Kunci perangkat lain tidak sah"); }
        if (pub.length != 65 || epub.length != 65) throw new Exception("Kunci perangkat lain tidak sah");
        if (!idOf(pub).equals(o.optString("id"))) throw new Exception("Identitas perangkat lain tidak cocok dengan kuncinya");
        if (me.id.equals(o.optString("id"))) throw new Exception("Tidak bisa menyambung ke diri sendiri");
        byte[] hc = client ? mine : theirs, hs = client ? theirs : mine;
        byte[] salt = sha256(u32(hc.length), hc, u32(hs.length), hs);
        byte[] ikm = concat(ecdh(eph.getPrivate(), epub), ecdh(me.priv, pub));
        byte[] c2s = hkdf(ikm, salt, "rk1 c2s", 32), s2c = hkdf(ikm, salt, "rk1 s2c", 32);
        byte[] k = hkdf(ikm, salt, "rk1 kode", 4);
        long v = ((k[0] & 0xffL) << 24) | ((k[1] & 0xffL) << 16) | ((k[2] & 0xffL) << 8) | (k[3] & 0xffL);
        Hs r = new Hs();
        r.code = String.format("%06d", v % 1000000);
        r.sess = client ? new Session(s, in, out, c2s, s2c) : new Session(s, in, out, s2c, c2s);
        Peer p = new Peer();
        p.id = o.optString("id");
        String nm = o.optString("name", "Perangkat");
        p.name = nm.length() > 60 ? nm.substring(0, 60) : nm;
        p.type = "hp".equals(o.optString("type")) ? "hp" : "pc";
        p.port = o.optInt("port", 0);
        p.pub = o.optString("pub");
        r.peer = p;
        return r;
    }
}
