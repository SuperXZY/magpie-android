package ai.usemagpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面。设计要点：
 * <ul>
 *   <li>整页可滚动，小屏不会把按钮挤出屏幕；</li>
 *   <li>底部固定操作条，主操作（控制台 / 接入 dsh-go）始终可达；</li>
 *   <li>卡片分组，状态用颜色点 + 文案双重表达；</li>
 *   <li>控制台全屏覆盖，带返回按钮，处理刘海与状态栏内边距。</li>
 * </ul>
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusDot, statusTitle, statusSub;
    private TextView logView;
    private TextView chipPort, chipKey, chipFloat;
    private Button startBtn, stopBtn, restartBtn;
    private FrameLayout consoleHolder;
    private LinearLayout consoleOverlay;
    private WebView web;

    private TextView updStatus, updUpstream;
    private Button updBtn;
    private boolean updBusy;
    private Updater.Release pending;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Magpie.init(this);
        // 上次进程被杀时状态文件可能停在「启动中」，先核对一次
        Magpie.reconcile(this);
        setContentView(buildUi());
        askNotificationPermission();
        // 双路径启动：Service 负责保活与悬浮窗，但 Service 可能因 ROM 限制起不来，
        // 所以引擎在这里也拉一次。两者都是幂等的。
        //
        // 不能在 UI 线程同步调用：start() 里的 killNow 最多阻塞 5 秒，
        // 会把界面卡住、甚至 ANR。
        final Context app = getApplicationContext();
        new Thread(() -> {
            try {
                Magpie.start(app);
            } catch (Throwable e) {
                Magpie.append("引擎启动异常: " + e);
            }
        }, "magpie-boot").start();
        try {
            MagpieService.start(this);
        } catch (Throwable e) {
            Magpie.append("前台服务启动失败（不影响引擎）: " + e);
        }
        if (Magpie.floatEnabled(this) && !FloatingMonitor.permitted(this)) {
            ui.postDelayed(this::promptOverlayOnce, 1200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
        if (updUpstream != null) {
            maybeAutoCheck();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            consoleHolder.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (consoleOverlay != null && consoleOverlay.getVisibility() == View.VISIBLE) {
            closeConsole();
            return;
        }
        super.onBackPressed();
    }

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /* ---------------------------------------------------------------- 尺寸 */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 状态栏高度，用于给内容让位（刘海/挖孔）。 */
    private int statusBarInset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
            if (in != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return in.getInsets(WindowInsets.Type.statusBars()).top;
                }
                return in.getSystemWindowInsetTop();
            }
        }
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int navBarInset() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowInsets in = getWindow().getDecorView().getRootWindowInsets();
            if (in != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    return in.getInsets(WindowInsets.Type.navigationBars()).bottom;
                }
                return in.getSystemWindowInsetBottom();
            }
        }
        return dp(16);
    }

    /* ------------------------------------------------------------------ UI */

    private View buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(getColor(R.color.bg));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFitsSystemWindows(false);
        page.setPadding(0, statusBarInset(), 0, 0);
        shell.addView(page, new FrameLayout.LayoutParams(-1, -1));

        // ---- 顶部标题栏 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(20), dp(16), dp(16), dp(12));
        TextView brand = text("magpie", 24, R.color.fg, Typeface.BOLD);
        top.addView(brand);
        TextView ver = text(" v" + Magpie.version(), 11, R.color.muted, Typeface.NORMAL);
        ver.setPadding(0, dp(6), 0, 0);
        top.addView(ver);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        // 悬浮窗快捷开关
        TextView floatChip = chip("悬浮");
        floatChip.setOnClickListener(v -> toggleFloat());
        top.addView(floatChip);
        page.addView(top);

        // ---- 可滚动主体 ----
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBaselineAligned(false);
        body.setPadding(dp(16), 0, dp(16), dp(12));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 状态卡
        LinearLayout statusCard = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setBaselineAligned(false);
        head.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 18, R.color.muted, Typeface.NORMAL);
        head.addView(statusDot);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
        tlp.setMargins(dp(10), 0, 0, 0);
        statusTitle = text("未运行", 18, R.color.fg, Typeface.BOLD);
        statusSub = text("", 12, R.color.muted, Typeface.NORMAL);
        statusSub.setPadding(0, dp(3), 0, 0);
        titles.addView(statusTitle);
        titles.addView(statusSub);
        head.addView(titles, tlp);
        statusCard.addView(head);

        // 信息 chips
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(14), 0, 0);
        chipPort = chip(":3425");
        chipKey = chip("key magpie");
        chipFloat = chip("悬浮");
        chips.addView(chipPort);
        chips.addView(gap(dp(8)));
        chips.addView(chipKey);
        chips.addView(gap(dp(8)));
        chips.addView(chipFloat);
        statusCard.addView(chips);

        // 控制按钮
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setPadding(0, dp(16), 0, 0);
        startBtn = pill("启动", true);
        stopBtn = pill("停止", false);
        restartBtn = pill("重启", false);
        ctl.addView(startBtn, weight1());
        ctl.addView(gap(dp(10)));
        ctl.addView(stopBtn, weight1());
        ctl.addView(gap(dp(10)));
        ctl.addView(restartBtn, weight1());
        statusCard.addView(ctl);

        startBtn.setOnClickListener(v -> {
            MagpieService.start(this);
            toast("正在启动引擎");
            ui.postDelayed(this::refresh, 600);
        });
        stopBtn.setOnClickListener(v -> {
            MagpieService.stop(this);
            toast("已停止");
            ui.postDelayed(this::refresh, 400);
        });
        restartBtn.setOnClickListener(v -> {
            // 交给服务进程做同步的 stop+start，避免两个进程抢同一个引擎
            MagpieService.restart(this);
            toast("正在重启");
            ui.postDelayed(this::refresh, 700);
        });

        body.addView(statusCard, blockMargin());

        // 接入卡
        body.addView(sectionTitle("接入 dsh-go"), tightMargin());
        LinearLayout dshCard = card();
        dshCard.addView(kv("Base URL", Magpie.gatewayUrl()));
        dshCard.addView(divider());
        dshCard.addView(kv("API Key", Magpie.TOKEN));
        dshCard.addView(divider());
        dshCard.addView(kv("协议", "OpenAI（Chat Completions）"));
        LinearLayout dshActions = new LinearLayout(this);
        dshActions.setOrientation(LinearLayout.HORIZONTAL);
        dshActions.setPadding(0, dp(14), 0, 0);
        Button copyBtn = pill("复制参数", false);
        Button guideBtn = pill("接入说明", false);
        copyBtn.setOnClickListener(v -> copyDshRecipe());
        guideBtn.setOnClickListener(v -> showDshSheet());
        dshActions.addView(copyBtn, weight1());
        dshActions.addView(gap(dp(10)));
        dshActions.addView(guideBtn, weight1());
        dshCard.addView(dshActions);
        body.addView(dshCard, blockMargin());

        // 版本与更新卡
        body.addView(sectionTitle("版本与更新"), tightMargin());
        body.addView(buildUpdateCard(), blockMargin());

        // 保活卡
        body.addView(sectionTitle("后台保活"), tightMargin());
        LinearLayout keepCard = card();
        keepCard.addView(settingRow("悬浮监控", () -> Magpie.floatEnabled(this) ? "已开启" : "已关闭",
                () -> Magpie.floatEnabled(this) ? "关闭" : "开启", this::toggleFloat));
        keepCard.addView(divider());
        keepCard.addView(settingRow("悬浮窗权限",
                () -> FloatingMonitor.permitted(this) ? "已授权" : "未授权",
                () -> FloatingMonitor.permitted(this) ? "已授权" : "去授权", this::askOverlay));
        keepCard.addView(divider());
        keepCard.addView(settingRow("开机自启",
                () -> Magpie.autostart(this) ? "已开启" : "已关闭",
                () -> Magpie.autostart(this) ? "关闭" : "开启", this::toggleAutostart));
        keepCard.addView(divider());
        keepCard.addView(settingRow("电池优化",
                () -> "建议加入白名单", () -> "设置", this::askBattery));
        body.addView(keepCard, blockMargin());

        // 日志卡
        body.addView(sectionTitle("引擎日志"), tightMargin());
        LinearLayout logCard = card();
        logView = text("", 11, R.color.muted, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(2), 1f);
        logView.setTextIsSelectable(true);
        logCard.addView(logView);
        body.addView(logCard, blockMargin());

        body.addView(text(Magpie.deviceLine(), 11, R.color.muted, Typeface.NORMAL), blockMargin());

        // ---- 底部固定操作条 ----
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(16), dp(10), dp(16), dp(10) + navBarInset());
        bottom.setBackgroundColor(getColor(R.color.surface));
        Button consoleBtn = pill("打开控制台", true);
        consoleBtn.setOnClickListener(v -> openConsole());
        Button dshBtn = pill("接入 dsh-go", false);
        dshBtn.setOnClickListener(v -> showDshSheet());
        bottom.addView(consoleBtn, weight1());
        bottom.addView(gap(dp(10)));
        bottom.addView(dshBtn, weight1());
        page.addView(bottom);

        shell.addView(buildConsoleOverlay(), new FrameLayout.LayoutParams(-1, -1));
        return shell;
    }

    /* ---------------------------------------------------------------- 更新 */

    /**
     * 引擎活在 APK 的 lib/ 里，安装后只读，没法单独替换；所以「和电脑版同步」
     * 落到整包更新上。一张卡里说清三件事：本包版本、内置引擎、上游到哪了。
     */
    private View buildUpdateCard() {
        LinearLayout c = card();
        c.addView(kv("应用版本", Updater.versionName(this) + " · " + Updater.versionCode(this)));
        c.addView(divider());
        c.addView(kv("内置引擎", "magpie " + Magpie.version()));
        c.addView(divider());
        updUpstream = kvLive(c, "上游最新", "未检查");
        c.addView(divider());

        updStatus = text("检查更新会把新引擎随整包一起装上", 11, R.color.muted, Typeface.NORMAL);
        updStatus.setLineSpacing(dp(2), 1f);
        c.addView(updStatus);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(14), 0, 0);
        updBtn = pill("检查更新", false);
        Button relBtn = pill("发布页", false);
        updBtn.setOnClickListener(v -> onUpdateButton());
        relBtn.setOnClickListener(v -> openReleasePage());
        actions.addView(updBtn, weight1());
        actions.addView(gap(dp(10)));
        actions.addView(relBtn, weight1());
        c.addView(actions);
        return c;
    }

    private void onUpdateButton() {
        if (pending != null) {
            startUpdate(pending);
        } else {
            checkUpdates();
        }
    }

    /** 查 GitHub：本仓库的 release 决定能不能升级，上游仓库只用来显示版本。 */
    private void checkUpdates() {
        if (updBusy) {
            return;
        }
        updBusy = true;
        updBtn.setEnabled(false);
        updBtn.setText("检查中…");
        updStatus.setTextColor(getColor(R.color.muted));
        updStatus.setText("正在查询 GitHub…");
        new Thread(() -> {
            final Updater.Check c = Updater.check(this);
            ui.post(() -> {
                updBusy = false;
                updBtn.setEnabled(true);
                prefs().edit().putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
                updUpstream.setText(c.upstreamTag.isEmpty() ? "未知" : "magpie " + c.upstreamTag);
                if (c.newer != null) {
                    pending = c.newer;
                    updBtn.setText("下载并安装");
                    updStatus.setTextColor(getColor(R.color.accent));
                    updStatus.setText("有新版本 " + c.newer.tag + " · " + mb(c.newer.size)
                            + "，点「下载并安装」；装之前会校验 SHA-256");
                } else {
                    pending = null;
                    updBtn.setText("检查更新");
                    updStatus.setTextColor(c.error == null ? getColor(R.color.ok) : getColor(R.color.warn));
                    updStatus.setText(c.error != null ? c.error
                            : "已是最新（本包 " + c.ourVersion + "）");
                }
            });
        }, "magpie-check").start();
    }

    private void startUpdate(final Updater.Release r) {
        if (!Updater.canInstall(this)) {
            updStatus.setTextColor(getColor(R.color.warn));
            updStatus.setText("还需要允许一次「安装未知应用」");
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("Android 要求手动授权一次：设置 → 安装未知应用 → magpie → 允许。"
                            + "授权后返回本页，再点一次「下载并安装」。")
                    .setPositiveButton("去授权", (d, w) -> Updater.openInstallPermission(this))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        updBtn.setEnabled(false);
        updBtn.setText("准备下载…");
        updStatus.setTextColor(getColor(R.color.muted));
        updStatus.setText("开始下载…");
        Updater.downloadAndInstall(this, r, new Updater.Callback() {
            @Override
            public void say(String text) {
                updStatus.setText(text);
                boolean bad = text.contains("失败") || text.contains("已删除");
                updBtn.setEnabled(bad || !text.contains("安装"));
                updBtn.setText(bad ? "重试" : (text.contains("安装") ? "等待安装" : "下载中…"));
            }

            @Override
            public void progress(float fraction, long done, long total) {
                updBtn.setText("下载 " + Math.round(fraction * 100) + "%");
                updStatus.setText(mb(done) + " / " + mb(total));
            }
        });
    }

    private String mb(long bytes) {
        if (bytes <= 0) {
            return "—";
        }
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576f);
    }

    private void openReleasePage() {
        openExternally(Uri.parse("https://github.com/" + Updater.HOST_REPO + "/releases/latest"));
    }

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences(Magpie.PREFS, MODE_PRIVATE);
    }

    /** 6 小时一次静默自检，避免每次开屏都打 GitHub API。 */
    private void maybeAutoCheck() {
        long last = prefs().getLong("lastUpdateCheck", 0);
        if (System.currentTimeMillis() - last > 6L * 3600 * 1000) {
            ui.postDelayed(this::checkUpdates, 1500);
        }
    }

    /* --------------------------------------------------------------- 控件工厂 */

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        return c;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getColor(R.color.stroke));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
        p.setMargins(0, dp(10), 0, dp(10));
        v.setLayoutParams(p);
        return v;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 12, R.color.muted, Typeface.BOLD);
        t.setLetterSpacing(0.06f);
        return t;
    }

    private LinearLayout.LayoutParams blockMargin() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(16));
        return p;
    }

    private LinearLayout.LayoutParams tightMargin() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(4), 0, 0, dp(8));
        return p;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, -2, 1f);
    }

    private View gap(int w) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
        return v;
    }

    private TextView text(String s, float size, int color, int style) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(getColor(color));
        t.setTypeface(Typeface.create("sans-serif", style));
        return t;
    }

    /** 小圆角标签。 */
    private TextView chip(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(getColor(R.color.fg2));
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.chip));
        bg.setCornerRadius(dp(20));
        bg.setStroke(Math.max(1, dp(0.5f)), getColor(R.color.stroke));
        t.setBackground(bg);
        return t;
    }

    /** 大圆角按钮，primary 用强调色。 */
    private Button pill(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(primary ? 0xFF0B0D10 : getColor(R.color.fg));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? getColor(R.color.accent) : getColor(R.color.surface2));
        bg.setCornerRadius(dp(12));
        if (!primary) {
            bg.setStroke(Math.max(1, dp(0.5f)), getColor(R.color.stroke));
        }
        b.setBackground(bg);
        b.setMinHeight(dp(46));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setStateListAnimator(null);
        return b;
    }

    private View kv(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = text(k, 13, R.color.muted, Typeface.NORMAL);
        key.setSingleLine(true);
        row.addView(key);
        TextView val = text(v, 13, R.color.fg, Typeface.NORMAL);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
        vlp.setMargins(dp(12), 0, 0, 0);
        row.addView(val, vlp);
        return row;
    }

    /** 与 kv 相同，但把右侧值返回出来，便于后续 setText。 */
    private TextView kvLive(LinearLayout card, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView key = text(k, 13, R.color.muted, Typeface.NORMAL);
        key.setSingleLine(true);
        row.addView(key);
        TextView val = text(v, 13, R.color.fg, Typeface.NORMAL);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        val.setGravity(Gravity.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, -2, 1f);
        vlp.setMargins(dp(12), 0, 0, 0);
        row.addView(val, vlp);
        card.addView(row);
        return val;
    }

    /** 带右侧操作的一行设置项。 */
    private View settingRow(String label, java.util.function.Supplier<String> value,
                            java.util.function.Supplier<String> action, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(label, 14, R.color.fg, Typeface.NORMAL));
        texts.addView(text(value.get(), 11, R.color.muted, Typeface.NORMAL));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView act = text(action.get(), 13, R.color.accent, Typeface.BOLD);
        act.setPadding(dp(12), dp(8), dp(2), dp(8));
        act.setOnClickListener(v -> onClick.run());
        row.addView(act);
        return row;
    }

    /* -------------------------------------------------------------- 状态刷新 */

    private void refresh() {
        Magpie.syncState(this);
        Magpie.State s = Magpie.state();

        int color;
        String title;
        switch (s) {
            case RUNNING:
                color = R.color.ok;
                title = "运行中";
                break;
            case STARTING:
                color = R.color.warn;
                title = "启动中…";
                break;
            case ERROR:
                color = R.color.err;
                title = "异常退出";
                break;
            default:
                color = R.color.muted;
                title = "未运行";
        }
        statusDot.setTextColor(getColor(color));
        statusTitle.setText(title);

        String sub;
        if (s == Magpie.State.RUNNING) {
            long up = Magpie.uptimeSeconds();
            sub = "网关 127.0.0.1:" + Magpie.GATEWAY_PORT
                    + (up > 0 ? " · 已运行 " + human(up) : "");
        } else if (s == Magpie.State.ERROR) {
            sub = Magpie.lastError();
        } else {
            sub = "点「启动」把网关跑起来";
        }
        statusSub.setText(sub);

        startBtn.setEnabled(s != Magpie.State.RUNNING && s != Magpie.State.STARTING);
        stopBtn.setEnabled(s == Magpie.State.RUNNING || s == Magpie.State.STARTING);
        restartBtn.setEnabled(s == Magpie.State.RUNNING);
        startBtn.setAlpha(startBtn.isEnabled() ? 1f : 0.45f);
        stopBtn.setAlpha(stopBtn.isEnabled() ? 1f : 0.45f);
        restartBtn.setAlpha(restartBtn.isEnabled() ? 1f : 0.45f);

        boolean fl = Magpie.floatEnabled(this);
        boolean perm = FloatingMonitor.permitted(this);
        chipFloat.setText(fl ? (perm ? "悬浮 · 防冻结" : "悬浮 · 缺权限") : "悬浮 · 关");
        chipFloat.setTextColor(getColor(fl && perm ? R.color.ok : R.color.muted));

        // 卡在「启动中」超过 12 秒时，把诊断信息顶到日志前面，
        // 用户不必再猜是权限、ABI 还是端口占用
        StringBuilder shown = new StringBuilder();
        if (s == Magpie.State.STARTING) {
            long sec = Magpie.uptimeSeconds();
            if (sec > 12) {
                shown.append("—— 启动超过 ").append(sec).append(" 秒，诊断 ——\n");
                shown.append(Magpie.diagnose(this)).append("\n—— 日志 ——\n");
            }
        }
        shown.append(Magpie.logFromFile(this));
        String log = shown.toString();
        if (!log.equals(logView.getText().toString())) {
            logView.setText(log);
        }
    }

    private String human(long sec) {
        if (sec < 60) {
            return sec + " 秒";
        }
        if (sec < 3600) {
            return (sec / 60) + " 分";
        }
        return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分";
    }

    /* ---------------------------------------------------------------- 控制台 */

    private View buildConsoleOverlay() {
        consoleOverlay = new LinearLayout(this);
        consoleOverlay.setOrientation(LinearLayout.VERTICAL);
        consoleOverlay.setBackgroundColor(getColor(R.color.bg));
        consoleOverlay.setVisibility(View.GONE);
        consoleOverlay.setPadding(0, statusBarInset(), 0, 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        Button back = pill("‹ 返回", false);
        back.setOnClickListener(v -> closeConsole());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        bar.addView(back, blp);
        TextView t = text("magpie 控制台", 15, R.color.fg, Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reload = pill("刷新", false);
        reload.setOnClickListener(v -> {
            if (web != null) {
                web.loadUrl(Magpie.webUrl(this));
            }
        });
        bar.addView(reload, new LinearLayout.LayoutParams(-2, -2));
        consoleOverlay.addView(bar);

        consoleHolder = new FrameLayout(this);
        consoleHolder.setBackgroundColor(getColor(R.color.bg));
        consoleOverlay.addView(consoleHolder, new LinearLayout.LayoutParams(-1, 0, 1f));
        return consoleOverlay;
    }

    private void openConsole() {
        if (web == null) {
            web = buildWebView();
            consoleHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        }
        consoleOverlay.setVisibility(View.VISIBLE);
        web.loadUrl(Magpie.webUrl(this));
    }

    private void closeConsole() {
        consoleOverlay.setVisibility(View.GONE);
    }

    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, true);
        w.setBackgroundColor(getColor(R.color.bg));
        w.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        openExternally(req.getUrl());
                        tmp.destroy();
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SHIM_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = u.getHost() == null ? "" : u.getHost();
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                        return false;
                    }
                }
                openExternally(u);
                return true;
            }
        });
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void open(String url) {
                if (url == null || url.isEmpty()) {
                    return;
                }
                runOnUiThread(() -> openExternally(Uri.parse(url)));
            }
        }, "MagpieHost");
        return w;
    }

    private void openExternally(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
            copy(u.toString(), "已复制链接，可在浏览器打开");
        }
    }

    private static final String SHIM_JS =
            "(function(){"
            + "if(window.__magpieOpenShim)return;window.__magpieOpenShim=1;"
            + "var orig=window.open;"
            + "window.open=function(u){"
            + "  if(u&&typeof u==='string'&&/^https?:/i.test(u)){"
            + "    try{MagpieHost.open(u);}catch(e){}"
            + "    return {closed:false,opener:null,focus:function(){},close:function(){},postMessage:function(){}};"
            + "  }"
            + "  try{return orig.apply(window,arguments);}catch(e){return null;}"
            + "};"
            + "})();";

    /* -------------------------------------------------------------- dsh 接入 */

    private void copyDshRecipe() {
        String s = "Base URL: " + Magpie.gatewayUrl() + "\n"
                + "API Key: " + Magpie.TOKEN + "\n"
                + "协议: OpenAI（Chat Completions）\n"
                + "模型: 在 dsh-go 里点「获取模型」自动列出";
        copy(s, "接入参数已复制");
    }

    private void showDshSheet() {
        new AlertDialog.Builder(this)
                .setTitle("接入 dsh-go")
                .setMessage("dsh-go 的本机引擎跑在同一台手机的 PRoot 里，和 magpie 共用 127.0.0.1。\n\n"
                        + "在 dsh-go 里：设置 › 模型 › 添加渠道\n"
                        + "· 兼容协议：OpenAI\n"
                        + "· Base URL：" + Magpie.gatewayUrl() + "\n"
                        + "· API Key：" + Magpie.TOKEN + "\n"
                        + "· 点「获取模型」，勾选要用的模型，保存\n\n"
                        + "之后在对话顶部的模型胶囊里就能选到 magpie 的模型。")
                .setPositiveButton("复制参数", (d, w) -> copyDshRecipe())
                .setNeutralButton("打开 dsh-go", (d, w) -> openDsh("dshgo://open/models"))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void openDsh(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            toast("没有安装 dsh-go");
        }
    }

    /* ---------------------------------------------------------------- 设置项 */

    private void toggleFloat() {
        boolean on = !Magpie.floatEnabled(this);
        Magpie.setFloatEnabled(this, on);
        MagpieService.setFloat(this, on);
        toast(on ? "悬浮监控已开启（进程不会被冻结）" : "悬浮监控已关闭");
        ui.postDelayed(() -> {
            setContentView(buildUi());
            refresh();
        }, 350);
    }

    private void promptOverlayOnce() {
        boolean asked = getSharedPreferences(Magpie.PREFS, Context.MODE_PRIVATE)
                .getBoolean("overlayAsked", false);
        if (asked || FloatingMonitor.permitted(this)) {
            return;
        }
        getSharedPreferences(Magpie.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean("overlayAsked", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("开启悬浮监控")
                .setMessage("悬浮监控挂件会一直显示 magpie 的网关状态。\n\n"
                        + "它同时是保活的关键：带可见窗口的进程不会被 Android 冻结，"
                        + "网关才能在你看 dsh-go 时持续工作。\n\n"
                        + "下一步请在系统设置里允许「显示在其他应用上层」。")
                .setPositiveButton("去授权", (d, w) -> askOverlay())
                .setNegativeButton("以后", null)
                .show();
    }

    private void askOverlay() {
        if (FloatingMonitor.permitted(this)) {
            toast("悬浮窗权限已授予");
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            toast("系统没有这个设置页");
        }
    }

    private void toggleAutostart() {
        boolean on = !Magpie.autostart(this);
        Magpie.setAutostart(this, on);
        toast(on ? "开机自启已开启" : "开机自启已关闭");
        ui.postDelayed(() -> {
            setContentView(buildUi());
            refresh();
        }, 250);
    }

    private void askBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                toast("系统没有这个设置页");
            }
        }
    }

    /* ---------------------------------------------------------------- 工具 */

    private void copy(String s, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("magpie", s));
        toast(msg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
