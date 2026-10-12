package dev.dual5g;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone LSPosed UI. No su and no companion KernelSU module. */
public class MainActivity extends Activity {
    private static final int BG=0xFF0B1120, PANEL=0xFF172238, STROKE=0xFF29384F;
    private static final int WHITE=0xFFF3F7FF, MUTED=0xFFA1B2CD, GREEN=0xFF5DE2B1, BLUE=0xFF6EA9FF;
    private TextView status, updated, details, heading, qciResult, bandProbeResult;
    private LinearLayout overviewRow;
    private final SimCard[] sims = new SimCard[2];
    private LinearLayout debugBox;
    
    private boolean expanded = false;
    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private boolean foreground = false;
    private final Runnable livePoll = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            sendQuietStatus();
            if (lastReplyAt == 0L && details != null) {
                details.setText("电话进程没有返回状态。请检查 LSPosed 模块是否启用并作用于 com.android.phone；随后重启手机。\n控制通信尚未建立，频段及设备检测无法工作。");
            }
            liveHandler.postDelayed(this, 4000L);
        }
    };
    private boolean receiverRegistered = false;
    private TextView nasProbeResult;
    private TextView rfProbeResult;
    private boolean rfAutoStarted = false;
    private boolean nasAutoStarted = false;
    private boolean dmsAutoStarted = false;
    private volatile boolean dmsInFlight = false;
    private long dmsLastAttemptAt = 0L;
    private long lastReplyAt = 0L;
    private long requestStartedAt = 0L;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            lastReplyAt = android.os.SystemClock.elapsedRealtime();
            String raw = intent.getStringExtra("status");
            boolean enabled = intent.getBooleanExtra("enabled", true);
            status.setText(enabled ? "●  自动策略已启用" : "●  自动策略已停用");
            status.setTextColor(enabled ? GREEN : MUTED);
            updated.setText("（最后更新时间：" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + "）");
            if (raw == null) raw = "电话进程未提供状态数据";
            details.setText(raw);
            if (qciResult != null) {
                String diagnosis = field(raw, "QCI 检测状态：");
                qciResult.setText("QCI 检测：" + (diagnosis.isEmpty() ? "电话服务未提供诊断状态" : diagnosis));
            }
            showStatus(raw);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // Conservative system bar setup: avoid accessing WindowInsetsController
        // during Activity creation (may be unavailable on customized ROMs).
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(0); // light icons on dark bar
        ScrollView scroll = new ScrollView(this);
        if (Build.VERSION.SDK_INT >= 30) {
            scroll.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                // Keep all dashboard content below status bar and above navigation bar.
                view.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = vertical();
        root.setPadding(dp(20), dp(26), dp(20), dp(36));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout brandRow = horizontal();
        root.addView(brandRow);
        android.widget.ImageView appIcon = new android.widget.ImageView(this);
        appIcon.setImageResource(dev.dual5g.R.mipmap.ic_launcher);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        iconParams.rightMargin = dp(12);
        brandRow.addView(appIcon, iconParams);
        heading = label(brandRow, "网络切换控制台", 24, WHITE, true);
        heading.setPadding(0, dp(8), 0, dp(6));
        // Compact first screen: service state only, SIM information lives in overview.

        LinearLayout hero = panel(root, dp(15), dp(12));
        LinearLayout row = horizontal(); hero.addView(row);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBaselineAligned(false);
        status = label(row, "●  正在连接电话服务", 15, MUTED, true);
        updated = label(row, "（等待首次同步）", 11, MUTED, false);
        updated.setPadding(dp(4), 0, 0, 0);

        label(root, "SIM 网络概览", 17, WHITE, true).setPadding(0, dp(27), 0, dp(10));
        // Fixed two-column grid. Empty slots keep their space for stable layout.
        overviewRow=horizontal();
        overviewRow.setGravity(Gravity.TOP);
        root.addView(overviewRow, new LinearLayout.LayoutParams(-1,-2));
        for (int i=0;i<2;i++) sims[i]=addSim(overviewRow, i);


        label(root, "快捷操作", 17, WHITE, true).setPadding(0, dp(21), 0, dp(10));
        Button refresh = action(root, "↻   刷新网络状态", BLUE, 0xFF152845, "status", true);
        refresh.setContentDescription("刷新双卡状态");
        LinearLayout actions = horizontal(); root.addView(actions);
        action(actions, "启用自动切换", BG, GREEN, "enable", false);
        action(actions, "立即同步", WHITE, PANEL, "sync", false);
        LinearLayout secondary = horizontal(); root.addView(secondary);
        action(secondary, "停用策略", MUTED, PANEL, "disable", false);
        action(secondary, "恢复原始模式", 0xFFFFB4AA, PANEL, "restore", false);
        Button qciButton = action(root, "⌁   读取 QCI / 5QI（实验性）", BLUE, PANEL, "qci", true);
        qciButton.setContentDescription("仅按需尝试获取当前数据连接的 QCI 或 5QI；不参与实时刷新");
        qciResult = label(root, "QCI 检测：尚未检测", 12, MUTED, false);
        qciResult.setPadding(dp(4), dp(6), dp(4), dp(8));
        label(root, "基带硬件支持频段", 17, WHITE, true).setPadding(0, dp(22), 0, dp(8));
        LinearLayout dmsPanel = panel(root, dp(14), dp(12));
        bandProbeResult = label(dmsPanel, "准备自动读取高通基带能力…", 12, MUTED, false);
        bandProbeResult.setTextIsSelectable(true);
        label(root, "基带当前频段偏好（NAS · 实验性）", 17, WHITE, true).setPadding(0, dp(18), 0, dp(8));
        LinearLayout nasPanel = panel(root, dp(14), dp(12));
        nasProbeResult = label(nasPanel, "准备自动读取 NAS 配置…", 12, MUTED, false);
        nasProbeResult.setTextIsSelectable(true);
        label(root, "当前实际射频频段（NAS RF · 实验性）", 17, WHITE, true).setPadding(0, dp(18), 0, dp(8));
        LinearLayout rfPanel = panel(root, dp(14), dp(12));
        rfProbeResult = label(rfPanel, "准备自动读取当前射频活动信息…", 12, MUTED, false);
        rfProbeResult.setTextIsSelectable(true);
        Button restart = action(root, "⟳   重启电话服务（Root）", 0xFFFFB4AA, 0xFF39202A, "restart-phone", true);
        restart.setContentDescription("确认后使用 Root 重启电话进程，可能会暂时断网");

        TextView expander = label(root, "调试日志  ▾", 15, WHITE, true);
        expander.setPadding(0, dp(22), 0, dp(10));
        debugBox = panel(root, dp(14), dp(14));
        details = label(debugBox, "等待电话服务响应…", 12, MUTED, false);
        details.setTextIsSelectable(true);
        details.setTypeface(Typeface.MONOSPACE);
        debugBox.setVisibility(View.GONE);
        expander.setOnClickListener(v -> {
            expanded=!expanded;
            expander.setText(expanded ? "调试日志  ▴" : "调试日志  ▾");
            debugBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
        });
        label(root, "LSPosed · 推荐作用域 com.android.phone     |     v0.5.7", 11, MUTED, false)
                .setPadding(0, dp(18), 0, 0);
        try {
            registerReceiver(updates, new IntentFilter(PhoneHook.RESULT), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ex) {
            android.util.Log.e("Dual5G-UI", "registerReceiver failed", ex);
            details.setText("接收状态广播失败：" + ex);
        }
    }

    private SimCard addSim(LinearLayout row, int slot) {
        LinearLayout card=vertical();
        card.setPadding(dp(13),dp(16),dp(13),dp(16));
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(PANEL); bg.setCornerRadius(dp(18)); bg.setStroke(dp(1),STROKE);
        card.setBackground(bg);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1f);
        cp.topMargin=dp(8);
        if(slot==0) cp.rightMargin=dp(6); else cp.leftMargin=dp(6);
        row.addView(card,cp);
        TextView title=label(card,"SIM "+(slot+1),16,WHITE,true);
        TextView pill=label(card,"未检测",11,MUTED,true);
        pill.setPadding(0,dp(5),0,dp(9));
        TextView carrier=label(card,"未插入 SIM",12,MUTED,false);
        carrier.setMaxLines(2);
        carrier.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView mode=label(card,"—",24,WHITE,true);
        mode.setPadding(0,dp(10),0,dp(5));
        LinearLayout networkRow=horizontal();
        card.addView(networkRow);
        TextView real=label(networkRow,"数据：—",11,MUTED,false);
        TextView voice=label(networkRow,"语音：—",11,MUTED,false);
        LinearLayout.LayoutParams np1=new LinearLayout.LayoutParams(0,-2,1f);
        LinearLayout.LayoutParams np2=new LinearLayout.LayoutParams(0,-2,1f);
        real.setLayoutParams(np1); voice.setLayoutParams(np2);
        TextView band=label(card,"频段：—",11,BLUE,true);
        band.setPadding(0,dp(7),0,0);
        TextView qos=label(card,"QCI/5QI：—   上行：—   下行：—",11,MUTED,false);
        qos.setPadding(0,dp(5),0,0);
        return new SimCard(card,title,pill,carrier,mode,real,voice,band,qos);
    }

    private void setEmptySlot(SimCard c) {
        c.pill.setText("○  未插卡");
        c.pill.setTextColor(MUTED);
        c.carrier.setText("未检测到活动 SIM");
        c.mode.setText("无 SIM");
        c.mode.setTextColor(MUTED);
        c.real.setText("数据：—");
        c.voice.setText("语音：—");
        c.band.setText("频段：—");
        c.qos.setText("QCI/5QI：—   上行：—   下行：—");
        c.title.setText(c.slotTitle + " (—)");
        c.container.setAlpha(0.68f);
    }

    private void showStatus(String raw) {
        int dataId=parseInt(raw, "默认数据订阅 ID：\\s*(-?\\d+)", -1);
        int count=0;
        for (int i=0;i<2;i++) {
            SimCard c=sims[i];
            String section=section(raw,i);
            if (section==null) { setEmptySlot(c); continue; }
            int subId=parseInt(section,"订阅 ID：\\s*(-?\\d+)",-1);
            // A section without a valid subscription is not an inserted SIM.
            if(subId<0) { setEmptySlot(c); continue; }
            count++;
            c.container.setAlpha(1f);
            boolean active=subId==dataId;
            c.title.setText(c.slotTitle + " (subId " + subId + ")");
            c.pill.setText(active ? "●  当前上网卡" : "●  已插卡");
            c.pill.setTextColor(active ? GREEN : BLUE);
            c.carrier.setText(field(section,"运营商："));
            String mode=field(section,"网络模式：");
            boolean hasNr=mode.contains("允许 5G");
            c.mode.setText(hasNr ? "5G 优先" : mode.contains("4G") ? "4G 优先" : "未知模式");
            c.mode.setTextColor(hasNr ? GREEN : WHITE);
            c.real.setText("数据："+field(section,"当前数据网络："));
            c.voice.setText("语音："+field(section,"当前语音网络："));
            String band = field(section,"当前连接频段：");
            c.band.setText(formatBandLine(band));
            String reportedQci = field(section, "QCI/5QI：");
            // Do not invent values for unsupported modem / ColorOS implementations.
            c.qos.setText("QCI/5QI：" + (reportedQci.isEmpty() ? "—" : reportedQci)
                    + "   上行：—   下行：—");

        }

    }

    /** Render connected band and channel side by side, without modifying telephony hooks. */
    private static String formatBandLine(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.startsWith("—")) return "频段：—   ARFCN：—";
        String[] pieces = raw.split("\\s*[·]\\s*", 2);
        String bands = pieces[0].trim();
        if (pieces.length == 1) return "频段：" + bands + "   ARFCN：—";
        String channel = pieces[1].trim().replaceFirst("^(NR-ARFCN|EARFCN)\\s+", "$1：");
        return "频段：" + bands + "   " + channel;
    }

    private static String section(String raw, int slot) {
        Pattern p=Pattern.compile("──── 卡 "+(slot+1)+" \\(Slot "+slot+"\\) ────([\\s\\S]*?)(?=\\n──── 卡 |\\z)");
        Matcher m=p.matcher(raw); return m.find()?m.group(1):null;
    }
    private static int parseInt(String s,String regex,int fallback) {
        Matcher m=Pattern.compile(regex).matcher(s);
        if (!m.find()) return fallback;
        try {return Integer.parseInt(m.group(1));} catch(Exception e) {return fallback;}
    }
    private static String field(String s,String key) {
        int p=s.indexOf(key); if(p<0) return "—";
        int end=s.indexOf('\n',p); if(end<0) end=s.length();
        String v=s.substring(p+key.length(),end).trim(); return v.isEmpty()?"—":v;
    }
    private static class SimCard {
        final LinearLayout container;
        final TextView title,pill,carrier,mode,real,voice,band,qos;
        final String slotTitle;
        SimCard(LinearLayout box,TextView title,TextView pill,TextView carrier,TextView mode,TextView real,TextView voice,TextView band,TextView qos) {
            this.container=box;this.title=title;this.pill=pill;this.carrier=carrier;this.mode=mode;this.real=real;this.voice=voice;this.band=band;this.qos=qos;
            this.slotTitle=title.getText().toString();
        }
    }
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout horizontal(){LinearLayout l=new LinearLayout(this);l.setOrientation(0);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private TextView label(LinearLayout parent,String text,int size,int color,boolean bold){
        TextView t=new TextView(this); t.setText(text);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); parent.addView(t);return t;
    }
    private LinearLayout panel(LinearLayout parent,int padding,int marginTop){
        LinearLayout box=vertical();box.setPadding(padding,padding,padding,padding);
        GradientDrawable bg=new GradientDrawable();bg.setColor(PANEL);bg.setCornerRadius(dp(20));bg.setStroke(dp(1),STROKE);
        box.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=marginTop;
        parent.addView(box,p);return box;
    }
    private Button action(LinearLayout parent,String title,int textColor,int background,String command,boolean full){
        Button b=new Button(this);b.setText(title);b.setTextColor(textColor);b.setTextSize(13);b.setAllCaps(false);
        GradientDrawable bg=new GradientDrawable();bg.setColor(background);bg.setCornerRadius(dp(14));b.setBackground(bg);
        LinearLayout.LayoutParams p=full?new LinearLayout.LayoutParams(-1,dp(51)):new LinearLayout.LayoutParams(0,dp(51),1);
        p.topMargin=dp(8);if(!full)p.rightMargin=dp(8);
        parent.addView(b,p);
        b.setOnClickListener(v -> {
            if ("restart-phone".equals(command)) { confirmRestartPhone(b); return; }
            lockButton(b);
            if ("qci".equals(command) && qciResult != null) {
                qciResult.setText("QCI 检测：请求已发送，等待电话进程回应…");
            }
            send(command);
        });
        return b;
    }
    /** Visible press feedback and 5-second per-button cooldown. */
    private void lockButton(Button button) {
        if (!button.isEnabled()) return;
        button.setEnabled(false);
        button.setAlpha(0.5f);
        String title = button.getText().toString();
        button.setText("⏳  处理中…");
        liveHandler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                button.setText(title);
                button.setAlpha(1f);
                button.setEnabled(true);
            }
        }, 5000L);
    }


    /** Auto-run once when UI first becomes visible. Never on the 4-second phone polling path. */
    private void autoReadDmsBands() {
        if (dmsInFlight || dmsAutoStarted || bandProbeResult == null) return;
        dmsAutoStarted = true;
        dmsInFlight = true;
        dmsLastAttemptAt = android.os.SystemClock.elapsedRealtime();
        bandProbeResult.setText("正在自动读取硬件频段（KernelSU Root，最长 10 秒）…");
        new Thread(() -> {
            String raw = runDmsProbe();
            String formatted = formatDmsBands(raw);
            runOnUiThread(() -> {
                dmsInFlight = false;
                if (!isFinishing() && !isDestroyed() && bandProbeResult != null)
                    bandProbeResult.setText(formatted);
            });
        }, "Dual5G-AutoDMS").start();
    }

    private static String formatDmsBands(String raw) {
        if (raw == null || !raw.contains("QUERY_OK:"))
            return "基带读取失败（非官方规格）\n" + (raw == null ? "无返回数据" : raw);
        String lte = "";
        String nr = "";
        for (String line : raw.split("\n")) {
            String clean = line.trim();
            if (clean.startsWith("Extended LTE bands:")) lte = clean.substring("Extended LTE bands:".length()).trim();
            else if (clean.startsWith("NR5G bands:")) nr = clean.substring("NR5G bands:".length()).trim();
            else if (lte.isEmpty() && clean.startsWith("LTE B bands (legacy mask):"))
                lte = clean.substring("LTE B bands (legacy mask):".length()).trim();
        }
        if (lte.isEmpty() && nr.isEmpty()) return "基带返回成功，但未解析出频段清单\n" + raw;
        // Strip protocol count to avoid implying this is the RF front-end/active band mask.
        lte = lte.replaceFirst("^count=\\d+;\\s*", "");
        nr = nr.replaceFirst("^count=\\d+;\\s*", "");
        return "● 基带实测 · Qualcomm DMS\n" +
                "5G NR：" + (nr.isEmpty() ? "未上报" : nr) + "\n" +
                "4G LTE：" + (lte.isEmpty() ? "未上报" : lte) + "\n" +
                "说明：基带能力不等于当前启用频段或射频前端全部能力";
    }

    private String runDmsProbe() {
        Process p = null;
        try {
            // APK asset is packaged by GitHub Actions from an NDK-built standalone binary.
            java.io.File src = new java.io.File(getFilesDir(), "dms_probe_arm64");
            try (java.io.InputStream in = getAssets().open("dms_probe_arm64");
                 java.io.FileOutputStream out = new java.io.FileOutputStream(src)) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            // Don't execute from app-private data (Android app-data execute restrictions).
            // Root copies the trusted bundled executable to a fixed, app-specific temporary path.
            String from = src.getAbsolutePath();
            if (!from.matches("/data/(user|user_de)/0/dev\\.dual5g/files/dms_probe_arm64") &&
                !from.matches("/data/data/dev\\.dual5g/files/dms_probe_arm64"))
                return "DMS 检测：应用数据路径不符合预期，未执行：" + from;
            String path = "/data/local/tmp/dual5g_dms_probe_arm64";
            String cmd = "[ \"$(id -u)\" = 0 ] || exit 91; " +
                    "cp '" + from + "' '" + path + "' || exit 92; " +
                    "chmod 0700 '" + path + "' || exit 93; " +
                    "'" + path + "'; rc=$?; rm -f '" + path + "'; exit $rc";
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final Process process = p;
            Thread collect = new Thread(() -> {
                try (java.io.InputStream input = process.getInputStream()) {
                    byte[] buf = new byte[2048]; int n;
                    while ((n = input.read(buf)) >= 0 && bytes.size() < 16384) bytes.write(buf, 0, n);
                } catch (Exception ignored) {}
            }, "Dual5G-DMS-Output");
            collect.setDaemon(true); collect.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "DMS 检测：超时（10 秒）。请检查 KernelSU 授权窗口，或 QRTR 是否可访问。";
            }
            collect.join(600);
            String stdout = bytes.toString("UTF-8").trim();
            return "DMS 检测（退出码 " + p.exitValue() + "）：\n" +
                    (stdout.isEmpty() ? "无输出；请检查 su、SELinux、执行权限" : stdout);
        } catch (java.io.FileNotFoundException e) {
            return "DMS 探测组件缺失；请用项目提供的 GitHub Actions 工作流构建 APK。";
        } catch (Throwable e) {
            return "DMS 检测失败：" + e.getClass().getSimpleName() + "：" + e.getMessage();
        } finally {
            if (p != null) p.destroy();
        }
    }


    /** One-time experimental NAS read, independent from the phone Hook and 4s refresh. */
    private void autoReadNasPreference() {
        if (nasAutoStarted || nasProbeResult == null) return;
        nasAutoStarted = true;
        nasProbeResult.setText("正在自动读取 NAS 频段偏好（最长 10 秒）…");
        new Thread(() -> {
            String raw = runNasProbe();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && nasProbeResult != null)
                    nasProbeResult.setText("NAS 频段偏好（不等于运营商允许的频段）\n" + raw);
            });
        }, "Dual5G-AutoNAS").start();
    }

    private String runNasProbe() {
        Process p = null;
        try {
            java.io.File src = new java.io.File(getFilesDir(), "nas_probe_arm64");
            try (java.io.InputStream in = getAssets().open("nas_probe_arm64");
                 java.io.FileOutputStream out = new java.io.FileOutputStream(src)) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            String from = src.getAbsolutePath();
            if (!from.matches("/data/(user|user_de)/0/dev\\.dual5g/files/nas_probe_arm64") &&
                !from.matches("/data/data/dev\\.dual5g/files/nas_probe_arm64"))
                return "NAS：应用数据路径不符合预期：" + from;
            String path = "/data/local/tmp/dual5g_nas_probe_arm64";
            String cmd = "[ \"$(id -u)\" = 0 ] || exit 91; " +
                "cp '" + from + "' '" + path + "' || exit 92; " +
                "chmod 0700 '" + path + "' || exit 93; " +
                "'" + path + "'; rc=$?; rm -f '" + path + "'; exit $rc";
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process current = p;
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            Thread collector = new Thread(() -> {
                try (java.io.InputStream in = current.getInputStream()) {
                    byte[] b = new byte[2048]; int n;
                    while ((n = in.read(b)) >= 0 && data.size() < 16384) data.write(b, 0, n);
                } catch (Exception ignored) {}
            }, "Dual5G-NAS-Output");
            collector.setDaemon(true); collector.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly(); return "NAS 读取超时（10 秒）";
            }
            collector.join(600);
            return "退出码 " + p.exitValue() + "：\n" + data.toString("UTF-8").trim();
        } catch (java.io.FileNotFoundException e) {
            return "NAS 原生组件缺失：请使用项目内 GitHub Actions 构建";
        } catch (Throwable e) {
            return "NAS 读取失败：" + e;
        } finally { if (p != null) p.destroy(); }
    }

    /** One-time read-only NAS RF activity query, independent from phone Hook. */
    private void autoReadRfBands() {
        if (rfAutoStarted || rfProbeResult == null) return;
        rfAutoStarted = true;
        rfProbeResult.setText("正在读取 NAS RF 当前活动频段（最长 10 秒）…");
        new Thread(() -> {
            String raw = runRfProbe();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed() && rfProbeResult != null)
                    rfProbeResult.setText("当前射频活动信息（不代表完整启用频段或运营商允许频段）\n" + raw);
            });
        }, "Dual5G-AutoRF").start();
    }

    private String runRfProbe() {
        Process p = null;
        try {
            java.io.File src = new java.io.File(getFilesDir(), "nas_rf_probe_arm64");
            try (java.io.InputStream in = getAssets().open("nas_rf_probe_arm64");
                 java.io.FileOutputStream out = new java.io.FileOutputStream(src)) {
                byte[] b = new byte[8192]; int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
            }
            String from = src.getAbsolutePath();
            if (!from.matches("/data/(user|user_de)/0/dev\\.dual5g/files/nas_rf_probe_arm64") &&
                !from.matches("/data/data/dev\\.dual5g/files/nas_rf_probe_arm64"))
                return "RF：应用数据路径不符合预期：" + from;
            String path = "/data/local/tmp/dual5g_nas_rf_probe_arm64";
            String cmd = "[ \"$(id -u)\" = 0 ] || exit 91; " +
                "cp '" + from + "' '" + path + "' || exit 92; " +
                "chmod 0700 '" + path + "' || exit 93; " +
                "'" + path + "'; rc=$?; rm -f '" + path + "'; exit $rc";
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final Process current = p;
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            Thread collector = new Thread(() -> {
                try (java.io.InputStream in = current.getInputStream()) {
                    byte[] b = new byte[2048]; int n;
                    while ((n = in.read(b)) >= 0 && data.size() < 16384) data.write(b, 0, n);
                } catch (Exception ignored) {}
            }, "Dual5G-RF-Output");
            collector.setDaemon(true); collector.start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly(); return "RF 读取超时（10 秒）";
            }
            collector.join(600);
            return "退出码 " + p.exitValue() + "：\n" + data.toString("UTF-8").trim();
        } catch (java.io.FileNotFoundException e) {
            return "RF 原生组件缺失：请使用项目内 GitHub Actions 构建";
        } catch (Throwable e) {
            return "RF 读取失败：" + e;
        } finally { if (p != null) p.destroy(); }
    }

    private void confirmRestartPhone(Button button) {
        new AlertDialog.Builder(this)
            .setTitle("重启电话服务？")
            .setMessage("需要 KernelSU 向本 APP 授予 Root。执行后两张 SIM 可能短暂断网、重新注册；若 Hook 存在问题也可能影响电话服务。建议先备份稳定版。\n\n仅在你主动确认后执行，不会自动重启整台手机。")
            .setNegativeButton("取消", (d,w) -> {})
            .setPositiveButton("确认重启", (d,w) -> {
                lockButton(button);
                status.setText("●  正在重启电话服务…");
                status.setTextColor(MUTED);
                new Thread(() -> {
                    String message = runRootRestart();
                    runOnUiThread(() -> {
                        updated.setText("（最后更新时间：等待服务恢复）");
                        if (details != null) details.setText(message);
                        // Allow phone process to come up and LSPosed to reattach.
                        liveHandler.postDelayed(this::sendQuietStatus, 4000L);
                    });
                }, "Dual5G-RestartPhone").start();
            }).show();
    }

    /** No shell interpolation from user input; target PID is checked numeric before SIGTERM. */
    private String runRootRestart() {
        try {
            Process process = new ProcessBuilder("su", "-c",
                "uid=$(id -u); [ \"$uid\" = 0 ] || exit 91; " +
                "pid=$(pidof com.android.phone); [ -n \"$pid\" ] || exit 92; " +
                "for p in $pid; do case \"$p\" in *[!0-9]*|'') exit 93;; esac; kill -TERM \"$p\" || exit 94; done")
                .redirectErrorStream(true).start();
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroy();
                return "Root 操作超时或授权弹窗未完成，请检查 KernelSU 授权。";
            }
            int result = process.exitValue();
            if (result == 0) return "已向电话进程发送 SIGTERM；等待系统重新拉起并恢复 SIM 注册。";
            if (result == 91) return "Root 未授权：请在 KernelSU 中授予本 APP Root 权限。";
            if (result == 92) return "没有找到 com.android.phone 进程，未执行重启。";
            return "重启未成功，退出码 " + result + "。请检查 Root 权限及系统限制。";
        } catch (Throwable ex) {
            android.util.Log.e("Dual5G-UI", "phone restart failed", ex);
            return "重启失败：" + ex.getClass().getSimpleName() + "（请检查 KernelSU 授权）";
        }
    }

    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void send(String command){
        requestStartedAt = android.os.SystemClock.elapsedRealtime();
        if(details!=null) details.setText("已发送 "+command+"，等待电话服务响应…\n如果持续无响应，请检查 LSPosed 作用域及日志。");
        if(updated!=null) updated.setText("（正在请求…）");
        try {
            Intent i=new Intent(PhoneHook.CONTROL).setPackage("com.android.phone");
            i.putExtra("command",command);
            sendBroadcast(i);

        } catch (Throwable e) {
            if (details != null) details.setText("广播发送失败：" + e);
        }
    }
    private void sendQuietStatus() {
        try {
            Intent i = new Intent(PhoneHook.CONTROL).setPackage("com.android.phone");
            i.putExtra("command", "status");
            sendBroadcast(i);
        } catch (RuntimeException e) {
            android.util.Log.w("Dual5G-UI", "Live polling failed", e);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        foreground = true;
        liveHandler.removeCallbacks(livePoll);
        sendQuietStatus();
        liveHandler.postDelayed(livePoll, 4000L);
        autoReadDmsBands();
        autoReadNasPreference();
        autoReadRfBands();
    }
    @Override protected void onPause() {
        foreground = false;
        liveHandler.removeCallbacks(livePoll);
        super.onPause();
    }
    @Override protected void onDestroy(){
        if (receiverRegistered) {
            try { unregisterReceiver(updates); }
            catch (RuntimeException ex) { android.util.Log.w("Dual5G-UI", "unregisterReceiver", ex); }
        }
        super.onDestroy();
    }
}
