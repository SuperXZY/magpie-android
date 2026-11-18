package ai.usemagpie.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * magpie 引擎（Go 二进制，放在 jniLibs 里以 libmagpie.so 的名字随 APK 释放）
 * 的生命周期与状态。整个应用只有这一个进程，Service 与 Activity 共享这些静态字段。
 */
public final class Magpie {

    public static final String PREFS = "magpie";
    public static final int GATEWAY_PORT = 3425;
    public static final int WEB_PORT = 3430;
    /** 网关的本机口令，与 magpie 在桌面上给 agent 用的默认值一致。 */
    public static final String TOKEN = "magpie";

    public enum State { STOPPED, STARTING, RUNNING, ERROR }

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final int LOG_MAX = 800;

    private static volatile State state = State.STOPPED;
    private static volatile String lastError = "";
    private static volatile Process proc;
    private static volatile long startedAt;
    private static volatile boolean stopWanted;

    private static File home, configDir, cacheDir, logFile;
    private static String webKey;

    private Magpie() {
    }

    /* ---------------------------------------------------------------- paths */

    public static synchronized void init(Context ctx) {
        if (home != null) {
            return;
        }
        Context app = ctx.getApplicationContext();
        File files = app.getFilesDir();
        home = new File(files, "home");
        configDir = new File(home, ".config");
        cacheDir = new File(home, ".cache");
        mkdirs(home);
        mkdirs(configDir);
        mkdirs(cacheDir);
        mkdirs(new File(configDir, "magpie"));
        mkdirs(new File(cacheDir, "magpie"));
        logFile = new File(files, "magpie.log");
        webKey = loadWebKey(app);
        seedSettings();
    }

    private static void mkdirs(File f) {
        if (!f.isDirectory() && !f.mkdirs() && !f.isDirectory()) {
            append("无法创建目录 " + f);
        }
    }

    /** 首次运行时写一份 settings.json：关掉自更新，并把国内镜像默认打开。 */
    private static void seedSettings() {
        File s = new File(new File(configDir, "magpie"), "settings.json");
        if (s.isFile()) {
            return;
        }
        String json = "{\"noAutoUpdate\":true,\"noAutoDownload\":true,\"lang\":\"zh\","
                + "\"chinaMirror\":true,\"npmRegistry\":\"https://registry.npmmirror.com\"}\n";
        try (FileWriter w = new FileWriter(s)) {
            w.write(json);
        } catch (IOException e) {
            append("写 settings.json 失败: " + e);
        }
    }

    private static String loadWebKey(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String k = p.getString("webKey", null);
        if (k != null && k.length() >= 16) {
            return k;
        }
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        k = sb.toString();
        p.edit().putString("webKey", k).apply();
        return k;
    }

    public static String webKey(Context ctx) {
        init(ctx);
        return webKey;
    }

    public static String webUrl(Context ctx) {
        return "http://127.0.0.1:" + WEB_PORT + "/?k=" + webKey(ctx);
    }

    public static String gatewayUrl() {
        return "http://127.0.0.1:" + GATEWAY_PORT + "/v1";
    }

    public static File logFile(Context ctx) {
        init(ctx);
        return logFile;
    }

    public static File home(Context ctx) {
        init(ctx);
        return home;
    }

    /* ---------------------------------------------------------------- state */

    public static State state() {
        return state;
    }

    public static String lastError() {
        return lastError;
    }

    public static long startedAt() {
        return startedAt;
    }

    /** 已运行秒数，未运行时 0。 */
    public static long uptimeSeconds() {
        long t = startedAt;
        if (t <= 0 || state != State.RUNNING) {
            return 0;
        }
        return (SystemClock.elapsedRealtime() - t) / 1000;
    }

    public static boolean running() {
        return state == State.RUNNING;
    }

    public static boolean busy() {
        return state == State.STARTING;
    }

    /* -------------------------------------------------------------- process */

    /**
     * 引擎二进制：随 APK 以 libmagpie.so 释放到 nativeLibraryDir，并从那里执行。
     * Android 10 起禁止 execve 应用私有目录里的文件（W^X），只有 lib 目录可执行。
     */
    public static File binary(Context ctx) {
        init(ctx);
        return new File(new File(ctx.getApplicationInfo().nativeLibraryDir), "libmagpie.so");
    }

    /**
     * Bun 也必须在 lib 目录里：magpie 默认把 Bun 下到 ~/.cache/magpie/bun/<版本>/，
     * 那里不可执行。这里把随包的 libbun.so 指给 magpie（MAGPIE_BUN），
     * 插件就用它，不再下载。
     */
    public static File bun(Context ctx) {
        init(ctx);
        File f = new File(new File(ctx.getApplicationInfo().nativeLibraryDir), "libbun.so");
        return f.isFile() ? f : null;
    }

    private static void chmod(File f) {
        try {
            Process p = new ProcessBuilder("/system/bin/chmod", "0755", f.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ignored) {
            f.setExecutable(true, true);
        }
    }

    /** 引擎是否真的能跑起来（Android 10+ 的 W^X 只允许 lib 目录下的文件被执行）。 */
    public static boolean executable(File bin) {
        return bin.isFile() && bin.canExecute();
    }

    public static void start(Context ctx) {
        init(ctx);
        synchronized (LOCK) {
            if (state == State.RUNNING || state == State.STARTING) {
                return;
            }
            File bin = binary(ctx);
            if (!bin.isFile()) {
                fail("找不到引擎 " + bin);
                return;
            }
            stopWanted = false;
            state = State.STARTING;
            lastError = "";
            startedAt = SystemClock.elapsedRealtime();
            append("启动 " + bin + " (v" + version() + ")");

            List<String> cmd = new ArrayList<>();
            cmd.add(bin.getAbsolutePath());
            cmd.add("serve");
            cmd.add("--web=127.0.0.1:" + WEB_PORT);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(home);
            pb.redirectErrorStream(true);
            // 保留系统环境（Android 的解析器与属性服务依赖它），只覆盖 magpie 需要的
            Map<String, String> env = pb.environment();
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", new File(ctx.getCacheDir(), "tmp").getAbsolutePath());
            env.put("XDG_CONFIG_HOME", configDir.getAbsolutePath());
            env.put("XDG_CACHE_HOME", cacheDir.getAbsolutePath());
            env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
            env.put("MAGPIE_ADDR", "127.0.0.1:" + GATEWAY_PORT);
            env.put("MAGPIE_WEB_KEY", webKey);
            env.put("DO_NOT_TRACK", "1");
            env.put("DISABLE_AUTOUPDATER", "1");
            env.put("TZ", java.util.TimeZone.getDefault().getID());
            // 国内网络：raw.githubusercontent.com / github releases 直连常常 TLS 超时，
            // 打开 magpie 自己的镜像回退（npmmirror / gh-proxy）
            env.put("MAGPIE_MIRRORS", "on");
            env.put("MAGPIE_NPM_REGISTRY", "https://registry.npmmirror.com");
            env.put("MAGPIE_GITHUB_MIRROR", "https://gh-proxy.com");
            // Bun 从 lib 目录跑（应用私有目录在 Android 10+ 不可执行）
            File bun = bun(ctx);
            if (bun != null) {
                env.put("MAGPIE_BUN", bun.getAbsolutePath());
            }
            mkdirs(new File(ctx.getCacheDir(), "tmp"));

            try {
                Process p = pb.start();
                proc = p;
                pump(p);
                watch(p);
            } catch (IOException e) {
                fail("启动失败: " + e.getMessage());
            }
        }
    }

    public static void stop(Context ctx) {
        init(ctx);
        Process p;
        synchronized (LOCK) {
            stopWanted = true;
            p = proc;
            proc = null;
            if (p == null) {
                state = State.STOPPED;
                return;
            }
        }
        append("停止引擎");
        p.destroy();
        final Process dying = p;
        new Thread(() -> {
            try {
                if (!dying.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)) {
                    dying.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            synchronized (LOCK) {
                if (proc == null) {
                    state = State.STOPPED;
                }
            }
        }, "magpie-stop").start();
    }

    public static void restart(Context ctx) {
        stop(ctx);
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                Thread.sleep(1200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            start(app);
        }, "magpie-restart").start();
    }

    private static void pump(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    append(line);
                }
            } catch (IOException ignored) {
                // 进程结束时流被关掉是正常的
            }
        }, "magpie-log");
        t.setDaemon(true);
        t.start();
    }

    private static void watch(Process p) {
        Thread t = new Thread(() -> {
            Boolean up = null;
            for (int i = 0; i < 120; i++) {
                if (proc != p) {
                    return;
                }
                up = probe(WEB_PORT);
                if (Boolean.TRUE.equals(up)) {
                    synchronized (LOCK) {
                        if (proc == p) {
                            state = State.RUNNING;
                        }
                    }
                    append("就绪: 网关 127.0.0.1:" + GATEWAY_PORT + " · 控制台 127.0.0.1:" + WEB_PORT);
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            int code = -1;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (LOCK) {
                if (proc == p) {
                    proc = null;
                    state = stopWanted ? State.STOPPED : State.ERROR;
                    if (!stopWanted) {
                        lastError = "引擎退出，代码 " + code;
                        append(lastError);
                    }
                }
            }
        }, "magpie-watch");
        t.setDaemon(true);
        t.start();
    }

    /** 端口上有人应答就是起来了。 */
    public static boolean probe(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void fail(String why) {
        lastError = why;
        state = State.ERROR;
        append(why);
    }

    /* ------------------------------------------------------------------ log */

    public static void append(String line) {
        String stamped = line;
        synchronized (LOG) {
            LOG.addLast(stamped);
            while (LOG.size() > LOG_MAX) {
                LOG.removeFirst();
            }
        }
        if (logFile != null) {
            try (FileWriter w = new FileWriter(logFile, true)) {
                w.write(stamped);
                w.write('\n');
            } catch (IOException ignored) {
                // 日志写不下去不该影响引擎
            }
        }
    }

    public static String logText() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOG) {
            for (String l : LOG) {
                sb.append(l).append('\n');
            }
        }
        return sb.toString();
    }

    /** 引擎版本，取自 README 里的发行号；由构建脚本替换。 */
    public static String version() {
        return "0.1.4384";
    }

    public static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ") · " + Build.SUPPORTED_ABIS[0];
    }
}
