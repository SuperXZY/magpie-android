package ai.usemagpie.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机或升级后按设置自动拉起引擎。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(a)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a);
        if (!boot) {
            return;
        }
        Magpie.init(context);
        boolean auto = context.getSharedPreferences(Magpie.PREFS, Context.MODE_PRIVATE)
                .getBoolean("autostart", true);
        if (auto) {
            MagpieService.start(context);
        }
    }
}
