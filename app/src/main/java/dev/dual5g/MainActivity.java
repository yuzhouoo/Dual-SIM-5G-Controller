package dev.dual5g;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class MainActivity extends Activity {
    private static final String SCRIPT = "/data/adb/modules/dual_sim_5g/controller.sh";
    private TextView output;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(28, 32, 28, 24);
        TextView header = new TextView(this);
        header.setText("双卡 5G 自动切换\n默认数据卡：允许 5G / 副卡：不允许 5G\n需要先安装 KernelSU 模块，并授权本 App Root。\nLSPosed 作用域：电话服务 (com.android.phone)。");
        header.setTextSize(17); layout.addView(header);
        add(layout, "读取状态", "status"); add(layout, "启用并同步", "enable");
        add(layout, "立即同步", "once"); add(layout, "停用（保留当前模式）", "disable");
        add(layout, "恢复安装时的两卡模式", "restore");
        output = new TextView(this); output.setTextIsSelectable(true); layout.addView(output);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        runCommand("status");
    }
    private void add(LinearLayout parent, String title, String action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> runCommand(action)); parent.addView(button);
    }
    private void runCommand(String action) {
        output.setText("正在执行 " + action + " …");
        new Thread(() -> {
            StringBuilder result = new StringBuilder();
            try {
                // Fixed whitelist: no user input enters a privileged shell command.
                Process p = new ProcessBuilder("su", "-c", SCRIPT + " " + action).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) { if (result.length() < 12000) result.append(line).append('\n'); }
                }
                result.append("exitCode=").append(p.waitFor());
            } catch (Throwable t) { result.append(t.toString()); }
            runOnUiThread(() -> output.setText(result.toString()));
        }, "Dual5G-root").start();
    }
}
