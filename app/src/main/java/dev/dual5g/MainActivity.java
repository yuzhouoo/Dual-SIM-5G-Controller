package dev.dual5g;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** No runtime root privileges and no KernelSU companion module. */
public class MainActivity extends Activity {
    private TextView output;
    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean enabled = intent.getBooleanExtra("enabled", true);
            output.setText("自动切换：" + (enabled ? "启用" : "停用") + "\n\n"
                    + intent.getStringExtra("status"));
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(30, 32, 30, 24);
        TextView t = new TextView(this);
        t.setText("Dual SIM 5G Controller 0.2\n单 APK · 无需 KernelSU 模块 / su\n\n"
                + "数据卡：允许 5G (NR)\n非数据卡：关闭 NR，保留 4G/3G/2G\n\n"
                + "需要在 LSPosed 中启用模块，并勾选『电话服务 com.android.phone』作用域，然后重启手机。\n"
                + "第一次修改网络设置前请先确认两张卡都能正常通话。\n");
        t.setTextSize(16); l.addView(t);
        add(l, "读取状态", "status");
        add(l, "启用并立即同步", "enable");
        add(l, "立即同步", "sync");
        add(l, "停用（保留当前模式）", "disable");
        add(l, "恢复首次接管前的模式", "restore");
        output = new TextView(this);
        output.setTextIsSelectable(true); output.setText("等待电话进程响应…");
        l.addView(output);
        ScrollView scroll = new ScrollView(this); scroll.addView(l); setContentView(scroll);
        registerReceiver(updates, new IntentFilter(PhoneHook.RESULT), Context.RECEIVER_EXPORTED);
        send("status");
    }
    private void add(LinearLayout l, String title, String command) {
        Button b = new Button(this); b.setText(title); b.setOnClickListener(v -> send(command)); l.addView(b);
    }
    private void send(String command) {
        if (output != null) output.setText("已发送 " + command + "，等待电话服务响应…\n如果持续无响应，请确认 LSPosed 已启用本模块、作用域包含 com.android.phone，并重启手机。");
        Intent i = new Intent(PhoneHook.CONTROL).setPackage("com.android.phone");
        i.putExtra("command", command);
        sendBroadcast(i);
    }
    @Override protected void onDestroy() {
        unregisterReceiver(updates); super.onDestroy();
    }
}
