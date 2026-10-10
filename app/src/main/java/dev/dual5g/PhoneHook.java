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
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.ServiceState;
import java.lang.reflect.Method;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.LinkedHashSet;
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
    private static volatile String hardwareDiagnostic = "尚未检测；点击「检测设备无线能力」";
    private static volatile String bandsDiagnostic = "尚未读取；点击「读取当前连接频段」";
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
                        } else if ("hardware".equals(command)) {
                            hardwareDiagnostic = queryHardwareCapability();
                        } else if ("bands".equals(command)) {
                            bandsDiagnostic = queryRegisteredBands();
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
        XposedBridge.log("Dual5G STANDALONE v0.4.0 phone hook initialized; no su, no KSU module");
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
            XposedBridge.log("Dual5G STANDALONE v0.4.0 policy: " + lastStatus);
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
        b.append("设备能力检测：").append(hardwareDiagnostic).append("\n");
        b.append("基站频段查询：").append(bandsDiagnostic).append("\n");
        b.append("最近操作：").append(lastStatus).append("\n\n");
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
                    int dataType = tm.getDataNetworkType();
                    int voiceType = tm.getVoiceNetworkType();
                    b.append("当前数据网络：").append(networkName(dataType)).append("\n");
                    b.append("当前语音网络：").append(networkName(voiceType)).append("\n");
                    b.append("当前连接频段：").append(liveBands(tm)).append("\n");
                    b.append("当前允许制式：").append(formatRats(mask)).append("（不是启用频段列表）\n");
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
        try {
            ServiceState state = tm.getServiceState();
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
                            return bandsText("N", nr.getBands(), nr.getNrarfcn(), "NR-ARFCN");
                        }
                        if (cell instanceof CellIdentityLte) {
                            CellIdentityLte lte = (CellIdentityLte) cell;
                            return bandsText("B", lte.getBands(), lte.getEarfcn(), "EARFCN");
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Reflection restrictions vary by ColorOS build: parse this snapshot only.
            }
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

    /** Read-only modem RAT capability. Android does not provide an exhaustive hardware-band API. */
    private static String queryHardwareCapability() {
        if (app == null) return "电话进程未就绪";
        StringBuilder b = new StringBuilder();
        try {
            TelephonyManager base = app.getSystemService(TelephonyManager.class);
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            if (base == null) return "电话服务不可用";
            List<SubscriptionInfo> subs = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                appendRadioCapability(b, "设备默认 Modem", base);
            } else {
                for (SubscriptionInfo sub : subs) {
                    if (sub.getSimSlotIndex() < 0) continue;
                    appendRadioCapability(b, "卡槽 " + (sub.getSimSlotIndex() + 1),
                            base.createForSubscriptionId(sub.getSubscriptionId()));
                }
            }
        } catch (Throwable t) {
            b.append("探测失败：").append(t.getClass().getSimpleName()).append(" ").append(t.getMessage());
            XposedBridge.log("Dual5G hardware capability probe error: " + t);
        }
        b.append("\n【设备读取】仅无线制式能力（NR/LTE 等），不是逐频段 N/B 清单。")
         .append("\n【完整频段】系统不提供通用枚举接口；支持机型使用单独标注的官方规格兜底。");
        return b.toString();
    }

    private static void appendRadioCapability(StringBuilder b, String name, TelephonyManager tm) {
        b.append(name).append("：");
        try {
            long raf = tm.getSupportedRadioAccessFamily();
            b.append(formatRats(raf)).append("；RAF=0x").append(Long.toHexString(raf));
            if (raf == 0) b.append("（未返回有效能力）");
        } catch (Throwable t) {
            b.append("读取受限：").append(t.getClass().getSimpleName());
        }
        b.append('\n');
    }

    /** Read-only, on-demand cell information. NEVER call from normal status polling. */
    private static String queryRegisteredBands() {
        if (app == null) return "电话进程未就绪";
        StringBuilder result = new StringBuilder("仅已注册小区：");
        try {
            SubscriptionManager sm = app.getSystemService(SubscriptionManager.class);
            TelephonyManager base = app.getSystemService(TelephonyManager.class);
            if (sm == null || base == null) return "电话服务不可用";
            List<SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) return "无活动 SIM";
            for (SubscriptionInfo sub : subs) {
                int slot = sub.getSimSlotIndex();
                if (slot < 0) continue;
                result.append("\nSIM ").append(slot + 1).append(": ");
                try {
                    List<CellInfo> cells = base.createForSubscriptionId(sub.getSubscriptionId()).getAllCellInfo();
                    if (cells == null) { result.append("系统未上报小区信息"); continue; }
                    LinkedHashSet<String> found = new LinkedHashSet<>();
                    for (CellInfo cell : cells) {
                        if (cell == null || !cell.isRegistered()) continue;
                        if (cell instanceof CellInfoNr) {
                            CellIdentityNr id = (CellIdentityNr) ((CellInfoNr) cell).getCellIdentity();
                            for (int band : id.getBands()) if (band > 0) found.add("N" + band);
                        } else if (cell instanceof CellInfoLte) {
                            CellIdentityLte id = (CellIdentityLte) ((CellInfoLte) cell).getCellIdentity();
                            for (int band : id.getBands()) if (band > 0) found.add("B" + band);
                        }
                    }
                    result.append(found.isEmpty() ? "未上报已注册 LTE/NR 频段（NSA 可能只提供 LTE 锚点）" : android.text.TextUtils.join(" / ", found));
                } catch (Throwable e) {
                    result.append("读取失败：").append(e.getClass().getSimpleName())
                          .append(" ").append(e.getMessage());
                }
            }
            result.append("\n说明：此结果仅代表系统报告的已注册小区，不包含全部载波聚合或基带启用频段。");
            return result.toString();
        } catch (Throwable e) {
            XposedBridge.log("Dual5G bands diagnostic failure: " + e);
            return "频段诊断失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
        }
    }

    private static String formatRats(long mask) {
        StringBuilder r = new StringBuilder();
        if ((mask & TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0) r.append("NR/5G ");
        if ((mask & TelephonyManager.NETWORK_TYPE_BITMASK_LTE) != 0) r.append("LTE/4G ");
        return r.length() == 0 ? "其他制式" : r.toString().trim();
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
