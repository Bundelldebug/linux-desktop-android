package com.oneuibook.linux;

import android.graphics.Bitmap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.DESKeySpec;

/**
 * Pure Java RFB 3.8 VNC client (RFC 6143).
 * Supports None & VNCAuth (DES challenge-response), 32-bit BGRA framebuffer blitting,
 * Raw (0), CopyRect (1), and DesktopSize (-223) encodings, pointer, keyboard and clipboard events.
 */
final class VncClient {
    interface Callback {
        void onConnected(int w, int h, String desktopName);
        void onFrameUpdated(Bitmap framebuffer);
        void onError(String message);
        void onDisconnected();
    }

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean running;
    private int fbW, fbH;
    private Bitmap fb;
    private int[] pixels;
    private final Object writeLock = new Object();

    void connect(String host, int port, String password, Callback cb) {
        running = true;
        Thread t = new Thread(() -> {
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 8000);
                socket.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 65536));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 8192));

                // 1. ProtocolVersion handshake
                byte[] ver = new byte[12];
                in.readFully(ver);
                String sVer = new String(ver, StandardCharsets.US_ASCII);
                if (!sVer.startsWith("RFB ")) throw new RuntimeException("VNC 서버가 아닙니다: " + sVer.trim());
                out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                // 2. Security negotiation
                int numTypes = in.readUnsignedByte();
                if (numTypes == 0) throw new RuntimeException("서버 연결 거부: " + readReason());
                int chosen = 0;
                for (int i = 0; i < numTypes; i++) {
                    int ty = in.readUnsignedByte();
                    if (ty == 1 && (password == null || password.isEmpty())) chosen = 1;
                    else if (ty == 2 && chosen != 1) chosen = 2;
                    else if (ty == 1 && chosen == 0) chosen = 1;
                }
                if (chosen == 0) throw new RuntimeException("지원하지 않는 VNC 보안 방식입니다");
                out.writeByte(chosen);
                out.flush();

                if (chosen == 2) {
                    byte[] challenge = new byte[16];
                    in.readFully(challenge);
                    byte[] resp = encryptVncAuth(password == null ? "" : password, challenge);
                    out.write(resp);
                    out.flush();
                }

                int secRes = in.readInt();
                if (secRes != 0) throw new RuntimeException("VNC 인증 실패 (" + secRes + ")");

                // 3. ClientInit (shared = 1) & ServerInit
                out.writeByte(1);
                out.flush();
                fbW = in.readUnsignedShort();
                fbH = in.readUnsignedShort();
                skipExact(in, 16); // server pixel format
                int nameLen = in.readInt();
                int readLen = Math.max(0, Math.min(nameLen, 4096));
                byte[] nameBytes = new byte[readLen];
                in.readFully(nameBytes);
                if (nameLen > readLen) skipExact(in, nameLen - readLen);
                String desktopName = new String(nameBytes, StandardCharsets.UTF_8);

                allocFb(fbW, fbH);
                sendSetPixelFormat();
                sendSetEncodings();
                cb.onConnected(fbW, fbH, desktopName);
                requestUpdate(false);

                // 4. Server-to-client message loop
                while (running) {
                    int msg = in.readUnsignedByte();
                    if (msg == 0) { // FramebufferUpdate
                        in.readUnsignedByte(); // padding
                        int rects = in.readUnsignedShort();
                        for (int r = 0; r < rects; r++) {
                            int rx = in.readUnsignedShort();
                            int ry = in.readUnsignedShort();
                            int rw = in.readUnsignedShort();
                            int rh = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == 0) readRawRect(rx, ry, rw, rh);
                            else if (enc == 1) readCopyRect(rx, ry, rw, rh);
                            else if (enc == -223) { allocFb(rw, rh); cb.onConnected(fbW, fbH, desktopName); }
                            else throw new RuntimeException("지원하지 않는 인코딩: " + enc);
                        }
                        if (fb != null) {
                            Bitmap snap = Bitmap.createBitmap(pixels, fbW, fbH, Bitmap.Config.ARGB_8888);
                            fb = snap;
                            cb.onFrameUpdated(snap);
                        }
                        requestUpdate(true);
                    } else if (msg == 1) { // SetColourMapEntries
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                        int n = in.readUnsignedShort();
                        skipExact(in, n * 6L);
                    } else if (msg == 2) { // Bell
                        // ignore
                    } else if (msg == 3) { // ServerCutText
                        skipExact(in, 3);
                        long len = in.readInt() & 0xFFFFFFFFL;
                        if (len > 0) skipExact(in, len);
                    }
                }
            } catch (Throwable e) {
                if (running) cb.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            } finally {
                close();
                cb.onDisconnected();
            }
        }, "vnc-client");
        t.start();
    }

    private void allocFb(int w, int h) {
        fbW = Math.max(1, w);
        fbH = Math.max(1, h);
        pixels = new int[fbW * fbH];
        fb = Bitmap.createBitmap(fbW, fbH, Bitmap.Config.ARGB_8888);
    }

    private void readRawRect(int rx, int ry, int rw, int rh) throws Exception {
        byte[] row = new byte[rw * 4];
        for (int y = 0; y < rh; y++) {
            in.readFully(row);
            int dy = ry + y;
            if (dy < 0 || dy >= fbH) continue;
            int base = dy * fbW;
            for (int x = 0; x < rw; x++) {
                int dx = rx + x;
                if (dx < 0 || dx >= fbW) continue;
                int o = x * 4;
                int b = row[o] & 0xFF, g = row[o + 1] & 0xFF, r = row[o + 2] & 0xFF;
                pixels[base + dx] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private void readCopyRect(int rx, int ry, int rw, int rh) throws Exception {
        int sx = in.readUnsignedShort();
        int sy = in.readUnsignedShort();
        int[] copy = new int[rw * rh];
        for (int y = 0; y < rh; y++) {
            int sRow = (sy + y) * fbW + sx;
            if (sy + y >= 0 && sy + y < fbH && sx >= 0 && sx + rw <= fbW)
                System.arraycopy(pixels, sRow, copy, y * rw, rw);
        }
        for (int y = 0; y < rh; y++) {
            int dRow = (ry + y) * fbW + rx;
            if (ry + y >= 0 && ry + y < fbH && rx >= 0 && rx + rw <= fbW)
                System.arraycopy(copy, y * rw, pixels, dRow, rw);
        }
    }

    private void sendSetPixelFormat() throws Exception {
        synchronized (writeLock) {
            out.writeByte(0); // SetPixelFormat
            out.writeByte(0); out.writeByte(0); out.writeByte(0); // pad
            out.writeByte(32); // bpp
            out.writeByte(24); // depth
            out.writeByte(0);  // little-endian
            out.writeByte(1);  // true-color
            out.writeShort(255); out.writeShort(255); out.writeShort(255);
            out.writeByte(16); out.writeByte(8); out.writeByte(0); // R=16, G=8, B=0 -> BGRA bytes
            out.writeByte(0); out.writeByte(0); out.writeByte(0);
            out.flush();
        }
    }

    private void sendSetEncodings() throws Exception {
        synchronized (writeLock) {
            out.writeByte(2); // SetEncodings
            out.writeByte(0);
            out.writeShort(3);
            out.writeInt(0);    // Raw
            out.writeInt(1);    // CopyRect
            out.writeInt(-223); // DesktopSize
            out.flush();
        }
    }

    void requestUpdate(boolean incremental) {
        if (!running || out == null) return;
        try {
            synchronized (writeLock) {
                out.writeByte(3); // FramebufferUpdateRequest
                out.writeByte(incremental ? 1 : 0);
                out.writeShort(0); out.writeShort(0);
                out.writeShort(fbW); out.writeShort(fbH);
                out.flush();
            }
        } catch (Exception ignored) {}
    }

    private final java.util.concurrent.ExecutorService sendExec = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static void skipExact(DataInputStream in, long n) throws Exception {
        byte[] dump = new byte[4096];
        long rem = n;
        while (rem > 0) {
            int want = (int) Math.min(dump.length, rem);
            in.readFully(dump, 0, want);
            rem -= want;
        }
    }

    void sendPointer(int x, int y, int mask) {
        if (!running || out == null) return;
        int cx = Math.max(0, Math.min(fbW - 1, x));
        int cy = Math.max(0, Math.min(fbH - 1, y));
        sendExec.execute(() -> {
            try {
                synchronized (writeLock) {
                    out.writeByte(5); // PointerEvent
                    out.writeByte(mask);
                    out.writeShort(cx);
                    out.writeShort(cy);
                    out.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    void sendKey(int keysym, boolean down) {
        if (!running || out == null) return;
        sendExec.execute(() -> {
            try {
                synchronized (writeLock) {
                    out.writeByte(4); // KeyEvent
                    out.writeByte(down ? 1 : 0);
                    out.writeShort(0);
                    out.writeInt(keysym);
                    out.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    /** Sends UTF-8/ISO-8859-1 clipboard text to VNC server (ClientCutText, message 6). */
    void sendCutText(String text) {
        if (!running || out == null || text == null || text.isEmpty()) return;
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        sendExec.execute(() -> {
            try {
                synchronized (writeLock) {
                    out.writeByte(6);
                    out.writeByte(0); out.writeByte(0); out.writeByte(0);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.flush();
                }
            } catch (Exception ignored) {}
        });
    }

    void close() {
        running = false;
        sendExec.shutdownNow();
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private String readReason() throws Exception {
        int len = in.readInt();
        byte[] b = new byte[Math.max(0, Math.min(len, 1024))];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** RFC 6143 VNCAuth: DES ECB with bit-reversed 8-byte key over the 16-byte challenge. */
    static byte[] encryptVncAuth(String password, byte[] challenge) throws Exception {
        byte[] key = new byte[8];
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < Math.min(8, pw.length); i++) {
            int b = pw[i] & 0xFF, rev = 0;
            for (int bit = 0; bit < 8; bit++) { rev = (rev << 1) | (b & 1); b >>= 1; }
            key[i] = (byte) rev;
        }
        Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, SecretKeyFactory.getInstance("DES").generateSecret(new DESKeySpec(key)));
        return c.doFinal(challenge);
    }
}
