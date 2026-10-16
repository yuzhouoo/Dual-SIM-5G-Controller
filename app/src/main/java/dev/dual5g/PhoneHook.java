package dev.dual5g;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
    // Reconcile on phone-process startup, after SIM switches, and periodically.
    // Android/ROM may restore a saved USER preference after our first write.
    private static final long WATCHDOG_MS = 20_000L;
    private static final long RETRY_MS = 2_500L;
    private static final Runnable SYNC = () -> apply(false);
    private static final Runnable WATCHDOG = new Runnable() {
        @Override public void run() {
            try { apply(false); }
            finally {
                Handler h = handler;
                if (h != null) h.postDelayed(this, WATCHDOG_MS);
            }
        }
    };
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
        filter.addAction("android.intent.action.SIM_STATE_CHANGED");
        filter.addAction("android.intent.action.BOOT_COMPLETED");
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
        XposedBridge.log("Dual5G v0.6.1 phone hook initialized; watchdog enabled");
        // No reliance on the UI opening or on a one-off boot-time broadcast.
        schedule(2500);
        handler.postDelayed(WATCHDOG, 7000);
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
            if (sm == null || base == null) { lastStatus = "系统电话服务未就绪，稍后重试"; schedule(RETRY_MS); return; }
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.size() == 0) { lastStatus = "没有活动 SIM 卡，稍后重试"; schedule(RETRY_MS); return; }
            int defaultId = SubscriptionManager.getDefaultDataSubscriptionId();
            if (!restore && !SubscriptionManager.isValidSubscriptionId(defaultId)) {
                lastStatus = "默认数据卡尚未就绪，稍后重试"; schedule(RETRY_MS); return;
            }
            if (!restore) {
                boolean foundDefault = false;
                for (SubscriptionInfo si : subs) {
                    if (si.getSubscriptionId() == defaultId && si.getSimSlotIndex() >= 0) {
                        foundDefault = true; break;
                    }
                }
                if (!foundDefault) {
                    lastStatus = "默认上网卡还未出现在活动卡列表，稍后重试";
                    schedule(RETRY_MS);
                    return;
                }
            }
            StringBuilder report = new StringBuilder();
            report.append("defaultDataSubId=").append(defaultId).append('\n');
            SharedPreferences.Editor editor = p.edit();
            int changed = 0;
            for (SubscriptionInfo si : subs) {
                int id = si.getSubscriptionId();
                int slot = si.getSimSlotIndex();
                if (slot < 0) continue;
                // Default data SIM might change mid-reconciliation; never apply
                // an old default-to-secondary mapping to the new default SIM.
                if (!restore && SubscriptionManager.getDefaultDataSubscriptionId() != defaultId) {
                    lastStatus = "默认上网卡切换中，重新校验";
                    schedule(1200);
                    return;
                }
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
                    if (verified != target) {
                        schedule(RETRY_MS);
                        throw new IllegalStateException("slot " + slot + " 写入未生效，稍后重试");
                    }
                    changed++;
                }
                report.append("slot").append(slot).append(" subId=").append(id)
                      .append(" ").append((target & NR) != 0 ? "5G允许" : "4G优先")
                      .append(current != target ? " 已更新" : " 无需修改").append('\n');
            }
            editor.apply();
            if (!restore && SubscriptionManager.getDefaultDataSubscriptionId() != defaultId) {
                schedule(1200);
                report.append("默认上网卡已变化，正在重新校验\n");
            }
            if (restore) report.append("已尝试恢复首次接管时备份的模式；自动切换已停用\n");
            report.append("修改次数=").append(changed);
            lastStatus = report.toString();
            // Avoid spamming LSPosed logs every watchdog tick.
            if (changed > 0 || restore) XposedBridge.log("Dual5G v0.6.1 reconcile: " + lastStatus);
        } catch (SecurityException e) {
            lastStatus = "权限不足：电话进程 API 被 ROM 拒绝: " + e;
            schedule(5000);
            XposedBridge.log("Dual5G permission failure " + e);
        } catch (Throwable e) {
            lastStatus = "同步失败: " + e;
            schedule(5000);
            XposedBridge.log("Dual5G apply failure " + e);
        }
    }

    /** Fresh, read-only diagnostics. Runs in the privileged phone process. */
    private static String snapshot() {
        StringBuilder b = new StringBuilder();
        b.append("【实时双卡网络状态】\n");
        b.append("最近操作：").append(lastStatus).append("\n");
        b.append("\n");
        try {
            int dataId = SubscriptionManager.getDefaultDataSubscriptionId();
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            TelephonyManager base = app.getSystemService(TelephonyManager.class);
            if (sm == null || base == null) return b.append("电话服务未就绪").toString();
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) return b.append("未发现活动 SIM 卡").toString();
            b.append("默认数据订阅 ID：").append(dataId).append("\n");
            boolean matched = false;
            for (SubscriptionInfo si : subs) {
                int slot = si.getSimSlotIndex();
                if (slot < 0) continue;
                int id = si.getSubscriptionId();
                boolean activeData = id == dataId;
                matched |= activeData;
                b.append("\n──── 卡 ").append(slot + 1).append(" (Slot ").append(slot).append(") ────\n");
                b.append("运营商：").append(si.getCarrierName()).append("\n");
                b.append("订阅 ID：").append(id).append("\n");
                b.append("当前上网卡：").append(activeData ? "是 ★" : "否").append("\n");
                try {
                    TelephonyManager tm = base.createForSubscriptionId(id);
                    long mask = tm.getAllowedNetworkTypesForReason(USER);
                    b.append("网络模式：").append((mask & NR) != 0 ? "允许 5G (NR)" : "最高 4G（未允许 NR）").append("\n");
                    b.append("用户网络掩码：").append(mask).append(" (0x")
                            .append(Long.toHexString(mask)).append(")\n");
                } catch (Throwable t) {
                    b.append("读取该卡失败：").append(t.getClass().getSimpleName())
                            .append(": ").append(t.getMessage()).append("\n");
                }
            }
            if (!matched) b.append("\n警告：默认上网卡未匹配任何活动卡槽\n");
        } catch (Throwable t) {
            b.append("实时读取失败：").append(t).append("\n");
            XposedBridge.log("Dual5G snapshot error: " + t);
        }
        return b.toString();
    }

    private static void reply() {
        try {
            Intent out = new Intent(RESULT).setPackage("dev.dual5g");
            out.putExtra("status", snapshot());
            out.putExtra("enabled", prefs().getBoolean("enabled", true));
            app.sendBroadcast(out);
        } catch (Throwable t) { XposedBridge.log("Dual5G reply failed " + t); }
    }
}
