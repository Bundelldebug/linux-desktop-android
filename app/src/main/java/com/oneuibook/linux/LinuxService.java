package com.oneuibook.linux;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Keeps the rootless PRoot + Xvnc desktop session alive in the background. */
public final class LinuxService extends Service {
    private static final String CHANNEL = "linux_session";
    private PowerManager.WakeLock wakeLock;

    static void start(Context c, String label) {
        Intent i = new Intent(c, LinuxService.class).putExtra("label", label);
        try {
            c.startForegroundService(i);
        } catch (Throwable t) {
            LinuxEngine.log("[안내] 백그라운드 유지 서비스 시작 생략: " + t.getClass().getSimpleName());
        }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, LinuxService.class)); } catch (Throwable ignored) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            String label = intent != null && intent.getStringExtra("label") != null
                    ? intent.getStringExtra("label") : "리눅스 데스크탑";
            if ((intent != null && "STOP".equals(intent.getAction()))
                    || (!LinuxEngine.isRunning() && !LinuxEngine.launching)) {
                if (intent != null && "STOP".equals(intent.getAction())) LinuxEngine.stopDesktop(this);
                NotificationManager nm0 = getSystemService(NotificationManager.class);
                if (nm0 != null && nm0.getNotificationChannel(CHANNEL) == null) {
                    nm0.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW));
                }
                Notification tmp = new Notification.Builder(this, CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_linux)
                        .setContentTitle("리눅스 데스크탑 종료 중")
                        .build();
                if (Build.VERSION.SDK_INT >= 34) startForeground(101, tmp, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                else startForeground(101, tmp);
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
                return START_NOT_STICKY;
            }
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW));
            }
            PendingIntent openPi = PendingIntent.getActivity(this, 1,
                    new Intent(this, DesktopActivity.class).putExtra("host", "127.0.0.1").putExtra("port", LinuxEngine.vncPort),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stopPi = PendingIntent.getService(this, 2,
                    new Intent(this, LinuxService.class).setAction("STOP"),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_linux)
                    .setContentTitle(label + " 실행 중")
                    .setContentText("탭해서 데스크탑 화면 열기 · VNC :" + (LinuxEngine.vncPort - 5900))
                    .setOngoing(true)
                    .setContentIntent(openPi)
                    .addAction(new Notification.Action.Builder(null, "종료", stopPi).build())
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(101, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(101, n);
            }
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oneuibook:linux");
                wakeLock.acquire(4 * 60 * 60 * 1000L);
            }
            return START_NOT_STICKY;
        } catch (Throwable t) {
            LinuxEngine.log("[안내] 포그라운드 서비스 제한으로 일반 모드로 계속 실행: " + t.getMessage());
            try { stopSelf(); } catch (Throwable ignored) {}
            return START_NOT_STICKY;
        }
    }

    @Override public void onDestroy() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
