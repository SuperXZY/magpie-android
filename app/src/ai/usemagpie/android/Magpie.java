package ai.usemagpie.android;

import android.content.Context;

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

    /**
     * 开机自启开关，存在文件里：BootReceiver 在默认进程、服务在 :magpie 进程，
     * SharedPreferences 默认不跨进程同步。
     */
    public static boolean autostart(Context ctx) {
        init(ctx);
        File f = new File(ctx.getFilesDir(), "autostart");
        if (!f.isFile()) {
            return true;
        }
        try {
            return !"off".equals(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return true;
        }
    }

    public static void setAutostart(Context ctx, boolean on) {
        init(ctx);
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "autostart"), false)) {
            w.write(on ? "on" : "off");
        } catch (IOException e) {
            append("写 autostart 失败: " + e);
        }
    }

    /** 悬浮窗开关：默认开，用户关过就不再自动开。 */
    public static boolean floatEnabled(Context ctx) {
        init(ctx);
        File f = new File(ctx.getFilesDir(), "float");
        if (!f.isFile()) {
            return true;
        }
        try {
            return !"off".equals(new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            return true;
        }
    }

    public static void setFloatEnabled(Context ctx, boolean on) {
        init(ctx);
        try (FileWriter w = new FileWriter(new File(ctx.getFilesDir(), "float"), false)) {
            w.write(on ? "on" : "off");
        } catch (IOException e) {
            append("写 float 失败: " + e);
        }
    }
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
        // logFile 必须先赋值：mkdirs 失败时会调用 append，而 append 依赖它。
        // 之前放在后面，首次初始化的错误全被静默丢掉了。
        logFile = new File(files, "magpie.log");
        home = new File(files, "home");
        configDir = new File(home, ".config");
        cacheDir = new File(home, ".cache");
        mkdirs(home);
        mkdirs(configDir);
        mkdirs(cacheDir);
        mkdirs(new File(configDir, "magpie"));
        mkdirs(new File(cacheDir, "magpie"));
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

    /**
     * webKey 存在文件里而不是 SharedPreferences：引擎跑在 :magpie 进程、
     * 界面跑在 :main 进程，SharedPreferences 默认不跨进程同步，两个进程
     * 各自首次初始化会生成两个不同的 key，界面就拿不到引擎的 cookie。
     */
    private static String loadWebKey(Context ctx) {
        File f = new File(ctx.getFilesDir(), "webkey");
        try {
            if (f.isFile()) {
                String k = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (k.length() >= 16) {
                    return k;
                }
            }
        } catch (IOException ignored) {
            // 读失败就重新生成
        }
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xf, 16));
            sb.append(Character.forDigit(x & 0xf, 16));
        }
        String k = sb.toString();
        try (FileWriter w = new FileWriter(f, false)) {
            w.write(k);
        } catch (IOException e) {
            append("写 webkey 失败: " + e);
        }
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

    /**
     * 引擎状态。
     *
     * <p>服务和界面现在在同一个进程里，静态字段就是共享的，直接读写即可。
     * 早先用状态文件跨进程同步，但那需要独立进程；独立进程在部分 ROM 上
     * 起不来，反而导致「一直启动中」。文件仍然写一份，只作为下次启动时
     * 判断上次是否异常退出的依据，不再用于实时状态。
     */
    private static void saveState(Context ctx, State s, String err) {
        state = s;
        lastError = err == null ? "" : err;
        try {
            File f = new File(ctx.getFilesDir(), "engine.state");
            String line = s.name() + "\n" + lastError + "\n" + startedAt + "\n";
            try (FileWriter w = new FileWriter(f, false)) {
                w.write(line);
            }
        } catch (IOException ignored) {
            // 状态文件写不下去不影响运行
        }
    }

    /**
     * 启动时纠正残留状态。
     *
     * <p>上次进程被杀时状态文件可能停在 STARTING/RUNNING，但端口其实没人在听。
     * 这种情况下界面会永远显示「启动中」，所以进程一起来就先核对一次。
     */
    public static void reconcile(Context ctx) {
        init(ctx);
        File f = new File(ctx.getFilesDir(), "engine.state");
        State prev = null;
        if (f.isFile()) {
            try {
                String[] parts = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).split("\n", -1);
                if (parts.length >= 1) {
                    prev = State.valueOf(parts[0].trim());
                }
            } catch (Exception ignored) {
                // 文件坏了就当没有
            }
        }
        boolean listening = probe(GATEWAY_PORT) || probe(WEB_PORT);
        if (!listening && (prev == State.STARTING || prev == State.RUNNING)) {
            append("上次退出时状态是 " + prev + "，但端口没有监听，重置为未运行");
            saveState(ctx, State.STOPPED, "");
        }
    }

    /** 同进程下不需要从文件刷新，保留接口让调用方不必改。 */
    public static void syncState(Context ctx) {
        // 同一进程，内存里的就是最新的
    }

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
     * 引擎二进制。
     *
     * <p>首选 APK 释放出的 <code>lib/arm64-v8a/libmagpie.so</code>（nativeLibraryDir）：
     * 那是 Android 唯一保证可执行的目录。
     *
     * <p>但少数机型/ROM 把 nativeLibraryDir 挂成 noexec，或者
     * <code>extractNativeLibs=false</code> 时该目录里没有文件。这时回退到
     * filesDir 下复制一份并 chmod 0755——Android 10+ 的 W^X 通常禁止执行
     * 应用私有目录的文件，但在部分 ROM（尤其未强制 enforce 的）仍然可行，
     * 所以作为兜底而不是首选。
     */
    public static File binary(Context ctx) {
        init(ctx);
        File lib = new File(new File(ctx.getApplicationInfo().nativeLibraryDir), "libmagpie.so");
        if (lib.isFile() && lib.length() > 0 && lib.canExecute()) {
            return lib;
        }
        if (lib.isFile() && lib.length() > 0) {
            append("lib 目录的引擎不可执行，尝试 chmod: " + lib);
            chmod(lib);
            if (lib.canExecute()) {
                return lib;
            }
        } else {
            append("nativeLibraryDir 里没有引擎: " + lib);
        }

        // 兜底：复制到 filesDir 并赋可执行位
        File out = new File(new File(ctx.getFilesDir(), "bin"), "magpie");
        try {
            long want = lib.isFile() ? lib.length() : 0;
            if (out.isFile() && out.length() == want && want > 0 && out.canExecute()) {
                return out;
            }
            if (!lib.isFile()) {
                return lib; // 连源文件都没有，交给调用方报错
            }
            mkdirs(out.getParentFile());
            File tmp = new File(out.getParentFile(), "magpie.tmp");
            try (java.io.InputStream in = new java.io.FileInputStream(lib);
                 java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
            }
            if (out.exists()) {
                out.delete();
            }
            if (!tmp.renameTo(out)) {
                append("无法把引擎复制到 " + out);
                return lib;
            }
            chmod(out);
            append("已把引擎复制到 " + out + "（canExecute=" + out.canExecute() + "）");
            return out;
        } catch (IOException e) {
            append("复制引擎失败: " + e);
            return lib;
        }
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
        // init 在锁外调用：它自己是 synchronized 方法，若在 LOCK 内调用
        // 会与其它路径形成反向的锁顺序（死锁）。
        init(ctx);
        final File bin = binary(ctx);
        synchronized (LOCK) {
            if (state == State.RUNNING || state == State.STARTING) {
                return;
            }
            // 旧进程还挂着（stop 是异步的）：先等它真的退掉，否则新进程会和它抢端口，
            // 表现为永远停在「启动中」。这里同步清掉。
            if (proc != null) {
                append("start: 上一个引擎还在，先结束它");
                killNow(ctx, proc);
                proc = null;
            }
            if (!bin.isFile()) {
                fail(ctx, "找不到引擎 " + bin);
                return;
            }
            // 可执行性是 Android 上最常见的失败点，先明确检查并报出来
            if (!bin.canExecute()) {
                append("引擎文件不可执行: " + bin + "（canExecute=false）");
            }
            stopWanted = false;
            startedAt = SystemClock.elapsedRealtime();
            saveState(ctx, State.STARTING, "");
            append("启动 " + bin + " (v" + version() + ")");
            append("nativeLibraryDir=" + ctx.getApplicationInfo().nativeLibraryDir);
            append("ABI=" + Build.SUPPORTED_ABIS[0] + " SDK=" + Build.VERSION.SDK_INT);

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
                append("进程已创建 pid=" + pidOf(p));
                pump(p);
                watch(ctx, p);
            } catch (IOException e) {
                // 这一条最关键：W^X、ABI 不匹配、文件缺失都在这里暴露
                fail(ctx, "无法执行引擎: " + e.getMessage());
                append("提示：permission denied 说明该目录不允许执行；"
                        + "exec format error 说明 APK 的 ABI 与设备不符");
            }
        }
    }

    /** 拿 Process 的 pid，仅用于日志。 */
    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 立刻结束一个进程，最多等 3 秒。 */
    private static void killNow(Context ctx, Process p) {
        try {
            p.destroy();
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    public static void stop(Context ctx) {
        init(ctx);
        Process p;
        synchronized (LOCK) {
            stopWanted = true;
            p = proc;
            proc = null;
            // 立刻置 STOPPED，界面不必等进程真正退出
            saveState(ctx, State.STOPPED, "");
        }
        if (p == null) {
            return;
        }
        append("停止引擎");
        killNow(ctx, p);
        append("引擎已停止");
    }

    public static void restart(Context ctx) {
        init(ctx);
        // 同步完成：先停干净，再启动。异步会让新进程和旧进程抢端口。
        stop(ctx);
        start(ctx);
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

    /**
     * 等引擎就绪。
     *
     * <p>两个判据：控制台端口（3430）能连上，或者进程已经打印了就绪行。
     * 只探一个端口会漏：引擎可能先开网关（3425）再开控制台。
     *
     * <p>启动超时是 60 秒，超时置 ERROR 而不是一直停在 STARTING —— 之前
     * 「一直显示启动中」就是这个状态没有出口。
     */
    private static void watch(Context ctx, Process p) {
        Thread t = new Thread(() -> {
            boolean ready = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                if (proc != p) {
                    return;
                }
                if (p.isAlive() && (probe(WEB_PORT) || probe(GATEWAY_PORT))) {
                    ready = true;
                    break;
                }
                if (!p.isAlive()) {
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (ready) {
                synchronized (LOCK) {
                    if (proc == p) {
                        saveState(ctx, State.RUNNING, "");
                    }
                }
                append("就绪: 网关 127.0.0.1:" + GATEWAY_PORT + " · 控制台 127.0.0.1:" + WEB_PORT);
            } else if (p.isAlive()) {
                // 还活着但端口一直没起来
                synchronized (LOCK) {
                    if (proc == p) {
                        saveState(ctx, State.ERROR, "引擎 60 秒内没有就绪");
                        append("引擎 60 秒内没有就绪，请查看日志");
                    }
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
                    if (stopWanted) {
                        saveState(ctx, State.STOPPED, "");
                    } else {
                        String why = "引擎退出，代码 " + code;
                        saveState(ctx, State.ERROR, why);
                        append(why);
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

    private static void fail(Context ctx, String why) {
        saveState(ctx, State.ERROR, why);
        append(why);
    }

    /* ------------------------------------------------------------------ log */

    public static void append(String line) {
        synchronized (LOG) {
            LOG.addLast(line);
            while (LOG.size() > LOG_MAX) {
                LOG.removeFirst();
            }
        }
        // 用 filesDir 现算路径：进程重启后静态 logFile 可能还没赋值，
        // 之前那样会静默丢掉最早的几行（恰恰是最关键的启动诊断）
        File f = logFile;
        if (f == null) {
            return;
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(line);
            w.write('\n');
        } catch (IOException ignored) {
            // 日志写不下去不该影响引擎
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

    /**
     * 日志从文件读：引擎在 :magpie 进程里写，界面在 :main 进程里读，
     * 内存里的 LOG 各是各的，只有文件是共享的。
     */
    public static String logFromFile(Context ctx) {
        init(ctx);
        if (logFile == null || !logFile.isFile()) {
            return "";
        }
        try {
            long len = logFile.length();
            long from = Math.max(0, len - 48 * 1024);   // 只看最后 48 KB
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "r")) {
                raf.seek(from);
                byte[] buf = new byte[(int) (len - from)];
                raf.readFully(buf);
                String s = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                if (from > 0) {
                    int nl = s.indexOf('\n');
                    if (nl >= 0) {
                        s = s.substring(nl + 1);
                    }
                }
                return s;
            }
        } catch (IOException e) {
            return logText();
        }
    }

    /** 引擎版本，取自 README 里的发行号；由构建脚本替换。 */
    public static String version() {
        return "0.1.4722";
    }

    /**
     * 诊断信息：界面在「启动中」卡住时显示这些，用户一眼能看出卡在哪。
     */
    public static String diagnose(Context ctx) {
        init(ctx);
        StringBuilder sb = new StringBuilder();
        File bin = binary(ctx);
        sb.append("状态: ").append(state).append('\n');
        if (!lastError.isEmpty()) {
            sb.append("错误: ").append(lastError).append('\n');
        }
        sb.append("引擎: ").append(bin.getAbsolutePath()).append('\n');
        sb.append("  存在=").append(bin.isFile())
          .append(" 可执行=").append(bin.canExecute())
          .append(" 大小=").append(bin.length() / (1024 * 1024)).append("MB\n");
        sb.append("端口: 网关 ").append(GATEWAY_PORT).append('=')
          .append(probe(GATEWAY_PORT) ? "监听中" : "未监听")
          .append(" · 控制台 ").append(WEB_PORT).append('=')
          .append(probe(WEB_PORT) ? "监听中" : "未监听").append('\n');
        Process p = proc;
        sb.append("进程: ").append(p == null ? "无" : (p.isAlive() ? "存活" : "已退出")).append('\n');
        sb.append("ABI: ").append(Build.SUPPORTED_ABIS[0])
          .append(" · SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("悬浮窗权限: ").append(FloatingMonitor.permitted(ctx) ? "已授予" : "未授予");
        return sb.toString();
    }

    public static String deviceLine() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE
                + " (SDK " + Build.VERSION.SDK_INT + ") · " + Build.SUPPORTED_ABIS[0];
    }
}
