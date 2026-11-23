package ai.usemagpie.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮监控挂件。
 *
 * <p>它同时解决两个问题：
 * <ol>
 *   <li><b>保活</b>：有可见窗口的进程是 <i>visible</i> 优先级。Android 的
 *       cached-app freezer 只会冻结 cached 进程，带可见窗口的进程不会被选中，
 *       所以引擎所在进程（:magpie）永远不会被冻。</li>
 *   <li><b>监控</b>：实时显示网关是否在线、端口、近 60 秒的请求数，
 *       你盯着 dsh-go 时也能一眼看出网关是否还在工作。</li>
 * </ol>
 *
 * <p>挂件可拖动，点一下打开 magpie 主界面。默认显示；用户可在主界面关掉。
 */
public final class FloatingMonitor {

    private static final String TAG = "magpie-float";

    private final Context ctx;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private View root;
    private TextView dot, title, sub;
    private boolean shown;

    /** 近 60 秒的成功/失败请求计数，由 MagpieService 的心跳填写。 */
    private volatile int okCount, failCount;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            ui.postDelayed(this, 1000);
        }
    };

    public FloatingMonitor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WindowManager) this.ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 当前是否允许悬浮窗（用户授权了 SYSTEM_ALERT_WINDOW）。 */
    public static boolean permitted(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.provider.Settings.canDrawOverlays(ctx);
        }
        return true;
    }

    public boolean isShown() {
        return shown;
    }

    /** 显示挂件；没有权限时返回 false，调用方去申请。 */
    public boolean show() {
        if (shown && root != null) {
            return true;
        }
        if (!permitted(ctx)) {
            Magpie.append("悬浮窗：缺少「显示在其他应用上层」权限");
            return false;
        }
        if (root != null) {
            // 残留的视图先摘掉
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
                // 已不在窗口上
            }
            root = null;
        }
        root = build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                // NOT_FOCUSABLE：不抢输入焦点，不干扰 dsh-go 打字；
                // 但仍然是可见窗口，进程保持 visible
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        // 部分 ROM 要求这两个标志才允许显示
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(12);
        lp.y = dp(120);
        try {
            wm.addView(root, lp);
        } catch (Throwable e) {
            // 权限被撤销、或者窗口类型不被允许，都会抛
            Magpie.append("悬浮窗添加失败: " + e);
            root = null;
            shown = false;
            return false;
        }
        attachDrag(lp);
        shown = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
        // 立刻画一次，不必等第一个 tick
        update();
        Magpie.append("悬浮监控已显示");
        return true;
    }

    public void hide() {
        ui.removeCallbacks(tick);
        if (root != null) {
            try {
                wm.removeView(root);
            } catch (Exception ignored) {
                // 已经被系统移除
            }
            root = null;
        }
        shown = false;
    }

    /** 由服务心跳写入，挂件据此显示近一分钟的请求情况。 */
    public void report(int ok, int fail) {
        okCount = ok;
        failCount = fail;
    }

    /* ------------------------------------------------------------------ UI */

    private View build() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(10), dp(7), dp(12), dp(7));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE616191E);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0xFF2B3038);
        box.setBackground(bg);

        dot = new TextView(ctx);
        dot.setText("●");
        dot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        dot.setTextColor(0xFF9AA3AE);
        box.addView(dot);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.setMargins(dp(7), 0, 0, 0);
        texts.setLayoutParams(tlp);

        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setTextColor(0xFFE8EAED);
        title.setText("magpie");
        texts.addView(title);

        sub = new TextView(ctx);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        sub.setTextColor(0xFF9AA3AE);
        sub.setText("连接中…");
        texts.addView(sub);

        box.addView(texts);
        return box;
    }

    private void update() {
        if (root == null) {
            return;
        }
        boolean up = Magpie.probe(Magpie.GATEWAY_PORT);
        if (up) {
            dot.setTextColor(0xFF3DDC84);
            title.setText("magpie 运行中");
        } else {
            dot.setTextColor(0xFFFF6B6B);
            title.setText("magpie 未响应");
        }
        String traffic = okCount + " 成功";
        if (failCount > 0) {
            traffic += " · " + failCount + " 失败";
        }
        long sec = Magpie.uptimeSeconds();
        sub.setText(":" + Magpie.GATEWAY_PORT + " · " + traffic + (sec > 0 ? " · " + human(sec) : ""));
    }

    private String human(long sec) {
        if (sec < 60) {
            return sec + "s";
        }
        if (sec < 3600) {
            return (sec / 60) + "m";
        }
        return (sec / 3600) + "h" + ((sec % 3600) / 60) + "m";
    }

    /** 拖动移动位置，轻点打开主界面。 */
    private void attachDrag(WindowManager.LayoutParams lp) {
        final int[] start = new int[2];
        final float[] touch = new float[2];
        final boolean[] moved = new boolean[1];

        root.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    start[0] = lp.x;
                    start[1] = lp.y;
                    touch[0] = e.getRawX();
                    touch[1] = e.getRawY();
                    moved[0] = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - touch[0]);
                    int dy = (int) (e.getRawY() - touch[1]);
                    if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                        moved[0] = true;
                    }
                    if (moved[0]) {
                        lp.x = start[0] + dx;
                        lp.y = start[1] + dy;
                        try {
                            wm.updateViewLayout(root, lp);
                        } catch (Exception ignored) {
                            // 视图已被移除
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved[0]) {
                        openApp();
                    }
                    return true;
                default:
                    return false;
            }
        });
    }

    private void openApp() {
        try {
            android.content.Intent i = new android.content.Intent(ctx, MainActivity.class);
            i.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
        } catch (Exception ignored) {
            // 后台启动 Activity 受限时忽略
        }
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
