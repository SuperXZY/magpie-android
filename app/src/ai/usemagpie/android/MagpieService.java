package ai.usemagpie.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 引擎的前台服务，跑在独立进程 :magpie 里。
 *
 * <p>保活是三层叠加的，因为国产 ROM 会逐层突破：
 * <ol>
 *   <li><b>独立进程 + 前台服务</b>：UI 退后台被冻结时，这个进程不跟着冻；</li>
 *   <li><b>悬浮窗</b>：带可见窗口的进程是 visible 优先级，冻结器根本不选它
 *       （这是最硬的一层，也是"切一下才好"的根治手段）；</li>
 *   <li><b>看门狗</b>：每 15 秒探一次端口，引擎真死了就拉起。</li>
 * </ol>
 */
public class MagpieService extends Service {

    static final String ACTION_START = "ai.usemagpie.android.START";
    static final String ACTION_STOP = "ai.usemagpie.android.STOP";
    static final String ACTION_PING = "ai.usemagpie.android.PING";
    static final String ACTION_FLOAT_ON = "ai.usemagpie.android.FLOAT_ON";
    static final String ACTION_FLOAT_OFF = "ai.usemagpie.android.FLOAT_OFF";
    static final String ACTION_RESTART = "ai.usemagpie.android.RESTART";

    private static final String TAG = "magpie-svc";
    private static final String CHANNEL = "magpie-gateway";
    private static final int NOTIF_ID = 0x4d47;

    /** 端口自检间隔。 */
    private static final long WATCHDOG_MS = 15_000;

    private PowerManager.WakeLock wake;
    private Handler handler;
    private Runnable watchdog;
    private FloatingMonitor monitor;

    /** 近一分钟的请求计数（粗粒度，只为挂件显示）。 */
    private long windowStart;
    private int okCount, failCount;

    @Override
    public void onCreate() {
        super.onCreate();
        Magpie.init(this);
        Magpie.reconcile(this);
        handler = new Handler(Looper.getMainLooper());
        monitor = new FloatingMonitor(this);
        windowStart = System.currentTimeMillis();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();

        // startForeground 一旦抛异常，整个进程会被系统杀掉——引擎也会跟着没。
        // 所以整段都包起来，失败时至少让引擎跑起来。
        try {
            return handle(action);
        } catch (Throwable e) {
            Magpie.append("服务处理 " + action + " 失败: " + e);
            try {
                Magpie.start(this);
            } catch (Throwable ignored) {
                // 引擎自己也起不来时，状态会由 Magpie 写进日志
            }
            return START_STICKY;
        }
    }

    private int handle(String action) {
        if (ACTION_STOP.equals(action)) {
            stopWatchdog();
            if (monitor != null) {
                monitor.hide();
            }
            releaseWake();
            // stop 里的 killNow 最多阻塞 5 秒；先置状态让界面立刻响应，
            // 真正的回收放到后台线程
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    Magpie.stop(app);
                } catch (Throwable e) {
                    Magpie.append("停止失败: " + e);
                }
            }, "magpie-stop").start();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_FLOAT_ON.equals(action)) {
            startForegroundCompat();
            acquireWake();
            Magpie.start(this);
            startWatchdog();
            boolean ok = monitor != null && monitor.show();
            Magpie.append(ok ? "悬浮窗已开启（进程保持 visible，不会被冻结）"
                    : "悬浮窗开启失败：需要「显示在其他应用上层」权限");
            return START_STICKY;
        }

        if (ACTION_FLOAT_OFF.equals(action)) {
            // 走 startForegroundService 进来的 intent 必须先 startForeground，
            // 否则 Android 12+ 抛 ForegroundServiceDidNotStartInTimeException
            startForegroundCompat();
            if (monitor != null) {
                monitor.hide();
            }
            Magpie.append("悬浮窗已关闭");
            return START_STICKY;
        }

        if (ACTION_RESTART.equals(action)) {
            startForegroundCompat();
            acquireWake();
            startWatchdog();
            Magpie.append("重启引擎");
            // restart 里的 killNow 最多阻塞 5 秒，不能占着 onStartCommand，
            // 否则触发 ANR。放到后台线程。
            final Context app = getApplicationContext();
            new Thread(() -> {
                try {
                    Magpie.restart(app);
                } catch (Throwable e) {
                    Magpie.append("重启失败: " + e);
                }
            }, "magpie-restart").start();
            return START_STICKY;
        }

        if (ACTION_PING.equals(action)) {
            startForegroundCompat();
            acquireWake();
            startWatchdog();
            tick();
            return START_STICKY;
        }

        // 前台服务必须在 5 秒内声明，否则系统抛 ANR 并杀进程
        startForegroundCompat();
        acquireWake();
        Magpie.start(this);
        startWatchdog();

        // 默认开启悬浮窗（用户关过就不再自动开）
        if (Magpie.floatEnabled(this) && monitor != null && !monitor.isShown()) {
            if (!monitor.show()) {
                Magpie.append("悬浮窗需要「显示在其他应用上层」权限，请到主界面授权");
            }
        }
        return START_STICKY;
    }

    /* ------------------------------------------------------------- 前台通知 */

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ch.setShowBadge(false);
            ch.setSound(null, null);
            ch.enableVibration(false);
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = buildNotification(pi);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "specialUse refused, falling back", e);
            startForeground(NOTIF_ID, n);
        }
    }

    private Notification buildNotification(PendingIntent pi) {
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_notify)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void refreshNotification() {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            Intent open = new Intent(this, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIF_ID, buildNotification(pi));
        } catch (Exception ignored) {
            // 通知更新失败不影响引擎
        }
    }

    /* --------------------------------------------------------------- WakeLock */

    private void acquireWake() {
        if (wake != null && wake.isHeld()) {
            return;
        }
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "magpie:gateway");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    private void releaseWake() {
        if (wake != null && wake.isHeld()) {
            wake.release();
        }
        wake = null;
    }

    /* ---------------------------------------------------------------- 看门狗 */

    private void startWatchdog() {
        if (watchdog != null) {
            return;
        }
        watchdog = new Runnable() {
            @Override
            public void run() {
                try {
                    tick();
                } catch (Exception e) {
                    Log.w(TAG, "watchdog tick failed", e);
                }
                if (watchdog != null) {
                    handler.postDelayed(this, WATCHDOG_MS);
                }
            }
        };
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            handler.removeCallbacks(watchdog);
            watchdog = null;
        }
    }

    /** 每次心跳：确认引擎活着；死了就拉起；刷新挂件与通知。 */
    private void tick() {
        boolean up = Magpie.probe(Magpie.GATEWAY_PORT);

        long now = System.currentTimeMillis();
        if (now - windowStart > 60_000) {
            windowStart = now;
            okCount = 0;
            failCount = 0;
        }
        if (up) {
            okCount++;
        } else {
            failCount++;
        }

        if (!up) {
            Magpie.State st = Magpie.state();
            if (st == Magpie.State.RUNNING || st == Magpie.State.ERROR || st == Magpie.State.STOPPED) {
                Magpie.append("看门狗：网关未应答，重新拉起引擎");
                Magpie.start(this);
            }
        }

        // 悬浮窗可能在启动时因权限未授予而失败；用户授权后（进程不必重启）
        // 这里会自动补上。这是「授权了却没有悬浮窗」的主要修复。
        if (monitor != null && Magpie.floatEnabled(this) && !monitor.isShown()) {
            if (FloatingMonitor.permitted(this)) {
                if (monitor.show()) {
                    Magpie.append("悬浮监控已补显示（权限已授予）");
                }
            }
        }

        if (monitor != null) {
            monitor.report(okCount, failCount);
        }
        refreshNotification();
    }

    /* ---------------------------------------------------------------- 生命周期 */

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 用户从最近任务里划掉 magpie：网关必须继续服务 dsh-go，所以不自杀
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        stopWatchdog();
        if (monitor != null) {
            monitor.hide();
        }
        releaseWake();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* --------------------------------------------------------------- 便捷入口 */

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MagpieService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, MagpieService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {
            // 服务已不在时忽略
        }
    }

    public static void ping(Context ctx) {
        Intent i = new Intent(ctx, MagpieService.class).setAction(ACTION_PING);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // 后台启动限制下失败是正常的
        }
    }

    public static void setFloat(Context ctx, boolean on) {
        Intent i = new Intent(ctx, MagpieService.class)
                .setAction(on ? ACTION_FLOAT_ON : ACTION_FLOAT_OFF);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // 后台启动限制下失败是正常的
        }
    }

    /** 重启引擎：由服务进程同步执行 stop + start，避免跨进程抢端口。 */
    public static void restart(Context ctx) {
        Intent i = new Intent(ctx, MagpieService.class).setAction(ACTION_RESTART);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
            // 后台启动限制下失败是正常的
        }
    }
}
