package com.oneuibook.linux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Rootless Linux container manager (bundled PRoot + aarch64 tar.gz rootfs + XFCE4 + TigerVNC).
 * Supports true one-click launch (verified URLs + auto-discovery fallback + cross-protocol redirects
 * -> full POSIX/GNU/PAX tar.gz extract -> symlink-safe provision -> PRoot/Xvnc -> VNC).
 */
final class LinuxEngine {
    private LinuxEngine() {}

    static final class Distro {
        final String id, name, category, summary, url, indexUrl, fileRegex, pkgMgr;
        final int sizeMb, color;
        Distro(String id, String name, String category, String summary, String url,
               String indexUrl, String fileRegex, String pkgMgr, int sizeMb, int color) {
            this.id = id; this.name = name; this.category = category; this.summary = summary;
            this.url = url; this.indexUrl = indexUrl; this.fileRegex = fileRegex;
            this.pkgMgr = pkgMgr; this.sizeMb = sizeMb; this.color = color;
        }
    }

    static final String[] DISTRO_CATS = {"전체", "경량", "데비안·우분투", "롤링·개발"};

    // All 10 primary URLs live-verified HTTP 200 + official directory-index auto-discovery fallback
    static final Distro[] DISTROS = {
            new Distro("alpine", "Alpine 3.22", "경량", "apk · XFCE4 · 초경량 빠른 부팅",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.8-aarch64.tar.gz",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/",
                    "alpine-minirootfs-3\\.22\\.\\d+-aarch64\\.tar\\.gz", "apk", 180, 0xFF0D597F),
            new Distro("ubuntu", "Ubuntu 24.04 LTS", "데비안·우분투", "apt · Noble Numbat arm64",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/",
                    "ubuntu-base-24\\.04(?:\\.\\d+)?-base-arm64\\.tar\\.gz", "apt", 520, 0xFFE95420),
            new Distro("ubuntu26", "Ubuntu 26.04 LTS", "데비안·우분투", "apt · 최신 LTS arm64",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/",
                    "ubuntu-base-26\\.04(?:\\.\\d+)?-base-arm64\\.tar\\.gz", "apt", 540, 0xFFF97316),
            new Distro("ubuntu22", "Ubuntu 22.04 LTS", "데비안·우분투", "apt · Jammy Jellyfish 안정판",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/",
                    "ubuntu-base-22\\.04(?:\\.\\d+)?-base-arm64\\.tar\\.gz", "apt", 500, 0xFFD9480F),
            new Distro("debian", "Debian 12 Bookworm", "데비안·우분투", "apt · 안정 버전 arm64",
                    "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/bookworm/slim/oci/blobs/rootfs.tar.gz",
                    null, null, "apt", 460, 0xFFA80030),
            new Distro("debian13", "Debian 13 Trixie", "데비안·우분투", "apt · 최신 데비안 arm64",
                    "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/trixie/slim/oci/blobs/rootfs.tar.gz",
                    null, null, "apt", 470, 0xFFBE123C),
            new Distro("arch", "Arch Linux ARM", "롤링·개발", "pacman · 최신 롤링 릴리스",
                    "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
                    null, null, "pacman", 580, 0xFF1793D1),
            new Distro("manjaro", "Manjaro ARM", "롤링·개발", "pacman · Arch 기반 데스크탑",
                    "https://github.com/manjaro-arm/rootfs/releases/latest/download/Manjaro-ARM-aarch64-latest.tar.gz",
                    null, null, "pacman", 600, 0xFF35BF5C),
            new Distro("alpine321", "Alpine 3.21", "경량", "apk · 안정 구버전 초경량",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.10-aarch64.tar.gz",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/",
                    "alpine-minirootfs-3\\.21\\.\\d+-aarch64\\.tar\\.gz", "apk", 175, 0xFF1E6F9F),
            new Distro("ubuntu25", "Ubuntu 25.10", "롤링·개발", "apt · 최신 패키지 arm64",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/25.10/release/ubuntu-base-25.10-base-arm64.tar.gz",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/25.10/release/",
                    "ubuntu-base-25\\.10(?:\\.\\d+)?-base-arm64\\.tar\\.gz", "apt", 530, 0xFFEA580C),
    };

    static final class VncProfile {
        String id = UUID.randomUUID().toString();
        String name = "로컬 리눅스 데스크탑";
        String host = "127.0.0.1";
        int port = 5901;
        String password = "";
        int width = 1280, height = 720;
        boolean local;

        JSONObject toJson() throws Exception {
            return new JSONObject().put("id", id).put("name", name).put("host", host)
                    .put("port", port).put("password", password).put("w", width).put("h", height).put("local", local);
        }

        static VncProfile from(JSONObject o) {
            VncProfile v = new VncProfile();
            v.id = o.optString("id", v.id);
            v.name = o.optString("name", v.name);
            v.host = o.optString("host", "127.0.0.1");
            v.port = o.optInt("port", 5901);
            v.password = o.optString("password", "");
            v.width = o.optInt("w", 1280);
            v.height = o.optInt("h", 720);
            v.local = o.optBoolean("local", false);
            return v;
        }
    }

    interface Listener { void onEngineChanged(); }

    static final ExecutorService EXEC = Executors.newCachedThreadPool();
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    static final List<VncProfile> profiles = new ArrayList<>();
    static final StringBuilder consoleLog = new StringBuilder();

    static volatile String activeDistroId = null;
    static volatile String selectedDistroId = "alpine";
    static volatile boolean installing = false;
    static volatile boolean launching = false;
    static volatile int installProgress = 0;
    static volatile String installStatus = "";
    static volatile String lastError = "";
    static volatile Process runningProc = null;
    static String resolution = "auto";
    static int dpiScale = 120;
    static int vncPort = 5901;
    static boolean touchpadMode = true;

    private static volatile long lastUiNotifyMs = 0;

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("linux_desktop", Context.MODE_PRIVATE);
    }

    static void init(Context c) {
        SharedPreferences p = prefs(c);
        resolution = p.getString("resolution", "auto");
        dpiScale = p.getInt("dpi_scale", 120);
        selectedDistroId = p.getString("selected_distro", "alpine");
        vncPort = p.getInt("vnc_port", 5901);
        touchpadMode = p.getBoolean("touchpad", true);
        profiles.clear();
        try {
            JSONArray a = new JSONArray(p.getString("profiles", "[]"));
            for (int i = 0; i < a.length(); i++) profiles.add(VncProfile.from(a.getJSONObject(i)));
        } catch (Exception ignored) {}
        if (profiles.isEmpty()) {
            VncProfile def = new VncProfile();
            def.name = "로컬 리눅스 데스크탑 (PRoot)";
            def.host = "127.0.0.1";
            def.port = 5901;
            def.local = true;
            profiles.add(def);
            saveProfiles(c);
        }
        if (consoleLog.length() == 0) {
            log("리눅스 데스크탑 준비 완료 (루팅 불필요 · PRoot 내장)");
            log("기기 ABI: " + Arrays.toString(Build.SUPPORTED_ABIS) + " · 자동 해상도: " + resolveGeometry(c));
        }
    }

    static void saveProfiles(Context c) {
        try {
            JSONArray a = new JSONArray();
            for (VncProfile v : profiles) a.put(v.toJson());
            prefs(c).edit().putString("profiles", a.toString())
                    .putString("resolution", resolution)
                    .putInt("dpi_scale", dpiScale)
                    .putString("selected_distro", selectedDistroId)
                    .putInt("vnc_port", vncPort)
                    .putBoolean("touchpad", touchpadMode).apply();
        } catch (Exception ignored) {}
    }

    static String resolveGeometry(Context c) {
        if (resolution != null && !"auto".equals(resolution) && resolution.contains("x")) {
            return resolution;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        if (w <= 0 || h <= 0) return "1280x720";
        if (w > 2560) {
            float s = 2560f / w;
            w = Math.round(w * s);
            h = Math.round(h * s);
        }
        w = Math.max(640, (w / 2) * 2);
        h = Math.max(480, (h / 2) * 2);
        return w + "x" + h;
    }

    static String resolutionLabel(Context c) {
        if ("auto".equals(resolution)) return "기기 해상도 맞춤 (" + resolveGeometry(c) + ")";
        return resolution;
    }

    static void notifyChanged() {
        lastUiNotifyMs = System.currentTimeMillis();
        MAIN.post(() -> { for (Listener l : listeners) l.onEngineChanged(); });
    }

    private static void notifyThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastUiNotifyMs >= 320) notifyChanged();
    }

    static void log(String line) {
        synchronized (consoleLog) {
            if (consoleLog.length() > 24000) consoleLog.delete(0, 8000);
            consoleLog.append(line).append('\n');
        }
        notifyThrottled();
    }

    static String getLog() {
        synchronized (consoleLog) { return consoleLog.toString(); }
    }

    static Distro findDistro(String id) {
        if (id != null) for (Distro d : DISTROS) if (d.id.equals(id)) return d;
        return DISTROS[0];
    }

    static File rootfsDir(Context c, String id) {
        return new File(c.getFilesDir(), "rootfs/" + id);
    }

    static boolean isInstalled(Context c, String id) {
        File r = rootfsDir(c, id);
        return new File(r, "etc/os-release").exists() || new File(r, "bin/sh").exists() || new File(r, "usr/bin/env").exists();
    }

    static File prootBinary(Context c) {
        String nat = c.getApplicationInfo().nativeLibraryDir;
        if (nat != null) {
            File f = new File(nat, "libproot.so");
            if (f.exists() && f.canExecute()) return f;
        }
        File custom = new File(c.getFilesDir(), "bin/proot");
        if (custom.exists() && custom.canExecute()) return custom;
        return null;
    }

    static void prepareProotEnv(Context c, Map<String, String> env) {
        String nat = c.getApplicationInfo().nativeLibraryDir;
        File libDir = new File(c.getFilesDir(), "lib");
        libDir.mkdirs();
        if (nat != null) {
            File tallocSrc = new File(nat, "libtalloc.so");
            File tallocLink = new File(libDir, "libtalloc.so.2");
            if (tallocSrc.exists() && !Files.exists(tallocLink.toPath())) {
                try {
                    Files.deleteIfExists(tallocLink.toPath());
                    Files.createSymbolicLink(tallocLink.toPath(), tallocSrc.toPath());
                } catch (Throwable ignored) {
                    try (InputStream is = new FileInputStream(tallocSrc);
                         FileOutputStream fos = new FileOutputStream(tallocLink)) {
                        byte[] buf = new byte[16384]; int n;
                        while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                    } catch (Throwable ignored2) {}
                }
            }
            File loader = new File(nat, "libproot-loader.so");
            if (loader.exists()) env.put("PROOT_LOADER", loader.getAbsolutePath());
            File loader32 = new File(nat, "libproot-loader32.so");
            if (loader32.exists()) env.put("PROOT_LOADER_32", loader32.getAbsolutePath());
            env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":" + nat);
        }
        env.remove("LD_PRELOAD");
        File tmpDir = new File(c.getCacheDir(), "proot-tmp");
        tmpDir.mkdirs();
        env.put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
        env.put("PROOT_NO_SECCOMP", "1");
    }

    static boolean isRunning() {
        return runningProc != null && runningProc.isAlive();
    }

    static long dirSizeBytes(File dir) {
        if (dir == null || !dir.exists()) return 0;
        if (dir.isFile()) return dir.length();
        long sum = 0;
        File[] list = dir.listFiles();
        if (list != null) for (File f : list) sum += dirSizeBytes(f);
        return sum;
    }

    private static void toast(Context app, String msg) {
        MAIN.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    /** True one-click flow: select distro -> install if needed -> start PRoot + Xvnc -> wait port -> open VNC. */
    static void oneClickLaunch(Context ctx, Distro d, Runnable onVncReady) {
        selectedDistroId = d.id;
        saveProfiles(ctx);
        if (isRunning() && d.id.equals(activeDistroId)) {
            if (onVncReady != null) MAIN.post(onVncReady);
            return;
        }
        if (installing || launching) {
            log("[안내] 이미 설치 또는 실행 작업이 진행 중입니다.");
            return;
        }
        launching = true;
        lastError = "";
        installProgress = 2;
        installStatus = d.name + " 원클릭 준비 중...";
        notifyChanged();

        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                if (!isInstalled(app, d.id)) {
                    boolean ok = installSync(app, d);
                    if (!ok) return;
                }
                provisionContainer(rootfsDir(app, d.id), d, resolveGeometry(app));
                if (isRunning()) stopDesktop(app);

                installProgress = 92;
                installStatus = d.name + " 데스크탑 부팅 중 (" + resolveGeometry(app) + ")...";
                notifyChanged();

                if (!startDesktopInternal(app, d)) {
                    if (lastError.isEmpty()) lastError = "PRoot 데스크탑 프로세스를 시작하지 못했습니다. 터미널 로그를 확인하세요.";
                    toast(app, lastError);
                    return;
                }

                boolean ready = waitForVncPort("127.0.0.1", vncPort, 240_000);
                if (ready) {
                    installProgress = 100;
                    installStatus = d.name + " 실행 준비 완료!";
                    lastError = "";
                    log(">>> VNC 서버 연결 준비 완료 (127.0.0.1:" + vncPort + ")");
                    notifyChanged();
                    if (onVncReady != null) MAIN.post(onVncReady);
                } else if (isRunning()) {
                    installStatus = "XFCE4 패키지 설치 진행 중 (터미널 탭에서 진행 확인 가능)";
                    log("[안내] 최초 실행 시 XFCE4 설치에 1~4분 걸릴 수 있습니다. 완료 후 '화면 열기'를 누르세요.");
                    notifyChanged();
                } else {
                    lastError = "리눅스 세션이 예기치 않게 종료되었습니다. 터미널 탭에서 로그를 확인하세요.";
                    installStatus = lastError;
                    toast(app, lastError);
                    notifyChanged();
                }
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                lastError = "원클릭 실행 실패: " + msg;
                installStatus = lastError;
                log("[오류] " + lastError);
                toast(app, lastError);
            } finally {
                launching = false;
                notifyChanged();
            }
        });
    }

    private static boolean waitForVncPort(String host, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && isRunning()) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), 400);
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(600); } catch (InterruptedException ie) { return false; }
            }
        }
        return false;
    }

    static void installDistro(Context ctx, Distro d) {
        if (installing) return;
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> installSync(app, d));
    }

    /** Opens an HTTP/HTTPS URL following cross-protocol 301/302/303/307/308 redirects safely. */
    static HttpURLConnection openWithRedirects(String rawUrl) throws IOException {
        String current = rawUrl;
        for (int hops = 0; hops < 10; hops++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "LinuxDesktop/9.5.3 (Android; aarch64)");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Encoding", "identity");
            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) throw new IOException("Redirect without Location (HTTP " + code + ")");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return conn;
        }
        throw new IOException("리다이렉트가 너무 많습니다");
    }

    /** If a point-release URL ever expires (404), auto-discovers the latest matching tar.gz from the official index page. */
    static String discoverLatestTarball(Distro d) {
        if (d.indexUrl == null || d.fileRegex == null) return null;
        HttpURLConnection conn = null;
        try {
            conn = openWithRedirects(d.indexUrl);
            if (conn.getResponseCode() != 200) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0 && baos.size() < 512 * 1024) baos.write(buf, 0, n);
            }
            String html = baos.toString("UTF-8");
            Matcher m = Pattern.compile(d.fileRegex).matcher(html);
            String best = null;
            while (m.find()) {
                String cand = m.group();
                if (best == null || compareVersionStrings(cand, best) > 0) best = cand;
            }
            if (best != null) return d.indexUrl + best;
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static int compareVersionStrings(String a, String b) {
        String[] pa = a.split("\\D+"), pb = b.split("\\D+");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = (i < pa.length && !pa[i].isEmpty()) ? Integer.parseInt(pa[i]) : 0;
            int vb = (i < pb.length && !pb[i].isEmpty()) ? Integer.parseInt(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return a.compareTo(b);
    }

    private static boolean installSync(Context app, Distro d) {
        if (installing) return false;
        installing = true;
        lastError = "";
        installProgress = 2;
        installStatus = d.name + " 다운로드 연결 중...";
        log(">>> [" + d.name + "] 설치 시작 (루팅 없음)");
        notifyChanged();

        File tmp = new File(app.getCacheDir(), d.id + "-rootfs.tar.gz");
        File dest = rootfsDir(app, d.id);
        HttpURLConnection conn = null;
        try {
            String targetUrl = d.url;
            conn = openWithRedirects(targetUrl);
            int code = conn.getResponseCode();
            if (code == 404 && d.indexUrl != null) {
                conn.disconnect();
                log("기존 릴리스 링크(404) 갱신 탐색 중: " + d.indexUrl);
                String discovered = discoverLatestTarball(d);
                if (discovered != null) {
                    targetUrl = discovered;
                    log("최신 루트 파일시스템 발견: " + targetUrl);
                    conn = openWithRedirects(targetUrl);
                    code = conn.getResponseCode();
                }
            }
            if (code < 200 || code >= 300) {
                throw new IOException("서버 응답 오류 (HTTP " + code + ")");
            }
            long total = conn.getContentLengthLong();
            installProgress = 5;
            installStatus = d.name + " 다운로드 중...";
            notifyChanged();

            try (InputStream is = new BufferedInputStream(conn.getInputStream(), 65536);
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                long read = 0;
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int p = 5 + (int) (read * 45 / total);
                        installProgress = Math.min(50, p);
                        installStatus = d.name + " 다운로드 중 (" + Ui.size(read) + " / " + Ui.size(total) + ")";
                    } else {
                        installProgress = Math.min(48, 5 + (int) (read / (2 * 1024 * 1024)));
                        installStatus = d.name + " 다운로드 중 (" + Ui.size(read) + ")";
                    }
                    notifyThrottled();
                }
            }
            if (tmp.length() < 1024) throw new IOException("다운로드된 파일이 비어 있습니다 (" + tmp.length() + " B)");
            log("다운로드 완료: " + Ui.size(tmp.length()));

            installProgress = 52;
            installStatus = "루트 파일시스템 압축 해제 중...";
            notifyChanged();
            if (dest.exists()) deleteRec(dest);
            dest.mkdirs();
            int files = extractTarGz(tmp, dest);
            log("압축 해제 완료: " + files + "개 파일");

            installProgress = 86;
            installStatus = "XFCE4 · VNC 초기 스크립트 구성 중...";
            notifyChanged();
            provisionContainer(dest, d, resolveGeometry(app));
            tmp.delete();

            installProgress = 90;
            installStatus = d.name + " 설치 완료!";
            log("<<< [" + d.name + "] 설치가 완료되었습니다.");
            notifyChanged();
            return true;
        } catch (Throwable t) {
            if (tmp.exists()) tmp.delete();
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            lastError = d.name + " 설치 실패: " + msg;
            installStatus = lastError;
            log("[오류] " + lastError);
            toast(app, lastError);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
            installing = false;
            notifyChanged();
        }
    }

    /** Full POSIX/USTAR + GNU LongLink ('L'/'K') + PAX ('x') + Hardlink ('1') tar.gz extractor. */
    static int extractTarGz(File tarGz, File destDir) throws Exception {
        int count = 0;
        Path destCanon = destDir.getCanonicalFile().toPath();
        List<String[]> deferredHardlinks = new ArrayList<>();
        String nextLongName = null, nextLongLink = null;
        long nextPaxSize = -1;

        try (InputStream fis = new BufferedInputStream(new FileInputStream(tarGz), 65536);
             GZIPInputStream gis = new GZIPInputStream(fis, 65536)) {
            byte[] hdr = new byte[512];
            byte[] buf = new byte[32768];
            while (readFully(gis, hdr, 512) == 512) {
                boolean allZero = true;
                for (byte b : hdr) if (b != 0) { allZero = false; break; }
                if (allZero) break;

                byte type = hdr[156];
                long size = nextPaxSize >= 0 ? nextPaxSize : tarOctal(hdr, 124, 12);
                int mode = (int) tarOctal(hdr, 100, 8);
                nextPaxSize = -1;

                // GNU LongName ('L') / LongLink ('K')
                if (type == 'L' || type == 'K') {
                    String longVal = readTarStringPayload(gis, size);
                    if (type == 'L') nextLongName = longVal; else nextLongLink = longVal;
                    skipTarPad(gis, size);
                    continue;
                }
                // PAX extended header ('x') / global header ('g')
                if (type == 'x' || type == 'g') {
                    String pax = readTarStringPayload(gis, size);
                    if (type == 'x') {
                        int pos = 0;
                        while (pos < pax.length()) {
                            int sp = pax.indexOf(' ', pos);
                            if (sp <= pos) break;
                            int recLen;
                            try { recLen = Integer.parseInt(pax.substring(pos, sp)); } catch (Exception e) { break; }
                            if (recLen <= 0 || pos + recLen > pax.length()) break;
                            String rec = pax.substring(sp + 1, pos + recLen);
                            if (rec.endsWith("\n")) rec = rec.substring(0, rec.length() - 1);
                            int eq = rec.indexOf('=');
                            if (eq > 0) {
                                String k = rec.substring(0, eq), v = rec.substring(eq + 1);
                                if ("path".equals(k)) nextLongName = v;
                                else if ("linkpath".equals(k)) nextLongLink = v;
                                else if ("size".equals(k)) { try { nextPaxSize = Long.parseLong(v); } catch (Exception ignored) {} }
                            }
                            pos += recLen;
                        }
                    }
                    skipTarPad(gis, size);
                    continue;
                }

                String name = nextLongName != null ? nextLongName : tarString(hdr, 0, 100);
                if (nextLongName == null) {
                    String prefix = tarString(hdr, 345, 155);
                    if (!prefix.isEmpty()) name = prefix + "/" + name;
                }
                String linkName = nextLongLink != null ? nextLongLink : tarString(hdr, 157, 100);
                nextLongName = null;
                nextLongLink = null;

                name = stripLeadingPath(name);
                File out = new File(destDir, name);
                boolean safe = !name.isEmpty() && !name.contains("..") && out.getCanonicalFile().toPath().startsWith(destCanon);

                if (safe) {
                    if (type == '5') {
                        out.mkdirs();
                    } else if (type == '2' && !linkName.isEmpty()) {
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try {
                            Files.deleteIfExists(out.toPath());
                            Files.createSymbolicLink(out.toPath(), new File(linkName).toPath());
                        } catch (Throwable ignored) {}
                    } else if (type == '1' && !linkName.isEmpty()) {
                        String targetRel = stripLeadingPath(linkName);
                        File target = new File(destDir, targetRel);
                        if (target.exists() && target.isFile()) {
                            linkOrCopy(target, out);
                        } else {
                            deferredHardlinks.add(new String[]{targetRel, name});
                        }
                        count++;
                    } else if (type == '0' || type == 0) {
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try { Files.deleteIfExists(out.toPath()); } catch (Throwable ignored) {}
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            long rem = size;
                            while (rem > 0) {
                                int r = gis.read(buf, 0, (int) Math.min(buf.length, rem));
                                if (r < 0) break;
                                fos.write(buf, 0, r);
                                rem -= r;
                            }
                        }
                        if ((mode & 0111) != 0) out.setExecutable(true, false);
                        count++;
                        if ((count & 255) == 0) {
                            installProgress = Math.min(85, 52 + count / 300);
                            installStatus = "압축 해제 중... (" + count + "개 파일)";
                            notifyThrottled();
                        }
                    } else {
                        skipFully(gis, size);
                    }
                } else {
                    skipFully(gis, size);
                }
                skipTarPad(gis, size);
            }
        }
        // Resolve any hardlinks whose targets appeared later in the archive
        for (String[] pair : deferredHardlinks) {
            File target = new File(destDir, pair[0]);
            File out = new File(destDir, pair[1]);
            if (target.exists() && target.isFile()) linkOrCopy(target, out);
        }
        return count;
    }

    private static void linkOrCopy(File target, File out) {
        try {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            Files.deleteIfExists(out.toPath());
            try {
                Files.createLink(out.toPath(), target.toPath());
            } catch (Throwable linkErr) {
                try (InputStream is = new FileInputStream(target); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] b = new byte[16384];
                    int n;
                    while ((n = is.read(b)) > 0) fos.write(b, 0, n);
                }
                if (target.canExecute()) out.setExecutable(true, false);
            }
        } catch (Throwable ignored) {}
    }

    private static String stripLeadingPath(String s) {
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static String readTarStringPayload(InputStream in, long size) throws Exception {
        int len = (int) Math.min(size, 65536);
        byte[] data = new byte[len];
        readFully(in, data, len);
        if (size > len) skipFully(in, size - len);
        int end = len;
        while (end > 0 && data[end - 1] == 0) end--;
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }

    private static void skipTarPad(InputStream in, long size) throws Exception {
        long pad = (512 - (size % 512)) % 512;
        if (pad > 0) skipFully(in, pad);
    }

    static void provisionContainer(File root, Distro d) throws Exception {
        provisionContainer(root, d, "1280x720");
    }

    static void provisionContainer(File root, Distro d, String geom) throws Exception {
        writeText(new File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        writeText(new File(root, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");
        String installCmd;
        switch (d.pkgMgr) {
            case "apk":
                installCmd = "apk update && apk add --no-cache xfce4 xfce4-terminal tigervnc dbus dbus-x11 font-noto-cjk xorg-server";
                break;
            case "pacman":
                installCmd = "sed -i 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf 2>/dev/null || true; pacman-key --init || true; pacman -Syu --noconfirm xfce4 xfce4-terminal tigervnc dbus noto-fonts-cjk";
                break;
            default:
                installCmd = "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold xfce4 xfce4-terminal tigervnc-standalone-server dbus-x11 fonts-noto-cjk";
                break;
        }
        int disp = vncPort - 5900;
        String startScript = "#!/bin/sh\n"
                + "export HOME=/root\n"
                + "export USER=root\n"
                + "export DISPLAY=:" + disp + "\n"
                + "export LANG=C.UTF-8\n"
                + "export XDG_RUNTIME_DIR=/tmp/runtime-root\n"
                + "mkdir -p /tmp/runtime-root /tmp/.X11-unix /dev/shm /root/.vnc\n"
                + "chmod 1777 /tmp/.X11-unix /dev/shm 2>/dev/null || true\n"
                + "if ! command -v Xvnc >/dev/null 2>&1; then\n"
                + "  echo '[LinuxDesktop] XFCE4 및 TigerVNC 최초 설치 중...'\n"
                + "  " + installCmd + "\n"
                + "fi\n"
                + "rm -f /tmp/.X" + disp + "-lock /tmp/.X11-unix/X" + disp + "\n"
                + "cat > /root/.vnc/xstartup << 'EOF'\n"
                + "#!/bin/sh\n"
                + "unset SESSION_MANAGER\n"
                + "unset DBUS_SESSION_BUS_ADDRESS\n"
                + "exec dbus-launch --exit-with-session startxfce4\n"
                + "EOF\n"
                + "chmod +x /root/.vnc/xstartup\n"
                + "Xvnc $DISPLAY -rfbport " + vncPort + " -geometry " + geom + " -depth 24 -dpi " + dpiScale + " -SecurityTypes None -localhost &\n"
                + "sleep 1\n"
                + "exec /root/.vnc/xstartup\n";
        File sc = new File(root, "root/start-desktop.sh");
        writeText(sc, startScript);
        sc.setExecutable(true, false);
    }

    static List<String> buildProotArgs(Context c, File proot, File rootfs, String shellCmd) {
        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("-0");
        args.add("--link2symlink");
        args.add("-r"); args.add(rootfs.getAbsolutePath());
        args.add("-b"); args.add("/dev");
        args.add("-b"); args.add("/proc");
        args.add("-b"); args.add("/sys");
        args.add("-b"); args.add(c.getCacheDir().getAbsolutePath() + ":/tmp");
        args.add("-w"); args.add("/root");
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("TERM=xterm-256color");
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("/bin/sh");
        args.add("-c");
        args.add(shellCmd);
        return args;
    }

    static void startDesktop(Context c, Distro d) {
        EXEC.execute(() -> startDesktopInternal(c.getApplicationContext(), d));
    }

    private static boolean startDesktopInternal(Context c, Distro d) {
        if (isRunning()) { log("이미 리눅스 데스크탑이 실행 중입니다."); return true; }
        File rootfs = rootfsDir(c, d.id);
        if (!isInstalled(c, d.id)) { lastError = "먼저 " + d.name + "을 설치하세요."; log("[안내] " + lastError); return false; }
        File proot = prootBinary(c);
        if (proot == null) {
            lastError = "PRoot 네이티브 런타임(libproot.so)을 찾을 수 없습니다 (arm64-v8a 기기 필요).";
            log("[오류] " + lastError);
            return false;
        }
        try { provisionContainer(rootfs, d, resolveGeometry(c)); } catch (Exception ignored) {}
        activeDistroId = d.id;
        LinuxService.start(c, d.name);
        notifyChanged();
        try {
            List<String> cmd = buildProotArgs(c, proot, rootfs, "sh /root/start-desktop.sh");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            prepareProotEnv(c, pb.environment());
            pb.redirectErrorStream(true);
            String geom = resolveGeometry(c);
            log(">>> PRoot 데스크탑 시작: " + d.name + " (" + geom + ", DPI " + dpiScale + ", VNC :" + (vncPort - 5900) + ")");
            runningProc = pb.start();
            EXEC.execute(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(runningProc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) log(line);
                } catch (Throwable ignored) {}
                try {
                    int exit = runningProc != null ? runningProc.waitFor() : 0;
                    log("<<< 리눅스 세션 종료 (exit=" + exit + ")");
                } catch (Throwable ignored) {}
                runningProc = null;
                activeDistroId = null;
                LinuxService.stop(c);
                notifyChanged();
            });
            return true;
        } catch (Throwable t) {
            lastError = "데스크탑 실행 실패: " + t.getMessage();
            log("[오류] " + lastError);
            runningProc = null;
            activeDistroId = null;
            LinuxService.stop(c);
            notifyChanged();
            return false;
        }
    }

    static void stopDesktop(Context c) {
        if (runningProc != null) {
            runningProc.destroy();
            runningProc = null;
        }
        activeDistroId = null;
        LinuxService.stop(c);
        log("리눅스 데스크탑 세션을 중지했습니다.");
        notifyChanged();
    }

    static void runShellCommand(Context c, String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) return;
        log("$ " + cmd);
        EXEC.execute(() -> {
            try {
                File proot = prootBinary(c);
                String targetId = activeDistroId != null ? activeDistroId : selectedDistroId;
                ProcessBuilder pb;
                if (proot != null && isInstalled(c, targetId)) {
                    pb = new ProcessBuilder(buildProotArgs(c, proot, rootfsDir(c, targetId), cmd));
                    prepareProotEnv(c, pb.environment());
                } else {
                    pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) log(line);
                }
                p.waitFor();
                notifyChanged();
            } catch (Throwable t) {
                log("[오류] " + t.getMessage());
                notifyChanged();
            }
        });
    }

    static void removeDistro(Context c, Distro d) {
        if (d.id.equals(activeDistroId)) stopDesktop(c);
        deleteRec(rootfsDir(c, d.id));
        log(d.name + " 루트 파일시스템을 삭제했습니다.");
        notifyChanged();
    }

    private static void deleteRec(File f) {
        if (f == null) return;
        if (!Files.exists(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] list = f.listFiles();
            if (list != null) for (File ch : list) deleteRec(ch);
        }
        try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) { f.delete(); }
    }

    /** Symlink-safe text writer: removes dangling symlinks (like /etc/resolv.conf) before writing! */
    private static void writeText(File f, String text) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try { Files.deleteIfExists(f.toPath()); } catch (Throwable ignored) {}
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return off;
            off += r;
        }
        return off;
    }

    private static void skipFully(InputStream in, long n) throws Exception {
        byte[] tmp = new byte[8192];
        while (n > 0) {
            int r = in.read(tmp, 0, (int) Math.min(tmp.length, n));
            if (r < 0) break;
            n -= r;
        }
    }

    private static String tarString(byte[] hdr, int off, int len) {
        int end = off;
        while (end < off + len && hdr[end] != 0) end++;
        return new String(hdr, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] hdr, int off, int len) {
        // Handle GNU base-256 binary size encoding (high bit set)
        if ((hdr[off] & 0x80) != 0) {
            long v = 0;
            for (int i = off + 1; i < off + len; i++) v = (v << 8) | (hdr[i] & 0xFF);
            return v;
        }
        String s = tarString(hdr, off, len);
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (Exception e) { return 0; }
    }
}
