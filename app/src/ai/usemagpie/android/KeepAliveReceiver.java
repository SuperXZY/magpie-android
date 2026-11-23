package ai.usemagpie.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 保活广播接收器。
 *
 * <p>系统清理掉服务后会发这些广播；能收到就说明进程还活着，立刻把服务顶回去。
 * 收不到（进程被彻底杀死）时靠 {@link BootReceiver} 的开机自启和用户手动打开。
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    private static final String TAG = "magpie-keep";

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) {
            return;
        }
        boolean revive = Intent.ACTION_PACKAGE_RESTARTED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                || Intent.ACTION_BOOT_COMPLETED.equals(a)
                || "android.intent.action.USER_PRESENT".equals(a)
                || "android.intent.action.SCREEN_ON".equals(a)
                || "android.intent.action.ACTION_POWER_CONNECTED".equals(a)
                || "android.intent.action.ACTION_POWER_DISCONNECTED".equals(a)
                || "android.app.action.APP_BLOCK_STATE_CHANGED".equals(a)
                || Intent.ACTION_TIME_TICK.equals(a);
        if (!revive) {
            return;
        }
        Magpie.init(context);
        if (!Magpie.autostart(context)) {
            return;
        }
        Log.i(TAG, "reviving gateway on " + a);
        try {
            MagpieService.start(context);
        } catch (Exception e) {
            // Android 12+ 在后台启动前台服务有限制，失败时等用户打开 App
            Log.w(TAG, "revive refused", e);
        }
    }
}
