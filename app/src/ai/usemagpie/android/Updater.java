package ai.usemagpie.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * 应用内实时更新。
 *
 * <p>为什么更新整包而不是只换引擎：Android 10 起的 W^X 只允许执行
 * {@code nativeLibraryDir} 里的文件，那个目录安装后只读，引擎没法热替换。
 * 想让引擎跟上电脑版（上游一天几十个版本），只能整包更新——这也是 Android 原生
 * 应用做自动更新的唯一合规路径：{@link PackageInstaller}，不需要 root、不需要无障碍。
 *
 * <p>流程：查 GitHub Release → 比版本 → 下载 → 校验 SHA-256 → 交给系统安装器。
 * 网络与写盘都在后台线程，回调切回主线程。
 */
public final class Updater {

    /** 本项目的发布仓库：APK 在这里。 */
    public static final String HOST_REPO = "SuperXZY/magpie-android";
    /** 上游 releases 仓库：只读版本号，用来显示「电脑版到哪了」。 */
    public static final String UPSTREAM_REPO = "yetone/magpie-releases";

    public static final String APK_ASSET = "magpie.apk";
    private static final String UA = "magpie-android-updater";

    public interface Callback {
        /** 主线程，一句状态文案。 */
        void say(String text);

        /** 主线程，下载进度。 */
        void progress(float fraction, long done, long total);
    }

    /** 一次查询的结果。 */
    public static final class Check {
        public String hostTag = "";
        public String upstreamTag = "";
        public String ourVersion = "";
        public int ourCode = 0;
        public Release newer;
        public String error;
    }

    /** 一个可安装的版本。 */
    public static final class Release {
        public String tag = "";
        public String apkUrl = "";
        public long size = 0;
        public String sha256 = "";
    }

    private Updater() {
    }

    /* --------------------------------------------------------------- 版本号 */

    public static String versionName(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName == null ? "?" : p.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    public static int versionCode(Context ctx) {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** "v1.1.0" / "1.1.0" → {1,1,0}；解析不出来返回 null。 */
    private static int[] parse(String tag) {
        if (tag == null) {
            return null;
        }
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int cut = s.indexOf('-');
        if (cut > 0) {
            s = s.substring(0, cut);
        }
        String[] parts = s.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        int[] out = new int[]{0, 0, 0};
        try {
            for (int i = 0; i < parts.length && i < 3; i++) {
                out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", ""));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** a 比 b 新则 true。 */
    private static boolean newerThan(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (x[i] != y[i]) {
                return x[i] > y[i];
            }
        }
        return false;
    }

    /* ----------------------------------------------------------------- 网络 */

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                return sb.toString();
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject latestRelease(String repo) throws Exception {
        return new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest"));
    }

    private static Release releaseOf(JSONObject o) throws Exception {
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!APK_ASSET.equals(a.optString("name"))) {
                    continue;
                }
                r.apkUrl = a.optString("browser_download_url", "");
                r.size = a.optLong("size");
                String d = a.optString("digest", "");
                if (d.startsWith("sha256:")) {
                    r.sha256 = d.substring(7).toLowerCase(Locale.ROOT);
                }
                break;
            }
        }
        return r;
    }

    /* ----------------------------------------------------------------- 检查 */

    /** 查两个仓库。**必须在后台线程调**。 */
    public static Check check(Context ctx) {
        Check c = new Check();
        c.ourVersion = versionName(ctx);
        c.ourCode = versionCode(ctx);
        try {
            JSONObject host = latestRelease(HOST_REPO);
            c.hostTag = host.optString("tag_name", "");
            Release r = releaseOf(host);
            if (!r.apkUrl.isEmpty() && newerThan(r.tag, c.ourVersion)) {
                c.newer = r;
            }
        } catch (Exception e) {
            c.error = "查发布页失败：" + shortErr(e);
        }
        try {
            c.upstreamTag = latestRelease(UPSTREAM_REPO).optString("tag_name", "");
        } catch (Exception e) {
            if (c.error == null) {
                c.error = "查上游版本失败：" + shortErr(e);
            }
        }
        return c;
    }

    private static String shortErr(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 90 ? m.substring(0, 90) : m;
    }

    /* ------------------------------------------------------- 下载 + 安装 */

    /** 是否允许安装未知来源；API 26 以下恒 true。 */
    public static boolean canInstall(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return true;
        }
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** 跳到「安装未知应用」授权页。 */
    public static void openInstallPermission(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName())));
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES));
            } catch (Exception ignored) {
                // 少数 ROM 没这个页面，只能让用户自己去设置里找
            }
        }
    }

    /**
     * 下载并交给系统安装器。**在后台线程调**，回调已切主线程。
     * 提交后系统会弹安装界面，本进程随后可能被杀（正常）。
     */
    public static void downloadAndInstall(final Activity act, final Release r, final Callback cb) {
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dir = new File(act.getCacheDir(), "update");
            if (!dir.isDirectory() && !dir.mkdirs()) {
                post(main, cb, "无法创建下载目录");
                return;
            }
            File apk = new File(dir, APK_ASSET);
            try {
                download(r, apk, main, cb);
                post(main, cb, "校验中…");
                String got = sha256(apk);
                if (!r.sha256.isEmpty() && !r.sha256.equalsIgnoreCase(got)) {
                    apk.delete();
                    post(main, cb, "校验失败（期望 " + cut(r.sha256) + "，实际 " + cut(got) + "），已删除");
                    return;
                }
                post(main, cb, "校验通过，正在唤起安装器…");
                install(act, apk);
            } catch (Exception e) {
                apk.delete();
                post(main, cb, "更新失败：" + shortErr(e));
            }
        }, "magpie-update").start();
    }

    private static String cut(String h) {
        return h == null || h.length() < 12 ? String.valueOf(h) : h.substring(0, 12) + "…";
    }

    private static void post(Handler h, final Callback cb, final String text) {
        h.post(() -> cb.say(text));
    }

    private static void download(final Release r, final File out,
                                 final Handler main, final Callback cb) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", UA);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            long total = c.getContentLength() > 0 ? c.getContentLength() : r.size;
            long done = 0;
            long lastPost = 0;
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = c.getInputStream();
                 OutputStream os = new FileOutputStream(out)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastPost > 400) {
                        lastPost = now;
                        final float f = total > 0 ? (float) done / total : 0f;
                        final long d = done, t = total;
                        main.post(() -> cb.progress(f, d, t));
                    }
                }
                os.flush();
            }
            if (total > 0 && done < total) {
                throw new IOException("下载不完整：" + done + "/" + total);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** 交给 PackageInstaller：不需要 FileProvider、不需要外部存储。 */
    private static void install(Activity act, File apk) throws IOException {
        PackageInstaller pm = act.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pm.createSession(sp);
        PackageInstaller.Session s = pm.openSession(id);
        try {
            long len = apk.length();
            try (InputStream in = new FileInputStream(apk);
                 OutputStream os = s.openWrite("base.apk", 0, len)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                }
                os.flush();
                s.fsync(os);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            // 装完由系统拉起本应用；用 Activity 而不是自定义广播，
            // 省掉 Android 13+ 动态注册接收器要带的导出标记。
            Intent back = new Intent(act, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(act, 0, back, flags);
            s.commit(pi.getIntentSender());
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
                // 提交后 session 由系统接管
            }
        }
    }
}
