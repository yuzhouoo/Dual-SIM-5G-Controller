package dev.dual5g;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.telephony.SubscriptionManager;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Legacy Xposed API entry; scope: com.android.phone only. */
public class PhoneHook implements IXposedHookLoadPackage {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.phone".equals(lpparam.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                final Application app = (Application) param.thisObject;
                if (!INSTALLED.compareAndSet(false, true)) return;
                try {
                    IntentFilter filter = new IntentFilter();
                    filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
                    filter.addAction("android.telephony.action.SIM_CARD_STATE_CHANGED");
                    filter.addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED");
                    BroadcastReceiver receiver = new BroadcastReceiver() {
                        @Override public void onReceive(Context context, Intent intent) {
                            // Never block the telephony main thread. Root helper may be denied on some KSU setups.
                            trigger("broadcast:" + intent.getAction());
                        }
                    };
                    if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                    else app.registerReceiver(receiver, filter);
                    XposedBridge.log("Dual5G: phone receiver active");
                    trigger("phone-start");
                } catch (Throwable t) { XposedBridge.log("Dual5G register failed: " + t); }
            }
        });
    }
    private static void trigger(String reason) {
        Thread t = new Thread(() -> {
            try {
                Process p = new ProcessBuilder("su", "-c", "/data/adb/modules/dual_sim_5g/controller.sh once").redirectErrorStream(true).start();
                // Do not wait inside com.android.phone; background daemon is primary fallback.
                p.getInputStream().close();
            } catch (Throwable e) { XposedBridge.log("Dual5G immediate trigger unavailable (daemon remains active): " + e); }
        }, "Dual5G-signal");
        t.setDaemon(true); t.start();
    }
}
