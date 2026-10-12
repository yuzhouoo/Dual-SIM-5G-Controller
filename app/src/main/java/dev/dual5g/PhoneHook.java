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
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoLte;
import android.telephony.ServiceState;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private static volatile String hardwareDiagnostic = "尚未检测（可点击检测设备无线能力）";
    private static final Runnable SYNC = () -> apply(false);
    // QoS detection is EXPLICITLY on-demand and NEVER part of live status or policy.
    private static final String[] QCI_VALUES = {"—", "—"};
    private static volatile String qosDiagnostic = "尚未检测；点击读取 QCI/5QI";
    private static volatile int qosToken = 0;
    private static final java.util.concurrent.ThreadPoolExecutor QOS_WORKER = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            r -> { Thread t = new Thread(r, "Dual5G-qos-on-demand"); t.setDaemon(true); return t; },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    // Only for a bounded, best-effort serving-cell fallback. Never run potentially
    // blocking OEM cell queries directly on the policy / IPC HandlerThread.
    private static final ThreadPoolExecutor CELL_WORKER = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
            r -> { Thread t = new Thread(r, "Dual5G-cell-read"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());

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
                        } else if ("qci".equals(command)) {
                            startQciProbe();
                        } else if ("hardware".equals(command)) {
                            hardwareDiagnostic = queryHardwareCapability();
                        } else if ("bands".equals(command)) {
                            // Current bands are included in every fresh status snapshot.
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
        XposedBridge.log("Dual5G STANDALONE v0.5.1 phone hook initialized; no su, no KSU module");
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
            XposedBridge.log("Dual5G STANDALONE v0.2.4 policy: " + lastStatus);
        } catch (SecurityException e) {
            lastStatus = "权限不足：电话进程 API 被 ROM 拒绝: " + e;
            XposedBridge.log("Dual5G permission failure " + e);
        } catch (Throwable e) {
            lastStatus = "同步失败: " + e;
            XposedBridge.log("Dual5G apply failure " + e);
        }
    }

    /** Fresh, read-only diagnostics. Runs in the privileged phone process. */
    private static String snapshot() {
        StringBuilder b = new StringBuilder();
        b.append("【实时双卡网络状态】\n");
        b.append("最近操作：").append(lastStatus).append("\n");
        b.append("设备能力检测：").append(hardwareDiagnostic).append("\n");
        b.append("QCI 检测状态：").append(qosDiagnostic).append("\n\n");
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
                b.append("QCI/5QI：").append(slot < QCI_VALUES.length ? QCI_VALUES[slot] : "—").append("\n");
                b.append("当前上网卡：").append(activeData ? "是 ★" : "否").append("\n");
                try {
                    TelephonyManager tm = base.createForSubscriptionId(id);
                    long mask = tm.getAllowedNetworkTypesForReason(USER);
                    b.append("网络模式：").append((mask & NR) != 0 ? "允许 5G (NR)" : "最高 4G（未允许 NR）").append("\n");
                    b.append("用户网络掩码：").append(mask).append(" (0x")
                            .append(Long.toHexString(mask)).append(")\n");
                    int dataType = tm.getDataNetworkType();
                    int voiceType = tm.getVoiceNetworkType();
                    b.append("当前数据网络：").append(networkName(dataType)).append("\n");
                    b.append("当前语音网络：").append(networkName(voiceType)).append("\n");
                    try { b.append("当前连接频段：").append(liveBands(tm)).append("\n"); }
                    catch (Throwable e) { b.append("当前连接频段：—（读取失败）\n"); }
                    b.append("提示：数据网络 UNKNOWN 不等于无信号，副卡未启用数据时可能返回 UNKNOWN\n");
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

    /** Live serving-cell band: query ServiceState only, never cache or use historical cell logs.
     * The OEM may hide NetworkRegistrationInfo behind non-SDK access restrictions.
     * A narrow parsing fallback uses the *current* ServiceState dump, not dumpsys history.
     */
    private static String liveBands(TelephonyManager tm) {
        String primary = serviceStateBands(tm);
        if (primary != null && !primary.startsWith("—")) return primary;
        // Independently attempt registered cell information if the Android 16
        // ServiceState snapshot is sanitized. Strict time limit + bounded worker.
        String backup = registeredCellBands(tm);
        if (backup != null && !backup.startsWith("—")) return backup;
        return "—（ServiceState: " + primary + "；已注册小区: " + backup + "）";
    }

    private static String serviceStateBands(TelephonyManager tm) {
        try {
            ServiceState state;
            try {
                // getServiceState() may return a location-sanitized snapshot on Android 16.
                state = tm.getServiceState(TelephonyManager.INCLUDE_LOCATION_DATA_FINE);
            } catch (Throwable fineError) {
                state = tm.getServiceState();
            }
            if (state == null) return "—（未上报 ServiceState）";
            StringBuilder answer = new StringBuilder();
            try {
                Method method = ServiceState.class.getDeclaredMethod("getNetworkRegistrationInfoList");
                method.setAccessible(true);
                Object all = method.invoke(state);
                if (all instanceof List) {
                    for (Object info : (List<?>) all) {
                        if (info == null) continue;
                        String text = String.valueOf(info);
                        if (!text.contains("transportType=WWAN") || !text.contains("domain=PS")) continue;
                        if (!(text.contains("registrationState=HOME") || text.contains("registrationState=ROAMING"))) continue;
                        Object cell = info.getClass().getMethod("getCellIdentity").invoke(info);
                        if (cell instanceof CellIdentityNr) {
                            CellIdentityNr nr = (CellIdentityNr) cell;
                            String value = bandsText("N", nr.getBands(), nr.getNrarfcn(), "NR-ARFCN");
                            if (!value.startsWith("—")) return value;
                        }
                        if (cell instanceof CellIdentityLte) {
                            CellIdentityLte lte = (CellIdentityLte) cell;
                            String value = bandsText("B", lte.getBands(), lte.getEarfcn(), "EARFCN");
                            if (!value.startsWith("—")) return value;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Reflection restrictions vary by ColorOS build: parse this snapshot only.
            }
            // OEM implementations sometimes hide the full list but expose a direct PS/WWAN entry.
            try {
                Method getOne = ServiceState.class.getDeclaredMethod("getNetworkRegistrationInfo", int.class, int.class);
                getOne.setAccessible(true);
                Object info = getOne.invoke(state, 2, 1); // DOMAIN_PS, TRANSPORT_TYPE_WWAN
                if (info != null) {
                    String line = info.toString();
                    if (line.contains("registrationState=HOME") || line.contains("registrationState=ROAMING")) {
                        Object cell = info.getClass().getMethod("getCellIdentity").invoke(info);
                        if (cell instanceof CellIdentityNr) {
                            CellIdentityNr nr = (CellIdentityNr) cell;
                            String value = bandsText("N", nr.getBands(), nr.getNrarfcn(), "NR-ARFCN");
                            if (!value.startsWith("—")) return value;
                        } else if (cell instanceof CellIdentityLte) {
                            CellIdentityLte lte = (CellIdentityLte) cell;
                            String value = bandsText("B", lte.getBands(), lte.getEarfcn(), "EARFCN");
                            if (!value.startsWith("—")) return value;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            String dump = state.toString();
            Matcher m = Pattern.compile("NetworkRegistrationInfo\\{\\s*domain=PS\\s+transportType=WWAN(.*?)(?=NetworkRegistrationInfo\\{|\\]\\s*,?\\s*mNrFrequencyRange|$)", Pattern.DOTALL).matcher(dump);
            while (m.find()) {
                String ps = m.group(1);
                if (!(ps.contains("registrationState=HOME") || ps.contains("registrationState=ROAMING"))) continue;
                boolean nr = ps.contains("CellIdentityNr:");
                boolean lte = ps.contains("CellIdentityLte:");
                if (!nr && !lte) continue;
                Matcher bm = Pattern.compile("mBands\\s*=\\s*\\[([^\\]]*)\\]").matcher(ps);
                if (!bm.find()) continue;
                StringBuilder bands = new StringBuilder();
                for (String item : bm.group(1).split(",")) {
                    try {
                        int band = Integer.parseInt(item.trim());
                        if (band <= 0) continue;
                        if (bands.length() > 0) bands.append(" / ");
                        bands.append(nr ? "N" : "B").append(band);
                    } catch (NumberFormatException ignored) { }
                }
                if (bands.length() == 0) continue;
                Matcher freq = Pattern.compile(nr ? "mNrArfcn\\s*=\\s*(\\d+)" : "mEarfcn\\s*=\\s*(\\d+)").matcher(ps);
                if (freq.find()) {
                    int channel = Integer.parseInt(freq.group(1));
                    if (channel >= 0 && channel != Integer.MAX_VALUE)
                        bands.append(" · ").append(nr ? "NR-ARFCN " : "EARFCN ").append(channel);
                }
                return bands.toString();
            }
            return "—（未上报有效注册频段）";
        } catch (Throwable t) {
            return "—（读取受限：" + t.getClass().getSimpleName() + "）";
        }
    }

    /** Android may expose registered cell identity even when ServiceState is scrubbed.
     * Only accept a registered cell; never infer from neighboring/unregistered cells.
     * CellInfo may be cached by Android and is not an independent modem refresh.
     */
    private static String registeredCellBands(TelephonyManager tm) {
        Future<String> future;
        try {
            future = CELL_WORKER.submit(() -> {
                List<CellInfo> cells = tm.getAllCellInfo();
                if (cells == null || cells.isEmpty()) return "—（小区列表为空/权限不足）";
                for (CellInfo cell : cells) {
                    if (cell == null || !cell.isRegistered()) continue;
                    if (cell instanceof CellInfoNr) {
                        android.telephony.CellIdentity identity = cell.getCellIdentity();
                        if (identity instanceof CellIdentityNr) {
                            CellIdentityNr nr = (CellIdentityNr) identity;
                            String value = bandsText("N", nr.getBands(), nr.getNrarfcn(), "NR-ARFCN");
                            if (!value.startsWith("—")) return value;
                        }
                    } else if (cell instanceof CellInfoLte) {
                        CellIdentityLte lte = ((CellInfoLte) cell).getCellIdentity();
                        String value = bandsText("B", lte.getBands(), lte.getEarfcn(), "EARFCN");
                        if (!value.startsWith("—")) return value;
                    }
                }
                return "—（未发现注册的 NR/LTE 小区频段）";
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            return "—（读取线程忙）";
        } catch (Throwable t) {
            return "—（提交失败：" + t.getClass().getSimpleName() + "）";
        }
        try {
            return future.get(650, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return "—（650ms 超时）";
        } catch (Throwable t) {
            return "—（" + t.getClass().getSimpleName() + "）";
        }
    }

    private static String bandsText(String prefix, int[] bands, int channel, String channelName) {
        if (bands == null || bands.length == 0) return "—（未上报 Band）";
        StringBuilder b = new StringBuilder();
        for (int band : bands) {
            if (band <= 0) continue;
            if (b.length() != 0) b.append(" / ");
            b.append(prefix).append(band);
        }
        if (b.length() == 0) return "—（无有效 Band）";
        if (channel >= 0 && channel != Integer.MAX_VALUE) b.append(" · ").append(channelName).append(" ").append(channel);
        return b.toString();
    }

    /** Optional snapshot of existing default QoS for each phone slot.
     * Never called by snapshot(), the policy worker or a telephony callback.
     * Does not request a modem operation. No network/session writes.
     */
    private static void startQciProbe() {
        final int token = ++qosToken;
        QCI_VALUES[0] = "检测中"; QCI_VALUES[1] = "检测中";
        qosDiagnostic = "正在尝试读取当前数据承载（最多 2 秒）";
        handler.postDelayed(() -> {
            if (token == qosToken && qosDiagnostic.startsWith("正在")) {
                qosToken++;
                QCI_VALUES[0] = "—（超时）";
                QCI_VALUES[1] = "—（超时）";
                qosDiagnostic = "超时：ColorOS 电话服务未及时返回 QoS 数据";
                reply();
            }
        }, 2000L);
        try {
            QOS_WORKER.execute(() -> {
                final String[] result = { readQciSlot(0), readQciSlot(1) };
                Handler h = handler;
                if (h != null) h.post(() -> {
                    if (qosToken != token) return; // stale completion ignored
                    QCI_VALUES[0] = result[0]; QCI_VALUES[1] = result[1];
                    qosDiagnostic = "检测完成；仅代表已建立数据承载的 QoS，非套餐优先级";
                    reply();
                });
            });
        } catch (Throwable e) {
            QCI_VALUES[0] = "—"; QCI_VALUES[1] = "—";
            qosDiagnostic = "检测任务不可用：" + e.getClass().getSimpleName();
        }
    }

    private static String readQciSlot(int slot) {
        try {
            Class<?> factory = Class.forName("com.android.internal.telephony.PhoneFactory");
            Method getPhone = factory.getDeclaredMethod("getPhone", int.class);
            getPhone.setAccessible(true);
            Object phone = getPhone.invoke(null, slot);
            Object controller = callIfPresent(phone, "getDataNetworkController");
            Object networks = callIfPresent(controller, "getDataNetworkList");
            if (!(networks instanceof Iterable)) return "—（接口未提供）";
            for (Object network : (Iterable<?>) networks) {
                Object response = callIfPresent(network, "getDataCallResponse");
                Object qos = callIfPresent(response, "getDefaultQos");
                if (qos == null) qos = callIfPresent(network, "getDefaultQos");
                Object qci = callIfPresent(qos, "getQci");
                Object fiveQi = callIfPresent(qos, "get5Qi");
                if (fiveQi instanceof Number && ((Number)fiveQi).intValue() > 0)
                    return "5QI " + fiveQi + standardPriority(((Number) fiveQi).intValue(), true);
                if (qci instanceof Number && ((Number)qci).intValue() > 0)
                    return "QCI " + qci + standardPriority(((Number) qci).intValue(), false);
            }
            return "—（未上报）";
        } catch (Throwable ex) { return "—（" + ex.getClass().getSimpleName() + "）"; }
    }

    /** Standardized priority levels for the classic 1..9 QCI/5QI values. */
    private static String standardPriority(int value, boolean nr) {
        final int[] qci = {0, 2, 4, 3, 5, 1, 6, 7, 8, 9};
        final int[] fiveQi = {0, 20, 40, 30, 50, 10, 60, 70, 80, 90};
        if (value < 1 || value > 9) return "（优先级未映射）";
        return "（标准优先级 " + (nr ? fiveQi[value] : qci[value]) + "）";
    }

    private static Object callIfPresent(Object owner, String name) {
        if (owner == null) return null;
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method method = c.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(owner);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable error) { return null; }
        }
        return null;
    }

    /** Read-only on-demand modem RAT report. This is not a complete band capability list. */
    private static String queryHardwareCapability() {
        try {
            TelephonyManager base = app.getSystemService(TelephonyManager.class);
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            if (base == null || sm == null) return "电话服务未就绪";
            StringBuilder b = new StringBuilder();
            List<SubscriptionInfo> list = sm.getActiveSubscriptionInfoList();
            if (list != null) for (SubscriptionInfo si : list) {
                if (si.getSimSlotIndex() < 0) continue;
                try {
                    long raf = base.createForSubscriptionId(si.getSubscriptionId()).getSupportedRadioAccessFamily();
                    b.append("卡槽 ").append(si.getSimSlotIndex()+1)
                     .append("【设备读取】支持制式 RAF=0x")
                     .append(Long.toHexString(raf)).append("；NR=")
                     .append((raf & NR) != 0 ? "是" : "否/未返回")
                     .append("\n");
                } catch (Throwable e) {
                    b.append("卡槽 ").append(si.getSimSlotIndex()+1)
                     .append("：无法获取（").append(e.getClass().getSimpleName()).append("）\n");
                }
            }
            return b.append("【说明】设备读取仅报告无线制式，完整 N/B 频段请参考标注为官方公布的列表。").toString();
        } catch (Throwable t) { return "检测失败：" + t.getClass().getSimpleName(); }
    }

    private static String networkName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G NR";
            case TelephonyManager.NETWORK_TYPE_LTE: return "4G LTE";
            case TelephonyManager.NETWORK_TYPE_UMTS: return "3G UMTS";
            case TelephonyManager.NETWORK_TYPE_HSPA: return "3G HSPA";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "3G HSPA+";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "2G EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS: return "2G GPRS";
            case TelephonyManager.NETWORK_TYPE_GSM: return "2G GSM";
            case TelephonyManager.NETWORK_TYPE_UNKNOWN: return "UNKNOWN / 未知";
            default: return "NETWORK_TYPE_" + type + "（未映射）";
        }
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
