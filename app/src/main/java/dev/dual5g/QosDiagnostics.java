package dev.dual5g;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe, read-only discovery of Android diagnostic QoS fields. No modem setters and no phone Hook. */
final class QosDiagnostics {
    private QosDiagnostics() {}
    private static final Pattern QCI = Pattern.compile("(?i)(?<![a-z])(?:qci|qosClassIdentifier)\\s*[:= ]\\s*(\\d{1,3})(?!\\d)");
    private static final Pattern FIVEQI = Pattern.compile("(?i)(?:5qi|fiveQi|five_qi)\\s*[:= ]\\s*(\\d{1,3})(?!\\d)");
    private static final Pattern RATE = Pattern.compile("(?i)(?:maxBitrateKbps|guaranteedBitrateKbps|downlinkMaxBitRateKbps|uplinkMaxBitRateKbps|apn.ambr|session.ambr|aggregateMaxBitRate)[^\\n]{0,100}");
    private static final Pattern SAFE_RATE = Pattern.compile("(?i)(?:maxBitrateKbps|guaranteedBitrateKbps|downlinkMaxBitRateKbps|uplinkMaxBitRateKbps|apn.ambr|session.ambr|aggregateMaxBitRate)\\s*[=:]\\s*[0-9]{1,12}");
    static String read() {
        LinkedHashSet<String> qci = new LinkedHashSet<>(), fiveqi = new LinkedHashSet<>(), rates = new LinkedHashSet<>();
        StringBuilder status = new StringBuilder();
        for (String service : new String[]{"telephony", "connectivity"}) {
            Process p = null;
            try {
                // Only read system diagnostic dumps. Never send raw output to UI: it may contain identifiers.
                p = new ProcessBuilder("su", "-c", "dumpsys " + service).redirectErrorStream(true).start();
                final Process proc = p;
                final String[] error = {null};
                Thread worker = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String line; int lines = 0;
                        while ((line = reader.readLine()) != null && ++lines < 100000) {
                            collect(line, QCI, qci, 8, "QCI=");
                            collect(line, FIVEQI, fiveqi, 8, "5QI=");
                            Matcher m = SAFE_RATE.matcher(line);
                            while (m.find() && rates.size() < 12) rates.add(m.group().trim());
                        }
                    } catch (Exception ex) { error[0] = ex.getClass().getSimpleName(); }
                }, "Dual5G-QosDumpReader");
                worker.setDaemon(true); worker.start();
                if (!p.waitFor(7, TimeUnit.SECONDS)) { p.destroyForcibly(); status.append(service).append(": 超时；"); }
                else if (p.exitValue() != 0) status.append(service).append(": exit=").append(p.exitValue()).append("；");
                worker.join(400);
                if (error[0] != null) status.append(service).append(": ").append(error[0]).append("；");
            } catch (Exception e) { status.append(service).append(": ").append(e.getClass().getSimpleName()).append("；"); }
            finally { if (p != null) p.destroy(); }
        }
        StringBuilder result = new StringBuilder("Root 只读诊断（telephony / connectivity）\n");
        result.append("QCI 候选：").append(qci.isEmpty() ? "未在系统转储中发现" : qci).append("\n");
        result.append("5QI 候选：").append(fiveqi.isEmpty() ? "未在系统转储中发现" : fiveqi).append("\n");
        result.append("QoS 速率字段：").append(rates.isEmpty() ? "未发现" : rates).append("\n");
        result.append("套餐签约上/下行：无法由这些字段直接证明；未读取。\n");
        result.append("提示：候选值可能来自历史、IMS 或其他承载，未完成 SIM/APN 归属；不得视为当前订阅的 QCI 或签约速率。\n");
        if (status.length() > 0) result.append("诊断状态：").append(status);
        return result.toString();
    }
    private static void collect(String line, Pattern regex, LinkedHashSet<String> to, int limit, String prefix) {
        Matcher m = regex.matcher(line);
        while (m.find() && to.size() < limit) to.add(prefix + m.group(1));
    }
}
