package ai.usemagpie.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewParent;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 一屏应用：顶部引擎状态与操作，中间是接入信息，底部是引擎日志；
 * 点「打开控制台」进入 magpie 自己的 Web 控制台（全屏 WebView）。
 */
public class MainActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusDot, statusTitle, statusSub, logView;
    private Button startBtn, stopBtn, restartBtn, consoleBtn;
    private FrameLayout consoleHolder;
    private LinearLayout consoleOverlay;
    private WebView web;

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
        setContentView(buildUi());
        askNotificationPermission();
        MagpieService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
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
            toggleConsole();
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

    /* ------------------------------------------------------------------ UI */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(getColor(R.color.bg));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.bg));
        shell.addView(root, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(14), dp(12), dp(8));
        bar.addView(text("magpie", 22, R.color.fg, Typeface.BOLD), new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(text("v" + Magpie.version(), 12, R.color.muted, Typeface.NORMAL));
        root.addView(bar);

        // 状态卡
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(card, margins(dp(12), 0, dp(12), dp(10)));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = text("●", 16, R.color.muted, Typeface.NORMAL);
        head.addView(statusDot);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        statusTitle = text("未运行", 17, R.color.fg, Typeface.BOLD);
        statusSub = text("", 12, R.color.muted, Typeface.NORMAL);
        titles.addView(statusTitle);
        titles.addView(statusSub);
        head.addView(titles, margins(dp(10), 0, 0, 0));
        card.addView(head);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setLayoutParams(margins(0, dp(12), 0, 0));
        startBtn = button("启动");
        stopBtn = button("停止");
        restartBtn = button("重启");
        buttons.addView(startBtn, weight1());
        buttons.addView(gap(dp(8)));
        buttons.addView(stopBtn, weight1());
        buttons.addView(gap(dp(8)));
        buttons.addView(restartBtn, weight1());
        card.addView(buttons);

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
            Magpie.restart(this);
            toast("正在重启");
            ui.postDelayed(this::refresh, 800);
        });

        // 信息卡
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundResource(R.drawable.card);
        info.setPadding(dp(16), dp(4), dp(16), dp(8));
        root.addView(info, margins(dp(12), 0, dp(12), dp(10)));

        row(info, "网关地址", Magpie.gatewayUrl(), "复制", () -> copy(Magpie.gatewayUrl(), "网关地址已复制"));
        row(info, "网关密钥", Magpie.TOKEN, "复制", () -> copy(Magpie.TOKEN, "密钥已复制"));
        row(info, "控制台", "127.0.0.1:" + Magpie.WEB_PORT, "复制", () -> copy(Magpie.webUrl(this), "控制台链接已复制"));
        row(info, "接入 dsh-go", "OpenAI 兼容渠道", "参数", this::showDshSheet);
        row(info, "开机自启", autostartOn() ? "已开启" : "已关闭", autostartOn() ? "关闭" : "开启", this::toggleAutostart);
        row(info, "后台保活", "加入电池白名单", "设置", this::askBattery);
        row(info, "本机", Magpie.deviceLine(), null, null);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions, margins(dp(12), 0, dp(12), dp(10)));
        consoleBtn = button("打开控制台");
        actions.addView(consoleBtn, weight1());
        actions.addView(gap(dp(8)));
        Button dshBtn = button("接入 dsh-go");
        actions.addView(dshBtn, weight1());
        consoleBtn.setOnClickListener(v -> toggleConsole());
        dshBtn.setOnClickListener(v -> showDshSheet());

        TextView logTitle = text("引擎日志", 12, R.color.muted, Typeface.NORMAL);
        logTitle.setPadding(dp(16), 0, dp(16), dp(4));
        root.addView(logTitle);
        ScrollView logScroll = new ScrollView(this);
        logView = text("", 11, R.color.muted, Typeface.NORMAL);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(dp(16), 0, dp(16), dp(16));
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        shell.addView(buildConsoleOverlay(), new FrameLayout.LayoutParams(-1, -1));
        return shell;
    }

    /** 全屏控制台：盖住整屏，带返回按钮。 */
    private View buildConsoleOverlay() {
        consoleOverlay = new LinearLayout(this);
        consoleOverlay.setOrientation(LinearLayout.VERTICAL);
        consoleOverlay.setBackgroundColor(getColor(R.color.bg));
        consoleOverlay.setVisibility(View.GONE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        Button back = button("‹ 返回");
        back.setOnClickListener(v -> toggleConsole());
        bar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(text("magpie 控制台", 15, R.color.fg, Typeface.BOLD),
                new LinearLayout.LayoutParams(0, -2, 1));
        Button reload = button("刷新");
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

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(l, t, r, b);
        return p;
    }

    private LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, -2, 1);
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

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(getColor(R.color.fg));
        b.setBackgroundResource(R.drawable.btn);
        b.setMinHeight(dp(42));
        b.setPadding(dp(8), dp(6), dp(8), dp(6));
        return b;
    }

    private void row(LinearLayout parent, String label, String value, Runnable action) {
        row(parent, label, value, "复制", action);
    }

    private void row(LinearLayout parent, String label, String value, String actionLabel, Runnable action) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(9), 0, dp(9));
        TextView l = text(label, 12, R.color.muted, Typeface.NORMAL);
        l.setSingleLine(true);
        line.addView(l);
        TextView v = text(value, 12, R.color.fg, Typeface.NORMAL);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setGravity(Gravity.END);
        line.addView(v, margins(dp(10), 0, dp(8), 0));
        if (action != null && actionLabel != null) {
            TextView c = text(actionLabel, 12, R.color.accent, Typeface.BOLD);
            c.setPadding(dp(6), dp(2), dp(2), dp(2));
            c.setOnClickListener(x -> action.run());
            line.addView(c);
        }
        parent.addView(line);
    }

    /* -------------------------------------------------------------- 状态刷新 */

    private void refresh() {
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
                    + " · 控制台 " + Magpie.WEB_PORT
                    + (up > 0 ? " · 已运行 " + human(up) : "");
        } else if (s == Magpie.State.ERROR) {
            sub = Magpie.lastError();
        } else {
            sub = "网关 " + Magpie.gatewayUrl();
        }
        statusSub.setText(sub);

        startBtn.setEnabled(s != Magpie.State.RUNNING && s != Magpie.State.STARTING);
        stopBtn.setEnabled(s == Magpie.State.RUNNING || s == Magpie.State.STARTING);
        restartBtn.setEnabled(s == Magpie.State.RUNNING);

        String log = Magpie.logText();
        if (!log.equals(logView.getText().toString())) {
            logView.setText(log);
            ViewParent parent = logView.getParent();
            if (parent instanceof ScrollView) {
                ScrollView sv = (ScrollView) parent;
                sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private String human(long sec) {
        if (sec < 60) {
            return sec + " 秒";
        }
        if (sec < 3600) {
            return (sec / 60) + " 分 " + (sec % 60) + " 秒";
        }
        return (sec / 3600) + " 小时 " + ((sec % 3600) / 60) + " 分";
    }

    /* ---------------------------------------------------------------- 控制台 */

    private void toggleConsole() {
        if (consoleOverlay.getVisibility() == View.VISIBLE) {
            consoleOverlay.setVisibility(View.GONE);
            consoleBtn.setText("打开控制台");
            return;
        }
        if (web == null) {
            web = buildWebView();
            consoleHolder.addView(web, new FrameLayout.LayoutParams(-1, -1));
        }
        consoleOverlay.setVisibility(View.VISIBLE);
        web.loadUrl(Magpie.webUrl(this));
        consoleBtn.setText("收起控制台");
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
        // 订阅登录/更新等页面用 window.open 打开新窗口，WebView 默认会把它丢掉
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
                // 新窗口一律交给系统浏览器：OAuth 页在外部浏览器里更可靠，
                // 回跳用 magpie 自己的回调地址
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
                // window.open 在 WebView 里不一定触发 onCreateWindow（弹窗被拦、
                // 非用户手势等），这里再兜一层：把新窗口的地址交给系统浏览器。
                // 只在控制台自己的页面上注入，OAuth 回跳地址仍是 magpie 的回调。
                view.evaluateJavascript(SHIM_JS, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    String host = u.getHost() == null ? "" : u.getHost();
                    // 只有本机控制台留在 WebView 里，其余（登录页、文档）交给浏览器
                    if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                        return false;
                    }
                }
                openExternally(u);
                return true;
            }
        });
        w.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void open(String url) {
                if (url == null || url.isEmpty()) {
                    return;
                }
                runOnUiThread(() -> openExternally(Uri.parse(url)));
            }
        }, "MagpieHost");
        return w;
    }

    /**
     * 页面里的 window.open 改成交给宿主：OAuth 登录、文档链接都走系统浏览器，
     * 控制台本身仍是同一个 WebView（cookie 不丢）。
     *
     * 返回一个占位 window 对象而不是 null：magpie 的 openSignInPage 把 null 当成
     * "弹窗被拦"，会显示错误；占位对象让 `w.opener = null` 照常成立。
     */
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

    private void openExternally(Uri u) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (ActivityNotFoundException ignored) {
            // 没有应用能开：把链接给用户自己复制
            copy(u.toString(), "已复制链接，可在浏览器打开");
        }
    }

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

    /* ---------------------------------------------------------------- 工具 */

    private boolean autostartOn() {
        return getSharedPreferences(Magpie.PREFS, Context.MODE_PRIVATE).getBoolean("autostart", true);
    }

    private void toggleAutostart() {
        boolean on = !autostartOn();
        getSharedPreferences(Magpie.PREFS, Context.MODE_PRIVATE).edit().putBoolean("autostart", on).apply();
        toast(on ? "开机自启已开启" : "开机自启已关闭");
        setContentView(buildUi());
        refresh();
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

    private void copy(String s, String msg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("magpie", s));
        toast(msg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
