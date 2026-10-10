package dev.dual5g;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Runs inside the privileged com.android.phone process. NO su/KernelSU module is used. */
public class PhoneHook implements IXposedHookLoadPackage {
    public static final String CONTROL = "dev.dual5g.CONTROL";
    public static final String RESULT = "dev.dual5g.RESULT";
    public static final String PERMISSION = "dev.dual5g.permission.CONTROL";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final long NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR;
    private static final int USER = TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER;
    private static volatile Handler handler;
    private static volatile Application app;
    private static volatile String lastStatus = "尚未同步";
    private static final Runnable SYNC = () -> apply(false);

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!"com.android.phone".equals(lp.packageName)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!INSTALLED.compareAndSet(false, true)) return;
                try { init((Application)param.thisObject); }
                catch (Throwable e) { INSTALLED.set(false); XposedBridge.log("Dual5G init FAILED: " + e); }
            }
        });
    }

    private static void init(Application application) {
        app = application;
        HandlerThread thread = new HandlerThread("Dual5G-policy");
        thread.start(); handler = new Handler(thread.getLooper());
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
        filter.addAction("android.telephony.action.SIM_CARD_STATE_CHANGED");
        filter.addAction("android.telephony.action.SIM_APPLICATION_STATE_CHANGED");
        filter.addAction("android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED");
        application.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { schedule(1500); }
        }, filter, Context.RECEIVER_EXPORTED);

        // This receiver requires a signature-level permission owned by the APK,
        // so other apps cannot issue privileged requests.
        application.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                final String command = intent.getStringExtra("command");
                handler.post(() -> {
                    try {
                        SharedPreferences prefs = prefs();
                        if ("enable".equals(command)) {
                            prefs.edit().putBoolean("enabled", true).apply(); apply(false);
                        } else if ("disable".equals(command)) {
                            prefs.edit().putBoolean("enabled", false).apply();
                            lastStatus = "自动切换已关闭；当前网络模式保留";
                        } else if ("restore".equals(command)) {
                            prefs.edit().putBoolean("enabled", false).apply(); apply(true);
                        } else if ("sync".equals(command)) {
                            apply(false);
                        } else if (!"status".equals(command)) {
                            lastStatus = "未知操作";
                        }
                    } catch (Throwable t) {
                        lastStatus = "操作失败: " + t;
                        XposedBridge.log("Dual5G command error: " + t);
                    }
                    reply();
                });
            }
        }, new IntentFilter(CONTROL), PERMISSION, handler, Context.RECEIVER_EXPORTED);
        XposedBridge.log("Dual5G phone hook initialized, permission-based control enabled");
        schedule(4000);
    }

    private static SharedPreferences prefs() {
        return app.getSharedPreferences("dual5g_policy", Context.MODE_PRIVATE);
    }

    private static void schedule(long delay) {
        Handler h = handler;
        if (h != null) { h.removeCallbacks(SYNC); h.postDelayed(SYNC, delay); }
    }

    private static void apply(boolean restore) {
        if (app == null) return;
        try {
            SharedPreferences p = prefs();
            if (!restore && !p.getBoolean("enabled", true)) {
                lastStatus = "自动切换已停用"; return;
            }
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            TelephonyManager base = app.getSystemService(TelephonyManager.class);
            if (sm == null || base == null) { lastStatus = "系统电话服务未就绪"; return; }
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.size() == 0) { lastStatus = "没有活动 SIM 卡"; return; }
            int defaultId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (!restore && !SubscriptionManager.isValidSubscriptionId(defaultId)) {
                lastStatus = "默认数据卡尚未就绪"; return;
            }
            StringBuilder report = new StringBuilder();
            report.append("defaultDataSubId=").append(defaultId).append('\n');
            SharedPreferences.Editor editor = p.edit();
            int changed = 0;
            for (SubscriptionInfo si : subs) {
                int id = si.getSubscriptionId();
                int slot = si.getSimSlotIndex();
                if (slot < 0) continue;
                TelephonyManager tm = base.createForSubscriptionId(id);
                long current = tm.getAllowedNetworkTypesForReason(USER);
                String key = "backup_" + id;
                if (!p.contains(key)) editor.putLong(key, current);
                long target;
                if (restore) {
                    if (!p.contains(key)) { report.append("slot").append(slot).append(": 无备份\n"); continue; }
                    target = p.getLong(key, current);
                } else {
                    target = id == defaultId ? (current | NR) : (current & ~NR);
                }
                if (current != target) {
                    tm.setAllowedNetworkTypesForReason(USER, target);
                    long verified = tm.getAllowedNetworkTypesForReason(USER);
                    if (verified != target) throw new IllegalStateException("slot " + slot + " 写入未生效");
                    changed++;
                }
                report.append("slot").append(slot).append(" subId=").append(id)
                      .append(" ").append((target & NR) != 0 ? "5G允许" : "4G优先")
                      .append(current != target ? " 已更新" : " 无需修改").append('\n');
            }
            editor.apply();
            if (restore) report.append("已尝试恢复首次接管时备份的模式；自动切换已停用\n");
            report.append("修改次数=").append(changed);
            lastStatus = report.toString();
            XposedBridge.log("Dual5G " + lastStatus);
        } catch (SecurityException e) {
            lastStatus = "权限不足：电话进程 API 被 ROM 拒绝: " + e;
            XposedBridge.log("Dual5G permission failure " + e);
        } catch (Throwable e) {
            lastStatus = "同步失败: " + e;
            XposedBridge.log("Dual5G apply failure " + e);
        }
    }

    private static void reply() {
        try {
            Intent out = new Intent(RESULT).setPackage("dev.dual5g");
            out.putExtra("status", lastStatus);
            out.putExtra("enabled", prefs().getBoolean("enabled", true));
            app.sendBroadcast(out);
        } catch (Throwable t) { XposedBridge.log("Dual5G reply failed " + t); }
    }
}
