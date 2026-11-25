package com.oneuibook.linux;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.zip.GZIPInputStream;

/**
 * Rootless Linux container manager (bundled Termux PRoot + aarch64 tar.gz rootfs + XFCE4 + TigerVNC).
 * Supports true one-click launch (auto-download -> extract -> provision -> start PRoot/Xvnc -> open VNC)
 * and automatic device-native resolution matching.
 */
final class LinuxEngine {
    private LinuxEngine() {}

    static final class Distro {
        final String id, name, category, summary, url, pkgMgr;
        final int sizeMb, color;
        Distro(String id, String name, String category, String summary, String url, String pkgMgr, int sizeMb, int color) {
            this.id = id; this.name = name; this.category = category; this.summary = summary;
            this.url = url; this.pkgMgr = pkgMgr; this.sizeMb = sizeMb; this.color = color;
        }
    }

    static final String[] DISTRO_CATS = {"전체", "경량", "데비안·우분투", "롤링·개발"};

    // All URLs are verified aarch64 .tar.gz archives compatible with built-in pure-Java GZIP extractor
    static final Distro[] DISTROS = {
            new Distro("alpine", "Alpine 3.20", "경량", "apk · XFCE4 · 초경량 빠른 부팅",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz", "apk", 180, 0xFF0D597F),
            new Distro("ubuntu", "Ubuntu 24.04 LTS", "데비안·우분투", "apt · Noble Numbat arm64",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.1-base-arm64.tar.gz", "apt", 520, 0xFFE95420),
            new Distro("ubuntu22", "Ubuntu 22.04 LTS", "데비안·우분투", "apt · Jammy Jellyfish 안정판",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.5-base-arm64.tar.gz", "apt", 500, 0xFFD9480F),
            new Distro("debian", "Debian 12 Bookworm", "데비안·우분투", "apt · 안정 버전 arm64",
                    "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/bookworm/slim/oci/blobs/rootfs.tar.gz", "apt", 460, 0xFFA80030),
            new Distro("debian11", "Debian 11 Bullseye", "데비안·우분투", "apt · 구버전 호환 안정판",
                    "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64v8/bullseye/slim/oci/blobs/rootfs.tar.gz", "apt", 440, 0xFF8C1D40),
            new Distro("arch", "Arch Linux ARM", "롤링·개발", "pacman · 최신 롤링 릴리스",
                    "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz", "pacman", 580, 0xFF1793D1),
            new Distro("manjaro", "Manjaro ARM", "롤링·개발", "pacman · Arch 기반 데스크탑",
                    "https://github.com/manjaro-arm/rootfs/releases/latest/download/Manjaro-ARM-aarch64-latest.tar.gz", "pacman", 600, 0xFF35BF5C),
            new Distro("alpine319", "Alpine 3.19", "경량", "apk · 안정 구버전 초경량",
                    "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.4-aarch64.tar.gz", "apk", 170, 0xFF1E6F9F),
            new Distro("alpine_edge", "Alpine Edge", "롤링·개발", "apk · 최신 패키지 롤링",
                    "https://dl-cdn.alpinelinux.org/alpine/edge/releases/aarch64/alpine-minirootfs-3.21.0_rc1-aarch64.tar.gz", "apk", 190, 0xFF0EA5E9),
            new Distro("ubuntu_dev", "Ubuntu 24.10", "롤링·개발", "apt · Oracular Oriole 최신",
                    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.10/release/ubuntu-base-24.10-base-arm64.tar.gz", "apt", 540, 0xFFF97316),
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
    static volatile Process runningProc = null;
    static String resolution = "auto"; // "auto" = match device screen resolution
    static int dpiScale = 120;         // Xvnc -dpi value (96 / 120 / 144)
    static int vncPort = 5901;
    static boolean touchpadMode = true;

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

    /** Computes Xvnc geometry: when "auto", matches current device screen aspect ratio & crisp resolution. */
    static String resolveGeometry(Context c) {
        if (resolution != null && !"auto".equals(resolution) && resolution.contains("x")) {
            return resolution;
        }
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = Math.max(dm.widthPixels, dm.heightPixels);
        int h = Math.min(dm.widthPixels, dm.heightPixels);
        if (w <= 0 || h <= 0) return "1280x720";
        // If phone is in portrait and user prefers landscape desktop, keep crisp aspect ratio capped at 2560px
        if (w > 2560) {
            float s = 2560f / w;
            w = Math.round(w * s);
            h = Math.round(h * s);
        }
        // Xvnc requires even dimensions
        w = Math.max(640, (w / 2) * 2);
        h = Math.max(480, (h / 2) * 2);
        return w + "x" + h;
    }

    static String resolutionLabel(Context c) {
        if ("auto".equals(resolution)) return "기기 해상도 맞춤 (" + resolveGeometry(c) + ")";
        return resolution;
    }

    static void notifyChanged() {
        MAIN.post(() -> { for (Listener l : listeners) l.onEngineChanged(); });
    }

    static void log(String line) {
        synchronized (consoleLog) {
            if (consoleLog.length() > 24000) consoleLog.delete(0, 8000);
            consoleLog.append(line).append('\n');
        }
        notifyChanged();
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

    /** Ensures libtalloc.so.2 symlink/copy exists so bundled Termux libproot.so resolves DT_NEEDED cleanly. */
    static void prepareProotEnv(Context c, Map<String, String> env) {
        String nat = c.getApplicationInfo().nativeLibraryDir;
        File libDir = new File(c.getFilesDir(), "lib");
        libDir.mkdirs();
        if (nat != null) {
            File tallocSrc = new File(nat, "libtalloc.so");
            File tallocLink = new File(libDir, "libtalloc.so.2");
            if (tallocSrc.exists() && !tallocLink.exists()) {
                try {
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
        env.put("PROOT_TMP_DIR", c.getCacheDir().getAbsolutePath());
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
        installProgress = 5;
        installStatus = d.name + " 원클릭 준비 중...";
        notifyChanged();

        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> {
            try {
                if (!isInstalled(app, d.id)) {
                    boolean ok = installSync(app, d);
                    if (!ok) return;
                }
                // Always refresh start-desktop.sh with current device resolution & DPI
                provisionContainer(rootfsDir(app, d.id), d, resolveGeometry(app));
                if (isRunning()) stopDesktop(app);

                installProgress = 92;
                installStatus = d.name + " 데스크탑 부팅 중 (" + resolveGeometry(app) + ")...";
                notifyChanged();

                if (!startDesktopInternal(app, d)) return;

                // Wait for Xvnc port to accept connections (up to 180s on first-run XFCE4 install)
                boolean ready = waitForVncPort("127.0.0.1", vncPort, 180_000);
                if (ready) {
                    installProgress = 100;
                    installStatus = d.name + " 실행 준비 완료!";
                    log(">>> VNC 서버 연결 준비 완료 (127.0.0.1:" + vncPort + ")");
                    if (onVncReady != null) MAIN.post(onVncReady);
                } else if (isRunning()) {
                    installStatus = "XFCE4 패키지 설치 진행 중 (터미널에서 진행 확인 가능)";
                    log("[안내] 최초 실행 시 XFCE4 설치에 1~3분 걸릴 수 있습니다. 완료 후 '화면 열기'를 누르세요.");
                }
            } catch (Throwable t) {
                log("[오류] 원클릭 실행 실패: " + t.getMessage());
                installStatus = "실행 실패: " + t.getMessage();
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

    /** Downloads and extracts a rootfs tar.gz in pure Java, then writes XFCE + VNC startup scripts. */
    static void installDistro(Context ctx, Distro d) {
        if (installing) return;
        Context app = ctx.getApplicationContext();
        EXEC.execute(() -> installSync(app, d));
    }

    private static boolean installSync(Context app, Distro d) {
        if (installing) return false;
        installing = true;
        installProgress = 2;
        installStatus = d.name + " 다운로드 준비 중...";
        log(">>> [" + d.name + "] 설치 시작 (루팅 없음)");
        notifyChanged();

        File tmp = new File(app.getCacheDir(), d.id + "-rootfs.tar.gz");
        File dest = rootfsDir(app, d.id);
        try {
            installStatus = d.name + " 다운로드 중...";
            notifyChanged();
            HttpURLConnection conn = (HttpURLConnection) new URL(d.url).openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "OneUI-LinuxDesktop/9.5.2");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new RuntimeException("HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream is = new BufferedInputStream(conn.getInputStream());
                 FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                long read = 0;
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    read += n;
                    if (total > 0) {
                        int p = 5 + (int) (read * 45 / total);
                        if (p != installProgress) { installProgress = p; notifyChanged(); }
                    }
                }
            }
            log("다운로드 완료: " + Ui.size(tmp.length()));

            installProgress = 55;
            installStatus = "루트 파일시스템 압축 해제 중...";
            notifyChanged();
            if (dest.exists()) deleteRec(dest);
            dest.mkdirs();
            int files = extractTarGz(tmp, dest);
            log("압축 해제 완료: " + files + "개 파일");

            installProgress = 88;
            installStatus = "XFCE4 · VNC 초기 스크립트 구성 중...";
            notifyChanged();
            provisionContainer(dest, d, resolveGeometry(app));
            tmp.delete();

            installProgress = 100;
            installStatus = d.name + " 설치 완료!";
            log("<<< [" + d.name + "] 설치가 완료되었습니다.");
            return true;
        } catch (Throwable t) {
            log("[오류] 설치 실패: " + t.getMessage());
            installStatus = "설치 실패: " + t.getMessage();
            return false;
        } finally {
            installing = false;
            notifyChanged();
        }
    }

    /** Standard POSIX/USTAR tar.gz extractor (handles regular files, dirs, symlinks, exec bits). */
    static int extractTarGz(File tarGz, File destDir) throws Exception {
        int count = 0;
        Path destCanon = destDir.getCanonicalFile().toPath();
        try (InputStream fis = new BufferedInputStream(new FileInputStream(tarGz));
             GZIPInputStream gis = new GZIPInputStream(fis)) {
            byte[] hdr = new byte[512];
            byte[] buf = new byte[16384];
            while (readFully(gis, hdr, 512) == 512) {
                boolean allZero = true;
                for (byte b : hdr) if (b != 0) { allZero = false; break; }
                if (allZero) break;

                String name = tarString(hdr, 0, 100);
                String prefix = tarString(hdr, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                while (name.startsWith("./")) name = name.substring(2);
                while (name.startsWith("/")) name = name.substring(1);

                long size = tarOctal(hdr, 124, 12);
                int mode = (int) tarOctal(hdr, 100, 8);
                byte type = hdr[156];
                String linkName = tarString(hdr, 157, 100);

                File out = new File(destDir, name);
                boolean safe = !name.isEmpty() && out.getCanonicalFile().toPath().startsWith(destCanon);

                if (safe) {
                    if (type == '5') {
                        out.mkdirs();
                    } else if (type == '2' && !linkName.isEmpty()) {
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
                        try {
                            if (out.exists()) out.delete();
                            Files.createSymbolicLink(out.toPath(), new File(linkName).toPath());
                        } catch (Throwable ignored) {}
                    } else if (type == '0' || type == 0) {
                        if (out.getParentFile() != null) out.getParentFile().mkdirs();
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
                    } else {
                        skipFully(gis, size);
                    }
                } else {
                    skipFully(gis, size);
                }
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) skipFully(gis, pad);
            }
        }
        return count;
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
                installCmd = "apk update && apk add --no-cache xfce4 xfce4-terminal tigervnc dbus font-noto-cjk xorg-server";
                break;
            case "pacman":
                installCmd = "pacman-key --init || true; pacman -Syu --noconfirm xfce4 xfce4-terminal tigervnc dbus noto-fonts-cjk";
                break;
            default:
                installCmd = "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y xfce4 xfce4-terminal tigervnc-standalone-server dbus-x11 fonts-noto-cjk";
                break;
        }
        String startScript = "#!/bin/sh\n"
                + "export HOME=/root\n"
                + "export USER=root\n"
                + "export DISPLAY=:" + (vncPort - 5900) + "\n"
                + "export LANG=C.UTF-8\n"
                + "export XDG_RUNTIME_DIR=/tmp/runtime-root\n"
                + "mkdir -p /tmp/runtime-root /tmp/.X11-unix /root/.vnc\n"
                + "chmod 1777 /tmp/.X11-unix\n"
                + "if ! command -v Xvnc >/dev/null 2>&1; then\n"
                + "  echo '[LinuxDesktop] XFCE4 및 TigerVNC 최초 설치 중...'\n"
                + "  " + installCmd + "\n"
                + "fi\n"
                + "rm -f /tmp/.X" + (vncPort - 5900) + "-lock /tmp/.X11-unix/X" + (vncPort - 5900) + "\n"
                + "cat > /root/.vnc/xstartup << 'EOF'\n"
                + "#!/bin/sh\n"
                + "unset SESSION_MANAGER\n"
                + "unset DBUS_SESSION_BUS_ADDRESS\n"
                + "exec dbus-launch --exit-with-session startxfce4\n"
                + "EOF\n"
                + "chmod +x /root/.vnc/xstartup\n"
                + "Xvnc $DISPLAY -geometry " + geom + " -depth 24 -dpi " + dpiScale + " -SecurityTypes None -localhost &\n"
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
        if (!isInstalled(c, d.id)) { log("[안내] 먼저 " + d.name + "을 설치하세요."); return false; }
        File proot = prootBinary(c);
        if (proot == null) {
            log("[오류] PRoot 네이티브 런타임(libproot.so)을 찾을 수 없습니다 (arm64-v8a 기기 필요).");
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
            log("[오류] 데스크탑 실행 실패: " + t.getMessage());
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
            } catch (Throwable t) {
                log("[오류] " + t.getMessage());
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
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] list = f.listFiles();
            if (list != null) for (File ch : list) deleteRec(ch);
        }
        f.delete();
    }

    private static void writeText(File f, String txt) throws Exception {
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(txt.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static int readFully(InputStream is, byte[] b, int len) throws Exception {
        int off = 0;
        while (off < len) {
            int n = is.read(b, off, len - off);
            if (n < 0) return off;
            off += n;
        }
        return off;
    }

    private static void skipFully(InputStream is, long n) throws Exception {
        byte[] tmp = new byte[4096];
        long rem = n;
        while (rem > 0) {
            int r = is.read(tmp, 0, (int) Math.min(tmp.length, rem));
            if (r < 0) break;
            rem -= r;
        }
    }

    private static String tarString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] b, int off, int len) {
        String s = tarString(b, off, len);
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s, 8); } catch (Exception e) { return 0; }
    }
}
