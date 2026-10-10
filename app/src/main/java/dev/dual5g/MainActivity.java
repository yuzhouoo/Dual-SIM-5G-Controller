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
    private TextView status, updated, details, heading;
    private LinearLayout overviewRow;
    private final SimCard[] sims = new SimCard[2];
    private LinearLayout debugBox;
    private TextView bandReport, hardwareReport;
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
    private long lastReplyAt = 0L;
    private long requestStartedAt = 0L;
    private String ipcMessage = "尚未收到电话进程回应";
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            lastReplyAt = android.os.SystemClock.elapsedRealtime();
            ipcMessage = "通信正常，已收到电话进程回应";
            String raw = intent.getStringExtra("status");
            boolean enabled = intent.getBooleanExtra("enabled", true);
            status.setText(enabled ? "●  自动策略已启用" : "●  自动策略已停用");
            status.setTextColor(enabled ? GREEN : MUTED);
            updated.setText("（最后更新时间：" + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()) + "）");
            if (raw == null) raw = "电话进程未提供状态数据";
            details.setText(raw);
            showStatus(raw);
            updateBands(raw);
            updateHardware(raw);
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

        label(root, "DUAL SIM / 5G", 12, BLUE, true);
        heading = label(root, "双卡网络控制台", 28, WHITE, true);
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

        // Separate the three meanings of band information.
        addHardwareBands(root);
        addLiveBandDiagnostics(root);

        label(root, "快捷操作", 17, WHITE, true).setPadding(0, dp(21), 0, dp(10));
        Button refresh = action(root, "↻   刷新网络状态", BLUE, 0xFF152845, "status", true);
        refresh.setContentDescription("刷新双卡状态");
        LinearLayout actions = horizontal(); root.addView(actions);
        action(actions, "启用自动切换", BG, GREEN, "enable", false);
        action(actions, "立即同步", WHITE, PANEL, "sync", false);
        LinearLayout secondary = horizontal(); root.addView(secondary);
        action(secondary, "停用策略", MUTED, PANEL, "disable", false);
        action(secondary, "恢复原始模式", 0xFFFFB4AA, PANEL, "restore", false);
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
        label(root, "LSPosed · 推荐作用域 com.android.phone     |     v0.4.6", 11, MUTED, false)
                .setPadding(0, dp(18), 0, 0);
        try {
            registerReceiver(updates, new IntentFilter(PhoneHook.RESULT), Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
        } catch (RuntimeException ex) {
            android.util.Log.e("Dual5G-UI", "registerReceiver failed", ex);
            details.setText("接收状态广播失败：" + ex);
        }
    }

    private void addLiveBandDiagnostics(LinearLayout root) {
        label(root, "频段实时诊断", 17, WHITE, true).setPadding(0, dp(22), 0, dp(10));
        LinearLayout block = panel(root, dp(16), 0);
        label(block, "① 实际支持", 13, BLUE, true);
        label(block, "硬件支持列表由上方官方规格提供。Android 未提供可靠的逐频段硬件能力枚举接口。", 12, MUTED, false);
        label(block, "② 当前启用", 13, BLUE, true).setPadding(0, dp(12), 0, 0);
        label(block, "可读取每张卡允许的 NR/LTE 等制式，但基带启用的全部 N/B Band 无通用接口，显示为「不可获取」。", 12, MUTED, false);
        label(block, "③ 当前连接 · 自动刷新", 13, BLUE, true).setPadding(0, dp(12), 0, 0);
        bandReport = label(block, "当前连接频段见上方双卡卡片，页面打开时每 4 秒刷新。", 12, WHITE, false);
        label(block, "仅展示当前注册频段，不记录历史；5G NSA 可能只显示 LTE 锚点。", 11, MUTED, false);
    }

    private void updateBands(String raw) {
        if (bandReport == null || raw == null) return;
        String prefix = "基站频段查询：";
        int p = raw.indexOf(prefix);
        if (p < 0) return;
        int end = raw.indexOf("\n【实时", p);
        if (end < 0) end = raw.indexOf("\n最近操作：", p);
        if (end < 0) end = raw.length();
        bandReport.setText(raw.substring(p + prefix.length(), end).trim());
    }

    private void updateHardware(String raw) {
        if (hardwareReport == null || raw == null) return;
        String prefix = "设备能力检测：";
        int p = raw.indexOf(prefix);
        if (p < 0) return;
        int end = raw.indexOf("\n基站频段查询：", p);
        if (end < 0) end = raw.length();
        hardwareReport.setText(raw.substring(p + prefix.length(), end).trim());
    }

    private void addHardwareBands(LinearLayout root) {
        label(root, "设备硬件支持频段", 17, WHITE, true)
                .setPadding(0, dp(24), 0, dp(10));
        LinearLayout block = panel(root, dp(16), 0);
        String model = Build.MODEL == null ? "未知" : Build.MODEL.trim();
        String device = Build.DEVICE == null ? "未知" : Build.DEVICE.trim();
        boolean chinaModel = model.equalsIgnoreCase("PJD110")
                || device.equalsIgnoreCase("PJD110");
        label(block, "OnePlus 12  ·  型号 " + model, 14, WHITE, true);
        label(block, "设备代号 " + device, 11, MUTED, false).setPadding(0, dp(3), 0, dp(8));
        label(block, "设备读取 · Modem 无线制式能力（非逐频段）", 12, BLUE, true);
        hardwareReport = label(block, "尚未检测。点击下方按钮读取设备支持的 NR/LTE 等制式。", 12, WHITE, false);
        hardwareReport.setTextIsSelectable(true);
        action(block, "⌁   检测设备无线能力", WHITE, 0xFF152845, "hardware", true);
        label(block, "系统只提供制式能力掩码，无法直接证明 N41/N78 等单个频段是否由当前基带支持。", 11, MUTED, false);
        if (!chinaModel) {
            label(block, "【官方规格】型号未匹配国行 PJD110，未采用国行频段表兜底，以免误报。", 12, MUTED, false);
            return;
        }
        label(block, "【官方公布】中国版 PJD110 · 完整频段表", 11, GREEN, true);
        TextView toggle = label(block, "查看全部频段  ▾", 13, BLUE, true);
        toggle.setPadding(0, dp(14), 0, dp(8));
        LinearLayout bands = vertical();
        block.addView(bands);
        bands.setVisibility(View.GONE);
        addBandLine(bands, "5G NR（13 个）", "N1 · N3 · N5 · N7 · N8 · N20 · N28A · N38 · N40 · N41 · N66 · N77 · N78");
        addBandLine(bands, "4G LTE FDD（12 个）", "B1 · B3 · B4 · B5 · B7 · B8 · B18 · B19 · B20 · B26 · B28A · B66");
        addBandLine(bands, "4G LTE TDD（5 个）", "B34 · B38 · B39 · B40 · B41");
        addBandLine(bands, "3G WCDMA", "B1 · B4 · B5 · B8");
        label(bands, "【官方公布】来源：一加 12 中国官网参数表。此列表不是设备读取结果，也不代表当前已启用或正在连接。", 11, MUTED, false)
                .setPadding(0, dp(10), 0, 0);
        toggle.setOnClickListener(v -> {
            boolean open = bands.getVisibility() != View.VISIBLE;
            bands.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "收起全部频段  ▴" : "查看全部频段  ▾");
        });
    }

    private void addBandLine(LinearLayout parent, String title, String values) {
        label(parent, title, 12, BLUE, true).setPadding(0, dp(10), 0, dp(3));
        label(parent, values, 12, WHITE, false);
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
        TextView qos=label(card,"QCI：—   上行：—   下行：—",11,MUTED,false);
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
        c.qos.setText("QCI：—   上行：—   下行：—");
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
            c.qos.setText((field(section,"当前数据网络：").toUpperCase(Locale.ROOT).contains("NR") ? "5QI" : "QCI") + "：—   上行：—   下行：—");

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
            if ("hardware".equals(command) && hardwareReport != null) {
                hardwareReport.setText("检测请求已发送，等待电话服务回应…");
            }
            lockButton(b);
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
            if ("hardware".equals(command)) {
                liveHandler.postDelayed(() -> {
                    if (hardwareReport != null && lastReplyAt < requestStartedAt) {
                        hardwareReport.setText("通信超时：电话进程没有回复。请检查 LSPosed 作用域 com.android.phone、重启手机，并查看调试日志。\n本次未进行设备能力检测。");
                    }
                }, 6000L);
            }
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
