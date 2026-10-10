package dev.dual5g;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone LSPosed UI. No su and no companion KernelSU module. */
public class MainActivity extends Activity {
    private static final int BG=0xFF0B1120, PANEL=0xFF172238, STROKE=0xFF29384F;
    private static final int WHITE=0xFFF3F7FF, MUTED=0xFFA1B2CD, GREEN=0xFF5DE2B1, BLUE=0xFF6EA9FF;
    private TextView status, updated, details, dataSim, heading;
    private LinearLayout overviewRow;
    private final SimCard[] sims = new SimCard[2];
    private LinearLayout debugBox;
    private boolean expanded = false;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String raw = intent.getStringExtra("status");
            boolean enabled = intent.getBooleanExtra("enabled", true);
            status.setText(enabled ? "●  自动策略已启用" : "●  自动策略已停用");
            status.setTextColor(enabled ? GREEN : MUTED);
            updated.setText("最后更新  " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date()));
            if (raw == null) raw = "电话进程未提供状态数据";
            details.setText(raw);
            showStatus(raw);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        // ColorOS 16 / Android 16: status bar must remain visible, with light icons
        // on the dark dashboard. Insets are applied below to prevent overlap.
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            window.getInsetsController().show(WindowInsets.Type.statusBars()
                    | WindowInsets.Type.navigationBars());
            window.getInsetsController().setSystemBarsAppearance(
                    0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            window.getDecorView().setSystemUiVisibility(0);
        }
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
        label(root, "智能管理默认数据卡的 5G 接入权限", 13, MUTED, false);

        LinearLayout hero = panel(root, dp(20), dp(22));
        LinearLayout row = horizontal(); hero.addView(row);
        status = label(row, "●  正在连接电话服务", 15, MUTED, true);
        updated = label(hero, "等待首次状态同步", 12, MUTED, false);
        updated.setPadding(0, dp(7), 0, dp(16));
        label(hero, "当前默认上网卡", 12, MUTED, false);
        dataSim = label(hero, "正在检测…", 26, WHITE, true);
        dataSim.setPadding(0, dp(4), 0, 0);

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
        label(root, "LSPosed · com.android.phone     |     v0.3.2", 11, MUTED, false)
                .setPadding(0, dp(18), 0, 0);
        registerReceiver(updates, new IntentFilter(PhoneHook.RESULT), Context.RECEIVER_EXPORTED);
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
        TextView real=label(card,"数据  —",11,MUTED,false);
        real.setPadding(0,dp(5),0,dp(5));
        TextView voice=label(card,"语音  —",11,MUTED,false);
        TextView sub=label(card,"subId  —",10,MUTED,false);
        sub.setPadding(0,dp(9),0,0);
        return new SimCard(card,pill,carrier,mode,real,voice,sub);
    }

    private void setEmptySlot(SimCard c) {
        c.pill.setText("○  未插卡");
        c.pill.setTextColor(MUTED);
        c.carrier.setText("未检测到活动 SIM");
        c.mode.setText("无 SIM");
        c.mode.setTextColor(MUTED);
        c.real.setText("数据  —");
        c.voice.setText("语音  —");
        c.sub.setText("subId  —");
        c.container.setAlpha(0.68f);
    }

    private void showStatus(String raw) {
        int dataId=parseInt(raw, "默认数据订阅 ID：\\s*(-?\\d+)", -1);
        boolean matched=false;
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
            if(active) { dataSim.setText("SIM "+(i+1)+"  ·  "+field(section,"运营商：")); matched=true; }
            c.pill.setText(active ? "●  当前上网卡" : "●  已插卡");
            c.pill.setTextColor(active ? GREEN : BLUE);
            c.carrier.setText(field(section,"运营商："));
            String mode=field(section,"网络模式：");
            boolean hasNr=mode.contains("允许 5G");
            c.mode.setText(hasNr ? "5G 优先" : mode.contains("4G") ? "4G 优先" : "未知模式");
            c.mode.setTextColor(hasNr ? GREEN : WHITE);
            c.real.setText("数据  "+field(section,"当前数据网络："));
            c.voice.setText("语音  "+field(section,"当前语音网络："));
            c.sub.setText("subId "+subId+" · Slot "+i);
        }
        if(count==0) {
            dataSim.setText("未检测到活动 SIM");
        } else if (!matched) {
            dataSim.setText(dataId<0 ? "尚未设置默认数据卡" : "数据卡待匹配 (subId "+dataId+")");
        }
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
        final TextView pill,carrier,mode,real,voice,sub;
        SimCard(LinearLayout box,TextView a,TextView b,TextView c,TextView d,TextView e,TextView f) {
            container=box;pill=a;carrier=b;mode=c;real=d;voice=e;sub=f;
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
        parent.addView(b,p);b.setOnClickListener(v->send(command));return b;
    }
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void send(String command){
        if(details!=null) details.setText("已发送 "+command+"，等待电话服务响应…\n如果持续无响应，请检查 LSPosed 作用域及日志。");
        if(updated!=null) updated.setText("正在请求电话服务…");
        Intent i=new Intent(PhoneHook.CONTROL).setPackage("com.android.phone");
        i.putExtra("command",command);sendBroadcast(i);
    }
    @Override protected void onResume(){super.onResume();if(details!=null)send("status");}
    @Override protected void onDestroy(){unregisterReceiver(updates);super.onDestroy();}
}
