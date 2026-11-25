package com.oneuibook.linux;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class LinuxUiTest {
    private File outDir;

    @Before public void setup() {
        outDir = new File(System.getProperty("linux.output", "."));
        outDir.mkdirs();
    }

    private void saveShot(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        v.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, name))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test public void tarGzAndProvisionAndProotArgs() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        File tarGz = new File(ctx.getCacheDir(), "sample.tar.gz");
        try (GZIPOutputStream gz = new GZIPOutputStream(new FileOutputStream(tarGz))) {
            byte[] content = "NAME=Alpine\n".getBytes(StandardCharsets.UTF_8);
            byte[] hdr = new byte[512];
            System.arraycopy("etc/os-release".getBytes(StandardCharsets.UTF_8), 0, hdr, 0, 14);
            System.arraycopy("0000644\0".getBytes(StandardCharsets.US_ASCII), 0, hdr, 100, 8);
            String sz = String.format("%011o\0", content.length);
            System.arraycopy(sz.getBytes(StandardCharsets.US_ASCII), 0, hdr, 124, 12);
            hdr[156] = '0';
            gz.write(hdr);
            gz.write(content);
            gz.write(new byte[512 - content.length]);
            gz.write(new byte[1024]);
        }
        File dest = new File(ctx.getFilesDir(), "test_rootfs");
        int n = LinuxEngine.extractTarGz(tarGz, dest);
        assertEquals(1, n);
        assertTrue(new File(dest, "etc/os-release").exists());
        LinuxEngine.provisionContainer(dest, LinuxEngine.DISTROS[0]);
        assertTrue(new File(dest, "root/start-desktop.sh").exists());
        List<String> args = LinuxEngine.buildProotArgs(ctx, new File("/bin/proot"), dest, "uname -a");
        assertTrue(args.contains("--link2symlink"));
        assertTrue(args.contains("uname -a"));
    }

    @Test public void rfb38VncLoopbackAndAuth() throws Exception {
        byte[] enc = VncClient.encryptVncAuth("secret", new byte[16]);
        assertEquals(16, enc.length);

        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        Thread srv = new Thread(() -> {
            try (Socket s = ss.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                byte[] cv = new byte[12]; in.readFully(cv);
                out.writeByte(1); out.writeByte(1); out.flush(); // None auth
                in.readUnsignedByte();
                out.writeInt(0); out.flush(); // OK
                in.readUnsignedByte(); // ClientInit
                out.writeShort(4); out.writeShort(4);
                out.write(new byte[16]);
                byte[] nm = "TestXFCE".getBytes(StandardCharsets.UTF_8);
                out.writeInt(nm.length); out.write(nm); out.flush();
                // Send 1 raw 4x4 framebuffer update
                out.writeByte(0); out.writeByte(0); out.writeShort(1);
                out.writeShort(0); out.writeShort(0); out.writeShort(4); out.writeShort(4);
                out.writeInt(0);
                byte[] px = new byte[4 * 4 * 4];
                for (int i = 0; i < px.length; i += 4) { px[i] = (byte) 0xFF; px[i + 1] = (byte) 0x6B; px[i + 2] = (byte) 0x2E; }
                out.write(px); out.flush();
                Thread.sleep(200);
            } catch (Throwable ignored) {}
        });
        srv.start();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean gotFrame = new AtomicBoolean(false);
        VncClient vc = new VncClient();
        vc.connect("127.0.0.1", port, "", new VncClient.Callback() {
            @Override public void onConnected(int w, int h, String name) { assertEquals("TestXFCE", name); }
            @Override public void onFrameUpdated(Bitmap fb) { gotFrame.set(fb.getWidth() == 4 && fb.getHeight() == 4); latch.countDown(); }
            @Override public void onError(String message) { latch.countDown(); }
            @Override public void onDisconnected() {}
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(gotFrame.get());
        vc.close();
        ss.close();
    }

    @Test public void launcherIconRenders() throws Exception {
        Context ctx = RuntimeEnvironment.getApplication();
        Drawable icon = ctx.getDrawable(R.mipmap.ic_launcher);
        assertNotNull(icon);
        // Render cropped to 72/108 adaptive viewport inside a One UI squircle mask
        Bitmap bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Path sq = new android.graphics.Path();
        float r = 122f, cx = 128f, cy = 128f, n = 2.76f;
        for (int i = 0; i <= 120; i++) {
            double a = i * Math.PI * 2.0 / 120.0;
            double ca = Math.cos(a), sa = Math.sin(a);
            float x = cx + (float) (Math.signum(ca) * Math.pow(Math.abs(ca), 2.0 / n) * r);
            float y = cy + (float) (Math.signum(sa) * Math.pow(Math.abs(sa), 2.0 / n) * r);
            if (i == 0) sq.moveTo(x, y); else sq.lineTo(x, y);
        }
        sq.close();
        c.clipPath(sq);
        // AdaptiveIconDrawable already applies the 72/108 viewport inside its bounds
        icon.setBounds(0, 0, 256, 256);
        icon.draw(c);
        try (FileOutputStream fos = new FileOutputStream(new File(outDir, "icon.png"))) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        bmp.recycle();
    }

    @Test @Config(qualifiers = "ko-rKR-w412dp-h915dp-xxhdpi")
    public void phoneScreens() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertEquals("리눅스 데스크탑", act.getString(R.string.app_name));
        saveShot(act.root, 1236, 2745, "phone-desktop.png");

        act.dock.select(1, false);
        act.onTab(1, false);
        saveShot(act.root, 1236, 2745, "phone-terminal.png");

        act.dock.select(2, false);
        act.onTab(2, false);
        saveShot(act.root, 1236, 2745, "phone-vnc.png");
    }

    @Test @Config(qualifiers = "ko-rKR-w840dp-h600dp-xhdpi")
    public void tabletSidebar() throws Exception {
        ActivityController<MainActivity> ctrl = Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity act = ctrl.get();
        assertEquals("9.5.1", MainActivity.VERSION);
        assertEquals(10, LinuxEngine.DISTROS.length);
        assertEquals(0xFFBE, DesktopActivity.mapKeySym(android.view.KeyEvent.KEYCODE_F1, new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_F1)));
        assertEquals('c', DesktopActivity.mapKeySym(android.view.KeyEvent.KEYCODE_C, new android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_C, 0, android.view.KeyEvent.META_CTRL_ON)));
        assertTrue(act.tabletUi());
        act.openSidebar(false);
        saveShot(act.root, 1680, 1200, "tablet-sidebar.png");
        // Verify dock search circle does not overlap header pill on narrow tablet with sidebar open
        assertTrue("Dock search overlaps header pill: " + act.dock.circle.right + " vs " + act.header.pill.left,
                act.dock.circle.right <= act.header.pill.left + 2f);
    }
}
